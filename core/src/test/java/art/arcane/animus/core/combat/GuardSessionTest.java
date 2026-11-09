package art.arcane.animus.core.combat;

import art.arcane.animus.core.config.CombatBudgets;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.runtime.RouteRules;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A hired guard through a long play session: the charge wanders, sprints, walks a dirt-path road, teleports, dies and
 * respawns, changes worlds and gets into fights, while routes run over time on terrain with partial blocks and the
 * contract is renewed and re-applied. The guard must never be left behind for long.
 */
class GuardSessionTest {
    private static final String BODYGUARD = "{\"targeting\":{\"scanRange\":16,\"fightBack\":true,\"lineOfSight\":\"required\"},"
            + "\"guard\":{\"followDistance\":4,\"pointRange\":2,\"catchUpDistance\":24,\"replanDistance\":1.5},"
            + "\"pursuit\":{\"speed\":6,\"giveUpRange\":24,\"leashRange\":32,\"returnToPost\":true},"
            + "\"melee\":{\"damage\":4,\"reach\":3,\"cooldownTicks\":15}}";
    private static final double PATH_TOP = 63.9375;

    /** Routes take time and end on the surface the planner found; the leg rules are the runtime's own. */
    private static final class Terrain extends MockCombatWorld {
        private record Route(WorldPosition goal, double speed) {}
        private final Map<UUID, Route> routes = new HashMap<>();
        final Map<UUID, String> refusals = new HashMap<>();
        final Random random = new Random(11);
        double busyChance;

        /** A dirt-path road runs along z = 0; everything else is full grass at y = 64. */
        static double surface(String world, double x, double z) {
            return WORLD.equals(world) && Math.abs(z) < 1.5 ? PATH_TOP : 64;
        }
        void place(UUID id, String world, double x, double z, double health) {
            CombatTarget old = real.get(id);
            double y = surface(world, x, z);
            real.put(id, new CombatTarget(old.ref(), old.type(), old.name(), new WorldPosition(world, new Vec3(x, y, z)), Hitbox.atFeet(new Vec3(x, y, z), .6, 1.8),
                    health, old.categories(), "", Optional.empty(), old.details()));
        }
        void advance() {
            for (Map.Entry<UUID, Route> entry : List.copyOf(routes.entrySet())) {
                VirtualNpcState state = npcs.get(entry.getKey());
                WorldPosition from = state.snapshot().position();
                Route route = entry.getValue();
                Vec3 delta = route.goal().point().subtract(from.point());
                double horizontal = Math.hypot(delta.x(), delta.z()), step = route.speed() / 20;
                boolean last = horizontal <= step;
                double x = last ? route.goal().point().x() : from.point().x() + delta.x() / horizontal * step;
                double z = last ? route.goal().point().z() : from.point().z() + delta.z() / horizontal * step;
                index.upsert(state.move(new WorldPosition(from.world(), new Vec3(x, surface(from.world(), x, z), z))));
                if (last) routes.remove(entry.getKey());
            }
        }
        @Override public MoveStatus move(UUID npc, WorldPosition goal, double speed) {
            if (random.nextDouble() < busyChance) return MoveStatus.BUSY;
            try { RouteRules.validateLeg(npcs.get(npc).snapshot().position(), goal, MovementProfile.Mode.WALK, speed, .6, 1.8); }
            catch (IllegalArgumentException refused) {
                refusals.put(npc, refused.getMessage());
                return MoveStatus.REJECTED;
            }
            refusals.remove(npc);
            moves.add(goal);
            routes.put(npc, new Route(goal, speed));
            return MoveStatus.ACCEPTED;
        }
        @Override public void stop(UUID npc) { routes.remove(npc); }
        @Override public void release(UUID npc) { routes.remove(npc); }
        /** Like the runtime: onto the surface inside the named block, trying the column a little up and down. */
        @Override public boolean teleport(UUID npc, WorldPosition destination) {
            teleports++;
            for (int lift : new int[] {0, 1, -1, 2, -2}) {
                double base = Math.floor(destination.point().y()) + lift, ground = surface(destination.world(), destination.point().x(), destination.point().z());
                if (ground < base || ground >= base + 1) continue;
                routes.remove(npc);
                index.upsert(npcs.get(npc).move(new WorldPosition(destination.world(), new Vec3(destination.point().x(), ground, destination.point().z()))));
                return true;
            }
            return false;
        }
        @Override public String movement(UUID npc) { return refusals.getOrDefault(npc, routes.containsKey(npc) ? "moving" : "idle"); }
    }

    private final Terrain world = new Terrain();
    private final List<String> diagnostics = new ArrayList<>();
    private final CombatEngine engine = new CombatEngine(CombatBudgets.DEFAULT, world, ProtectionAdapter.NONE, new Random(7), diagnostics::add);

    private static CombatSettings settings(String json) {
        JsonObject module = JsonParser.parseString(json).getAsJsonObject();
        module.addProperty("enabled", true);
        return CombatSettings.parseModule(module, CombatTesting.health("{\"killable\":true,\"maximumHealth\":30}"));
    }
    private void tick() {
        engine.tick();
        world.advance();
    }

    @Test void aHiredGuardKeepsUpThroughALongSession() {
        UUID guard = world.npc(0, 0, 30);
        CombatSettings settings = settings(BODYGUARD);
        assertTrue(engine.register(guard, "bodyguard", settings, CombatLoadout.NONE));
        UUID player = world.player("Hirer", 3, 3);
        TargetRef charge = new TargetRef(TargetKind.PLAYER, player);
        assertEquals(CombatOperations.Status.APPLIED, engine.guard(guard, Optional.of(charge)).status());
        Random random = new Random(42);
        String where = MockCombatWorld.WORLD;
        double x = 3, z = 3, heading = 0, speed = 0, health = 20;
        int phaseLeft = 0, dead = 0, away = 0, behind = 0, worst = 0, fights = 0, roadTicks = 0;
        String phase = "stand", worstAt = "";
        UUID foe = null;
        for (long tick = 0; tick < 36_000; tick++) {
            if (dead > 0 && --dead == 0) { x = 0; z = 6; health = 20; where = MockCombatWorld.WORLD; }
            if (away > 0 && --away == 0) { where = MockCombatWorld.WORLD; x += 4; }
            if (phaseLeft-- <= 0 && dead == 0 && foe == null) {
                phaseLeft = 100 + random.nextInt(300);
                int pick = random.nextInt(10);
                phase = switch (pick) {
                    case 0, 1 -> "stand";
                    case 2, 3 -> "wander";
                    case 4 -> "sprint";
                    case 5, 6 -> "road";
                    case 7 -> "teleport";
                    case 8 -> random.nextBoolean() ? "die" : "travel";
                    default -> "fight";
                };
                heading = random.nextDouble() * Math.PI * 2;
                speed = switch (phase) { case "wander", "road" -> .2; case "sprint" -> .28; default -> 0; };
                if (phase.equals("road")) { z = 0.3; heading = random.nextBoolean() ? 0 : Math.PI; }
                if (phase.equals("teleport")) { x += 40 + random.nextInt(60); z = random.nextBoolean() ? 0 : z + 30; }
                if (phase.equals("die")) { health = 0; dead = 60; }
                if (phase.equals("travel")) { where = "nether"; away = 200; }
                if (phase.equals("fight")) {
                    foe = world.monster("minecraft:zombie", x + 3, z);
                    fights++;
                }
            }
            if (dead == 0 && speed > 0) {
                x += Math.cos(heading) * speed;
                z = phase.equals("road") ? 0.3 : z + Math.sin(heading) * speed;
                if (Math.abs(x) > 300) heading = Math.PI - heading;
            }
            world.place(player, where, x, z, health);
            if (foe != null) {
                if (world.realDamage.getOrDefault(foe, 0.0) >= 20 || phaseLeft < -400) {
                    world.real.remove(foe);
                    foe = null;
                } else {
                    world.moveReal(foe, x + 2, z);
                    if (tick % 20 == 0) engine.realDamaged(charge, new TargetRef(TargetKind.MOB, foe));
                }
            }
            if (tick % 6_000 == 5_999) {
                // The contract is renewed; once a state flush also replaced the guard's brain, and the hire tick re-applies it.
                if (tick == 17_999) {
                    engine.unregister(guard);
                    assertTrue(engine.register(guard, "bodyguard", settings, CombatLoadout.NONE));
                }
                assertEquals(CombatOperations.Status.APPLIED, engine.guard(guard, Optional.of(charge)).status());
            }
            world.busyChance = tick % 3_000 < 300 ? .3 : 0;
            tick();
            VirtualNpcState.Snapshot self = world.npc(guard).orElseThrow();
            if (Math.abs(self.position().point().z()) < 1.5 && self.position().world().equals(MockCombatWorld.WORLD)) roadTicks++;
            boolean fighting = engine.status(guard).orElseThrow().target().isPresent();
            boolean lagging = dead == 0 && !fighting && self.position().world().equals(where) && self.position().point().distance(new Vec3(x, self.position().point().y(), z)) > 7;
            behind = lagging ? behind + 1 : 0;
            if (behind > worst) { worst = behind; worstAt = "tick " + tick + " phase " + phase + " guard " + self.position() + " charge " + where + " " + x + "," + z + " movement " + world.movement(guard); }
        }
        assertTrue(fights > 3, "the session included fights");
        assertTrue(roadTicks > 1_000, "the guard spent time on the dirt-path road: " + roadTicks);
        assertTrue(world.realDamage.size() >= 3, "the guard fought the attackers: " + world.realDamage);
        assertTrue(worst <= 100, "the guard was left more than 7 blocks behind for " + worst + " ticks at " + worstAt);
    }

    @Test void aGuardThatMakesNoProgressCatchesUpAndSaysWhyOnce() {
        UUID guard = world.npc(0, 20, 30);
        assertTrue(engine.register(guard, "bodyguard", settings(BODYGUARD), CombatLoadout.NONE));
        UUID player = world.player("Hirer", 12, 20);
        world.place(player, MockCombatWorld.WORLD, 12, 20, 20);
        assertEquals(CombatOperations.Status.APPLIED, engine.guard(guard, Optional.of(new TargetRef(TargetKind.PLAYER, player))).status());
        world.busyChance = 1;
        for (int tick = 0; tick < 400; tick++) tick();
        WorldPosition at = world.npc(guard).orElseThrow().position();
        assertTrue(at.point().distance(new Vec3(12, 64, 20)) <= 4, "the stalled guard caught up: " + at);
        assertEquals(1, diagnostics.stream().filter((String line) -> line.contains("bodyguard") && line.contains("no progress")).count(), String.valueOf(diagnostics));
    }
}
