package org.lsposed.npatch.metaloader;

import dalvik.system.InMemoryDexClassLoader;

import java.nio.ByteBuffer;

/**
 * An in-memory dex class loader with delegate-last (parent-last) resolution.
 *
 * <p>The framework loader dex bundles its own kotlin-stdlib. A plain
 * {@link InMemoryDexClassLoader} delegates to its parent (the host app class loader) first, so a
 * host app that ships an older / incompatible kotlin-stdlib shadows the framework's classes and
 * crashes it (e.g. {@code NoSuchFieldError kotlin.Result$Companion}).
 *
 * <p>{@code dalvik.system.DelegateLastClassLoader} would give the behaviour we want, but it has no
 * in-memory (ByteBuffer) constructor, so we replicate its lookup order here:
 * already-loaded &rarr; boot classpath &rarr; this dex &rarr; parent. This keeps the framework on
 * its OWN kotlin while it can still reach the host app through the parent.
 */
public final class DelegateLastInMemoryClassLoader extends InMemoryDexClassLoader {

    public DelegateLastInMemoryClassLoader(ByteBuffer dexBuffer, ClassLoader parent) {
        super(dexBuffer, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);

            if (c == null) {
                // 1. Boot classpath (android.*, java.*, ...). Object's loader is the boot loader.
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
