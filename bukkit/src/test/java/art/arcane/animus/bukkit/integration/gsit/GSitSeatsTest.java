package art.arcane.animus.bukkit.integration.gsit;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** GSit's main class names its PlaceholderAPI link; on a server without PlaceholderAPI its services are still read. */
class GSitSeatsTest {
    /** Stands in for PlaceholderAPI's expansion class, which the loader below refuses as a server without it would. */
    public static class Expansion {}
    public static class Link extends Expansion {}
    public static class Config { public final boolean allow = true; }
    public static class Main {
        private final Config config = new Config();
        public Config getConfigService() { return config; }
        public Link getPlaceholderAPILink() { return new Link(); }
    }

    /** Loads this test's classes itself, and never the expansion class. */
    private static final class WithoutPlaceholderApi extends ClassLoader {
        WithoutPlaceholderApi() { super(GSitSeatsTest.class.getClassLoader()); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(GSitSeatsTest.class.getName() + "$")) return super.loadClass(name, resolve);
            if (name.endsWith("$Expansion")) throw new ClassNotFoundException(name);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) return loaded;
                try (InputStream bytes = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    byte[] code = bytes.readAllBytes();
                    return defineClass(name, code, 0, code.length);
                } catch (IOException failure) { throw new ClassNotFoundException(name, failure); }
            }
        }
    }

    @Test void aServiceIsReadWithoutLoadingThePlaceholderApiLink() throws Exception {
        ClassLoader server = new WithoutPlaceholderApi();
        Object main = Class.forName(Main.class.getName(), true, server).getConstructor().newInstance();
        assertThrows(NoClassDefFoundError.class, () -> main.getClass().getMethod("getConfigService"), "listing the methods loads the link");
        Object config = GSitSeats.service(main, server, "getConfigService", Config.class.getName());
        assertEquals(Config.class.getName(), config.getClass().getName());
        assertEquals(true, config.getClass().getField("allow").getBoolean(config));
    }
}
