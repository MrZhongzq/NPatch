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
 * <p>{@code dalvik.system.DelegateLastClassLoader} has the delegation order we want but no
 * in-memory (ByteBuffer) constructor, and {@link InMemoryDexClassLoader} is {@code final} so it
 * cannot be subclassed. We therefore wrap an in-memory loader whose parent is the boot class
 * loader (so it only ever serves boot classes and our own dex) and resolve as:
 * already-loaded &rarr; boot + framework dex &rarr; host app. This keeps the framework on its OWN
 * kotlin while it can still reach the host app through the parent.
 */
public final class DelegateLastInMemoryClassLoader extends ClassLoader {

    private final ClassLoader dexLoader;

    public DelegateLastInMemoryClassLoader(ByteBuffer dexBuffer, ClassLoader parent) {
        super(parent);
        // parent = null -> boot class loader, so this delegate never resolves host app classes,
        // only the boot classpath and the framework's own dex (incl. its kotlin-stdlib).
        this.dexLoader = new InMemoryDexClassLoader(dexBuffer, null);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);

            if (c == null) {
                // 1. Boot classpath + the framework's own dex (never the host app).
                try {
                    c = dexLoader.loadClass(name);
                } catch (ClassNotFoundException ignored) {
                }
            }

            if (c == null) {
                // 2. The host app class loader.
                c = getParent().loadClass(name);
            }

            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }
}
