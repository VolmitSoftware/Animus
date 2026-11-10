package art.arcane.animus.bukkit.integration.quests;

import art.arcane.animus.bukkit.event.VirtualNpcInteractionEvent;
import art.arcane.animus.bukkit.runtime.BukkitNpcLookup;
import art.arcane.animus.core.npc.NpcDefinition;
import fr.skytasul.quests.api.events.internal.BQMobDeathEvent;
import fr.skytasul.quests.api.npcs.NpcClickType;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import me.pikamug.quests.listeners.BukkitNpcListener;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Animus NPCs seen through the Quests and BeautyQuests NPC APIs, compiled and run against the plugins' API jars. */
class QuestBridgesTest {
    private static final UUID BAKER = UUID.fromString("00000000-0000-4000-8000-000000000004");
    private static final UUID GHOST = UUID.fromString("00000000-0000-4000-8000-000000000005");
    private static final UUID WALKER = UUID.fromString("00000000-0000-4000-8000-000000000006");
    private static final UUID WORLD_ID = UUID.fromString("00000000-0000-4000-8000-0000000000ee");
    private static final World WORLD = (World) Proxy.newProxyInstance(QuestBridgesTest.class.getClassLoader(), new Class<?>[]{World.class},
            (Object proxy, java.lang.reflect.Method method, Object[] args) -> switch (method.getName()) {
                case "getName" -> "world";
                case "getUID" -> WORLD_ID;
                case "equals" -> proxy == args[0];
                case "hashCode" -> 1;
                default -> null;
            });

    private static NpcDefinition npc(UUID uuid, String id, String name, boolean spawned, double x) throws Exception {
        return NpcDefinition.parse("""
                {"schemaVersion":1,"revision":1,"uuid":"%s","id":"%s","group":"","name":"%s","type":"minecraft:player","spawned":%s,
                 "position":{"world":"world","x":%s,"y":64,"z":0,"yaw":0,"pitch":0}}
                """.formatted(uuid, id, name, spawned, x));
    }

    /** The quest NPC host every quest plugin bridge reads, over fixed definitions: the walker stands somewhere live. */
    private record Host(art.arcane.animus.bukkit.api.AnimusNpcs npcs, Map<UUID, Location> spots) implements QuestNpcHost {
        @Override public Optional<Location> location(UUID npc) { return Optional.ofNullable(spots.get(npc)); }
        @Override public Optional<Double> height(UUID npc) { return Optional.empty(); }
        @Override public java.util.OptionalInt citizensId(UUID npc) { return java.util.OptionalInt.empty(); }
        @Override public void teleport(String key, Location to) {}
        @Override public void spawned(String key, boolean spawned) {}
        @Override public void hide(UUID viewer, UUID npc, boolean hidden) {}
        @Override public boolean hidden(UUID viewer, UUID npc) { return false; }
    }

    private static QuestNpcHost npcs() throws Exception {
        Map<String, NpcDefinition> definitions = Map.of("citizens-4", npc(BAKER, "citizens-4", "&6Baker", true, 10),
                "ghost", npc(GHOST, "ghost", "Ghost", false, 20), "walker", npc(WALKER, "walker", "Walker", true, 30));
        return new Host(new BukkitNpcLookup(() -> definitions), Map.of(BAKER, new Location(WORLD, 10, 64, 0), GHOST, new Location(WORLD, 20, 64, 0),
                WALKER, new Location(WORLD, 31, 65, 2)));
    }

    private static Player player() {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(QuestBridgesTest.class.getClassLoader(), new Class<?>[]{Player.class},
                (Object proxy, java.lang.reflect.Method method, Object[] args) -> method.getName().equals("getUniqueId") ? id : null);
    }

    @Test void everyQuestPluginJoinsThroughOneEntry() {
        assertEquals("Quests", QuestIntegrations.bridge("Quests", "me.pikamug.quests.BukkitQuestsPlugin"));
        assertNull(QuestIntegrations.bridge("Quests", "com.leonardobishop.quests.bukkit.BukkitQuestsPlugin"));
        assertEquals("BeautyQuests", QuestIntegrations.bridge("BeautyQuests", "fr.skytasul.quests.BeautyQuestsPaper"));
        assertEquals("BetonQuest", QuestIntegrations.bridge("BetonQuest", "org.betonquest.betonquest.BetonQuest"));
        assertEquals("NotQuests", QuestIntegrations.bridge("NotQuests", "rocks.gravili.notquests.paper.NotQuests"));
        assertNull(QuestIntegrations.bridge("Typewriter", "com.typewritermc.engine.paper.TypewriterPaperPlugin"), "Typewriter loads its extension before plugins enable");
        assertNull(QuestIntegrations.bridge("Citizens", "net.citizensnpcs.Citizens"));
    }

    @Test void questsSeesAnimusNpcsByUuid() throws Exception {
        QuestsBridge.Npcs provider = new QuestsBridge.Npcs(npcs());
        assertEquals("Animus", provider.getLabel());
        assertTrue(provider.isNpc(BAKER));
        assertFalse(provider.isNpc(UUID.randomUUID()));
        assertFalse(provider.isNpc(null));
        assertEquals("Baker", provider.getName(BAKER));
        assertNull(provider.getName(UUID.randomUUID()));
        assertEquals(10.0, provider.getLocation(BAKER).getX());
        assertNull(provider.getLocation(UUID.randomUUID()));
        assertEquals(List.of(BAKER, GHOST, WALKER), provider.getAllNpcUniqueIds());
        Map<UUID, Location> near = provider.getNpcsByNearbyLocation(new Location(WORLD, 12, 64, 0), 5);
        assertEquals(List.of(BAKER), List.copyOf(near.keySet()));
        assertEquals(List.of(BAKER, WALKER), provider.getNpcsByLocationPredicate((UUID npc, Location at) -> true).keySet().stream().sorted().toList(), "a despawned NPC shows no quest marker");
    }

    @Test void questsClicksAreRightForUseAndLeftForAttack() {
        assertEquals(Optional.of(BukkitNpcListener.ClickType.RIGHT), QuestsBridge.click(VirtualNpcInteractionEvent.Action.INTERACT, false));
        assertEquals(Optional.of(BukkitNpcListener.ClickType.LEFT), QuestsBridge.click(VirtualNpcInteractionEvent.Action.ATTACK, false));
        assertEquals(Optional.empty(), QuestsBridge.click(VirtualNpcInteractionEvent.Action.INTERACT, true));
    }

    @Test void beautyQuestsFactoryServesAnimusNpcsById() throws Exception {
        BeautyQuestsBridge.Npcs factory = new BeautyQuestsBridge.Npcs(npcs());
        assertEquals(List.of("citizens-4", "ghost", "walker"), List.copyOf(factory.getIDs()));
        assertNull(factory.fetchNPC("nobody"));
        var baker = factory.fetchNPC("citizens-4");
        assertEquals("citizens-4", baker.getInternalId());
        assertEquals("Baker", baker.getName());
        assertTrue(baker.isSpawned());
        assertNull(baker.getEntity());
        assertEquals(10.0, baker.getLocation().getX());
        assertFalse(factory.fetchNPC("ghost").isSpawned());
    }

    @Test void beautyQuestsClicksKeepSneaking() {
        assertEquals(Optional.of(NpcClickType.SHIFT_RIGHT), BeautyQuestsBridge.click(VirtualNpcInteractionEvent.Action.INTERACT, true, false));
        assertEquals(Optional.of(NpcClickType.LEFT), BeautyQuestsBridge.click(VirtualNpcInteractionEvent.Action.ATTACK, false, false));
        assertEquals(Optional.empty(), BeautyQuestsBridge.click(VirtualNpcInteractionEvent.Action.ATTACK, false, true));
    }

    @Test void beautyQuestsKillTargetsAreAnimusNpcIds() throws Exception {
        BeautyQuestsBridge.Kills kills = new BeautyQuestsBridge.Kills(npcs());
        assertEquals("animusNPC", kills.getID());
        assertEquals("citizens-4", kills.fromValue("citizens-4"));
        assertNull(kills.fromValue("nobody"), "a deleted NPC fails to load like a missing Citizens NPC");
        assertEquals("Baker", kills.getName("citizens-4"));
        assertTrue(kills.mobApplies("citizens-4", "citizens-4"));
        assertFalse(kills.mobApplies("citizens-4", "walker"));
        Player killer = player();
        BQMobDeathEvent death = (BQMobDeathEvent) BeautyQuestsBridge.death("citizens-4", killer);
        assertEquals("citizens-4", death.getPluginMob());
        assertSame(killer, death.getKiller());
        assertNull(death.getBukkitEntity());
        assertEquals(1, death.getAmount());
    }
}
