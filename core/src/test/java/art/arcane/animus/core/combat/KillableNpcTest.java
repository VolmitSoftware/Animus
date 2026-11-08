package art.arcane.animus.core.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.config.CombatBudgets;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** NPCs made killable by {@code animus:health}: hurt, killed and brought back with no combat AI. */
class KillableNpcTest {
    private final MockCombatWorld world = new MockCombatWorld();
    private final CombatEngine engine = new CombatEngine(CombatBudgets.DEFAULT, world, ProtectionAdapter.NONE, new Random(3), (String line) -> {});

    private UUID killable(String health, String attributes, double maximum) {
        return killable(health, attributes, maximum, CombatLoadout.NONE);
    }
    private UUID killable(String health, String attributes, double maximum, CombatLoadout loadout) {
        UUID npc = world.npc(0, 0, maximum);
        CombatSettings settings = CombatSettings.parse(HealthSettingsTest.definition("{\"animus:health\":" + health + "}", attributes)).orElseThrow();
        assertTrue(engine.register(npc, "villager", settings, loadout));
        return npc;
    }
    private void run(int ticks) { for (int i = 0; i < ticks; i++) engine.tick(); }
    private static TargetRef player(UUID id) { return new TargetRef(TargetKind.PLAYER, id); }
    private double health(UUID npc) { return world.npc(npc).orElseThrow().health(); }
    private CombatWorld.DamageOutcome hit(UUID npc, UUID player, double amount) {
        return engine.playerAttack(npc, player(player), MockCombatWorld.at(-1, 64, 0), amount);
    }

    @Test void aPassiveNpcTakesHitsButNeverScansOrFightsBack() {
        UUID npc = killable("{\"killable\":true,\"invulnerableTicks\":0}", null, 20);
        UUID alice = world.player("Alice", 1, 0);
        run(40);
        assertEquals(0, world.realScans + world.npcScans, "A passive NPC never looks for targets");
        assertEquals(CombatWorld.DamageStatus.APPLIED, hit(npc, alice, 6).status());
        assertEquals(14, health(npc));
        run(40);
        assertTrue(world.realDamage.isEmpty(), "It does not fight back");
        assertTrue(engine.status(npc).orElseThrow().target().isEmpty());
        assertEquals(.4, world.knocked.get(npc), 1e-9, "A player's hit knocks it back");
    }

    @Test void knockbackResistanceShortensTheKnockback() {
        UUID npc = killable("{\"killable\":true}", "{\"knockback_resistance\":0.75}", 20);
        hit(npc, world.player("Alice", 1, 0), 2);
        assertEquals(.1, world.knocked.get(npc), 1e-9);
    }

    @Test void onlyListedSourcesHurtIt() {
        UUID npc = killable("{\"killable\":true,\"sources\":[\"mobs\"]}", null, 20);
        assertEquals(CombatWorld.DamageStatus.NO_CHANGE, hit(npc, world.player("Alice", 1, 0), 5).status());
        assertEquals(20, health(npc));
    }

    @Test void theAttackerPermissionGatesPlayers() {
        UUID npc = killable("{\"killable\":true,\"attackerPermission\":\"town.hit\",\"invulnerableTicks\":0}", null, 20);
        UUID alice = world.player("Alice", 1, 0), bob = world.player("Bob", 1, 0);
        world.permissions.add(bob + " town.hit");
        assertEquals(CombatWorld.DamageStatus.NO_CHANGE, hit(npc, alice, 5).status());
        assertEquals(CombatWorld.DamageStatus.APPLIED, hit(npc, bob, 5).status());
        assertEquals(15, health(npc));
    }

    @Test void anNpcThatIsNotKillableIsNeverRegisteredAndTakesNoHits() {
        CombatSettings settings = CombatSettings.parse(HealthSettingsTest.definition("{\"animus:health\":{\"killable\":false}}", null)).orElseThrow();
        assertFalse(settings.enabled());
    }

    @Test void deathRollsDropsWithCountsAndChancesDropsEquipmentAndCreditsTheKiller() {
        CombatLoadout loadout = new CombatLoadout(Optional.empty(), Map.of("head", "minecraft:iron_helmet"), Map.of(),
                List.of(new CombatWorld.Drop("minecraft:iron_helmet", 1, Optional.of("[damage=3]"))));
        UUID npc = killable("{\"killable\":true,\"death\":{\"experience\":4,\"dropEquipment\":true,\"drops\":["
                + "{\"item\":{\"id\":\"minecraft:emerald\",\"count\":2},\"maxCount\":2},"
                + "{\"item\":{\"id\":\"minecraft:diamond\"},\"chance\":0}]}}", null, 4, loadout);
        UUID alice = world.player("Alice", 1, 0);
        CombatWorld.DamageOutcome outcome = hit(npc, alice, 10);
        assertTrue(outcome.killed());
        assertEquals(List.of(new CombatWorld.Drop("minecraft:emerald", 2, Optional.empty()), new CombatWorld.Drop("minecraft:iron_helmet", 1, Optional.of("[damage=3]"))), world.drops);
        assertEquals(4, world.experience);
        assertEquals(List.of(player(alice)), world.credits);
        assertEquals(CombatState.DEAD, engine.status(npc).orElseThrow().state());
    }

    @Test void dropCountsStayWithinTheirRange() {
        for (int i = 0; i < 20; i++) {
            world.drops.clear();
            UUID npc = killable("{\"killable\":true,\"death\":{\"drops\":[{\"item\":{\"id\":\"minecraft:emerald\",\"count\":1},\"maxCount\":3}]}}", null, 1);
            hit(npc, world.player("Alice", 1, 0), 5);
            int amount = world.drops.getFirst().amount();
            assertTrue(amount >= 1 && amount <= 3, "Rolled " + amount);
        }
    }

    @Test void theAttackerSeesHealthOnTheActionBarWhenShown() {
        UUID npc = killable("{\"killable\":true,\"showHealth\":true}", null, 20);
        UUID alice = world.player("Alice", 1, 0);
        hit(npc, alice, 5);
        assertEquals(List.of("villager 15/20"), world.actionBars.get(alice));
    }

    @Test void respawnPointsInOrderCycleAndNoPointsMeansHome() {
        UUID npc = killable("{\"killable\":true,\"respawn\":{\"delayTicks\":5,\"points\":[{\"world\":\"world\",\"x\":10,\"y\":64,\"z\":0},{\"world\":\"world\",\"x\":20,\"y\":64,\"z\":0}]}}", null, 2);
        UUID alice = world.player("Alice", 1, 0);
        List<Double> places = new java.util.ArrayList<>();
        for (int death = 0; death < 3; death++) {
            hit(npc, alice, 5);
            run(4);
            assertEquals(CombatState.DEAD, engine.status(npc).orElseThrow().state(), "Not before the delay");
            run(3);
            places.add(world.npc(npc).orElseThrow().position().point().x());
            assertEquals(2, health(npc), "Full health again");
        }
        assertEquals(List.of(10.0, 20.0, 10.0), places);
        UUID homed = killable("{\"killable\":true,\"respawn\":{\"delayTicks\":1}}", null, 2);
        world.homes.put(homed, MockCombatWorld.at(-7, 64, 3));
        world.moveNpc(homed, 4, 4);
        hit(homed, alice, 5);
        run(3);
        assertEquals(new Vec3(-7, 64, 3), world.npc(homed).orElseThrow().position().point());
    }

    @Test void theNearestPointIsNearestToWhereItFell() {
        UUID npc = killable("{\"killable\":true,\"respawn\":{\"delayTicks\":1,\"order\":\"nearest\",\"points\":[{\"world\":\"world\",\"x\":50,\"y\":64,\"z\":0},{\"world\":\"world\",\"x\":-5,\"y\":64,\"z\":0}]}}", null, 2);
        hit(npc, world.player("Alice", 1, 0), 5);
        run(3);
        assertEquals(-5, world.npc(npc).orElseThrow().position().point().x());
    }

    @Test void respawningAwayFromItsPostWalksBack() {
        UUID npc = killable("{\"killable\":true,\"respawn\":{\"delayTicks\":1,\"points\":[{\"world\":\"world\",\"x\":12,\"y\":64,\"z\":0}]}}", null, 2);
        world.posts.put(npc, MockCombatWorld.at(0, 64, 0));
        hit(npc, world.player("Alice", 1, 0), 5);
        run(4);
        assertEquals(CombatState.RETURNING, engine.status(npc).orElseThrow().state());
        assertFalse(world.moves.isEmpty());
    }

    @Test void aPatrollingNpcResumesItsRouteAfterRespawning() {
        UUID npc = killable("{\"killable\":true,\"respawn\":{\"delayTicks\":1}}", null, 2);
        hit(npc, world.player("Alice", 1, 0), 5);
        run(3);
        assertTrue(world.released.contains(npc), "No post to return to, so the patrol resumes");
    }

    @Test void permadeathIsRecordedOnceAndOnlyARevivalBringsItBack() {
        UUID npc = killable("{\"killable\":true,\"respawn\":{\"afterDeath\":\"permadeath\"}}", null, 2);
        hit(npc, world.player("Alice", 1, 0), 5);
        run(400);
        assertEquals(List.of(npc), engine.drainPermadeaths());
        assertEquals(List.of(), engine.drainPermadeaths());
        assertTrue(world.revived.isEmpty());
        assertEquals(CombatOperations.Status.APPLIED, engine.revive(npc).status());
        assertEquals(List.of(npc), world.revived);
        assertEquals(2, health(npc));
        assertEquals(CombatOperations.Status.APPLIED, engine.revive(npc).status(), "Reviving a living NPC heals it");
    }

    @Test void aPermadeathWaitsForTheFallUnlessCombatCloses() {
        UUID npc = killable("{\"killable\":true,\"respawn\":{\"afterDeath\":\"permadeath\"}}", null, 2);
        hit(npc, world.player("Alice", 1, 0), 5);
        run(5);
        assertEquals(List.of(), engine.drainPermadeaths(), "The body is still falling");
        engine.close();
        assertEquals(List.of(npc), engine.drainPermadeaths(), "Closing records it at once");
    }

    @Test void regenerationHealsOnlyWhenOn() {
        UUID healing = killable("{\"killable\":true,\"regenerate\":true,\"regenerateTicks\":10}", null, 20);
        UUID still = killable("{\"killable\":true}", null, 20);
        UUID alice = world.player("Alice", 1, 0);
        hit(healing, alice, 5);
        hit(still, alice, 5);
        run(25);
        assertTrue(health(healing) > 15);
        assertEquals(15, health(still));
    }

    @Test void aDeathFromAnotherNpcIsCreditedToNoPlayer() {
        UUID npc = killable("{\"killable\":true}", null, 2);
        UUID hunter = world.npc(2, 0, 20);
        assertTrue(engine.register(hunter, "hunter", CombatSettings.parseModule(com.google.gson.JsonParser.parseString(
                "{\"enabled\":true,\"targets\":{\"npcs\":[\"villager\"]},\"targeting\":{\"scanIntervalTicks\":1},\"melee\":{\"damage\":10,\"cooldownTicks\":1}}").getAsJsonObject(), HealthSettings.UNKILLABLE), CombatLoadout.NONE));
        run(3);
        assertEquals(0, health(npc), "Combat NPCs may kill a killable NPC");
        assertEquals(List.of(new TargetRef(TargetKind.NPC, hunter)), world.credits);
    }

    @Test void theRespawnPointIsResolvedAtDeathTime() {
        UUID npc = killable("{\"killable\":true,\"respawn\":{\"delayTicks\":1,\"points\":[{\"world\":\"other\",\"x\":1,\"y\":2,\"z\":3}]}}", null, 2);
        hit(npc, world.player("Alice", 1, 0), 5);
        run(3);
        assertEquals(new WorldPosition("other", new Vec3(1, 2, 3)), world.npc(npc).orElseThrow().position());
    }
}
