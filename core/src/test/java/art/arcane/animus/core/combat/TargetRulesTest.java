package art.arcane.animus.core.combat;

import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TargetRulesTest {
    private static CombatTarget target(TargetKind kind, String type, String name, Set<TargetCategory> categories, String handle, String squad) {
        return new CombatTarget(new TargetRef(kind, UUID.randomUUID()), type, name, new WorldPosition("w", Vec3.ZERO), Hitbox.atFeet(Vec3.ZERO, 1, 2), 20, categories, handle, Optional.ofNullable(squad));
    }
    private static TargetRules rules(Set<TargetCategory> categories, Set<String> types, Set<String> players, Set<String> npcs, Set<String> names, Set<String> squads) {
        return new TargetRules(categories, types, players, npcs, names, squads);
    }

    @Test void categoriesAndTypesMatch() {
        CombatTarget zombie = target(TargetKind.MOB, "minecraft:zombie", "Zombie", Set.of(TargetCategory.MOBS, TargetCategory.MONSTERS), "", null);
        assertTrue(rules(Set.of(TargetCategory.MONSTERS), Set.of(), Set.of(), Set.of(), Set.of(), Set.of()).matches(zombie));
        assertFalse(rules(Set.of(TargetCategory.ANIMALS), Set.of(), Set.of(), Set.of(), Set.of(), Set.of()).matches(zombie));
        assertTrue(rules(Set.of(), Set.of("minecraft:zombie"), Set.of(), Set.of(), Set.of(), Set.of()).matches(zombie));
    }
    @Test void playersMatchByNameIgnoringCaseOrByUuid() {
        CombatTarget alice = target(TargetKind.PLAYER, "minecraft:player", "Alice", Set.of(TargetCategory.PLAYERS), "", null);
        assertTrue(rules(Set.of(), Set.of(), Set.of("Alice"), Set.of(), Set.of(), Set.of()).matches(alice));
        assertTrue(rules(Set.of(), Set.of(), Set.of(alice.ref().id().toString()), Set.of(), Set.of(), Set.of()).matches(alice));
        CombatTarget mob = target(TargetKind.MOB, "minecraft:zombie", "Alice", Set.of(TargetCategory.MOBS), "", null);
        assertFalse(rules(Set.of(), Set.of(), Set.of("alice"), Set.of(), Set.of(), Set.of()).matches(mob), "A custom-named mob is not that player");
    }
    @Test void npcsMatchByIdNameOrSquad() {
        CombatTarget npc = target(TargetKind.NPC, "minecraft:player", "Bandit Boss", Set.of(TargetCategory.NPCS), "boss", "raiders");
        assertTrue(rules(Set.of(), Set.of(), Set.of(), Set.of("boss"), Set.of(), Set.of()).matches(npc));
        assertTrue(rules(Set.of(), Set.of(), Set.of(), Set.of("Bandit Boss"), Set.of(), Set.of()).matches(npc));
        assertTrue(rules(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of("raiders")).matches(npc));
        assertFalse(rules(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of("town")).matches(npc));
    }
    @Test void customNamesMatchNonPlayers() {
        CombatTarget mob = target(TargetKind.MOB, "minecraft:zombie", "Bob", Set.of(TargetCategory.MOBS), "", null);
        assertTrue(rules(Set.of(), Set.of(), Set.of(), Set.of(), Set.of("bob"), Set.of()).matches(mob));
    }
}
