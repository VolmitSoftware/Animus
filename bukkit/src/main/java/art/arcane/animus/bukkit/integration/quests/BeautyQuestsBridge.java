package art.arcane.animus.bukkit.integration.quests;

import art.arcane.animus.bukkit.api.NpcInfo;
import art.arcane.animus.bukkit.event.VirtualNpcDeathEvent;
import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import fr.skytasul.quests.api.QuestsAPI;
import fr.skytasul.quests.api.events.internal.BQMobDeathEvent;
import fr.skytasul.quests.api.mobs.MobFactory;
import fr.skytasul.quests.api.npcs.BqInternalNpc;
import fr.skytasul.quests.api.npcs.BqInternalNpcFactory;
import fr.skytasul.quests.api.npcs.NpcClickType;
import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/**
 * BeautyQuests. Animus registers an NPC factory under {@code animus}, so stage NPCs, quest starters and pool NPCs
 * are saved as {@code animus#<npc id>}, and a kill target factory {@code animusNPC} for "kill this NPC" stages. Clicks
 * reach BeautyQuests the way a Citizens click does; when BeautyQuests handles one it cancels the Animus interaction,
 * unless its {@code dont cancel npc click} option is on.
 */
final class BeautyQuestsBridge {
    static final String FACTORY = "animus", MOBS = "animusNPC";

    private BeautyQuestsBridge() {}

    /** Registers the factories with the running BeautyQuests and listens for Animus clicks and deaths. */
    static void install(Plugin animus, QuestNpcHost npcs) {
        Npcs factory = new Npcs(npcs);
        Kills kills = new Kills(npcs);
        QuestsAPI.getAPI().addNpcFactory(FACTORY, factory);
        QuestsAPI.getAPI().registerMobFactory(kills);
        animus.getServer().getPluginManager().registerEvents(new Events(animus, npcs, factory, kills), animus);
    }

    /** The BeautyQuests click an Animus interaction is, or none for a preview. */
    static Optional<NpcClickType> click(VirtualNpcInteractionEvent.Action action, boolean sneaking, boolean preview) {
        return preview ? Optional.empty() : Optional.of(NpcClickType.of(action == VirtualNpcInteractionEvent.Action.ATTACK, sneaking));
    }

    /**
     * The death event BeautyQuests' kill stages count. Its constructor takes the killer as a {@code Player} in the
     * published API and as an {@code Entity} in BeautyQuests 2, so it is found by shape rather than linked by name.
     */
    static Event death(Object npc, Player killer) throws ReflectiveOperationException {
        for (Constructor<?> constructor : BQMobDeathEvent.class.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 4 && parameters[0] == Object.class && parameters[1].isInstance(killer) && parameters[2] == Entity.class && parameters[3] == int.class)
                return (Event) constructor.newInstance(npc, killer, null, 1);
        }
        throw new NoSuchMethodException("BQMobDeathEvent(Object, Player, Entity, int)");
    }

    /** Animus NPCs by id. They have no Bukkit entity, so BeautyQuests skips its particles and talk holograms on them. */
    static final class Npcs implements BqInternalNpcFactory {
        private final QuestNpcHost npcs;

        Npcs(QuestNpcHost npcs) { this.npcs = npcs; }

        @Override public int getTimeToWaitForNPCs() { return 0; }
        @Override public boolean isNPC(Entity entity) { return false; }
        @Override public Collection<String> getIDs() { return npcs.npcs().all().stream().map(NpcInfo::key).toList(); }
        @Override public BqInternalNpc fetchNPC(String id) { return id == null || npcs.npcs().byKey(id).isEmpty() ? null : new Npc(npcs, id); }
    }

    /** One Animus NPC; each answer reads the current definition, so renames and moves show at once. */
    record Npc(QuestNpcHost npcs, String key) implements BqInternalNpc {
        private Optional<NpcInfo> info() { return npcs.npcs().byKey(key); }
        @Override public String getInternalId() { return key; }
        @Override public String getName() { return info().map((NpcInfo npc) -> QuestNpcHost.plainName(npc.name())).orElse(key); }
        @Override public boolean isSpawned() { return info().map(NpcInfo::spawned).orElse(false); }
        @Override public Entity getEntity() { return null; }
        @Override public Location getLocation() { return info().flatMap((NpcInfo npc) -> npcs.location(npc.uuid())).orElse(null); }
        @Override public boolean setNavigationPaused(boolean paused) { return false; }
    }

    /** Kill targets: an Animus NPC by id, chosen in the stage editor by clicking it. */
    static final class Kills implements MobFactory<String> {
        private final QuestNpcHost npcs;
        final Map<UUID, Consumer<String>> choosing = new ConcurrentHashMap<>();

        Kills(QuestNpcHost npcs) { this.npcs = npcs; }

        @Override public String getID() { return MOBS; }
        @Override public ItemStack getFactoryItem() {
            ItemStack item = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) { meta.setDisplayName("Animus NPC"); item.setItemMeta(meta); }
            return item;
        }
        @Override public void itemClick(Player player, Consumer<String> chosen) {
            choosing.put(player.getUniqueId(), chosen);
            player.closeInventory();
            player.sendMessage("Click the Animus NPC to kill.");
        }
        @Override public String fromValue(String value) { return npcs.npcs().byKey(value).isPresent() ? value : null; }
        @Override public String getValue(String key) { return key; }
        @Override public String getName(String key) { return npcs.npcs().byKey(key).map((NpcInfo npc) -> QuestNpcHost.plainName(npc.name())).orElse(key); }
        @Override public EntityType getEntityType(String key) { return EntityType.PLAYER; }
        @Override public List<String> getDescriptiveLore(String key) { return List.of("Animus NPC: " + key); }
        @Override public boolean bukkitMobApplies(String key, Entity entity) { return false; }
    }

    /** Clicks and player kills of Animus NPCs, handed to BeautyQuests. */
    static final class Events implements Listener {
        private final Plugin animus;
        private final QuestNpcHost npcs;
        private final Npcs factory;
        private final Kills kills;

        Events(Plugin animus, QuestNpcHost npcs, Npcs factory, Kills kills) { this.animus = animus; this.npcs = npcs; this.factory = factory; this.kills = kills; }

        @EventHandler(ignoreCancelled = true)
        public void interacted(VirtualNpcInteractionEvent event) {
            Player player = event.getViewer();
            Optional<NpcClickType> click = click(event.getAction(), player.isSneaking(), event.isPreview());
            Optional<NpcInfo> npc = npcs.npcs().byUuid(event.getNpcId());
            if (click.isEmpty() || npc.isEmpty()) return;
            Consumer<String> chosen = kills.choosing.remove(player.getUniqueId());
            if (chosen != null) {
                event.setCancelled(true);
                main(() -> chosen.accept(npc.get().key()));
                return;
            }
            factory.npcClicked(event, npc.get().key(), player, click.get());
        }

        @EventHandler
        public void died(VirtualNpcDeathEvent event) {
            if (!event.getKillerType().map("player"::equals).orElse(false)) return;
            UUID killer = event.getKiller().orElseThrow();
            String key = event.getNpcKey();
            main(() -> {
                Player player = animus.getServer().getPlayer(killer);
                if (player == null) return;
                try { animus.getServer().getPluginManager().callEvent(death(key, player)); }
                catch (ReflectiveOperationException | RuntimeException failure) { animus.getLogger().warning("BeautyQuests kill of " + key + " not counted: " + failure); }
            });
        }

        private void main(Runnable work) {
            if (animus.getServer().isPrimaryThread()) work.run();
            else animus.getServer().getScheduler().runTask(animus, work);
        }
    }
}
