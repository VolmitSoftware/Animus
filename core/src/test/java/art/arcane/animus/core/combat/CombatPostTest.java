package art.arcane.animus.core.combat;

import art.arcane.animus.core.config.CombatBudgets;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A guard goes back onto its own post after every fight, so ten fights leave it exactly where it started. */
class CombatPostTest {
    private static final String GUARD = "{\"targets\":{\"categories\":[\"monsters\"]},\"targeting\":{\"scanIntervalTicks\":1,\"scanRange\":16},"
            + "\"melee\":{\"damage\":1,\"reach\":3,\"cooldownTicks\":20},\"pursuit\":{\"leashRange\":12,\"replanTicks\":5}}";
    private final MockCombatWorld world = new MockCombatWorld();
    private CombatEngine engine;

    private CombatEngine engine() {
        if (engine == null) engine = new CombatEngine(CombatBudgets.DEFAULT, world, ProtectionAdapter.NONE, new Random(7), (String line) -> {});
        return engine;
    }
    private UUID guard(double x, double z, boolean posted) {
        UUID npc = world.npc(x, z, 20);
        if (posted) world.posts.put(npc, MockCombatWorld.at(x, 64, z));
        JsonObject module = JsonParser.parseString(GUARD).getAsJsonObject();
        module.addProperty("enabled", true);
        assertTrue(engine().register(npc, "guard", CombatSettings.parseModule(module, CombatTesting.KILLABLE), CombatLoadout.NONE));
        return npc;
    }
    private void tick() { engine().tick(); world.flush(); }
    private CombatState state(UUID npc) { return engine().status(npc).orElseThrow().state(); }
    private Vec3 at(UUID npc) { return world.npc(npc).orElseThrow().position().point(); }
    private void runUntilIdle(UUID npc) {
        for (int i = 0; i < 400 && state(npc) != CombatState.IDLE; i++) tick();
        tick();
        assertEquals(CombatState.IDLE, state(npc), "the fight never ended");
    }
    /** One fight against a zombie in the given direction, which then leaves; returns how far the guard ends from its post. */
    private double fight(UUID npc, Vec3 post, double angle) {
        UUID zombie = world.monster("minecraft:zombie", post.x() + Math.cos(angle) * 8, post.z() + Math.sin(angle) * 8);
        for (int i = 0; i < 40 && engine().status(npc).orElseThrow().target().isEmpty(); i++) tick();
        assertTrue(engine().status(npc).orElseThrow().target().isPresent(), "the guard never took the zombie");
        for (int i = 0; i < 10; i++) tick();
        world.real.remove(zombie);
        runUntilIdle(npc);
        Vec3 end = at(npc);
        return Math.hypot(end.x() - post.x(), end.z() - post.z());
    }

    @Test void tenFightsLeaveNoDriftWhenEveryPathStopsShortOfTheGoal() {
        world.walkInstantly = true;
        world.stopShort = 1.3;
        Vec3 post = new Vec3(.5, 64, .5);
        UUID npc = guard(post.x(), post.z(), true);
        for (int fight = 0; fight < 10; fight++) {
            double off = fight(npc, post, fight * 1.1);
            assertEquals(0, off, 1e-9, "fight " + fight + " ended off the post");
        }
        assertEquals(10, world.poseRestores.getOrDefault(npc, 0), "every return restores the post's facing and pose");
    }

    @Test void aRefusedFinalStepFallsBackToANudgeOntoThePost() {
        world.walkInstantly = true;
        world.stopShort = 1.3;
        world.refuseSteps = true;
        Vec3 post = new Vec3(.5, 64, .5);
        UUID npc = guard(post.x(), post.z(), true);
        for (int fight = 0; fight < 10; fight++) assertEquals(0, fight(npc, post, fight * 2.3), 1e-9, "fight " + fight);
        assertTrue(world.teleports > 0);
    }

    /**
     * The Folia region runtime: a path ends beside the post, the final step is refused because the post lies in a chunk
     * another region owns, and the nudge is handed to that region and lands a tick later.
     */
    @Test void tenFightsLeaveNoDriftWhenTheFinalStepCrossesIntoAnotherRegion() {
        world.walkInstantly = true;
        world.stopShort = 1.3;
        world.refuseSteps = true;
        world.deferTeleports = true;
        Vec3 post = new Vec3(15.9, 64, 15.9);
        UUID npc = guard(post.x(), post.z(), true);
        for (int fight = 0; fight < 10; fight++) assertEquals(0, fight(npc, post, fight * .7), 1e-9, "fight " + fight);
        assertEquals(10, world.poseRestores.getOrDefault(npc, 0));
    }

    @Test void theNextFightIsLeashedToThePostNotWhereTheLastOneStopped() {
        world.walkInstantly = true;
        Vec3 post = new Vec3(.5, 64, .5);
        UUID npc = guard(post.x(), post.z(), true);
        // An admin or knockback leaves the guard 6 blocks off its post; its leash still runs from the post.
        world.moveNpc(npc, 6.5, .5);
        UUID zombie = world.monster("minecraft:zombie", 14, .5);
        for (int i = 0; i < 40 && engine().status(npc).orElseThrow().target().isEmpty(); i++) engine().tick();
        assertTrue(engine().status(npc).orElseThrow().target().isPresent());
        world.moveReal(zombie, 17, .5);
        for (int i = 0; i < 20; i++) engine().tick();
        assertTrue(engine().status(npc).orElseThrow().target().isEmpty(), "a zombie beyond the post's leash is let go");
        runUntilIdle(npc);
        assertEquals(0, Math.hypot(at(npc).x() - post.x(), at(npc).z() - post.z()), 1e-9);
    }

    @Test void aPatrollingGuardWithNoPostReturnsWhereItLeftItsRoute() {
        world.walkInstantly = true;
        UUID npc = guard(3.5, 3.5, false);
        assertEquals(Optional.empty(), world.post(npc));
        assertEquals(0, fight(npc, new Vec3(3.5, 64, 3.5), 0), 1e-9);
        assertTrue(world.released.contains(npc));
    }

    @Test void aGuardAlreadyOnItsPostAfterAFightDoesNotWalk() {
        Vec3 post = new Vec3(.5, 64, .5);
        UUID npc = guard(post.x(), post.z(), true);
        WorldPosition before = world.npc(npc).orElseThrow().position();
        UUID zombie = world.monster("minecraft:zombie", 2, .5);
        for (int i = 0; i < 10; i++) engine().tick();
        world.real.remove(zombie);
        runUntilIdle(npc);
        assertEquals(before.point(), at(npc));
        assertEquals(0, world.steps);
        assertEquals(1, world.poseRestores.getOrDefault(npc, 0), "a fight on the post still ends facing the post's way");
    }
}
