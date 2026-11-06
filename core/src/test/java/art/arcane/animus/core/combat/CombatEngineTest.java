package art.arcane.animus.core.combat;

import art.arcane.animus.core.config.CombatBudgets;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatEngineTest {
    private static final String HUNT_MONSTERS = "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":16},\"melee\":{\"damage\":4,\"reach\":3,\"cooldownTicks\":20}}";
    private static final String ARCHER = "{\"targets\":{\"categories\":[\"monsters\",\"npcs\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":30},\"pursuit\":{\"giveUpRange\":40},\"melee\":{\"enabled\":false},\"ranged\":{\"enabled\":true,\"damage\":5,\"range\":30,\"cooldownTicks\":20,\"velocity\":1.6}}";
    private final MockCombatWorld world = new MockCombatWorld();
    private final List<ProtectionAdapter.Request> protectionCalls = new ArrayList<>();
    private ProtectionAdapter protection = ProtectionAdapter.NONE;
    private CombatBudgets budgets = CombatBudgets.DEFAULT;
    private CombatEngine engine;

    private final List<String> diagnostics = new ArrayList<>();
    private java.util.function.BiFunction<String, WorldPosition, Optional<Boolean>> regions = (String region, WorldPosition at) -> Optional.empty();

    private CombatEngine engine() {
        if (engine == null) engine = new CombatEngine(budgets, world, new ProtectionAdapter() {
            @Override public Decision decide(Request request) {
                protectionCalls.add(request);
                return protection.decide(request);
            }
            @Override public Optional<Boolean> inRegion(String region, WorldPosition position) { return regions.apply(region, position); }
        }, new Random(7), diagnostics::add);
        return engine;
    }
    private static CombatSettings settings(String json) {
        JsonObject module = JsonParser.parseString(json).getAsJsonObject();
        module.addProperty("enabled", true);
        return CombatSettings.parseModule(module);
    }
    private UUID combatant(double x, double z, String json) {
        UUID npc = world.npc(x, z, 20);
        assertTrue(engine().register(npc, "npc-" + npc, settings(json), CombatLoadout.NONE));
        return npc;
    }
    private void run(int ticks) { for (int i = 0; i < ticks; i++) engine().tick(); }
    private CombatEngine.Status status(UUID npc) { return engine().status(npc).orElseThrow(); }
    private UUID onlyHelper() { return world.helpers.keySet().iterator().next(); }
    private static TargetRef mob(UUID id) { return new TargetRef(TargetKind.MOB, id); }
    private static TargetRef player(UUID id) { return new TargetRef(TargetKind.PLAYER, id); }
    private static WorldPosition near() { return MockCombatWorld.at(1, 64, 0); }

    @Test void idleNpcAcquiresAMonsterInRangeAndPursuesThenAttacks() {
        UUID npc = combatant(0, 0, HUNT_MONSTERS);
        UUID zombie = world.monster("minecraft:zombie", 10, 0);
        run(2);
        assertEquals(CombatState.PURSUING, status(npc).state());
        assertFalse(world.moves.isEmpty());
        assertTrue(world.realDamage.isEmpty());
        world.moveReal(zombie, 2, 0);
        run(1);
        assertEquals(CombatState.ATTACKING, status(npc).state());
        assertEquals(4, world.realDamage.get(zombie));
    }
    @Test void reachIsMeasuredToTheTargetsBoundsAndNotItsFeet() {
        UUID npc = combatant(0, 0, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1},\"melee\":{\"damage\":1,\"reach\":1.5,\"cooldownTicks\":1}}");
        UUID zombie = world.monster("minecraft:zombie", 1.7, 0);
        run(1);
        assertEquals(1, world.realDamage.get(zombie), "1.4 to the edge of the bounds is inside reach even though the feet are at 1.7");
        world.moveReal(zombie, 2.2, 0);
        world.realDamage.clear();
        run(3);
        assertTrue(world.realDamage.isEmpty(), "1.9 to the edge is out of reach");
        assertEquals(CombatState.PURSUING, status(npc).state());
    }
    @Test void cooldownSpacesAttacksEvenWhenTheTargetStaysInReach() {
        combatant(0, 0, HUNT_MONSTERS);
        UUID zombie = world.monster("minecraft:zombie", 2, 0);
        run(1);
        assertEquals(4, world.realDamage.get(zombie));
        run(19);
        assertEquals(4, world.realDamage.get(zombie));
        run(1);
        assertEquals(8, world.realDamage.get(zombie));
    }
    @Test void lineOfSightBlocksAcquisitionUnlessIgnored() {
        world.blocked = true;
        UUID blind = combatant(0, 0, HUNT_MONSTERS);
        UUID seeing = combatant(0, 0, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"lineOfSight\":\"ignored\"},\"melee\":{\"damage\":2,\"cooldownTicks\":1}}");
        world.monster("minecraft:zombie", 2, 0);
        run(3);
        assertEquals(CombatState.IDLE, status(blind).state());
        assertEquals(CombatState.ATTACKING, status(seeing).state());
    }
    @Test void aTargetBehindAWallIsRememberedThenForgotten() {
        UUID npc = combatant(0, 0, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"memoryTicks\":5},\"melee\":{\"damage\":4,\"cooldownTicks\":1}}");
        UUID zombie = world.monster("minecraft:zombie", 2, 0);
        run(2);
        double before = world.realDamage.get(zombie);
        world.blocked = true;
        run(4);
        assertEquals(before, world.realDamage.get(zombie), "No attack without line of sight");
        assertTrue(status(npc).target().isPresent());
        run(4);
        assertTrue(status(npc).target().isEmpty());
    }
    @Test void unavailableTerrainDropsTheTargetAndNeverAttacks() {
        UUID npc = combatant(0, 0, HUNT_MONSTERS);
        UUID zombie = world.monster("minecraft:zombie", 2, 0);
        run(2);
        double before = world.realDamage.get(zombie);
        world.unavailableTerrain = true;
        run(30);
        assertEquals(before, world.realDamage.get(zombie));
        assertTrue(status(npc).target().isEmpty());
    }
    @Test void fieldOfViewOnlyAcquiresWhatTheNpcFaces() {
        UUID npc = combatant(0, 0, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"lineOfSight\":\"field_of_view\"}}");
        world.yaw = 0; // Yaw 0 faces +Z.
        UUID zombie = world.monster("minecraft:zombie", 0, -5);
        run(3);
        assertTrue(status(npc).target().isEmpty());
        world.moveReal(zombie, 0, 5);
        run(40);
        assertTrue(status(npc).target().isPresent());
    }
    @Test void ignoresWinOverTargets() {
        UUID npc = combatant(0, 0, "{\"targets\":{\"categories\":[\"mobs\"]},\"ignores\":{\"entityTypes\":[\"minecraft:zombie\"]},\"targeting\":{\"scanIntervalTicks\":1}}");
        world.monster("minecraft:zombie", 2, 0);
        run(3);
        assertTrue(status(npc).target().isEmpty());
    }
    @Test void reactionDelayHoldsTheFirstAttack() {
        combatant(0, 0, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"reactionTicks\":5},\"melee\":{\"damage\":1,\"cooldownTicks\":1}}");
        UUID zombie = world.monster("minecraft:zombie", 2, 0);
        run(4);
        assertNull(world.realDamage.get(zombie));
        run(4);
        assertNotNull(world.realDamage.get(zombie));
    }
    @Test void aNewerNearTargetReplacesTheCurrentOneUnlessRetained() {
        UUID fickle = combatant(0, 0, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":16}}");
        UUID loyal = combatant(0, 0, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":16,\"retainTarget\":true}}");
        UUID far = world.monster("minecraft:zombie", 10, 0);
        run(2);
        UUID close = world.monster("minecraft:zombie", 5, 0);
        run(2);
        assertEquals(mob(close), status(fickle).target().orElseThrow());
        assertEquals(mob(far), status(loyal).target().orElseThrow());
    }
    @Test void avoidedEntitiesMakeTheNpcFleeAndThenReturn() {
        UUID npc = combatant(0, 0, "{\"avoids\":{\"entityTypes\":[\"minecraft:creeper\"]},\"avoidance\":{\"range\":8},\"targeting\":{\"scanIntervalTicks\":1},\"pursuit\":{\"replanTicks\":5}}");
        UUID creeper = world.monster("minecraft:creeper", 3, 0);
        run(2);
        assertEquals(CombatState.AVOIDING, status(npc).state());
        assertTrue(world.moves.getLast().point().x() < 0, "Flees directly away from the avoided entity");
        world.real.remove(creeper);
        world.walkInstantly = true;
        run(60);
        assertEquals(CombatState.IDLE, status(npc).state());
        assertTrue(world.released.contains(npc));
    }
    @Test void avoidanceUsesItsConfiguredReturnPointWhenPresent() {
        combatant(0, 0, "{\"avoids\":{\"entityTypes\":[\"minecraft:creeper\"]},\"avoidance\":{\"range\":8,\"returnPoint\":{\"world\":\"world\",\"x\":50,\"y\":64,\"z\":50}},\"targeting\":{\"scanIntervalTicks\":1}}");
        world.monster("minecraft:creeper", 3, 0);
        run(2);
        assertEquals(new Vec3(50, 64, 50), world.moves.getLast().point());
    }
    @Test void pursuitPastTheLeashAbandonsTheTargetReturnsAndResumesThePatrol() {
        UUID npc = combatant(0, 0, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":16},\"pursuit\":{\"leashRange\":12,\"giveUpRange\":40,\"replanTicks\":5}}");
        UUID zombie = world.monster("minecraft:zombie", 10, 0);
        run(2);
        assertEquals(CombatState.PURSUING, status(npc).state());
        world.moveNpc(npc, 13, 0);
        world.moveReal(zombie, 20, 0);
        run(1);
        assertEquals(CombatState.RETURNING, status(npc).state());
        assertTrue(status(npc).target().isEmpty());
        run(5);
        assertEquals(new Vec3(0, 64, 0), world.moves.getLast().point(), "Returns to where the engagement began");
        assertFalse(world.released.contains(npc), "The patrol stays held until the NPC is back");
        // A walk that ends half a block short finishes with a final step onto the point.
        world.moveNpc(npc, 0.5, 0);
        world.real.remove(zombie);
        run(15);
        assertEquals(CombatState.IDLE, status(npc).state());
        assertEquals(new Vec3(0, 64, 0), world.npc(npc).orElseThrow().position().point());
        assertTrue(world.released.contains(npc));
    }
    @Test void withoutReturnToPostThePatrolResumesWhereTheFightEnded() {
        UUID npc = combatant(0, 0, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1},\"pursuit\":{\"returnToPost\":false}}");
        UUID zombie = world.monster("minecraft:zombie", 10, 0);
        run(2);
        world.real.remove(zombie);
        run(2);
        assertEquals(CombatState.IDLE, status(npc).state());
        assertTrue(world.released.contains(npc));
    }
    @Test void aTargetBeyondGiveUpRangeIsLost() {
        UUID npc = combatant(0, 0, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":100},\"pursuit\":{\"giveUpRange\":10}}");
        UUID zombie = world.monster("minecraft:zombie", 5, 0);
        run(2);
        assertTrue(status(npc).target().isPresent());
        world.moveReal(zombie, 11, 0);
        run(1);
        assertTrue(status(npc).target().isEmpty());
    }
    @Test void aStuckPursuitTeleportsOnlyWhenAllowed() {
        UUID stays = combatant(0, 0, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":200},\"pursuit\":{\"replanTicks\":5}}");
        world.moveStatus = CombatWorld.MoveStatus.REJECTED;
        world.monster("minecraft:zombie", 10, 0);
        run(30);
        assertEquals(0, world.teleports);
        assertTrue(status(stays).target().isEmpty(), "An unreachable target is given up");
        engine().unregister(stays);
        combatant(0, 1, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":200},\"pursuit\":{\"replanTicks\":5,\"teleportWhenStuck\":true}}");
        run(30);
        assertEquals(1, world.teleports);
    }
    @Test void aBusyNavigatorDoesNotCountAsStuck() {
        UUID npc = combatant(0, 0, "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":200},\"pursuit\":{\"replanTicks\":5}}");
        world.moveStatus = CombatWorld.MoveStatus.BUSY;
        world.monster("minecraft:zombie", 10, 0);
        run(60);
        assertTrue(status(npc).target().isPresent());
    }
    @Test void squadMatesShareATargetAndNeverTargetEachOther() {
        String squad = "{\"targets\":{\"categories\":[\"monsters\",\"npcs\"]},\"squad\":{\"name\":\"town\",\"alertRange\":20},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":8}}";
        UUID first = combatant(0, 0, squad);
        UUID second = combatant(15, 0, squad);
        UUID zombie = world.monster("minecraft:zombie", 5, 0);
        run(1);
        assertEquals(mob(zombie), status(first).target().orElseThrow());
        assertEquals(mob(zombie), status(second).target().orElseThrow(), "An alert reaches a member too far to see it");
        world.real.remove(zombie);
        world.moveNpc(second, 1, 0);
        run(3);
        assertTrue(status(first).target().isEmpty(), "Squad mates are never targets even under the npcs category");
        assertTrue(status(second).target().isEmpty());
    }
    @Test void squadAlertsStopAtTheirRange() {
        String squad = "{\"targets\":{\"categories\":[\"monsters\"]},\"squad\":{\"name\":\"town\",\"alertRange\":10},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":8}}";
        combatant(0, 0, squad);
        UUID far = combatant(30, 0, squad);
        world.monster("minecraft:zombie", 5, 0);
        run(1);
        assertTrue(status(far).target().isEmpty());
    }
    @Test void squadAlertsAreBudgeted() {
        budgets = new CombatBudgets(true, 64, 64, 64, 32, 32, 1, 2, 64, 4, 32, 100, 40, 8, 100, 8);
        String squad = "{\"targets\":{\"categories\":[\"monsters\"]},\"squad\":{\"name\":\"town\",\"alertRange\":40},\"targeting\":{\"scanIntervalTicks\":200,\"scanRange\":4}}";
        UUID seer = combatant(0, 0, squad);
        UUID a = combatant(20, 0, squad);
        UUID b = combatant(30, 0, squad);
        run(1);
        UUID zombie = world.monster("minecraft:zombie", 2, 0);
        assertEquals(CombatOperations.Status.APPLIED, engine().target(seer, mob(zombie)).status());
        run(1);
        long alerted = Stream.of(a, b).filter(id -> status(id).target().isPresent()).count();
        assertEquals(1, alerted);
    }
    @Test void guardFollowsAndDefendsItsCharge() {
        UUID charge = world.player("Alice", 20, 0);
        UUID npc = combatant(0, 0, "{\"guard\":{\"player\":\"" + charge + "\",\"followDistance\":5,\"pointRange\":2},\"targeting\":{\"scanIntervalTicks\":1},\"melee\":{\"damage\":3}}");
        run(1);
        assertEquals(CombatState.GUARDING, status(npc).state());
        assertTrue(world.moves.getLast().point().distance(new Vec3(20, 64, 0)) <= 2.01, "Moves to a point near the guarded player");
        UUID attacker = world.monster("minecraft:zombie", 3, 0);
        engine().realDamaged(player(charge), mob(attacker));
        run(1);
        assertEquals(mob(attacker), status(npc).target().orElseThrow());
    }
    @Test void aGuardDefendsItsChargeAgainstAnNpc() {
        UUID charge = world.player("Alice", 4, 0);
        UUID guard = combatant(0, 0, "{\"guard\":{\"player\":\"" + charge + "\"},\"targeting\":{\"scanIntervalTicks\":200}}");
        UUID bandit = combatant(6, 0, "{\"targets\":{\"players\":[\"alice\"]},\"targeting\":{\"scanIntervalTicks\":1},\"melee\":{\"reach\":3}}");
        run(3);
        assertTrue(world.realDamage.containsKey(charge));
        assertEquals(new TargetRef(TargetKind.NPC, bandit), status(guard).target().orElseThrow());
    }
    @Test void aGuardDoesNotTargetItsChargeEvenWhenTheRulesMatch() {
        UUID charge = world.player("Alice", 2, 0);
        UUID npc = combatant(0, 0, "{\"targets\":{\"categories\":[\"players\"]},\"guard\":{\"player\":\"" + charge + "\"},\"targeting\":{\"scanIntervalTicks\":1}}");
        run(3);
        assertTrue(status(npc).target().isEmpty());
    }
    @Test void aGuardWithAnAbsentChargeReturnsToIdle() {
        UUID npc = combatant(0, 0, "{\"guard\":{\"player\":\"" + UUID.randomUUID() + "\"},\"targeting\":{\"scanIntervalTicks\":1}}");
        run(2);
        assertEquals(CombatState.IDLE, status(npc).state());
        assertTrue(world.moves.isEmpty());
    }
    @Test void npcVersusNpcAppliesArmorDeathDropsAndRespawn() {
        UUID hunter = combatant(0, 0, "{\"targets\":{\"npcs\":[\"prey\"]},\"targeting\":{\"scanIntervalTicks\":1},\"melee\":{\"damage\":10,\"cooldownTicks\":1,\"knockback\":0.5}}");
        UUID prey = world.npc(2, 0, 10);
        assertTrue(engine().register(prey, "prey", settings("{\"defense\":{\"maximumHealth\":10,\"armor\":0.5,\"invulnerableTicks\":0,\"knockback\":true},\"death\":{\"experience\":7,\"drops\":[{\"item\":\"minecraft:iron_ingot\",\"amount\":2,\"chance\":1},{\"item\":\"minecraft:gold_ingot\",\"chance\":0}]},\"respawn\":{\"delayTicks\":5,\"locations\":[{\"world\":\"world\",\"x\":40,\"y\":64,\"z\":40}]}}"), CombatLoadout.NONE));
        run(1);
        assertEquals(mobless(TargetKind.NPC, prey), status(hunter).target().orElseThrow());
        assertEquals(5, world.npc(prey).orElseThrow().health(), "Armor halves the hit");
        assertEquals(.5, world.knocked.get(prey));
        run(1);
        assertEquals(0, world.npc(prey).orElseThrow().health());
        assertEquals(CombatState.DEAD, status(prey).state());
        assertEquals(List.of(new CombatWorld.Drop("minecraft:iron_ingot", 2)), world.drops);
        assertEquals(7, world.experience);
        run(1);
        assertTrue(status(hunter).target().isEmpty(), "A dead NPC is no longer a target");
        run(6);
        assertEquals(List.of(prey), world.revived);
        assertEquals(new Vec3(40, 64, 40), world.npc(prey).orElseThrow().position().point());
        assertNotEquals(CombatState.DEAD, status(prey).state());
        assertEquals(1, world.drops.size(), "Death is processed once");
    }
    private static TargetRef mobless(TargetKind kind, UUID id) { return new TargetRef(kind, id); }
    @Test void aDeathFromOutsideTheEngineIsStillProcessed() {
        UUID npc = combatant(0, 0, "{\"death\":{\"experience\":3},\"respawn\":{\"delayTicks\":-1}}");
        world.damageVirtual(npc, player(UUID.randomUUID()), 100);
        run(200);
        assertEquals(CombatState.DEAD, status(npc).state());
        assertEquals(3, world.experience);
        assertTrue(world.revived.isEmpty(), "A delay of -1 never respawns");
    }
    @Test void deleteOnDeathRequestsOneDeletionAndNeverRespawns() {
        UUID npc = combatant(0, 0, "{\"respawn\":{\"delayTicks\":-1,\"deleteOnDeath\":true}}");
        assertEquals(List.of(), engine().drainDeletions());
        world.damageVirtual(npc, player(UUID.randomUUID()), 100);
        run(200);
        assertEquals(List.of(npc), engine().drainDeletions());
        assertEquals(List.of(), engine().drainDeletions(), "A death is reported once");
        assertTrue(world.revived.isEmpty());
    }
    @Test void deleteOnDeathCannotBeCombinedWithARespawnDelay() {
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class, () -> settings("{\"respawn\":{\"delayTicks\":5,\"deleteOnDeath\":true}}"));
        assertTrue(invalid.getMessage().startsWith("$.modules.animus:combat.respawn.deleteOnDeath"), invalid.getMessage());
    }
    @Test void anOwnerIgnoreNeverTargetsTheDefinitionOwner() {
        UUID owner = world.player("Owner", 2, 0);
        UUID npc = world.npc(0, 0, 20);
        assertTrue(engine().register(npc, "guard", settings("{\"targets\":{\"categories\":[\"players\"]},\"ignores\":{\"owner\":true},\"targeting\":{\"scanIntervalTicks\":1}}"),
                new CombatLoadout(Optional.of(owner), java.util.Map.of(), java.util.Map.of())));
        run(5);
        assertTrue(status(npc).target().isEmpty(), "The owner is ignored");
        UUID stranger = world.player("Stranger", 3, 0);
        run(2);
        assertEquals(player(stranger), status(npc).target().orElseThrow());
    }
    @Test void respawnWithoutAConfiguredLocationUsesThePlaceOfDeath() {
        UUID npc = combatant(3, 4, "{\"respawn\":{\"delayTicks\":1}}");
        world.damageVirtual(npc, player(UUID.randomUUID()), 100);
        run(3);
        assertEquals(new Vec3(3, 64, 4), world.npc(npc).orElseThrow().position().point());
    }
    @Test void playerAttacksRespectInvulnerabilityIgnoresAndRange() {
        UUID alice = world.player("Alice", 2, 0);
        UUID npc = combatant(0, 0, "{\"ignores\":{\"players\":[\"bob\"]},\"defense\":{\"invulnerableTicks\":10,\"protectedFromIgnored\":true,\"protectFromRange\":5},\"targeting\":{\"fightBack\":false}}");
        WorldPosition close = MockCombatWorld.at(2, 64, 0);
        assertEquals(CombatWorld.DamageStatus.APPLIED, engine().playerAttack(npc, player(alice), close, 3).status());
        assertEquals(CombatWorld.DamageStatus.NO_CHANGE, engine().playerAttack(npc, player(alice), close, 3).status(), "Inside invulnerability frames");
        run(10);
        assertEquals(CombatWorld.DamageStatus.NO_CHANGE, engine().playerAttack(npc, player(alice), MockCombatWorld.at(9, 64, 0), 3).status(), "Beyond protectFromRange");
        UUID bob = world.player("Bob", 2, 1);
        assertEquals(CombatWorld.DamageStatus.NO_CHANGE, engine().playerAttack(npc, player(bob), close, 3).status(), "Ignored attackers cannot hurt a protected NPC");
        assertEquals(17, world.npc(npc).orElseThrow().health());
    }
    @Test void anNpcWithoutEnabledCombatCannotBeHurt() {
        UUID plain = world.npc(0, 0, 20);
        assertEquals(CombatWorld.DamageStatus.NO_CHANGE, engine().playerAttack(plain, player(UUID.randomUUID()), near(), 5).status());
        assertEquals(20, world.npc(plain).orElseThrow().health());
    }
    @Test void invincibleNpcIsProtectedFromDamage() {
        UUID npc = combatant(0, 0, "{\"defense\":{\"invincible\":true}}");
        assertEquals(CombatWorld.DamageStatus.PROTECTED, engine().playerAttack(npc, player(UUID.randomUUID()), near(), 5).status());
    }
    @Test void knockbackCanBeRefused() {
        UUID npc = combatant(0, 0, "{\"defense\":{\"knockback\":false}}");
        engine().playerAttack(npc, player(UUID.randomUUID()), near(), 1);
        assertTrue(world.knocked.isEmpty());
    }
    @Test void fightBackEngagesAnAttackerAndRunawayFleesInstead() {
        UUID alice = world.player("Alice", 2, 0);
        UUID fights = combatant(0, 0, "{\"targeting\":{\"scanIntervalTicks\":100}}");
        engine().playerAttack(fights, player(alice), MockCombatWorld.at(2, 64, 0), 1);
        run(1);
        assertEquals(player(alice), status(fights).target().orElseThrow());
        UUID flees = combatant(5, 0, "{\"targeting\":{\"fightBack\":false,\"scanIntervalTicks\":100},\"avoidance\":{\"fleeWhenAttacked\":true,\"range\":10}}");
        engine().playerAttack(flees, player(alice), MockCombatWorld.at(2, 64, 0), 1);
        run(1);
        assertEquals(CombatState.AVOIDING, status(flees).state());
    }
    @Test void healingRestoresHealthAtTheConfiguredRate() {
        UUID npc = combatant(0, 0, "{\"defense\":{\"healRateTicks\":5,\"invulnerableTicks\":0},\"targeting\":{\"fightBack\":false}}");
        engine().playerAttack(npc, player(UUID.randomUUID()), near(), 4);
        run(6);
        assertEquals(17, world.npc(npc).orElseThrow().health());
    }

    @Test void protectionDenialAppliesNoDamageOnAnyPath() {
        protection = request -> ProtectionAdapter.Decision.DENY;
        UUID npc = combatant(0, 0, HUNT_MONSTERS);
        UUID zombie = world.monster("minecraft:zombie", 2, 0);
        run(2);
        assertTrue(world.realDamage.isEmpty());
        assertEquals(ProtectionAdapter.Action.NPC_ATTACK, protectionCalls.getFirst().action());
        assertEquals(zombie, protectionCalls.getFirst().target().id());
        assertEquals(CombatWorld.DamageStatus.PROTECTED, engine().playerAttack(npc, player(UUID.randomUUID()), near(), 5).status());
        assertEquals(20, world.npc(npc).orElseThrow().health());
        assertTrue(engine().protectionDenials() >= 2);
    }
    @Test void aFailingProtectionAdapterFailsClosed() {
        protection = request -> { throw new IllegalStateException("query failed"); };
        combatant(0, 0, HUNT_MONSTERS);
        world.monster("minecraft:zombie", 2, 0);
        run(2);
        assertTrue(world.realDamage.isEmpty());
        assertTrue(engine().protectionFailures() > 0);
    }
    @Test void aNullProtectionDecisionFailsClosed() {
        protection = request -> null;
        combatant(0, 0, HUNT_MONSTERS);
        world.monster("minecraft:zombie", 2, 0);
        run(2);
        assertTrue(world.realDamage.isEmpty());
    }

    @Test void aRangedHitAppliesExactlyOnceWhenCollisionAndARealCallbackBothFire() {
        combatant(0, 0, ARCHER);
        UUID target = world.npc(10, 0, 20);
        assertTrue(engine().register(target, "target", settings("{\"defense\":{\"invulnerableTicks\":0},\"targeting\":{\"fightBack\":false}}"), CombatLoadout.NONE));
        run(2);
        assertEquals(1, world.helperLaunches);
        UUID helper = onlyHelper();
        world.helpers.put(helper, new Vec3(11, 65, 0));
        run(1);
        assertEquals(15, world.npc(target).orElseThrow().health(), "Virtual collision applies the hit");
        assertTrue(world.removedHelpers.contains(helper));
        UUID zombie = world.monster("minecraft:zombie", 11, 0);
        assertFalse(engine().helperHitReal(helper, mob(zombie)), "A later real callback finds no live shot");
        assertTrue(world.realDamage.isEmpty());
        assertEquals(15, world.npc(target).orElseThrow().health());
    }
    @Test void aRealCallbackFirstBlocksALaterCollision() {
        combatant(0, 0, ARCHER);
        UUID zombie = world.monster("minecraft:zombie", 10, 0);
        run(2);
        UUID helper = onlyHelper();
        assertTrue(engine().ownsHelper(helper));
        assertTrue(engine().helperHitReal(helper, mob(zombie)));
        assertEquals(5, world.realDamage.get(zombie));
        assertFalse(engine().ownsHelper(helper));
        assertFalse(engine().helperHitReal(helper, mob(zombie)));
        assertEquals(5, world.realDamage.get(zombie));
        assertTrue(world.removedHelpers.contains(helper), "The real arrow is removed before damage is applied");
    }
    @Test void safeShotSpendsTheArrowWithoutHurtingAnUnintendedEntity() {
        combatant(0, 0, ARCHER);
        UUID zombie = world.monster("minecraft:zombie", 10, 0);
        UUID bystander = world.animal("minecraft:cow", 5, 0);
        run(2);
        UUID helper = onlyHelper();
        assertTrue(engine().helperHitReal(helper, mob(bystander)));
        assertFalse(world.realDamage.containsKey(bystander));
        assertFalse(world.realDamage.containsKey(zombie));
        assertTrue(world.removedHelpers.contains(helper));
    }
    @Test void aShotCannotHurtItsOwnerOrSquad() {
        UUID archer = combatant(0, 0, ARCHER.replace("{\"targets\"", "{\"squad\":{\"name\":\"a\"},\"targets\""));
        UUID mate = combatant(4, 3, "{\"squad\":{\"name\":\"a\"},\"defense\":{\"invulnerableTicks\":0}}");
        world.monster("minecraft:zombie", 10, 0);
        run(2);
        UUID helper = onlyHelper();
        world.helpers.put(helper, new Vec3(5, 65, 3.75));
        run(1);
        assertEquals(20, world.npc(mate).orElseThrow().health());
        assertEquals(20, world.npc(archer).orElseThrow().health());
        assertTrue(world.helpers.containsKey(helper), "It keeps flying");
    }
    @Test void protectionDeniedOnAProjectileHitStillRemovesTheHelper() {
        combatant(0, 0, ARCHER);
        UUID zombie = world.monster("minecraft:zombie", 10, 0);
        run(2);
        UUID helper = onlyHelper();
        protection = request -> ProtectionAdapter.Decision.DENY;
        assertTrue(engine().helperHitReal(helper, mob(zombie)));
        assertTrue(world.realDamage.isEmpty());
        assertTrue(world.helpers.isEmpty());
    }
    @Test void aBlockHitRetiresTheHelperWithoutDamage() {
        combatant(0, 0, ARCHER);
        UUID zombie = world.monster("minecraft:zombie", 10, 0);
        run(2);
        UUID helper = onlyHelper();
        assertTrue(engine().helperHitBlock(helper));
        assertFalse(engine().helperHitBlock(helper));
        assertTrue(world.helpers.isEmpty());
        assertFalse(world.realDamage.containsKey(zombie));
    }
    @Test void helpersAreRemovedOnExpiry() {
        combatant(0, 0, ARCHER);
        world.monster("minecraft:zombie", 10, 0);
        run(2);
        UUID helper = onlyHelper();
        run(CombatBudgets.DEFAULT.helperLifetimeTicks() + 1);
        assertTrue(world.removedHelpers.contains(helper));
    }
    @Test void helpersAreForgottenWhenTheirEntityVanishes() {
        combatant(0, 0, ARCHER);
        world.monster("minecraft:zombie", 10, 0);
        run(2);
        UUID helper = onlyHelper();
        world.helpers.remove(helper);
        run(1);
        assertFalse(engine().ownsHelper(helper));
    }
    @Test void helpersAreRemovedOnDisengageDespawnAndShutdown() {
        UUID archer = combatant(0, 0, ARCHER);
        world.monster("minecraft:zombie", 10, 0);
        run(2);
        UUID first = onlyHelper();
        assertEquals(CombatOperations.Status.APPLIED, engine().disengage(archer).status());
        assertTrue(world.removedHelpers.contains(first), "Cancellation");
        assertEquals(CombatOperations.Status.APPLIED, engine().engage(archer).status());
        run(25);
        UUID second = onlyHelper();
        engine().unregister(archer);
        assertTrue(world.removedHelpers.contains(second), "Despawn");
        combatant(0, 1, ARCHER);
        run(2);
        UUID third = onlyHelper();
        assertTrue(engine().ownsHelper(third));
        engine().close();
        assertTrue(world.helpers.isEmpty(), "Shutdown");
        assertTrue(world.removedHelpers.contains(third));
    }
    @Test void aDeadArcherLeavesNoHelpersBehind() {
        UUID archer = combatant(0, 0, ARCHER);
        world.monster("minecraft:zombie", 10, 0);
        run(2);
        UUID helper = onlyHelper();
        engine().playerAttack(archer, player(UUID.randomUUID()), near(), 100);
        assertTrue(world.removedHelpers.contains(helper));
    }
    @Test void helperBudgetsLimitLaunches() {
        budgets = new CombatBudgets(true, 64, 16, 4, 32, 32, 8, 2, 1, 1, 32, 100, 40, 8, 100, 8);
        combatant(0, 0, ARCHER.replace("\"cooldownTicks\":20", "\"cooldownTicks\":1"));
        world.monster("minecraft:zombie", 10, 0);
        run(10);
        assertEquals(1, world.helperLaunches);
    }
    @Test void aCancelledLaunchTracksNothing() {
        world.refuseHelpers = true;
        combatant(0, 0, ARCHER);
        world.monster("minecraft:zombie", 10, 0);
        run(3);
        assertTrue(world.helpers.isEmpty());
        assertEquals(0, engine().helperCount());
    }
    @Test void aRangedAttackerRespectsItsMinimumRange() {
        combatant(0, 0, ARCHER.replace("\"range\":30", "\"range\":30,\"minimumRange\":5"));
        world.monster("minecraft:zombie", 2, 0);
        run(3);
        assertEquals(0, world.helperLaunches);
    }
    @Test void safeShotHoldsFireWhenAFriendStandsInTheLine() {
        combatant(0, 0, ARCHER.replace("{\"targets\"", "{\"squad\":{\"name\":\"a\"},\"targets\""));
        combatant(4, 0, "{\"squad\":{\"name\":\"a\"}}");
        world.monster("minecraft:zombie", 10, 0);
        run(3);
        assertEquals(0, world.helperLaunches);
    }

    @Test void operationsDriveEngagementFromBehaviors() {
        UUID npc = combatant(0, 0, "{\"targeting\":{\"scanIntervalTicks\":1},\"melee\":{\"damage\":1,\"cooldownTicks\":1}}");
        UUID cow = world.animal("minecraft:cow", 2, 0);
        run(2);
        assertTrue(world.realDamage.isEmpty(), "No rule matches a cow");
        assertEquals(CombatOperations.Status.APPLIED, engine().target(npc, mob(cow)).status());
        run(1);
        assertEquals(1, world.realDamage.get(cow), "An explicit target overrides the rules");
        assertEquals(CombatOperations.Status.APPLIED, engine().release(npc).status());
        double dealt = world.realDamage.get(cow);
        run(3);
        assertEquals(dealt, world.realDamage.get(cow));
        assertEquals(CombatOperations.Status.INVALID_TARGET, engine().target(npc, mob(UUID.randomUUID())).status());
        assertEquals(CombatOperations.Status.INVALID_TARGET, engine().target(npc, new TargetRef(TargetKind.NPC, npc)).status());
        UUID charge = world.player("Alice", 30, 0);
        assertEquals(CombatOperations.Status.APPLIED, engine().guard(npc, Optional.of(player(charge))).status());
        run(1);
        assertEquals(CombatState.GUARDING, status(npc).state());
        assertEquals(CombatOperations.Status.APPLIED, engine().guard(npc, Optional.empty()).status());
        assertEquals(CombatOperations.Status.NOT_COMBATANT, engine().engage(UUID.randomUUID()).status());
    }
    @Test void disengageStopsAutonomousTargetingUntilEngaged() {
        UUID npc = combatant(0, 0, HUNT_MONSTERS);
        UUID zombie = world.monster("minecraft:zombie", 2, 0);
        run(2);
        double dealt = world.realDamage.get(zombie);
        engine().disengage(npc);
        run(30);
        assertEquals(dealt, world.realDamage.get(zombie));
        assertTrue(status(npc).target().isEmpty());
        assertTrue(status(npc).disengaged());
        engine().engage(npc);
        run(2);
        assertTrue(world.realDamage.get(zombie) > dealt);
    }
    @Test void aDeadNpcRefusesOperations() {
        UUID npc = combatant(0, 0, "{\"respawn\":{\"delayTicks\":-1}}");
        world.damageVirtual(npc, player(UUID.randomUUID()), 100);
        run(1);
        assertEquals(CombatOperations.Status.DEAD, engine().target(npc, mob(world.monster("minecraft:zombie", 1, 0))).status());
    }

    @Test void idleNpcsSleepAndBackOffWhenNothingIsNear() {
        combatant(0, 0, HUNT_MONSTERS);
        run(200);
        assertTrue(world.realScans < 20, "A guard with nothing near sleeps instead of scanning every tick: " + world.realScans);
    }
    @Test void aSleepingNpcWakesWhenAttacked() {
        UUID npc = combatant(0, 0, HUNT_MONSTERS);
        run(100);
        UUID alice = world.player("Alice", 2, 0);
        engine().playerAttack(npc, player(alice), MockCombatWorld.at(2, 64, 0), 1);
        run(1);
        assertTrue(status(npc).target().isPresent());
    }
    @Test void targetScansAreCappedPerTick() {
        budgets = new CombatBudgets(true, 64, 64, 2, 32, 32, 8, 2, 64, 4, 32, 100, 40, 8, 100, 8);
        for (int i = 0; i < 10; i++) combatant(i, 0, HUNT_MONSTERS);
        engine().tick();
        assertEquals(2, world.realScans);
    }
    @Test void brainsPerTickIsCappedButFair() {
        budgets = new CombatBudgets(true, 64, 2, 64, 32, 32, 8, 2, 64, 4, 32, 100, 40, 8, 100, 8);
        for (int i = 0; i < 6; i++) combatant(i * 40, 0, HUNT_MONSTERS);
        engine().tick();
        assertEquals(2, world.realScans);
        engine().tick();
        engine().tick();
        assertEquals(6, world.realScans);
    }
    @Test void pathRequestsAreCappedPerTick() {
        budgets = new CombatBudgets(true, 64, 64, 64, 32, 32, 8, 1, 64, 4, 32, 100, 40, 8, 100, 8);
        for (int i = 0; i < 4; i++) combatant(i, 0, HUNT_MONSTERS);
        world.monster("minecraft:zombie", 10, 0);
        engine().tick();
        assertEquals(1, world.moves.size());
    }
    @Test void registrationIsNotLimitedByTheActiveLimitAndReplacesSettingsInPlace() {
        budgets = new CombatBudgets(true, 1, 16, 4, 32, 32, 8, 2, 64, 4, 32, 100, 40, 8, 100, 8);
        UUID npc = combatant(0, 0, HUNT_MONSTERS);
        assertTrue(engine().register(npc, "same", settings("{}"), CombatLoadout.NONE), "Replacing an existing NPC does not need capacity");
        assertTrue(engine().register(world.npc(1, 1, 20), "other", settings("{}"), CombatLoadout.NONE), "Every guard registers; the limit only bounds active ones");
        assertTrue(engine().register(world.npc(2, 2, 20), "third", settings("{}"), CombatLoadout.NONE));
        assertEquals(3, engine().size());
    }
    @Test void guardsWithNoPlayerNearSleepWithoutScanningOrHoldingASlot() {
        budgets = new CombatBudgets(true, 1, 16, 4, 32, 32, 8, 2, 64, 4, 32, 100, 40, 8, 100, 8);
        world.playersAway = true;
        UUID first = combatant(0, 0, HUNT_MONSTERS);
        UUID second = combatant(100, 0, HUNT_MONSTERS);
        world.monster("minecraft:zombie", 2, 0);
        world.monster("minecraft:zombie", 102, 0);
        run(5);
        assertEquals(0, world.realScans, "A guard with no player near does not scan");
        assertEquals(0, engine().activeCount());
        assertEquals(0, engine().waitingCount());
        assertTrue(status(first).target().isEmpty());
        assertTrue(status(second).target().isEmpty());
        world.playersAway = false;
        run(45);
        assertTrue(status(first).target().isPresent() || status(second).target().isPresent(), "A player arriving wakes the guards");
    }
    @Test void guardsBeyondTheActiveLimitWaitTheirTurnFairlyAndPressureIsLoggedOnce() {
        budgets = new CombatBudgets(true, 1, 16, 4, 32, 32, 8, 2, 64, 4, 32, 100, 40, 8, 100, 8);
        UUID first = combatant(0, 0, HUNT_MONSTERS);
        UUID second = combatant(100, 0, HUNT_MONSTERS);
        UUID third = combatant(200, 0, HUNT_MONSTERS);
        UUID zombie = world.monster("minecraft:zombie", 2, 0);
        world.monster("minecraft:zombie", 102, 0);
        world.monster("minecraft:zombie", 202, 0);
        run(3);
        assertTrue(status(first).target().isPresent());
        assertTrue(status(second).target().isEmpty());
        assertTrue(status(third).target().isEmpty());
        assertEquals(1, engine().activeCount());
        assertEquals(2, engine().waitingCount());
        assertEquals(1, diagnostics.stream().filter((String line) -> line.contains("Combat capacity reached")).count());
        assertEquals("fighting-or-scanning=1/1 | waiting=2 | sleeping=0 | peak-waiting=2", engine().capacity());
        world.real.remove(zombie);
        run(200);
        assertTrue(status(second).target().isPresent(), "The first guard waiting gets the freed slot");
        assertTrue(status(third).target().isEmpty(), "The later guard keeps waiting");
        assertEquals(1, diagnostics.stream().filter((String line) -> line.contains("Combat capacity reached")).count(), "Pressure is logged once while it lasts");
        engine().unregister(second);
        run(3);
        assertTrue(status(third).target().isPresent());
        engine().unregister(third);
        engine().unregister(first);
        run(3);
        assertEquals(0, engine().activeCount());
        assertEquals(1, diagnostics.stream().filter((String line) -> line.contains("Combat capacity free")).count());
    }
    @Test void idleScanningGuardsTakeTurnsWhenOthersWait() {
        budgets = new CombatBudgets(true, 1, 16, 4, 32, 32, 8, 2, 64, 4, 32, 100, 40, 8, 100, 8);
        combatant(0, 0, HUNT_MONSTERS);
        combatant(100, 0, HUNT_MONSTERS);
        UUID late = combatant(200, 0, HUNT_MONSTERS);
        run(3);
        world.monster("minecraft:zombie", 202, 0);
        run(200);
        assertTrue(status(late).target().isPresent(), "A guard that only scans gives its slot to the next waiting guard");
    }
    @Test void targetsMustBeInTheSameWorld() {
        UUID npc = combatant(0, 0, HUNT_MONSTERS);
        UUID zombie = world.monster("minecraft:zombie", 2, 0);
        run(2);
        assertTrue(status(npc).target().isPresent());
        CombatTarget old = world.real.get(zombie);
        world.real.put(zombie, new CombatTarget(old.ref(), old.type(), old.name(), new WorldPosition("nether", old.position().point()), old.bounds(), 20, old.categories(), "", Optional.empty(), old.details()));
        run(1);
        assertTrue(status(npc).target().isEmpty());
    }
    @Test void aCancelledRealHitIsNotRetriedWhileItsTargetIsRefused() {
        world.realStatus = CombatWorld.DamageStatus.CANCELLED;
        combatant(0, 0, HUNT_MONSTERS);
        world.monster("minecraft:zombie", 2, 0);
        run(2);
        assertTrue(world.realDamage.isEmpty());
        assertEquals(2, protectionCalls.size(), "One check to choose the target, one for the hit");
        run(10);
        assertEquals(2, protectionCalls.size());
    }
}
