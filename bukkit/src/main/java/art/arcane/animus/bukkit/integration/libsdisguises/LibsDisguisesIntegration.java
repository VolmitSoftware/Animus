package art.arcane.animus.bukkit.integration.libsdisguises;

import art.arcane.animus.core.npc.NpcDisguise;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Logger;
import org.bukkit.plugin.PluginManager;

/**
 * Lets NPC definitions wear LibsDisguises disguises. Only this class is named outside the package, and it names no
 * LibsDisguises type, so Animus loads and runs without LibsDisguises; {@link LibsDisguisesBodies} is loaded only once
 * LibsDisguises is enabled. Install it before NPC definitions load.
 */
public final class LibsDisguisesIntegration {
    public static final String PLUGIN = "LibsDisguises";
    private static volatile Runnable reload = () -> {};
    private static volatile Executor serverThread = (Runnable task) -> { throw new RejectedExecutionException("Animus is starting"); };
    private static NpcDisguise.Resolver installed;

    private LibsDisguisesIntegration() {}

    /** Installs the disguise reader when LibsDisguises is enabled; true when it did. */
    public static synchronized boolean install(PluginManager plugins, Logger log) {
        if (!plugins.isPluginEnabled(PLUGIN)) return false;
        try {
            installed = new LibsDisguisesBodies(() -> reload.run(), () -> serverThread);
            NpcDisguise.install(installed);
            log.info("LibsDisguises found: NPC definitions with a disguise show its body");
            return true;
        } catch (LinkageError incompatible) {
            log.warning("LibsDisguises is installed but this version cannot be read, so NPC disguises are not shown: " + incompatible);
            return false;
        }
    }

    /** What runs when a player skin a disguise named arrives later: a reload of the NPC definitions. */
    public static void reloadWith(Runnable next) { reload = Objects.requireNonNull(next); }

    /** Where disguises read during a reload off the server thread are parsed: the server thread, or Folia's global region. */
    public static void serverThread(Executor next) { serverThread = Objects.requireNonNull(next); }

    public static synchronized void uninstall() {
        if (installed != null) NpcDisguise.uninstall(installed);
        installed = null;
        reload = () -> {};
        serverThread = (Runnable task) -> { throw new RejectedExecutionException("Animus is stopping"); };
    }
}
