package art.arcane.animus.core.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.navigation.VoxelSnapshot.Cell;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.presentation.BodyLook;
import art.arcane.animus.core.virtual.Vec3;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MovementStyleTest {
    private static final String PLAYER = "minecraft:player", VILLAGER = "minecraft:villager";
    private static final PathPlanner.Limits LIMITS = new PathPlanner.Limits(4096, 4096, 256);

    private static JsonObject definition(String type, String movement) {
        return JsonParser.parseString("""
                {"schemaVersion":2,"revision":1,"uuid":"00000000-0000-0000-0000-000000000001","id":"walker","group":"test","name":"Walker",
                 "type":"%s","spawned":true,"position":{"world":"world","x":0,"y":1,"z":0,"yaw":0,"pitch":0},"movement":%s}
                """.formatted(type, movement)).getAsJsonObject();
    }
    private static StyledLeg leg(String type, String movement, MovementStyle legStyle) {
        MovementSettings settings = MovementSettings.parse(definition(type, movement));
        return StyledLeg.of(settings, legStyle, BodyMobility.forRoute(type, 0.6, 1.8, MovementProfile.Mode.WALK), type);
    }
    private static PathExecution.Proposal step(MotionValidator.Medium medium, boolean onGround) {
        return new PathExecution.Proposal(Vec3.ZERO, new Vec3(0.1, 0, 0), medium, true, false, medium, onGround, 0, false, 0);
    }
    private static final StyledLeg.Headroom OPEN = (Vec3 at, double height) -> false, LOW = (Vec3 at, double height) -> true;

    @Test void styleSettingsParseWithDefaults() {
        MovementSettings plain = MovementSettings.parse(definition(PLAYER, "{}"));
        assertEquals(MovementStyle.WALK, plain.style());
        assertEquals(MovementStyle.OnLand.WALK, plain.onLand());
        assertTrue(plain.autoSwim());
        assertFalse(plain.autoCrawl());
        MovementSettings set = MovementSettings.parse(definition(PLAYER, "{\"style\":\"swim\",\"onLand\":\"avoid\",\"auto\":{\"swim\":false,\"crawl\":true}}"));
        assertEquals(new MovementSettings.Styling(MovementStyle.SWIM, MovementStyle.OnLand.AVOID, false, true), set.styling());
    }

    @Test void styleErrorsNameTheirField() {
        assertEquals(List.of("$.movement.style: expected walk, sneak, sprint, swim, crawl, fly, glide", "$.movement.onLand: expected walk or avoid",
                        "$.movement.auto.dive: unknown field", "$.movement.auto.swim: expected boolean"),
                NpcDefinition.validate(definition(PLAYER, "{\"style\":\"run\",\"onLand\":\"fly\",\"auto\":{\"swim\":1,\"dive\":true}}")));
    }

    @Test void routeAndPointStylesOverrideInOrder() throws Exception {
        WaypointSettings route = WaypointSettings.document("""
                {"schemaVersion":1,"style":"sneak","points":[{"x":0,"y":1,"z":0},{"x":4,"y":1,"z":0,"style":"sprint"}]}""");
        assertEquals(MovementStyle.SNEAK, route.styleTo(0));
        assertEquals(MovementStyle.SPRINT, route.styleTo(1));
        assertEquals(MovementStyle.SNEAK, route.styleTo(5), "a guide uses the route's style");
        WaypointSettings plain = WaypointSettings.document("{\"schemaVersion\":1,\"points\":[{\"x\":0,\"y\":1,\"z\":0}]}");
        assertNull(plain.styleTo(0), "no route style leaves the NPC's own");
        IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class,
                () -> WaypointSettings.document("{\"schemaVersion\":1,\"points\":[{\"x\":0,\"y\":1,\"z\":0,\"style\":\"run\"}]}"));
        assertEquals("$.points[0].style must be walk, sneak, sprint, swim, crawl, fly, glide", wrong.getMessage());
        IllegalArgumentException step = assertThrows(IllegalArgumentException.class,
                () -> WaypointSettings.document("{\"schemaVersion\":1,\"points\":[{\"recording\":\"walk\",\"style\":\"sneak\"}]}"));
        assertTrue(step.getMessage().startsWith("$.points[0].style: a recording step"), step.getMessage());
    }

    @Test void speedFollowsVanillaRatios() {
        assertEquals(0.1, leg(PLAYER, "{}", null).stepDistance(2), 1e-9);
        assertEquals(0.03, leg(PLAYER, "{\"style\":\"sneak\"}", null).stepDistance(2), 1e-9);
        assertEquals(0.13, leg(PLAYER, "{}", MovementStyle.SPRINT).stepDistance(2), 1e-9);
        assertEquals(0.03, leg(PLAYER, "{}", MovementStyle.CRAWL).stepDistance(2), 1e-9);
        StyledLeg swim = leg(PLAYER, "{}", MovementStyle.SWIM);
        assertEquals(0.1, swim.stepDistance(2), 1e-9, "a swimmer walks at walking speed on land");
        swim.moved(step(MotionValidator.Medium.WATER, false), NpcPose.STANDING, OPEN);
        assertEquals(0.13, swim.stepDistance(2), 1e-9, "and sprint-swims in water");
        assertEquals(MovementSettings.MAXIMUM_SPEED / 20, leg(PLAYER, "{}", MovementStyle.SPRINT).stepDistance(20), 1e-9, "no style passes the speed cap");
    }

    @Test void flyKeepsWalkingSpeedAndGlideCruisesAtTwiceIt() {
        assertEquals(0.1, leg(PLAYER, "{\"style\":\"fly\"}", null).stepDistance(2), 1e-9);
        assertEquals(MovementStyle.GLIDE_RATIO, 2, 1e-9);
        assertEquals(0.2, leg(PLAYER, "{\"style\":\"glide\"}", null).stepDistance(2), 1e-9);
        assertEquals(0.5, leg(PLAYER, "{}", MovementStyle.GLIDE).stepDistance(5), 1e-9, "the fastest route speed glides at the speed cap");
        assertEquals(MovementSettings.MAXIMUM_SPEED / 20, leg(PLAYER, "{}", MovementStyle.GLIDE).stepDistance(8), 1e-9, "a fast body never glides past the cap");
    }

    @Test void flyAndGlideStylesFlyAndTheOldFlagIsGone() {
        assertEquals(Set.of(MovementProfile.Mode.FLY), leg(PLAYER, "{\"style\":\"fly\"}", null).profile().modes());
        assertEquals(Set.of(MovementProfile.Mode.FLY), leg(VILLAGER, "{}", MovementStyle.FLY).profile().modes());
        assertEquals(Set.of(MovementProfile.Mode.WALK), leg(PLAYER, "{\"style\":\"fly\"}", MovementStyle.WALK).profile().modes(), "a walking leg of a flying NPC walks");
        assertEquals(Set.of(MovementProfile.Mode.FLY), MovementSettings.parse(definition(PLAYER, "{\"style\":\"glide\"}"))
                .apply(BodyMobility.forRoute(PLAYER, 0.6, 1.8, MovementProfile.Mode.WALK), PLAYER).modes(), "a recording walk of a gliding NPC flies");
        StyledLeg routeFlies = StyledLeg.of(MovementSettings.DEFAULT, null, BodyMobility.forRoute(PLAYER, 0.6, 1.8, MovementProfile.Mode.FLY), PLAYER);
        assertEquals(Set.of(MovementProfile.Mode.FLY), routeFlies.profile().modes(), "route mode fly still flies a walking NPC");
        assertEquals(List.of("$.movement.fly: unknown field"), NpcDefinition.validate(definition(PLAYER, "{\"fly\":true}")));
    }

    @Test void speedCombinesWithTheMovementSpeedAttribute() {
        MovementSettings settings = MovementSettings.parse(JsonParser.parseString("""
                {"schemaVersion":2,"revision":1,"uuid":"00000000-0000-0000-0000-000000000001","id":"walker","group":"test","name":"Walker",
                 "type":"minecraft:player","spawned":true,"position":{"world":"world","x":0,"y":1,"z":0,"yaw":0,"pitch":0},
                 "attributes":{"movement_speed":0.1},"movement":{"style":"sneak"}}""").getAsJsonObject());
        double base = settings.routeSpeed(false, 2);
        assertEquals(MovementSettings.walkSpeed(0.1, true), base, 1e-9);
        StyledLeg leg = StyledLeg.of(settings, null, BodyMobility.forRoute(PLAYER, 0.6, 1.8, MovementProfile.Mode.WALK), PLAYER);
        assertEquals(base * 0.3 / 20, leg.stepDistance(base), 1e-9);
    }

    @Test void swimLetsThePlannerCrossWaterAndAvoidKeepsToIt() {
        assertEquals(Set.of(MovementProfile.Mode.WALK), leg(PLAYER, "{}", null).profile().modes());
        assertEquals(Set.of(MovementProfile.Mode.WALK, MovementProfile.Mode.SWIM), leg(PLAYER, "{}", MovementStyle.SWIM).profile().modes());
        MovementProfile avoid = leg(PLAYER, "{\"onLand\":\"avoid\"}", MovementStyle.SWIM).profile();
        assertEquals(Set.of(MovementProfile.Mode.SWIM), avoid.modes());
        assertFalse(avoid.climbs());
        assertEquals(Set.of(MovementProfile.Mode.WALK), leg("minecraft:armor_stand", "{}", MovementStyle.SWIM).profile().modes(), "a body that sinks never swims");
        assertEquals(Set.of(MovementProfile.Mode.FLY), leg(PLAYER, "{}", MovementStyle.GLIDE).profile().modes());

        VoxelSnapshot.Builder pond = lane();
        for (int x = 1; x <= 3; x++) for (int y = 57; y <= 60; y++) pond.set(x, y, 3, Cell.WATER);
        assertNotEquals(PathPlanner.Status.FOUND, plan(pond, leg(PLAYER, "{}", null).profile()).status());
        PathPlanner.Result swum = plan(pond, leg(PLAYER, "{}", MovementStyle.SWIM).profile());
        assertEquals(PathPlanner.Status.FOUND, swum.status(), swum.toString());
        assertTrue(swum.path().stream().anyMatch(PathPlanner.Step::swimming));
    }

    @Test void crawlLetsAPlayerBodyPlanThroughAGapOneBlockHigh() {
        VoxelSnapshot.Builder gap = lane();
        gap.set(2, 62, 3, Cell.SOLID);
        assertNotEquals(PathPlanner.Status.FOUND, plan(gap, leg(PLAYER, "{}", null).profile()).status());
        StyledLeg crawl = leg(PLAYER, "{}", MovementStyle.CRAWL);
        assertTrue(crawl.crawls());
        assertEquals(MovementStyle.CRAWL_HEIGHT, crawl.height(), 1e-9);
        assertEquals(PathPlanner.Status.FOUND, plan(gap, crawl.profile()).status());
        StyledLeg auto = leg(PLAYER, "{\"auto\":{\"crawl\":true}}", null);
        assertEquals(PathPlanner.Status.FOUND, plan(gap, auto.profile()).status());
        assertFalse(leg(VILLAGER, "{}", MovementStyle.CRAWL).crawls(), "only a player body has a crawling pose");
        assertFalse(leg(PLAYER, "{}", MovementStyle.SNEAK).crawls());
    }

    @Test void aFlyingLegRunsAsAFlyingRoute() throws Exception {
        MovementSettings flier = MovementSettings.parse(definition(PLAYER, "{\"style\":\"fly\"}"));
        assertEquals(MovementProfile.Mode.FLY, flier.legMode(MovementProfile.Mode.WALK, null), "no gravity, doors or ground goals on its legs");
        assertEquals(MovementProfile.Mode.WALK, flier.legMode(MovementProfile.Mode.WALK, MovementStyle.WALK));
        assertEquals(MovementProfile.Mode.FLY, MovementSettings.DEFAULT.legMode(MovementProfile.Mode.WALK, MovementStyle.GLIDE));
        assertEquals(MovementProfile.Mode.SWIM, MovementSettings.DEFAULT.legMode(MovementProfile.Mode.SWIM, null));
        WaypointSettings gliding = WaypointSettings.document("""
                {"schemaVersion":1,"style":"glide","points":[{"x":0,"y":70.5,"z":0}]}""");
        assertEquals(70.5, gliding.points().getFirst().position().y(), 1e-9, "a flying route's points may hang in the air");
        WaypointSettings point = WaypointSettings.document("""
                {"schemaVersion":1,"points":[{"x":0,"y":1,"z":0},{"x":0,"y":70.5,"z":0,"style":"fly"}]}""");
        assertEquals(70.5, point.points().get(1).position().y(), 1e-9);
        IllegalArgumentException walking = assertThrows(IllegalArgumentException.class,
                () -> WaypointSettings.document("{\"schemaVersion\":1,\"points\":[{\"x\":0,\"y\":70.5,\"z\":0,\"style\":\"sneak\"}]}"));
        assertTrue(walking.getMessage().contains("walking waypoints require integer feet height"), walking.getMessage());
        JsonObject inline = definition(PLAYER, "{\"style\":\"fly\"}");
        inline.add("modules", JsonParser.parseString("{\"animus:navigation\":{\"enabled\":true,\"points\":[{\"x\":4,\"y\":7.5,\"z\":0}]}}"));
        assertEquals(7.5, WaypointSettings.parse(inline).orElseThrow().points().getFirst().position().y(), 1e-9, "an inline route of a flying NPC may end in the air");
    }

    @Test void playerBodiesShowTheStylePoseAndFlags() {
        assertEquals(new StyledLeg.Look(NpcPose.STANDING, 0), leg(PLAYER, "{}", null).moved(step(MotionValidator.Medium.GROUND, true), NpcPose.STANDING, OPEN));
        assertEquals(new StyledLeg.Look(NpcPose.SNEAKING, 0), leg(PLAYER, "{}", MovementStyle.SNEAK).moved(step(MotionValidator.Medium.GROUND, true), NpcPose.STANDING, OPEN));
        StyledLeg sprint = leg(PLAYER, "{}", MovementStyle.SPRINT);
        assertEquals(new StyledLeg.Look(NpcPose.STANDING, BodyLook.SPRINTING), sprint.moved(step(MotionValidator.Medium.GROUND, true), NpcPose.STANDING, OPEN));
        assertEquals(new StyledLeg.Look(NpcPose.STANDING, 0), sprint.resting(NpcPose.STANDING), "nobody sprints standing still");
        assertEquals(new StyledLeg.Look(NpcPose.SWIMMING, 0), leg(PLAYER, "{}", MovementStyle.CRAWL).moved(step(MotionValidator.Medium.GROUND, true), NpcPose.STANDING, OPEN));
        assertEquals(new StyledLeg.Look(NpcPose.STANDING, BodyLook.GLIDING), leg(PLAYER, "{}", MovementStyle.GLIDE).moved(step(MotionValidator.Medium.AIR, false), NpcPose.STANDING, OPEN));
        assertEquals(new StyledLeg.Look(NpcPose.STANDING, 0), leg(PLAYER, "{}", MovementStyle.FLY).moved(step(MotionValidator.Medium.AIR, false), NpcPose.STANDING, OPEN),
                "fly keeps the normal pose");
        assertEquals(new StyledLeg.Look(NpcPose.SNEAKING, 0), leg(PLAYER, "{}", null).moved(step(MotionValidator.Medium.GROUND, true), NpcPose.SNEAKING, OPEN),
                "a declared pose stays while walking");
    }

    @Test void automaticSwimAndCrawlSwitchPerStep() {
        StyledLeg walk = leg(PLAYER, "{\"auto\":{\"crawl\":true}}", null);
        assertEquals(new StyledLeg.Look(NpcPose.SWIMMING, BodyLook.SPRINTING), walk.moved(step(MotionValidator.Medium.WATER, false), NpcPose.STANDING, OPEN));
        assertEquals(new StyledLeg.Look(NpcPose.STANDING, 0), walk.moved(step(MotionValidator.Medium.GROUND, true), NpcPose.STANDING, OPEN));
        assertEquals(new StyledLeg.Look(NpcPose.SWIMMING, 0), walk.moved(step(MotionValidator.Medium.GROUND, true), NpcPose.STANDING, LOW));
        assertEquals(new StyledLeg.Look(NpcPose.SWIMMING, 0), walk.resting(NpcPose.STANDING), "a body resting in a low gap stays prone");
        assertEquals(new StyledLeg.Look(NpcPose.STANDING, 0), leg(PLAYER, "{\"auto\":{\"swim\":false}}", null)
                .moved(step(MotionValidator.Medium.WATER, false), NpcPose.STANDING, OPEN));
        assertEquals(new StyledLeg.Look(NpcPose.STANDING, 0), leg(PLAYER, "{}", null).moved(step(MotionValidator.Medium.GROUND, true), NpcPose.STANDING, LOW),
                "headroom is not read on a leg that cannot crawl");
    }

    @Test void otherBodiesShowOnlyTheSneakAndSprintFlags() {
        assertEquals(new StyledLeg.Look(NpcPose.STANDING, BodyLook.SNEAKING), leg(VILLAGER, "{}", MovementStyle.SNEAK).moved(step(MotionValidator.Medium.GROUND, true), NpcPose.STANDING, OPEN));
        assertEquals(new StyledLeg.Look(NpcPose.STANDING, BodyLook.SPRINTING), leg(VILLAGER, "{}", MovementStyle.SPRINT).moved(step(MotionValidator.Medium.GROUND, true), NpcPose.STANDING, OPEN));
        assertEquals(new StyledLeg.Look(NpcPose.STANDING, 0), leg(VILLAGER, "{}", MovementStyle.SWIM).moved(step(MotionValidator.Medium.WATER, false), NpcPose.STANDING, OPEN));
        assertEquals(0x02, BodyLook.named("villager").moving(NpcPose.STANDING, BodyLook.SNEAKING).sharedFlags(false));
    }

    /** A one-block lane between three-high walls on a one-block floor, from (0,61,3) to (4,61,3). */
    private static VoxelSnapshot.Builder lane() {
        VoxelSnapshot.Builder builder = new VoxelSnapshot.Builder("world", -2, 57, 1, 9, 10, 5).fill(Cell.AIR).availableChunk(-1, 0).availableChunk(0, 0);
        for (int x = -2; x <= 6; x++) for (int z = 1; z <= 5; z++) builder.set(x, 60, z, Cell.SOLID);
        for (int x = -1; x <= 5; x++) for (int z = 2; z <= 4; z++) for (int y = 61; y <= 63; y++) builder.set(x, y, z, Cell.SOLID);
        for (int x = 0; x <= 4; x++) for (int y = 61; y <= 63; y++) builder.set(x, y, 3, Cell.AIR);
        for (int x = -2; x <= 6; x++) for (int z = 1; z <= 5; z++) builder.set(x, 64, z, Cell.SOLID);
        return builder;
    }
    private static PathPlanner.Result plan(VoxelSnapshot.Builder builder, MovementProfile profile) {
        return new PathPlanner().plan(builder.build(), new BlockPos(0, 61, 3), new BlockPos(4, 61, 3), profile, LIMITS, () -> false);
    }
}
