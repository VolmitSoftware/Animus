package art.arcane.animus.bukkit.integration.quests;

import art.arcane.animus.bukkit.integration.betonquest.BetonQuestIntegration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Connects Animus NPCs to every quest plugin installed: Quests by PikaMug, BeautyQuests and BetonQuest join through
 * one {@link QuestNpcHost} once found enabled, and a plugin enabled later is joined when it enables. Each bridge names its plugin's own
 * types, so Animus never touches them without the plugin, and a plugin version whose API moved is logged instead of
 * failing the start. A quest plugin that stops is left; Animus's classes stay bound to the copy it joined, so one
 * reloaded on its own is joined again only once Animus restarts or reloads too. Closing leaves every plugin joined.
 */
public final class QuestIntegrations implements AutoCloseable {
    /** Quests by PikaMug; another plugin also named Quests has a different main class and is left alone. */
    static final String QUESTS = "Quests", QUESTS_MAIN = "me.pikamug.quests.BukkitQuestsPlugin", BEAUTY_QUESTS = "BeautyQuests",
            BETON_QUEST = "BetonQuest";

    private final JavaPlugin animus;
    private final QuestNpcHost host;
    private final Map<String, AutoCloseable> joined = new ConcurrentHashMap<>();
    private final Set<String> left = ConcurrentHashMap.newKeySet();

    private QuestIntegrations(JavaPlugin animus, QuestNpcHost host) { this.animus = animus; this.host = host; }

    /** While Animus enables: joins the quest plugins already enabled and those that enable later. */
    public static QuestIntegrations start(JavaPlugin animus, QuestNpcHost host) {
        QuestIntegrations integrations = new QuestIntegrations(animus, host);
        for (Plugin plugin : animus.getServer().getPluginManager().getPlugins()) if (plugin.isEnabled()) integrations.join(plugin);
        animus.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler public void enabled(PluginEnableEvent event) { integrations.join(event.getPlugin()); }
            @EventHandler public void disabled(PluginDisableEvent event) { integrations.leave(event.getPlugin()); }
        }, animus);
        return integrations;
    }

    @Override public void close() { for (String bridge : List.copyOf(joined.keySet())) leave(bridge); }

    private void leave(Plugin plugin) {
        String bridge = bridge(plugin.getName(), plugin.getClass().getName());
        if (bridge != null && joined.containsKey(bridge)) { leave(bridge); left.add(bridge); }
    }

    private void leave(String bridge) {
        AutoCloseable handle = joined.remove(bridge);
        if (handle == null) return;
        try { handle.close(); }
        catch (Exception | LinkageError failure) { animus.getLogger().log(Level.WARNING, bridge + " could not be left cleanly: " + failure); }
    }

    /** Which bridge a plugin takes, or null for a plugin Animus does not join when it enables. */
    public static String bridge(String name, String mainClass) {
        if (name.equals(QUESTS)) return mainClass.equals(QUESTS_MAIN) ? QUESTS : null;
        return Set.of(BEAUTY_QUESTS, BETON_QUEST).contains(name) ? name : null;
    }

    private void join(Plugin plugin) {
        String bridge = bridge(plugin.getName(), plugin.getClass().getName());
        if (bridge == null || joined.containsKey(bridge)) return;
        String version = plugin.getDescription().getVersion();
        if (left.contains(bridge)) {
            animus.getLogger().warning(bridge + " " + version + " was reloaded without Animus; restart the server or reload Animus to link Animus NPCs to it again");
            return;
        }
        try {
            AutoCloseable handle = switch (bridge) {
                case QUESTS -> QuestsBridge.install(animus, plugin, host);
                case BEAUTY_QUESTS -> BeautyQuestsBridge.install(animus, host);
                default -> {
                    if (!BetonQuestIntegration.register(animus, host)) { animus.getLogger().warning("BetonQuest: Animus NPCs need BetonQuest 3.0 or newer"); yield null; }
                    yield () -> {};
                }
            };
            if (handle == null) return;
            joined.put(bridge, handle);
            animus.getLogger().info("Linked " + bridge + " " + version + ": Animus NPCs work in its quests");
        } catch (LinkageError | RuntimeException moved) {
            animus.getLogger().log(Level.WARNING, bridge + " " + version + " could not be linked to Animus NPCs: " + moved);
        }
    }
}
