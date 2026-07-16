package org.lsposed.npatch.metaloader;

import dalvik.system.BaseDexClassLoader;

import java.nio.ByteBuffer;

/**
 * In-memory dex class loader with delegate-last (parent-last) resolution.
 *
 * <p>The framework loader dex bundles its own kotlin-stdlib. A plain in-memory loader delegates to
 * its parent (the host app class loader) first, so a host app that ships an older / incompatible
 * kotlin-stdlib shadows the framework's classes and crashes it (e.g.
 * {@code NoSuchFieldError kotlin.Result$Companion}).
 *
 * <p>This mirrors {@code hidden.ByteBufferDexClassLoader} / {@code VectorModuleClassLoader}: it
 * extends {@link BaseDexClassLoader} through the hidden {@code (ByteBuffer[], ClassLoader)}
 * constructor, so it loads dex from memory (no writable-path dex, which Android blocks) AND is a
 * real {@code BaseDexClassLoader} exposing the {@code pathList} field the framework introspects.
 * The resolution order is boot &rarr; this dex &rarr; parent, keeping the framework on its OWN
 * kotlin while it can still reach the host app through the parent.
 */
public final class DelegateLastInMemoryClassLoader extends BaseDexClassLoader {

    public DelegateLastInMemoryClassLoader(ByteBuffer[] dexFiles, ClassLoader parent) {
        super(dexFiles, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // synchronized(this) is equivalent to the default lock of a non-parallel-capable loader
        // (getClassLoadingLock is not visible under the metaloader's hidden-api stub classpath).
        synchronized (this) {
            Class<?> c = findLoadedClass(name);

            if (c == null) {
                // 1. Boot classpath (android.*, java.*, ...); Object's loader is the boot loader.
                try {
                    c = Class.forName(name, false, Object.class.getClassLoader());
                } catch (ClassNotFoundException ignored) {
                }
            }

            if (c == null) {
                // 2. This loader's own dex (the framework, incl. its own kotlin-stdlib).
                try {
                    c = findClass(name);
                } catch (ClassNotFoundException ignored) {
                }
            }

            if (c == null) {
                // 3. Parent (the host app class loader).
                c = getParent().loadClass(name);
            }

            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }
}
