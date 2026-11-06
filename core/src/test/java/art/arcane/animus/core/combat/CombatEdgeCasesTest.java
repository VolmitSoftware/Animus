package art.arcane.animus.core.combat;

import art.arcane.animus.core.config.CombatBudgets;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Cancelled and protected attacks, projectile credit, death and respawn, and cleanup. */
class CombatEdgeCasesTest {
    private static final String HUNT_MONSTERS = "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":16},\"melee\":{\"damage\":4,\"reach\":3,\"cooldownTicks\":20}}";
    private static final String ARCHER = "{\"targets\":{\"categories\":[\"monsters\",\"npcs\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":30},\"pursuit\":{\"giveUpRange\":40},\"melee\":{\"enabled\":false},\"ranged\":{\"enabled\":true,\"damage\":5,\"range\":30,\"cooldownTicks\":20,\"velocity\":1.6}}";
    private final MockCombatWorld world = new MockCombatWorld();
    private final List<ProtectionAdapter.Request> protectionCalls = new ArrayList<>();
    private ProtectionAdapter protection = ProtectionAdapter.NONE;
    private CombatEngine engine;

    private CombatEngine engine() {
        if (engine == null) engine = new CombatEngine(CombatBudgets.DEFAULT, world, (ProtectionAdapter.Request request) -> {
            protectionCalls.add(request);
            return protection.decide(request);
        }, new Random(7), (String line) -> {});
        return engine;
    }
    private static CombatSettings settings(String json) {
        JsonObject module = JsonParser.parseString(json).getAsJsonObject();
        module.addProperty("enabled", true);
        return CombatSettings.parseModule(module);
    }
    private UUID combatant(double x, double z, String json) { return combatant(x, z, json, CombatLoadout.NONE); }
    private UUID combatant(double x, double z, String json, CombatLoadout loadout) {
        UUID npc = world.npc(x, z, 20);
        assertTrue(engine().register(npc, "npc-" + npc, settings(json), loadout));
        return npc;
    }
    private void run(int ticks) { for (int i = 0; i < ticks; i++) engine().tick(); }
    private CombatEngine.Status status(UUID npc) { return engine().status(npc).orElseThrow(); }
    private static TargetRef mob(UUID id) { return new TargetRef(TargetKind.MOB, id); }
    private static TargetRef player(UUID id) { return new TargetRef(TargetKind.PLAYER, id); }
    private static ProtectionAdapter denying(UUID protectedTarget) {
        return (ProtectionAdapter.Request request) -> request.target().id().equals(protectedTarget)
                ? ProtectionAdapter.Decision.DENY : ProtectionAdapter.Decision.ALLOW;
    }

    @Test void aProtectedTargetIsNeverChased() {
        UUID npc = combatant(0, 0, HUNT_MONSTERS);
        UUID zombie = world.monster("minecraft:zombie", 10, 0);
        protection = denying(zombie);
        run(5);
        assertTrue(status(npc).target().isEmpty());
        assertTrue(world.moves.isEmpty(), "No walk toward a target protection refuses");
        assertTrue(world.realDamage.isEmpty());
    }
    @Test void aProtectedTargetIsPassedOverForAnUnprotectedOne() {
        UUID npc = combatant(0, 0, HUNT_MONSTERS);
        UUID sheltered = world.monster("minecraft:zombie", 2, 0);
        UUID exposed = world.monster("minecraft:husk", 5, 0);
        protection = denying(sheltered);
        run(1);
        assertEquals(mob(exposed), status(npc).target().orElseThrow());
    }
    @Test void aTargetThatBecomesProtectedMidPathIsDroppedAndTheNpcGoesHome() {
        UUID npc = combatant(0, 0, HUNT_MONSTERS);
        UUID zombie = world.monster("minecraft:zombie", 10, 0);
        run(2);
        assertEquals(CombatState.PURSUING, status(npc).state());
        world.moveNpc(npc, 4, 0);
        protection = denying(zombie);
        run(12);
        assertTrue(status(npc).target().isEmpty());
        assertEquals(CombatState.RETURNING, status(npc).state(), "The NPC walks back to where the fight began");
        assertTrue(world.realDamage.isEmpty());
    }
    @Test void aCancelledSwingEndsTheAttackAndTheTargetIsLeftAloneForAWhile() {
        world.realStatus = CombatWorld.DamageStatus.CANCELLED;
        UUID npc = combatant(0, 0, HUNT_MONSTERS);
        UUID zombie = world.monster("minecraft:zombie", 2, 0);
        run(2);
        assertTrue(status(npc).target().isEmpty(), "A cancelled hit drops the target");
        assertNotEquals(CombatState.ATTACKING, status(npc).state());
        assertTrue(world.realDamage.isEmpty());
        run(95);
        assertTrue(status(npc).target().isEmpty(), "A refused target is not chosen again at once");
        world.realStatus = CombatWorld.DamageStatus.APPLIED;
        run(10);
        assertEquals(mob(zombie), status(npc).target().orElseThrow(), "It is chosen again once the refusal ends");
    }
    @Test void aProtectedSwingDropsAnExplicitTargetToo() {
        UUID npc = combatant(0, 0, "{\"targeting\":{\"scanIntervalTicks\":1},\"melee\":{\"damage\":4,\"reach\":3,\"cooldownTicks\":20}}");
        UUID cow = world.animal("minecraft:cow", 2, 0);
        assertEquals(CombatOperations.Status.APPLIED, engine().target(npc, mob(cow)).status());
        protection = denying(cow);
        run(2);
        assertTrue(status(npc).target().isEmpty());
        assertTrue(world.realDamage.isEmpty());
    }
    @Test void aProtectedArrowSpendsItselfAndEndsTheFight() {
        UUID archer = combatant(0, 0, ARCHER);
        UUID zombie = world.monster("minecraft:zombie", 10, 0);
        run(2);
        UUID helper = world.helpers.keySet().iterator().next();
        assertEquals(mob(zombie), status(archer).target().orElseThrow());
        protection = denying(zombie);
        assertTrue(engine().helperHitReal(helper, mob(zombie)));
        assertTrue(world.removedHelpers.contains(helper));
        assertTrue(world.realDamage.isEmpty());
        assertTrue(status(archer).target().isEmpty(), "A shot protection refused ends the fight with that target");
    }
    @Test void anArrowCreditsItsNpcAndAppliesOnce() {
        List<UUID> attackers = new ArrayList<>();
        UUID archer = combatant(0, 0, ARCHER);
        UUID zombie = world.monster("minecraft:zombie", 10, 0);
        run(2);
        UUID helper = world.helpers.keySet().iterator().next();
        protection = (ProtectionAdapter.Request request) -> {
            attackers.add(request.attacker().id());
            return ProtectionAdapter.Decision.ALLOW;
        };
        assertTrue(engine().helperHitReal(helper, mob(zombie)));
        assertFalse(engine().helperHitReal(helper, mob(zombie)), "A spent arrow never hits twice");
        assertEquals(List.of(archer), attackers, "The hit is the archer's own");
        assertEquals(5, world.realDamage.get(zombie));
    }
    @Test void anInvincibleNpcViewPublishedToOtherEnginesSaysSo() {
        UUID statue = world.npc(2, 0, 20);
        CombatTarget view = CombatEngine.npcTarget(statue, "Statue", world.npc(statue).orElseThrow(), "statue",
                settings("{\"defense\":{\"invincible\":true}}"), CombatLoadout.NONE);
        assertTrue(view.details().invulnerable());
        assertFalse(CombatEngine.npcTarget(statue, "Statue", world.npc(statue).orElseThrow(), "statue", settings("{}"), CombatLoadout.NONE).details().invulnerable());
    }
    @Test void anInvulnerableRealEntityIsNeverTargeted() {
        UUID npc = combatant(0, 0, HUNT_MONSTERS);
        UUID zombie = world.monster("minecraft:zombie", 2, 0);
        world.details(zombie, 20, new CombatTarget.Details(20, CombatLoadout.EMPTY, CombatLoadout.EMPTY, List.of(), java.util.Set.of(), true));
        run(3);
        assertTrue(status(npc).target().isEmpty());
        assertTrue(protectionCalls.isEmpty(), "Protection is not asked about a target that cannot be hurt");
    }
    @Test void anInvincibleNpcIsNeverTargeted() {
        UUID hunter = combatant(0, 0, "{\"targets\":{\"categories\":[\"npcs\"]},\"targeting\":{\"scanIntervalTicks\":1}}");
        UUID statue = combatant(2, 0, "{\"defense\":{\"invincible\":true},\"targeting\":{\"fightBack\":false}}");
        run(3);
        assertTrue(status(hunter).target().isEmpty());
        assertEquals(CombatWorld.DamageStatus.PROTECTED, engine().playerAttack(statue, player(UUID.randomUUID()), MockCombatWorld.at(1, 64, 0), 5).status());
        assertEquals(20, world.npc(statue).orElseThrow().health());
    }
    @Test void aRespawnedGuardWalksBackToItsPostWithFreshState() {
        String json = "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":30},\"pursuit\":{\"giveUpRange\":40},\"melee\":{\"enabled\":false},"
                + "\"ranged\":{\"enabled\":true,\"damage\":5,\"range\":30,\"cooldownTicks\":1,\"velocity\":1.6,\"useAmmo\":true},"
                + "\"respawn\":{\"delayTicks\":5,\"locations\":[{\"world\":\"world\",\"x\":20,\"y\":64,\"z\":20}]}}";
        UUID guard = combatant(0, 0, json, new CombatLoadout(Optional.empty(), Map.of(), Map.of("minecraft:arrow", 2)));
        world.posts.put(guard, MockCombatWorld.at(0, 64, 0));
        UUID zombie = world.monster("minecraft:zombie", 10, 0);
        run(4);
        assertEquals(0, status(guard).ammunition());
        world.real.remove(zombie);
        engine().playerAttack(guard, player(UUID.randomUUID()), MockCombatWorld.at(1, 64, 0), 100);
        assertEquals(CombatState.DEAD, status(guard).state());
        assertTrue(world.helpers.isEmpty(), "A dead NPC leaves no projectiles");
        run(5);
        assertEquals(List.of(guard), world.revived);
        assertEquals(2, status(guard).ammunition(), "Ammunition is full again after a respawn");
        assertTrue(status(guard).target().isEmpty());
        assertEquals(CombatState.RETURNING, status(guard).state(), "A respawn away from the post walks back to it");
        world.walkInstantly = true;
        run(20);
        assertEquals(CombatState.IDLE, status(guard).state());
        assertEquals(new Vec3(0, 64, 0), world.npc(guard).orElseThrow().position().point());
    }
    @Test void aRespawnOnThePostStaysIdle() {
        UUID guard = combatant(3, 4, "{\"respawn\":{\"delayTicks\":1}}");
        world.posts.put(guard, MockCombatWorld.at(3, 64, 4));
        world.damageVirtual(guard, player(UUID.randomUUID()), 100);
        run(4);
        assertEquals(CombatState.IDLE, status(guard).state());
        assertTrue(world.moves.isEmpty());
    }
    @Test void aDespawnMidFlightRemovesTheProjectileAndLaterCallbacksDoNothing() {
        UUID archer = combatant(0, 0, ARCHER);
        UUID zombie = world.monster("minecraft:zombie", 10, 0);
        run(2);
        UUID helper = world.helpers.keySet().iterator().next();
        engine().unregister(archer);
        assertTrue(world.removedHelpers.contains(helper));
        assertEquals(0, engine().helperCount());
        assertFalse(engine().helperHitReal(helper, mob(zombie)));
        assertTrue(world.realDamage.isEmpty());
    }
    @Test void aStoppingEngineRefusesNewFightsButLetsShotsLand() {
        UUID archer = combatant(0, 0, ARCHER);
        world.monster("minecraft:zombie", 10, 0);
        run(2);
        engine().stop();
        assertTrue(status(archer).target().isEmpty());
        assertEquals(1, engine().helperCount());
        int launches = world.helperLaunches;
        run(engine().budgets().helperLifetimeTicks() + 1);
        assertEquals(launches, world.helperLaunches);
        assertTrue(engine().drained());
        engine().close();
        assertTrue(world.helpers.isEmpty());
    }
    @Test void aCancelledHitDuringAProtectedRegionPathNeverReachesDamage() {
        UUID npc = combatant(0, 0, HUNT_MONSTERS);
        UUID zombie = world.monster("minecraft:zombie", 2, 0);
        protection = (ProtectionAdapter.Request request) -> ProtectionAdapter.Decision.DENY;
        WorldPosition from = MockCombatWorld.at(1, 64, 0);
        assertEquals(CombatWorld.DamageStatus.PROTECTED, engine().playerAttack(npc, player(UUID.randomUUID()), from, 5).status(), "Real attacks on the NPC ask protection too");
        run(3);
        assertTrue(world.realDamage.isEmpty());
        assertFalse(world.realDamage.containsKey(zombie));
        assertEquals(20, world.npc(npc).orElseThrow().health());
    }
}
