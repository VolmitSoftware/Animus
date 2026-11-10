package art.arcane.animus.bukkit.integration.quests;

import art.arcane.animus.bukkit.api.NpcInfo;
import art.arcane.animus.bukkit.event.VirtualNpcDeathEvent;
import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import me.pikamug.quests.BukkitQuestsPlugin;
import me.pikamug.quests.dependencies.npc.BukkitNpcDependency;
import me.pikamug.quests.listeners.BukkitNpcListener;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

/**
 * Quests by PikaMug. Quests keeps a set of NPC providers keyed by NPC UUID; Animus joins it, so Animus NPCs give quests,
 * take deliveries, count as talk targets and as kill targets, and can be picked in the quest editor by clicking them.
 * Imported Citizens NPCs keep their UUIDs, so quests that already name them by UUID need no change.
 */
final class QuestsBridge {
    static final String LABEL = "Animus";

    private QuestsBridge() {}

    /**
     * Joins the running Quests plugin and listens for Animus NPC clicks and deaths on Animus's behalf. Closing the
     * result leaves Quests when Animus stops; Quests keeps its providers for as long as it runs.
     */
    static AutoCloseable install(Plugin animus, Plugin quests, QuestNpcHost npcs) {
        BukkitQuestsPlugin plugin = (BukkitQuestsPlugin) quests;
        Npcs provider = new Npcs(npcs);
        join(plugin.getDependencies().getNpcDependencies(), provider, plugin.getDependencies()::addNpcDependency);
        Clicks clicks = new Clicks(animus, plugin, provider);
        animus.getServer().getPluginManager().registerEvents(clicks, animus);
        return () -> {
            plugin.getDependencies().getNpcDependencies().remove(provider);
            HandlerList.unregisterAll(clicks);
        };
    }

    /** Adds the provider after dropping one an earlier Animus left, so Quests never asks two Animus providers. */
    static void join(Set<BukkitNpcDependency> providers, Npcs provider, Consumer<BukkitNpcDependency> add) {
        providers.removeIf((BukkitNpcDependency earlier) -> earlier.getClass().getName().equals(Npcs.class.getName()));
        add.accept(provider);
    }

    /** The quest click an Animus interaction is: right for use, left for attack; none for a preview. */
    static Optional<BukkitNpcListener.ClickType> click(VirtualNpcInteractionEvent.Action action, boolean preview) {
        if (preview) return Optional.empty();
        return Optional.of(action == VirtualNpcInteractionEvent.Action.ATTACK ? BukkitNpcListener.ClickType.LEFT : BukkitNpcListener.ClickType.RIGHT);
    }

    /** Animus NPCs as a Quests NPC provider. */
    static final class Npcs implements BukkitNpcDependency {
        private final QuestNpcHost npcs;

        Npcs(QuestNpcHost npcs) { this.npcs = npcs; }

        @Override public String getLabel() { return LABEL; }
        @Override public boolean isNpc(UUID uuid) { return uuid != null && npcs.npcs().byUuid(uuid).isPresent(); }
        @Override public String getName(UUID uuid) { return uuid == null ? null : npcs.npcs().byUuid(uuid).map((NpcInfo npc) -> QuestNpcHost.plainName(npc.name())).orElse(null); }
        @Override public Location getLocation(UUID uuid) { return uuid == null || !isNpc(uuid) ? null : npcs.location(uuid).orElse(null); }
        @Override public List<UUID> getAllNpcUniqueIds() { return npcs.npcs().all().stream().map(NpcInfo::uuid).toList(); }

        /** Spawned NPCs whose current spot passes {@code predicate}; Quests asks this for its quest marker effects. */
        @Override public Map<UUID, Location> getNpcsByLocationPredicate(BiPredicate<UUID, Location> predicate) {
            Map<UUID, Location> found = new HashMap<>();
            for (NpcInfo npc : npcs.npcs().all()) {
                if (!npc.spawned()) continue;
                npcs.location(npc.uuid()).filter((Location at) -> predicate.test(npc.uuid(), at)).ifPresent((Location at) -> found.put(npc.uuid(), at));
            }
            return found;
        }
    }

    /** Hands Animus clicks and player kills to Quests on the main thread, where Quests runs. */
    static final class Clicks extends BukkitNpcListener {
        private final Plugin animus;

        Clicks(Plugin animus, BukkitQuestsPlugin quests, Npcs provider) {
            super(quests, provider);
            this.animus = animus;
        }

        @EventHandler(ignoreCancelled = true)
        public void interacted(VirtualNpcInteractionEvent event) {
            Optional<ClickType> click = click(event.getAction(), event.isPreview());
            if (click.isEmpty() || !npcDependency.isNpc(event.getNpcId())) return;
            Player player = event.getViewer();
            UUID npc = event.getNpcId();
            main(() -> interactNPC(player, npc, click.get()));
        }

        @EventHandler
        public void died(VirtualNpcDeathEvent event) {
            if (!event.getKillerType().map("player"::equals).orElse(false)) return;
            UUID npc = event.getNpcId(), killer = event.getKiller().orElseThrow();
            main(() -> {
                Player player = animus.getServer().getPlayer(killer);
                if (player != null) preKillNPC(player, npc);
            });
        }

        private void main(Runnable work) {
            if (animus.getServer().isPrimaryThread()) work.run();
            else animus.getServer().getScheduler().runTask(animus, work);
        }
    }
}
