package art.arcane.animus.bukkit.integration.quests;

import art.arcane.animus.bukkit.api.NpcInfo;
import art.arcane.animus.bukkit.event.VirtualNpcDeathEvent;
import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import fr.skytasul.quests.api.QuestsAPI;
import fr.skytasul.quests.api.QuestsPlugin;
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
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/**
 * BeautyQuests. Animus registers an NPC factory under {@code animus}, so stage NPCs, quest starters and pool NPCs
 * are saved as {@code animus#<npc id>}, and a kill target factory {@code animusNPC} for "kill this NPC" stages. Clicks
 * reach BeautyQuests the way a Citizens click does; when BeautyQuests handles one it cancels the Animus interaction,
 * unless its {@code dont cancel npc click} option is on. BeautyQuests keeps a factory for as long as it runs and ties
 * its NPCs to that one instance, so the registered factory is a {@link Shared} one an Animus reload hands over to.
 */
final class BeautyQuestsBridge {
    static final String FACTORY = "animus", MOBS = "animusNPC";

    private BeautyQuestsBridge() {}

    /**
     * Registers the factories with the running BeautyQuests, or hands the factory an earlier Animus left over to this
     * one, and listens for Animus clicks and deaths. Closing the result leaves BeautyQuests when Animus stops.
     */
    static AutoCloseable install(Plugin animus, QuestNpcHost npcs) {
        Npcs factory = new Npcs(npcs);
        Kills kills = new Kills(npcs);
        BqInternalNpcFactory shared = Shared.adopt(registered(), factory).orElse(null);
        if (shared == null) {
            shared = new Shared(factory);
            QuestsAPI.getAPI().addNpcFactory(FACTORY, shared);
        }
        MobFactory.factories.removeIf((MobFactory<?> earlier) -> earlier.getClass().getName().equals(Kills.class.getName()));
        QuestsAPI.getAPI().registerMobFactory(kills);
        Events events = new Events(animus, npcs, shared, kills);
        animus.getServer().getPluginManager().registerEvents(events, animus);
        BqInternalNpcFactory registered = shared;
        return () -> {
            Shared.handOver(registered, null);
            MobFactory.factories.remove(kills);
            HandlerList.unregisterAll(events);
        };
    }

    /** The NPC factories BeautyQuests holds; its API does not list them, so they are read from its NPC manager. */
    private static Collection<?> registered() {
        try {
            Object manager = QuestsPlugin.getPlugin().getNpcManager();
            return (Collection<?>) manager.getClass().getMethod("getInternalFactories").invoke(manager);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unknown) { return List.of(); }
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

    /**
     * The factory BeautyQuests holds: it answers through the current Animus's factory, or with no NPCs while Animus is
     * stopped. It and {@link Ref} use only BeautyQuests and JDK types, so one an earlier Animus made keeps working for
     * the next, which finds it by class name and hands it its factory.
     */
    static final class Shared implements BqInternalNpcFactory, Consumer<BqInternalNpcFactory> {
        private volatile BqInternalNpcFactory current;

        Shared(BqInternalNpcFactory current) {
            this.current = current;
            new Ref(this, "").getInternalId();
        }

        /** Hands {@code next} to the shared factory among {@code registered}, made by this or an earlier Animus, if one is there. */
        static Optional<BqInternalNpcFactory> adopt(Collection<?> registered, BqInternalNpcFactory next) {
            for (Object factory : registered)
                if (factory instanceof BqInternalNpcFactory shared && factory.getClass().getName().equals(Shared.class.getName())) {
                    handOver(shared, next);
                    return Optional.of(shared);
                }
            return Optional.empty();
        }

        @SuppressWarnings("unchecked")
        static void handOver(BqInternalNpcFactory shared, BqInternalNpcFactory next) { ((Consumer<BqInternalNpcFactory>) shared).accept(next); }

        /** The Animus factory to answer through, or null while Animus is stopped. */
        @Override public void accept(BqInternalNpcFactory next) { current = next; }
        @Override public int getTimeToWaitForNPCs() { return 0; }
        @Override public boolean isNPC(Entity entity) { return false; }
        @Override public Collection<String> getIDs() { BqInternalNpcFactory now = current; return now == null ? List.of() : now.getIDs(); }
        @Override public BqInternalNpc fetchNPC(String id) { BqInternalNpcFactory now = current; return now == null || now.fetchNPC(id) == null ? null : new Ref(this, id); }
        BqInternalNpc live(String id) { BqInternalNpcFactory now = current; return now == null ? null : now.fetchNPC(id); }
    }

    /** One NPC as BeautyQuests keeps it: each answer reads the NPC through the shared factory's current Animus. */
    static final class Ref implements BqInternalNpc {
        private final Shared shared;
        private final String key;
        Ref(Shared shared, String key) { this.shared = shared; this.key = key; }
        @Override public String getInternalId() { return key; }
        @Override public String getName() { BqInternalNpc npc = shared.live(key); return npc == null ? key : npc.getName(); }
        @Override public boolean isSpawned() { BqInternalNpc npc = shared.live(key); return npc != null && npc.isSpawned(); }
        @Override public Entity getEntity() { return null; }
        @Override public Location getLocation() { BqInternalNpc npc = shared.live(key); return npc == null ? null : npc.getLocation(); }
        @Override public boolean setNavigationPaused(boolean paused) { return false; }
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

    /** Kill targets: an Animus NPC by id, chosen in the stage editor by clicking it within {@link #CHOOSE_MILLIS}. */
    static final class Kills implements MobFactory<String> {
        static final long CHOOSE_MILLIS = 60_000;
        private record Choice(Consumer<String> chosen, long until) {}
        private final QuestNpcHost npcs;
        private final Map<UUID, Choice> choosing = new ConcurrentHashMap<>();

        Kills(QuestNpcHost npcs) { this.npcs = npcs; }

        @Override public String getID() { return MOBS; }
        @Override public ItemStack getFactoryItem() {
            ItemStack item = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) { meta.setDisplayName("Animus NPC"); item.setItemMeta(meta); }
            return item;
        }
        @Override public void itemClick(Player player, Consumer<String> chosen) {
            choose(player.getUniqueId(), chosen, System.currentTimeMillis());
            player.closeInventory();
            player.sendMessage("Click the Animus NPC to kill within " + CHOOSE_MILLIS / 1000 + " seconds.");
        }

        void choose(UUID player, Consumer<String> chosen, long now) { choosing.put(player, new Choice(chosen, now + CHOOSE_MILLIS)); }
        /** Takes the player's pending choice, empty when there is none or it ran out. */
        Optional<Consumer<String>> chosen(UUID player, long now) {
            Choice choice = choosing.remove(player);
            return choice == null || choice.until() < now ? Optional.empty() : Optional.of(choice.chosen());
        }
        void forget(UUID player) { choosing.remove(player); }
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
        private final BqInternalNpcFactory factory;
        private final Kills kills;

        Events(Plugin animus, QuestNpcHost npcs, BqInternalNpcFactory factory, Kills kills) { this.animus = animus; this.npcs = npcs; this.factory = factory; this.kills = kills; }

        @EventHandler(ignoreCancelled = true)
        public void interacted(VirtualNpcInteractionEvent event) {
            Player player = event.getViewer();
            Optional<NpcClickType> click = click(event.getAction(), player.isSneaking(), event.isPreview());
            Optional<NpcInfo> npc = npcs.npcs().byUuid(event.getNpcId());
            if (click.isEmpty() || npc.isEmpty()) return;
            Optional<Consumer<String>> chosen = kills.chosen(player.getUniqueId(), System.currentTimeMillis());
            if (chosen.isPresent()) {
                event.setCancelled(true);
                main(() -> chosen.get().accept(npc.get().key()));
                return;
            }
            factory.npcClicked(event, npc.get().key(), player, click.get());
        }

        @EventHandler
        public void quit(PlayerQuitEvent event) { kills.forget(event.getPlayer().getUniqueId()); }

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
