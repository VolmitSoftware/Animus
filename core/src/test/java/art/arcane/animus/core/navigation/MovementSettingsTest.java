package art.arcane.animus.core.navigation;

import art.arcane.animus.core.npc.NpcDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MovementSettingsTest {
    private static JsonObject definition(String movement) {
        return JsonParser.parseString("""
                {"schemaVersion":1,"revision":1,"uuid":"00000000-0000-0000-0000-000000000001","id":"walker","group":"test","name":"Walker",
                 "type":"minecraft:player","spawned":true,"position":{"world":"world","x":0,"y":1,"z":0,"yaw":0,"pitch":0}%s}
                """.formatted(movement == null ? "" : ",\"movement\":" + movement)).getAsJsonObject();
    }

    @Test void absentMovementKeepsTheRouteDefaults() {
        assertEquals(MovementSettings.DEFAULT, MovementSettings.parse(definition(null)));
        assertTrue(MovementSettings.DEFAULT.avoidWater());
    }

    @Test void everyFieldParses() {
        MovementSettings movement = MovementSettings.parse(definition("""
                {"speed":1.5,"avoidWater":false,"fly":true,"openDoors":true,"stepHeight":1,"maximumFall":1,"pathRange":60,"arrivalMargin":0.5,
                 "stuck":{"afterTicks":120,"teleport":true}}
                """));
        assertEquals(new MovementSettings(OptionalDouble.of(1.5), false, true, true, OptionalDouble.of(1), OptionalInt.of(1), OptionalDouble.of(60), OptionalDouble.of(0.5),
                Optional.of(new MovementSettings.Stuck(120, true)), OptionalDouble.empty(), IdleFall.GRAVITY), movement);
    }

    @Test void errorsNameTheirField() {
        assertEquals(List.of("$.movement.speed: expected 1..5 blocks/second", "$.movement.stepHeight: expected 0..1.5 blocks",
                        "$.movement.maximumFall: expected whole 0..16 blocks", "$.movement.stuck.afterTicks: expected whole 1..12000 ticks", "$.movement.jump: unknown field"),
                NpcDefinition.validate(definition("{\"speed\":9,\"stepHeight\":2,\"maximumFall\":1.5,\"stuck\":{\"afterTicks\":0},\"jump\":1}")));
    }

    @Test void theRouteProfileTakesStepFallAndWater() {
        MovementSettings movement = new MovementSettings(OptionalDouble.empty(), false, false, false, OptionalDouble.of(1), OptionalInt.of(1), OptionalDouble.empty(),
                OptionalDouble.empty(), Optional.empty(), OptionalDouble.empty(), IdleFall.GRAVITY);
        MovementProfile profile = movement.apply(BodyMobility.forRoute("minecraft:player", .6, 1.8, MovementProfile.Mode.WALK), "minecraft:player");
        assertEquals(Set.of(MovementProfile.Mode.WALK, MovementProfile.Mode.SWIM), profile.modes());
        assertEquals(1, profile.stepHeight());
        assertEquals(1, profile.maximumFall());
        MovementProfile flying = new MovementSettings(OptionalDouble.empty(), true, true, true, OptionalDouble.empty(), OptionalInt.empty(), OptionalDouble.empty(),
                OptionalDouble.empty(), Optional.empty(), OptionalDouble.empty(), IdleFall.GRAVITY).apply(BodyMobility.forRoute("minecraft:player", .6, 1.8, MovementProfile.Mode.WALK), "minecraft:player");
        assertEquals(Set.of(MovementProfile.Mode.FLY), flying.modes());
        assertFalse(flying.opensDoors(), "only walking bodies open doors");
        assertFalse(profile.opensDoors());
        assertTrue(MovementSettings.parse(definition("{\"openDoors\":true}")).apply(profile, "minecraft:player").opensDoors());
        assertEquals(profile, MovementSettings.DEFAULT.apply(profile, "minecraft:player"));
    }

    @Test void arrivalRangeStuckSpeedAndGravityRules() {
        MovementSettings movement = MovementSettings.parse(definition("{\"speed\":3,\"pathRange\":10,\"arrivalMargin\":0.5,\"stuck\":{\"afterTicks\":100,\"teleport\":true}}"));
        art.arcane.animus.core.virtual.Vec3 goal = new art.arcane.animus.core.virtual.Vec3(0, 64, 0);
        assertTrue(movement.arrived(new art.arcane.animus.core.virtual.Vec3(0.4, 64, 0), goal));
        assertFalse(movement.arrived(new art.arcane.animus.core.virtual.Vec3(0.6, 64, 0), goal));
        assertFalse(MovementSettings.DEFAULT.arrived(goal, goal));
        assertEquals(10, movement.pathRange().orElseThrow());
        assertTrue(MovementSettings.DEFAULT.pathRange().isEmpty());
        assertTrue(movement.stuck(101));
        assertFalse(movement.stuck(100));
        assertTrue(movement.teleportsWhenStuck());
        assertEquals(3, movement.routeSpeed(false, 2));
        assertEquals(2, movement.routeSpeed(true, 2));
        JsonObject floating = definition(null);
        floating.addProperty("gravity", false);
        assertFalse(MovementSettings.falls("minecraft:player", floating));
        assertTrue(MovementSettings.falls("minecraft:player", definition(null)));
        assertFalse(MovementSettings.routeDeclaresSpeed(JsonParser.parseString("{\"modules\":{\"animus:navigation\":{\"points\":[]}}}").getAsJsonObject()));
    }

    private static JsonObject attributed(String type, String movement, String attributes) {
        JsonObject definition = definition(movement);
        definition.addProperty("type", type);
        definition.add("attributes", JsonParser.parseString(attributes));
        return definition;
    }

    @Test void movementAttributesFillWhatTheMovementObjectLeavesOut() {
        MovementSettings player = MovementSettings.parse(attributed("minecraft:player", null,
                "{\"movement_speed\":0.05,\"step_height\":1,\"safe_fall_distance\":6.5,\"jump_strength\":0.42}"));
        assertEquals(43.17 * 0.05, player.speed().orElseThrow(), 1e-9, "a player body walks as a player with that speed would");
        assertEquals(1, player.stepHeight().orElseThrow());
        assertEquals(6, player.maximumFall().orElseThrow());
        assertEquals(1.25, player.jumpHeight().orElseThrow(), 0.01, "the vanilla jump reaches about a block and a quarter");
        MovementSettings zombie = MovementSettings.parse(attributed("minecraft:zombie", null, "{\"movement_speed\":0.3}"));
        assertEquals(44.05 * 0.09, zombie.speed().orElseThrow(), 1e-9, "a mob body walks with the square of its speed, as vanilla mobs do");
        MovementSettings fast = MovementSettings.parse(attributed("minecraft:player", null, "{\"movement_speed\":2,\"step_height\":9,\"safe_fall_distance\":-3}"));
        assertEquals(10, fast.speed().orElseThrow(), "kept within the navigation range");
        assertEquals(MovementProfile.MAX_STEP, fast.stepHeight().orElseThrow());
        assertEquals(0, fast.maximumFall().orElseThrow());
    }

    @Test void theMovementObjectWinsOverAttributes() {
        MovementSettings movement = MovementSettings.parse(attributed("minecraft:player", "{\"speed\":2,\"stepHeight\":0.5,\"maximumFall\":2}",
                "{\"movement_speed\":0.2,\"step_height\":1,\"safe_fall_distance\":9}"));
        assertEquals(2, movement.speed().orElseThrow());
        assertEquals(0.5, movement.stepHeight().orElseThrow());
        assertEquals(2, movement.maximumFall().orElseThrow());
    }

    @Test void gravityAndJumpStrengthShapeTheJumpAndTheFall() {
        MovementSettings floaty = MovementSettings.parse(attributed("minecraft:player", null, "{\"gravity\":0.02}"));
        assertEquals(0.02, floaty.gravity());
        assertEquals(MovementProfile.MAX_JUMP, floaty.jumpHeight().orElseThrow(), "low gravity lets the vanilla jump climb high, up to the planner's limit");
        MovementProfile walk = BodyMobility.forRoute("minecraft:player", .6, 1.8, MovementProfile.Mode.WALK);
        assertEquals(MovementProfile.MAX_JUMP, floaty.apply(walk, "minecraft:player").jumpHeight());
        assertEquals(IdleFall.GRAVITY, MovementSettings.DEFAULT.gravity());
        assertTrue(MovementSettings.falls("minecraft:player", attributed("minecraft:player", null, "{\"gravity\":0.02}")));
        assertFalse(MovementSettings.falls("minecraft:player", attributed("minecraft:player", null, "{\"gravity\":0}")), "no gravity hovers");
        assertFalse(MovementSettings.falls("minecraft:player", attributed("minecraft:player", null, "{\"gravity\":-0.1}")));
    }

    @Test void aLighterGravityFallsSlower() {
        MotionValidator.Sampler open = (int x, int y, int z) -> y < 0 ? VoxelSnapshot.Cell.SOLID : VoxelSnapshot.Cell.AIR;
        IdleFall.Step normal = IdleFall.step(new art.arcane.animus.core.virtual.Vec3(.5, 50, .5), 0, IdleFall.GRAVITY, .6, 1.8, true, open);
        IdleFall.Step light = IdleFall.step(new art.arcane.animus.core.virtual.Vec3(.5, 50, .5), 0, 0.02, .6, 1.8, true, open);
        assertEquals((IdleFall.GRAVITY) * IdleFall.DRAG, normal.speed(), 1e-9);
        assertEquals(0.02 * IdleFall.DRAG, light.speed(), 1e-9);
        IdleFall.Step terminal = IdleFall.step(new art.arcane.animus.core.virtual.Vec3(.5, 50, .5), 5, 0.02, .6, 1.8, true, open);
        assertEquals(0.02 * IdleFall.DRAG / (1 - IdleFall.DRAG), terminal.speed(), 1e-9, "terminal speed follows gravity");
    }

    @Test void aWalkingRouteThatMayEnterWaterSwimsAcrossAPondAndWalksOnLand() {
        VoxelSnapshot.Builder builder = new VoxelSnapshot.Builder("world", 0, 0, 0, 10, 8, 7).fill(VoxelSnapshot.Cell.AIR).availableChunk(0, 0);
        for (int x = 0; x < 10; x++) for (int y = 0; y < 8; y++) for (int z = 0; z < 7; z++)
            if (x == 0 || x == 9 || z == 0 || z == 6 || y == 0 || y == 7) builder.set(x, y, z, VoxelSnapshot.Cell.SOLID);
        for (int z = 1; z <= 5; z++) {
            for (int y = 1; y <= 3; y++) { builder.set(1, y, z, VoxelSnapshot.Cell.SOLID); builder.set(7, y, z, VoxelSnapshot.Cell.SOLID); builder.set(8, y, z, VoxelSnapshot.Cell.SOLID); }
            for (int x = 2; x <= 6; x++) { builder.set(x, 1, z, VoxelSnapshot.Cell.SOLID); builder.set(x, 2, z, VoxelSnapshot.Cell.WATER); builder.set(x, 3, z, VoxelSnapshot.Cell.WATER); }
        }
        VoxelSnapshot pond = builder.build();
        MovementProfile walk = BodyMobility.forRoute("minecraft:player", .6, 1.8, MovementProfile.Mode.WALK);
        PathPlanner.Limits limits = new PathPlanner.Limits(5000, 5000, 500);
        BlockPos from = new BlockPos(1, 4, 3), to = new BlockPos(8, 4, 3);
        assertEquals(PathPlanner.Status.UNREACHABLE, new PathPlanner().plan(pond, from, to, MovementSettings.DEFAULT.apply(walk, "minecraft:player"), limits, () -> false).status());
        MovementProfile wading = MovementSettings.parse(definition("{\"avoidWater\":false}")).apply(walk, "minecraft:player");
        PathPlanner.Result crossed = new PathPlanner().plan(pond, from, to, wading, limits, () -> false);
        assertEquals(PathPlanner.Status.FOUND, crossed.status());
        assertTrue(crossed.path().stream().anyMatch(PathPlanner.Step::swimming), "it swims the water");
        assertFalse(crossed.path().getFirst().swimming() || crossed.path().getLast().swimming(), "and walks the banks");
    }
}
