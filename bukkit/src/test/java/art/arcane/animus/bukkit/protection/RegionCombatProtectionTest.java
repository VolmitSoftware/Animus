package art.arcane.animus.bukkit.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.bukkit.protection.RegionFlags.Flag;
import art.arcane.animus.bukkit.protection.RegionFlags.State;
import art.arcane.animus.core.combat.ProtectionAdapter;
import art.arcane.animus.core.combat.ProtectionAdapter.Action;
import art.arcane.animus.core.combat.ProtectionAdapter.Decision;
import art.arcane.animus.core.combat.ProtectionGate;
import art.arcane.animus.core.combat.TargetCategory;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegionCombatProtectionTest {
    private static final UUID NPC = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID ANIMAL = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final WorldPosition OUTSIDE = at(0), INSIDE = at(20);

    private static WorldPosition at(double x) { return new WorldPosition("world", new Vec3(x + .5, 61, .5)); }
    private static ProtectionAdapter.Request request(Action action, TargetRef attacker, WorldPosition from, TargetRef target, WorldPosition to, Set<TargetCategory> categories) {
        return new ProtectionAdapter.Request(action, attacker, from, target, to, categories);
    }
    private static ProtectionAdapter.Request npcHitsPlayer(WorldPosition from, WorldPosition to) {
        return request(Action.NPC_ATTACK, new TargetRef(TargetKind.NPC, NPC), from, new TargetRef(TargetKind.PLAYER, PLAYER), to, Set.of(TargetCategory.PLAYERS));
    }

    @Test void pvpDenyWhereTheTargetStandsProtectsAPlayer() {
        FakeRegionFlags flags = new FakeRegionFlags().set(20, 0, Flag.PVP, State.DENY);
        RegionCombatProtection protection = new RegionCombatProtection(flags);
        assertEquals(Decision.DENY, protection.decide(npcHitsPlayer(OUTSIDE, INSIDE)));
        assertEquals(Decision.ALLOW, protection.decide(npcHitsPlayer(OUTSIDE, at(5))));
    }

    @Test void pvpDenyWhereTheAttackerStandsAlsoDenies() {
        FakeRegionFlags flags = new FakeRegionFlags().set(20, 0, Flag.PVP, State.DENY);
        assertEquals(Decision.DENY, new RegionCombatProtection(flags).decide(npcHitsPlayer(INSIDE, OUTSIDE)));
    }

    @Test void npcAttackerIsANonMemberAndThePlayerTargetIsAskedAboutAsThemselves() {
        FakeRegionFlags flags = new FakeRegionFlags();
        new RegionCombatProtection(flags).decide(npcHitsPlayer(OUTSIDE, INSIDE));
        List<FakeRegionFlags.Query> pvp = flags.queries.stream().filter((FakeRegionFlags.Query q) -> q.flags().equals(List.of(Flag.PVP))).toList();
        assertEquals(2, pvp.size());
        for (FakeRegionFlags.Query query : pvp) assertNull(query.player());
        FakeRegionFlags.Query invincible = flags.queries.stream().filter((FakeRegionFlags.Query q) -> q.flags().equals(List.of(Flag.INVINCIBLE))).findFirst().orElseThrow();
        assertEquals(PLAYER, invincible.player());
        assertEquals(INSIDE, invincible.at());
    }

    @Test void playerAttackerIsAskedAboutAsThemselves() {
        FakeRegionFlags flags = new FakeRegionFlags();
        new RegionCombatProtection(flags).decide(request(Action.PLAYER_ATTACK, new TargetRef(TargetKind.PLAYER, PLAYER), OUTSIDE,
                new TargetRef(TargetKind.NPC, NPC), INSIDE, Set.of(TargetCategory.NPCS)));
        assertTrue(flags.queries.stream().filter((FakeRegionFlags.Query q) -> q.flags().equals(List.of(Flag.PVP))).allMatch((FakeRegionFlags.Query q) -> PLAYER.equals(q.player())));
        assertFalse(flags.queries.stream().anyMatch((FakeRegionFlags.Query q) -> q.flags().contains(Flag.INVINCIBLE)), "only a player target can be invincible");
    }

    @Test void invinciblePlayerIsNeverHurtEvenWherePvpIsAllowed() {
        FakeRegionFlags flags = new FakeRegionFlags().set(20, 0, Flag.INVINCIBLE, State.ALLOW).set(20, 0, Flag.PVP, State.ALLOW).set(0, 0, Flag.PVP, State.ALLOW);
        assertEquals(Decision.DENY, new RegionCombatProtection(flags).decide(npcHitsPlayer(OUTSIDE, INSIDE)));
    }

    @Test void pvpGuardsAnNpcTarget() {
        FakeRegionFlags flags = new FakeRegionFlags().set(20, 0, Flag.PVP, State.DENY);
        ProtectionAdapter.Request hit = request(Action.NPC_ATTACK, new TargetRef(TargetKind.NPC, NPC), OUTSIDE,
                new TargetRef(TargetKind.NPC, UUID.randomUUID()), INSIDE, Set.of(TargetCategory.NPCS));
        assertEquals(Decision.DENY, new RegionCombatProtection(flags).decide(hit));
    }

    @Test void damageAnimalsDecidesAnimalTargetsWhereTheAnimalStands() {
        FakeRegionFlags flags = new FakeRegionFlags().set(20, 0, Flag.DAMAGE_ANIMALS, State.DENY);
        RegionCombatProtection protection = new RegionCombatProtection(flags);
        TargetRef npc = new TargetRef(TargetKind.NPC, NPC), cow = new TargetRef(TargetKind.MOB, ANIMAL);
        assertEquals(Decision.DENY, protection.decide(request(Action.NPC_ATTACK, npc, OUTSIDE, cow, INSIDE, Set.of(TargetCategory.ANIMALS))));
        assertEquals(Decision.ALLOW, protection.decide(request(Action.NPC_ATTACK, npc, INSIDE, cow, OUTSIDE, Set.of(TargetCategory.ANIMALS))));
        assertFalse(flags.queries.stream().anyMatch((FakeRegionFlags.Query q) -> q.flags().contains(Flag.PVP)));
    }

    @Test void monstersAreNotLimitedByRegions() {
        FakeRegionFlags flags = new FakeRegionFlags().set(20, 0, Flag.PVP, State.DENY).set(20, 0, Flag.DAMAGE_ANIMALS, State.DENY).set(20, 0, Flag.MOB_DAMAGE, State.DENY);
        ProtectionAdapter.Request hit = request(Action.NPC_ATTACK, new TargetRef(TargetKind.NPC, NPC), INSIDE,
                new TargetRef(TargetKind.MOB, UUID.randomUUID()), INSIDE, Set.of(TargetCategory.MONSTERS));
        assertEquals(Decision.ALLOW, new RegionCombatProtection(flags).decide(hit));
        assertTrue(flags.queries.isEmpty());
    }

    @Test void mobDamageDecidesMobHitsAndControlWhereTheNpcStands() {
        FakeRegionFlags flags = new FakeRegionFlags().set(20, 0, Flag.MOB_DAMAGE, State.DENY);
        RegionCombatProtection protection = new RegionCombatProtection(flags);
        TargetRef zombie = new TargetRef(TargetKind.MOB, UUID.randomUUID()), npc = new TargetRef(TargetKind.NPC, NPC);
        for (Action action : List.of(Action.MOB_ATTACK, Action.MOB_CONTROL)) {
            assertEquals(Decision.DENY, protection.decide(request(action, zombie, OUTSIDE, npc, INSIDE, Set.of(TargetCategory.NPCS))), action.name());
            assertEquals(Decision.ALLOW, protection.decide(request(action, zombie, INSIDE, npc, OUTSIDE, Set.of(TargetCategory.NPCS))), action.name());
        }
        assertTrue(flags.queries.stream().allMatch((FakeRegionFlags.Query q) -> q.player() == null && q.flags().equals(List.of(Flag.MOB_DAMAGE))));
    }

    @Test void unsetFlagsAllow() {
        assertEquals(Decision.ALLOW, new RegionCombatProtection(new FakeRegionFlags()).decide(npcHitsPlayer(OUTSIDE, INSIDE)));
    }

    @Test void failedFlagQueryDeniesThroughTheGate() {
        FakeRegionFlags flags = new FakeRegionFlags();
        flags.failure = new IllegalStateException("WorldGuard is disabled");
        List<String> diagnostics = new ArrayList<>();
        ProtectionGate gate = new ProtectionGate(new RegionCombatProtection(flags), diagnostics::add);
        assertFalse(gate.allows(npcHitsPlayer(OUTSIDE, INSIDE)));
        assertFalse(gate.allows(request(Action.MOB_ATTACK, new TargetRef(TargetKind.MOB, UUID.randomUUID()), OUTSIDE, new TargetRef(TargetKind.NPC, NPC), INSIDE, Set.of(TargetCategory.NPCS))));
        assertEquals(2, gate.failures());
        assertEquals(2, gate.denials());
        assertTrue(diagnostics.get(0).contains("WorldGuard is disabled"), diagnostics.toString());
    }

    @Test void regionLeashAsksTheNamedRegionAndAFailureIsUnavailable() {
        FakeRegionFlags flags = new FakeRegionFlags().region("castle", 20.5, .5, true).region("castle", 40.5, .5, false);
        RegionCombatProtection protection = new RegionCombatProtection(flags);
        assertEquals(Optional.of(true), protection.inRegion("castle", INSIDE));
        assertEquals(Optional.of(false), protection.inRegion("castle", at(40)));
        assertEquals(Optional.empty(), protection.inRegion("keep", INSIDE));
        flags.failure = new IllegalStateException("WorldGuard is disabled");
        ProtectionGate gate = new ProtectionGate(protection, (String message) -> { });
        assertEquals(Optional.empty(), gate.inRegion("castle", INSIDE));
        assertEquals(1, gate.failures());
    }
}
