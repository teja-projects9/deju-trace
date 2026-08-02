package org.deju.agent.sql;

import java.io.IOException;
import java.io.InputStream;

/**
 * Defines the {@code com.example.*} fixtures itself instead of delegating, so they are
 * loaded <em>after</em> the agent installs and therefore reach the transformer.
 *
 * <p>This exists because Gradle's JUnit runner loads every class on the test classpath
 * during discovery, before a single test method runs. A {@code ClassFileTransformer} only
 * sees classes loaded after it is registered, so a fixture sitting on the test classpath is
 * already loaded and permanently invisible to it, the transformer would look broken while
 * being perfectly correct. Real applications have the opposite ordering: a
 * {@code -javaagent} installs during {@code premain}, long before a JDBC driver or a
 * connection pool's generated proxies are loaded.
 *
 * <p>Only {@code com.example.*} is taken over. Everything else, {@code java.sql},
 * {@code org.deju.agent}, still comes from the parent, so {@link SqlCarrier}, the runtime
 * and the payload classes are the same types the assertions use.
 */
final class FixtureLoader extends ClassLoader {

    FixtureLoader(ClassLoader parent) {
        super(parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!name.startsWith("com.example.")) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> existing = findLoadedClass(name);
            if (existing != null) {
                return existing;
            }
            byte[] bytes = read(name);
            Class<?> defined = defineClass(name, bytes, 0, bytes.length);
            if (resolve) {
                resolveClass(defined);
            }
            return defined;
        }
    }

    private byte[] read(String name) throws ClassNotFoundException {
        String resource = name.replace('.', '/') + ".class";
        try (InputStream in = getParent().getResourceAsStream(resource)) {
            if (in == null) {
                throw new ClassNotFoundException(name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
    }
}
