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
                Optional.of(new MovementSettings.Stuck(120, true))), movement);
    }

    @Test void errorsNameTheirField() {
        assertEquals(List.of("$.movement.speed: expected 1..5 blocks/second", "$.movement.stepHeight: expected 0..1.5 blocks",
                        "$.movement.maximumFall: expected whole 0..16 blocks", "$.movement.stuck.afterTicks: expected whole 1..12000 ticks", "$.movement.jump: unknown field"),
                NpcDefinition.validate(definition("{\"speed\":9,\"stepHeight\":2,\"maximumFall\":1.5,\"stuck\":{\"afterTicks\":0},\"jump\":1}")));
    }

    @Test void theRouteProfileTakesStepFallAndWater() {
        MovementSettings movement = new MovementSettings(OptionalDouble.empty(), false, false, false, OptionalDouble.of(1), OptionalInt.of(1), OptionalDouble.empty(),
                OptionalDouble.empty(), Optional.empty());
        MovementProfile profile = movement.apply(BodyMobility.forRoute("minecraft:player", .6, 1.8, MovementProfile.Mode.WALK), "minecraft:player");
        assertEquals(Set.of(MovementProfile.Mode.WALK, MovementProfile.Mode.SWIM), profile.modes());
        assertEquals(1, profile.stepHeight());
        assertEquals(1, profile.maximumFall());
        MovementProfile flying = new MovementSettings(OptionalDouble.empty(), true, true, true, OptionalDouble.empty(), OptionalInt.empty(), OptionalDouble.empty(),
                OptionalDouble.empty(), Optional.empty()).apply(BodyMobility.forRoute("minecraft:player", .6, 1.8, MovementProfile.Mode.WALK), "minecraft:player");
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
        assertTrue(movement.outOfRange(new art.arcane.animus.core.virtual.Vec3(11, 64, 0), goal));
        assertFalse(MovementSettings.DEFAULT.outOfRange(new art.arcane.animus.core.virtual.Vec3(99, 64, 0), goal));
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
}
