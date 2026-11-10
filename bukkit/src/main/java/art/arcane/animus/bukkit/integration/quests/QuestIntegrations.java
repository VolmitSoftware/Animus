package art.arcane.animus.bukkit.integration.quests;

import art.arcane.animus.bukkit.integration.betonquest.BetonQuestIntegration;
import art.arcane.animus.bukkit.integration.notquests.NotQuestsBridge;
import art.arcane.animus.bukkit.integration.typewriter.TypewriterExtension;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Connects Animus NPCs to every quest plugin installed: Quests by PikaMug, BeautyQuests, BetonQuest and NotQuests join
 * through one {@link QuestNpcHost} once found enabled, and a plugin enabled later is joined when it enables. Typewriter
 * loads Animus's extension, which {@link #load} installs before plugins enable. Each bridge names its plugin's own
 * types, so Animus never touches them without the plugin, and a plugin version whose API moved is logged instead of
 * failing the start.
 */
public final class QuestIntegrations {
    /** Quests by PikaMug; another plugin also named Quests has a different main class and is left alone. */
    static final String QUESTS = "Quests", QUESTS_MAIN = "me.pikamug.quests.BukkitQuestsPlugin", BEAUTY_QUESTS = "BeautyQuests",
            BETON_QUEST = "BetonQuest", NOT_QUESTS = "NotQuests";

    private final JavaPlugin animus;
    private final QuestNpcHost host;
    private final Set<String> joined = ConcurrentHashMap.newKeySet();

    private QuestIntegrations(JavaPlugin animus, QuestNpcHost host) { this.animus = animus; this.host = host; }

    /** Before any plugin enables: install the Typewriter extension so Typewriter loads it on this start. */
    public static void load(JavaPlugin animus) {
        Plugin typewriter = animus.getServer().getPluginManager().getPlugin("Typewriter");
        if (typewriter == null) return;
        try {
            Optional<String> installed = TypewriterExtension.install(typewriter.getDataFolder().toPath(), typewriter.getDescription().getVersion(),
                    animus.getDescription().getVersion(), animus.getClass().getClassLoader());
            installed.ifPresent((String line) -> animus.getLogger().info("Typewriter: " + line));
        } catch (IOException | RuntimeException failure) {
            animus.getLogger().log(Level.WARNING, "Typewriter: could not install the Animus extension: " + failure.getMessage());
        }
    }

    /** While Animus enables: joins the quest plugins already enabled and those that enable later. */
    public static void start(JavaPlugin animus, QuestNpcHost host) {
        QuestIntegrations integrations = new QuestIntegrations(animus, host);
        for (Plugin plugin : animus.getServer().getPluginManager().getPlugins()) if (plugin.isEnabled()) integrations.join(plugin);
        animus.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler public void enabled(PluginEnableEvent event) { integrations.join(event.getPlugin()); }
        }, animus);
    }

    /** Which bridge a plugin takes, or null for a plugin Animus does not join when it enables. */
    public static String bridge(String name, String mainClass) {
        if (name.equals(QUESTS)) return mainClass.equals(QUESTS_MAIN) ? QUESTS : null;
        return Set.of(BEAUTY_QUESTS, BETON_QUEST, NOT_QUESTS).contains(name) ? name : null;
    }

    private void join(Plugin plugin) {
        String bridge = bridge(plugin.getName(), plugin.getClass().getName());
        if (bridge == null || !joined.add(bridge)) return;
        String version = plugin.getDescription().getVersion();
        try {
            switch (bridge) {
                case QUESTS -> QuestsBridge.install(animus, plugin, host);
                case BEAUTY_QUESTS -> BeautyQuestsBridge.install(animus, host);
                case BETON_QUEST -> { if (!BetonQuestIntegration.register(animus, host)) { animus.getLogger().warning("BetonQuest: Animus NPCs need BetonQuest 3.0 or newer"); return; } }
                default -> NotQuestsBridge.register(animus, host);
            }
            animus.getLogger().info("Linked " + bridge + " " + version + ": Animus NPCs work in its quests");
        } catch (LinkageError | RuntimeException moved) {
            animus.getLogger().log(Level.WARNING, bridge + " " + version + " could not be linked to Animus NPCs: " + moved);
        }
    }
}
