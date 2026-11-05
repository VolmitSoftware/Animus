package art.arcane.animus.core.combat;

import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TargetRulesTest {
    private static final CombatTarget.Details PLAIN = new CombatTarget.Details(20, CombatLoadout.EMPTY, CombatLoadout.EMPTY, List.of(), Set.of());

    private static CombatTarget target(TargetKind kind, String type, String name, Set<TargetCategory> categories, String handle, String squad) {
        return target(kind, type, name, categories, handle, squad, 20, PLAIN);
    }
    private static CombatTarget target(TargetKind kind, String type, String name, Set<TargetCategory> categories, String handle, String squad, double health, CombatTarget.Details details) {
        return new CombatTarget(new TargetRef(kind, UUID.randomUUID()), type, name, new WorldPosition("w", Vec3.ZERO), Hitbox.atFeet(Vec3.ZERO, 1, 2), health, categories, handle,
                Optional.ofNullable(squad), details);
    }
    private static CombatTarget player(String name) { return target(TargetKind.PLAYER, "minecraft:player", name, Set.of(TargetCategory.PLAYERS), "", null); }
    private static TargetRules rules(String json) {
        JsonObject module = JsonParser.parseString("{\"targets\":" + json + "}").getAsJsonObject();
        return CombatSettings.parseModule(module).targets();
    }
    private static boolean matches(String json, CombatTarget target) { return rules(json).matches(target, TargetRules.Context.NONE); }

    @Test void categoriesAndTypesMatch() {
        CombatTarget zombie = target(TargetKind.MOB, "minecraft:zombie", "Zombie", Set.of(TargetCategory.MOBS, TargetCategory.MONSTERS), "", null);
        assertTrue(matches("{\"categories\":[\"monsters\"]}", zombie));
        assertFalse(matches("{\"categories\":[\"animals\"]}", zombie));
        assertTrue(matches("{\"entityTypes\":[\"minecraft:zombie\"]}", zombie));
    }
    @Test void playersMatchByNameIgnoringCaseOrByUuid() {
        CombatTarget alice = player("Alice");
        assertTrue(matches("{\"players\":[\"Alice\"]}", alice));
        assertTrue(matches("{\"players\":[\"" + alice.ref().id() + "\"]}", alice));
        CombatTarget mob = target(TargetKind.MOB, "minecraft:zombie", "Alice", Set.of(TargetCategory.MOBS), "", null);
        assertFalse(matches("{\"players\":[\"alice\"]}", mob), "A custom-named mob is not that player");
    }
    @Test void npcsMatchByIdNameOrSquad() {
        CombatTarget npc = target(TargetKind.NPC, "minecraft:player", "Bandit Boss", Set.of(TargetCategory.NPCS), "boss", "raiders");
        assertTrue(matches("{\"npcs\":[\"boss\"]}", npc));
        assertTrue(matches("{\"npcs\":[\"Bandit Boss\"]}", npc));
        assertTrue(matches("{\"squads\":[\"raiders\"]}", npc));
        assertFalse(matches("{\"squads\":[\"town\"]}", npc));
    }
    @Test void customNamesMatchNonPlayers() {
        CombatTarget mob = target(TargetKind.MOB, "minecraft:zombie", "Bob", Set.of(TargetCategory.MOBS), "", null);
        assertTrue(matches("{\"names\":[\"bob\"]}", mob));
    }
    @Test void patternsMatchAnywhereIgnoringCaseByKind() {
        assertTrue(matches("{\"patterns\":{\"players\":[\"^grief\"]}}", player("Griefer42")));
        assertTrue(matches("{\"patterns\":{\"players\":[\"bad\"]}}", player("xXBadGuyXx")));
        assertFalse(matches("{\"patterns\":{\"players\":[\"^bad$\"]}}", player("BadGuy")));
        assertFalse(matches("{\"patterns\":{\"npcs\":[\"alice\"]}}", player("Alice")), "Player names are not NPC patterns");
        CombatTarget unnamed = target(TargetKind.MOB, "minecraft:cave_spider", "", Set.of(TargetCategory.MOBS), "", null);
        assertTrue(matches("{\"patterns\":{\"names\":[\"spider\"]}}", unnamed), "A mob with no custom name is named by its type");
    }
    @Test void invalidOrOversizedPatternsAreRejectedWithTheirField() {
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class, () -> rules("{\"patterns\":{\"players\":[\"ok\",\"(open\"]}}"));
        assertTrue(invalid.getMessage().startsWith("$.modules.animus:combat.targets.patterns.players[1]: invalid pattern"), invalid.getMessage());
        IllegalArgumentException tooLong = assertThrows(IllegalArgumentException.class, () -> rules("{\"patterns\":{\"names\":[\"" + "a".repeat(129) + "\"]}}"));
        assertTrue(tooLong.getMessage().startsWith("$.modules.animus:combat.targets.patterns.names[0]"), tooLong.getMessage());
    }
    @Test void aCatastrophicPatternStopsAtItsStepLimitInsteadOfHanging() {
        NamePattern pattern = NamePattern.compile("(a+)+b", "$.p");
        long started = System.nanoTime();
        assertFalse(pattern.matches("a".repeat(60)));
        assertTrue(System.nanoTime() - started < 1_000_000_000L, "Bounded backtracking");
        assertEquals(NamePattern.compile("(a+)+b", "$.q"), pattern, "Patterns compare by source");
    }
    @Test void itemsHealthAndEffectsMatch() {
        CombatTarget.Details armed = new CombatTarget.Details(40, "minecraft:diamond_sword", "minecraft:shield", List.of("minecraft:iron_helmet"), Set.of("minecraft:strength"));
        CombatTarget knight = target(TargetKind.PLAYER, "minecraft:player", "Knight", Set.of(TargetCategory.PLAYERS), "", null, 10, armed);
        assertTrue(matches("{\"items\":{\"mainHand\":[\"diamond_sword\"]}}", knight));
        assertFalse(matches("{\"items\":{\"mainHand\":[\"shield\"]}}", knight));
        assertTrue(matches("{\"items\":{\"offHand\":[\"shield\"]}}", knight));
        assertTrue(matches("{\"items\":{\"worn\":[\"iron_\"]}}", knight));
        assertTrue(matches("{\"healthBelow\":30}", knight), "10 of 40 is 25 percent");
        assertFalse(matches("{\"healthAbove\":30}", knight));
        assertTrue(matches("{\"effects\":[\"minecraft:strength\"]}", knight));
        assertFalse(matches("{\"effects\":[\"minecraft:speed\"]}", knight));
        assertTrue(matches("{\"items\":{\"mainHand\":[\"air\"]}}", player("Unarmed")), "An empty hand holds air");
    }
    @Test void permissionsGroupsAndOwnerAskTheContext() {
        CombatTarget alice = player("Alice");
        TargetRules.Context context = new TargetRules.Context() {
            @Override public Optional<UUID> owner() { return Optional.of(alice.ref().id()); }
            @Override public boolean permitted(TargetRef who, String node) { return who.equals(alice.ref()) && (node.equals("town.outlaw") || node.equals("group.bandits")); }
        };
        assertTrue(rules("{\"permissions\":[\"town.outlaw\"]}").matches(alice, context));
        assertFalse(rules("{\"permissions\":[\"town.mayor\"]}").matches(alice, context));
        assertTrue(rules("{\"groups\":[\"bandits\"]}").matches(alice, context));
        assertTrue(rules("{\"owner\":true}").matches(alice, context));
        assertFalse(rules("{\"owner\":true}").matches(player("Bob"), context));
        assertFalse(rules("{\"owner\":true}").matches(alice, TargetRules.Context.NONE), "No owner recorded");
    }
    @Test void allNeedsEveryPartOnOneTarget() {
        CombatTarget.Details sword = new CombatTarget.Details(20, "minecraft:diamond_sword", CombatLoadout.EMPTY, List.of(), Set.of());
        CombatTarget armed = target(TargetKind.PLAYER, "minecraft:player", "Knight", Set.of(TargetCategory.PLAYERS), "", null, 20, sword);
        String rule = "{\"all\":[{\"categories\":[\"players\"],\"items\":{\"mainHand\":[\"sword\"]}}]}";
        assertTrue(matches(rule, armed));
        assertFalse(matches(rule, player("Unarmed")));
        CombatTarget zombie = target(TargetKind.MOB, "minecraft:zombie", "", Set.of(TargetCategory.MOBS, TargetCategory.MONSTERS), "", null, 20, sword);
        assertFalse(matches(rule, zombie), "A zombie with a sword is not a player");
    }
    @Test void nestedSetsCannotNestFurtherAndIgnoresCannotReactToEvents() {
        assertThrows(IllegalArgumentException.class, () -> rules("{\"all\":[{\"all\":[{\"categories\":[\"players\"]}]}]}"));
        assertThrows(IllegalArgumentException.class, () -> rules("{\"all\":[{}]}"));
        assertThrows(IllegalArgumentException.class, () -> rules("{\"together\":[[{\"categories\":[\"players\"]}]]}"), "A group needs two parts");
        IllegalArgumentException ignored = assertThrows(IllegalArgumentException.class,
                () -> CombatSettings.parseModule(JsonParser.parseString("{\"ignores\":{\"events\":{\"kinds\":[\"player_hurts_player\"]}}}").getAsJsonObject()));
        assertTrue(ignored.getMessage().contains("not available in ignores"), ignored.getMessage());
    }
}
