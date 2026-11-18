package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Every event's context is what its kind publishes to the editor: the same keys, each value of its published type. */
class TriggerContextTest {
    private static final UUID NPC = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    private static final Set<String> TYPES = Set.of("boolean", "number", "text", "list", "map");

    /** The value's type as an expression reads it. */
    static String type(Object value) {
        return switch (value) {
            case Boolean ignored -> "boolean";
            case Number ignored -> "number";
            case String ignored -> "text";
            case List<?> ignored -> "list";
            case Map<?, ?> ignored -> "map";
            default -> value.getClass().getSimpleName();
        };
    }
    /** The context has exactly the keys its kind publishes, each of the published type. */
    static void carries(TriggerKind kind, Map<String, Object> context) {
        assertEquals(kind.context().keySet(), context.keySet(), kind.jsonName());
        for (Map.Entry<String, Object> entry : context.entrySet())
            assertEquals(kind.context().get(entry.getKey()), type(entry.getValue()), kind.jsonName() + " context." + entry.getKey());
    }

    @Test void everyKindPublishesItsContextWithTheTypesAnExpressionReads() {
        for (TriggerKind kind : TriggerKind.values())
            for (Map.Entry<String, String> entry : kind.context().entrySet()) assertTrue(TYPES.contains(entry.getValue()), kind.jsonName() + " " + entry);
    }

    @Test void npcEventsCarryWhatTheirKindsPublish() {
        List<TriggerEvent> events = List.of(new TriggerEvent.Interaction(NPC, PLAYER, TriggerEvent.Action.DROP, true, TriggerEvent.Hand.OFF_HAND),
                new TriggerEvent.Proximity(NPC, PLAYER, "town:greeter#near"), new TriggerEvent.Spawn(NPC), new TriggerEvent.Despawn(NPC),
                new TriggerEvent.Damage(NPC, 2.5, Optional.of(new TriggerEvent.Attacker("player", PLAYER))), new TriggerEvent.Damage(NPC, 1, Optional.empty()),
                new TriggerEvent.Death(NPC, Optional.of(new TriggerEvent.Attacker("mob", OTHER))), new TriggerEvent.Waypoint(NPC, 2),
                new TriggerEvent.DialogueChoice(NPC, PLAYER, "menu", "yes"), new TriggerEvent.BuildFinished(NPC), new TriggerEvent.BuildPaused(NPC),
                new TriggerEvent.AnimationFinished(NPC, "town:golem/wave", 0), new TriggerEvent.Module(NPC, Optional.of(PLAYER), new Capabilities.Key("mod", "event")));
        Set<TriggerKind> covered = new HashSet<>();
        for (TriggerEvent event : events) { carries(event.kind(), event.context()); covered.add(event.kind()); }
        carries(TriggerKind.TIMER, Map.of());
        covered.add(TriggerKind.TIMER);
        for (TriggerKind kind : TriggerKind.values()) if (kind.npc() && !kind.sensed()) assertTrue(covered.contains(kind), kind.jsonName() + " has no event here");
    }

    @Test void serverEventsCarryWhatTheirKindsPublish() {
        BehaviorPlatform.HeldItem bread = new BehaviorPlatform.HeldItem(0, "minecraft:bread", 3, Optional.empty());
        List<ServerEvent> events = List.of(ServerEvent.join(PLAYER, true), ServerEvent.quit(PLAYER), ServerEvent.chat(PLAYER, "hi"), ServerEvent.death(PLAYER, Optional.of("fell")),
                ServerEvent.respawn(PLAYER), ServerEvent.worldChange(PLAYER, "world", "world_nether"), ServerEvent.area(PLAYER, TriggerBinding.Phase.ENTER, "plaza", "world", "move"),
                ServerEvent.inventory(TriggerKind.PLAYER_INVENTORY_OPEN, PLAYER, "chest", List.of(bread), "1.00,64.00,2.00,world"), ServerEvent.inventory(TriggerKind.PLAYER_INVENTORY_CLOSE, PLAYER, "player"),
                ServerEvent.inventoryClick(PLAYER, "chest", "left", 4, "minecraft:bread"), ServerEvent.item(TriggerKind.PLAYER_ITEM_DROP, PLAYER, "minecraft:bread", 2),
                ServerEvent.item(TriggerKind.PLAYER_ITEM_PICKUP, PLAYER, "minecraft:bread", 2), ServerEvent.item(TriggerKind.PLAYER_ITEM_CONSUME, PLAYER, "minecraft:bread", 1),
                ServerEvent.block(TriggerKind.PLAYER_BLOCK_PLACE, PLAYER, "minecraft:stone", "world", 1, 64, 2), ServerEvent.block(TriggerKind.PLAYER_BLOCK_BREAK, PLAYER, "minecraft:stone", "world", 1, 64, 2),
                ServerEvent.blockClick(PLAYER, "right", "main_hand", "minecraft:chest", "world", 1, 64, 2), ServerEvent.damaged(PLAYER, "fall", 3, ""),
                ServerEvent.damageEntity(PLAYER, "minecraft:zombie", OTHER, "player_attack", 4), ServerEvent.kill(PLAYER, "minecraft:zombie", OTHER),
                ServerEvent.projectileLaunch(Optional.of(PLAYER), "minecraft:arrow", OTHER, "minecraft:player", PLAYER.toString(), "world", 1, 64, 2),
                ServerEvent.projectileHit(Optional.of(PLAYER), "minecraft:arrow", OTHER, "minecraft:player", PLAYER.toString(), "", "", "minecraft:stone", "world", 1, 64, 2),
                ServerEvent.shootBow(Optional.of(PLAYER), "minecraft:player", PLAYER, "minecraft:bow", "minecraft:arrow", OTHER, 0.5, "main_hand", "world", 1, 64, 2),
                ServerEvent.quest(TriggerKind.QUEST_STARTED, PLAYER, "town:herbs", 1, "Herbs"), ServerEvent.quest(TriggerKind.QUEST_STAGE, PLAYER, "town:herbs", 2, "Herbs"),
                ServerEvent.quest(TriggerKind.QUEST_COMPLETED, PLAYER, "town:herbs", 3, "Herbs"), ServerEvent.serverStart(), ServerEvent.timer(),
                ServerEvent.module(Optional.empty(), new Capabilities.Key("mod", "event")), ServerEvent.command(Optional.of(PLAYER), "warp", "w", "home now"));
        Set<TriggerKind> covered = new HashSet<>();
        for (ServerEvent event : events) { carries(event.kind(), event.context()); covered.add(event.kind()); }
        for (TriggerKind kind : TriggerKind.values()) if (kind.server()) assertTrue(covered.contains(kind), kind.jsonName() + " has no event here");
        assertTrue(TriggerKind.PLAYER_DEATH.context().keySet().containsAll(ServerEvent.death(PLAYER, Optional.empty()).context().keySet()), "a death without a message carries less");
    }
}
