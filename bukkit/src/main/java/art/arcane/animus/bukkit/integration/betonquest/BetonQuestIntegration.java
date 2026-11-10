package art.arcane.animus.bukkit.integration.betonquest;

import art.arcane.animus.bukkit.integration.quests.QuestNpcHost;
import java.util.logging.Logger;
import org.betonquest.betonquest.api.BetonQuestApi;
import org.betonquest.betonquest.api.integration.Integration;
import org.betonquest.betonquest.api.integration.IntegrationService;
import org.betonquest.betonquest.api.service.npc.NpcRegistry;
import org.bukkit.plugin.Plugin;

/**
 * Registers Animus with BetonQuest 3 through its integration service: the {@code animus} NPC type, its reverse lookup, the
 * click bridge and the {@code animusnpckill} objective. Load this class only when BetonQuest is installed.
 */
public final class BetonQuestIntegration implements Integration {
    private final QuestNpcHost host;
    private final Logger log;

    private BetonQuestIntegration(QuestNpcHost host, Logger log) { this.host = host; this.log = log; }

    /** False when BetonQuest offers no integration service, such as a 2.x install. */
    public static boolean register(Plugin animus, QuestNpcHost host) {
        IntegrationService service = animus.getServer().getServicesManager().load(IntegrationService.class);
        if (service == null) return false;
        service.withPolicies().register(animus, () -> new BetonQuestIntegration(host, animus.getLogger()));
        return true;
    }

    @Override public void enable(BetonQuestApi api) {
        NpcRegistry npcs = api.npcs().registry();
        npcs.register(AnimusBqIdentifier.TYPE, new AnimusBqFactory(host));
        npcs.registerIdentifier(new AnimusBqIdentifier());
        api.bukkit().registerEvents(new AnimusBqCatcher(api.profiles(), npcs, host));
        api.objectives().registry().register(AnimusNpcKillObjective.TYPE, new AnimusNpcKillObjective.Factory(npcs, host));
        log.info("BetonQuest: animus NPC type and animusnpckill objective registered");
    }

    @Override public void postEnable(BetonQuestApi api) {}

    @Override public void disable() {}
}
