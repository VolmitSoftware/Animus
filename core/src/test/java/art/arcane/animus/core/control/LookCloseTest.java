package art.arcane.animus.core.control;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.virtual.Vec3;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LookCloseTest {
    private static JsonObject definition(String module, boolean imported) {
        return JsonParser.parseString("""
                {"schemaVersion":2,"revision":1,"uuid":"00000000-0000-0000-0000-000000000001","id":"greeter","group":"test",
                 "name":"Greeter","type":"minecraft:player","spawned":true,
                 "position":{"world":"world","x":0,"y":1,"z":0,"yaw":0,"pitch":0}%s%s}
                """.formatted(module == null ? "" : ",\"modules\":{\"animus:look-close\":" + module + "}", imported ? ",\"migration\":{}" : "")).getAsJsonObject();
    }
    private static String failure(String module) {
        return assertThrows(IllegalArgumentException.class, () -> LookClose.parse(definition(module, false)), module).getMessage();
    }

    @Test void presentModuleIsEnabledByDefaultWithDefaults() {
        assertTrue(LookClose.parse(definition(null, false)).isEmpty());
        assertEquals(new LookClose(10, false, false), LookClose.parse(definition("{}", false)).orElseThrow());
        assertEquals(new LookClose(64, true, true), LookClose.parse(definition("{\"enabled\":true,\"range\":64,\"lineOfSight\":true,\"whileNavigating\":true}", false)).orElseThrow());
        assertTrue(LookClose.parse(definition("{\"enabled\":false,\"range\":3}", false)).isEmpty());
    }
    @Test void importedDefinitionsNeedNoReviewAcceptance() {
        assertTrue(LookClose.parse(definition("{\"enabled\":true}", true)).isPresent());
        assertTrue(NpcDefinition.validate(definition("{\"enabled\":true}", true)).stream().noneMatch((String error) -> error.contains("look-close")));
    }
    @Test void malformedFieldsNameTheirExactPath() {
        assertTrue(failure("{\"range\":0.5}").startsWith("$.modules.animus:look-close.range"));
        assertTrue(failure("{\"range\":65}").startsWith("$.modules.animus:look-close.range"));
        assertTrue(failure("{\"range\":\"10\"}").startsWith("$.modules.animus:look-close.range"));
        assertTrue(failure("{\"enabled\":1}").startsWith("$.modules.animus:look-close.enabled"));
        assertTrue(failure("{\"lineOfSight\":\"yes\"}").startsWith("$.modules.animus:look-close.lineOfSight"));
        assertTrue(failure("{\"whileNavigating\":null}").startsWith("$.modules.animus:look-close.whileNavigating"));
        assertTrue(failure("{\"radius\":5}").startsWith("$.modules.animus:look-close.radius"));
        assertTrue(failure("[]").startsWith("$.modules.animus:look-close"));
        assertTrue(NpcDefinition.validate(definition("{\"range\":0}", false)).contains("$.modules.animus:look-close.range: expected 1..64 blocks"));
    }
    @Test void facingMatchesLookCommandGeometry() {
        LookClose.Facing east = LookClose.facing(new Vec3(0, 1.6, 0), new Vec3(5, 1.6, 0)).orElseThrow();
        assertEquals(-90, east.yaw(), 1e-4); assertEquals(0, east.pitch(), 1e-4);
        LookClose.Facing up = LookClose.facing(new Vec3(0, 0, 0), new Vec3(0, 3, 3)).orElseThrow();
        assertEquals(0, up.yaw(), 1e-4); assertEquals(-45, up.pitch(), 1e-4);
        assertTrue(LookClose.facing(Vec3.ZERO, Vec3.ZERO).isEmpty());
        assertEquals(1.6, LookClose.eye(Vec3.ZERO, 2).y(), 1e-9);
    }
    @Test void rankingPrefersViewersThenNearestWithinRangeAndIsBounded() {
        LookClose settings = new LookClose(10, false, false);
        Vec3 eye = Vec3.ZERO;
        List<LookClose.Candidate<String>> candidates = List.of(
                new LookClose.Candidate<>("far", new Vec3(11, 0, 0), true),
                new LookClose.Candidate<>("near-hidden", new Vec3(2, 0, 0), false),
                new LookClose.Candidate<>("viewer", new Vec3(8, 0, 0), true),
                new LookClose.Candidate<>("closer-viewer", new Vec3(4, 0, 0), true),
                new LookClose.Candidate<>("hidden", new Vec3(5, 0, 0), false));
        List<LookClose.Candidate<String>> ranked = settings.ranked(eye, candidates);
        assertEquals(List.of("closer-viewer", "viewer", "near-hidden"), ranked.stream().map(LookClose.Candidate::player).toList());
        assertTrue(settings.ranked(eye, List.of(new LookClose.Candidate<>("away", new Vec3(0, 0, 10.5), false))).isEmpty());
    }

    @Test void randomLookAndNpcTargetsParse() {
        LookClose look = LookClose.parse(definition("{\"npcs\":true,\"randomLook\":{\"enabled\":true,\"delayTicks\":60,\"yaw\":[0,360],\"pitch\":[-8,8]}}", false)).orElseThrow();
        assertTrue(look.npcs());
        assertEquals(new LookClose.RandomLook(true, 60, 0, 360, -8, 8), look.randomLook().orElseThrow());
        assertEquals(java.util.Optional.empty(), new LookClose(10, false, false).randomLook());
        assertTrue(failure("{\"randomLook\":{\"delayTicks\":0}}").startsWith("$.modules.animus:look-close.randomLook.delayTicks"));
        assertTrue(failure("{\"randomLook\":{\"pitch\":[10,-10]}}").startsWith("$.modules.animus:look-close.randomLook.pitch"));
        assertTrue(failure("{\"randomLook\":{\"yaw\":[0]}}").startsWith("$.modules.animus:look-close.randomLook.yaw"));
        assertTrue(failure("{\"randomLook\":{\"spin\":1}}").startsWith("$.modules.animus:look-close.randomLook.spin"));
        assertTrue(failure("{\"npcs\":\"yes\"}").startsWith("$.modules.animus:look-close.npcs"));
    }
    @Test void randomLookPicksAFacingInsideItsRangesOnlyWhenEnabled() {
        LookClose.RandomLook random = new LookClose.RandomLook(true, 60, 90, 90, -8, 8);
        java.util.random.RandomGenerator generator = new java.util.Random(7);
        for (int i = 0; i < 50; i++) {
            LookClose.Facing facing = random.pick(generator);
            assertEquals(90f, facing.yaw());
            assertTrue(facing.pitch() >= -8 && facing.pitch() <= 8);
        }
        assertFalse(new LookClose.RandomLook(false, 60, 0, 360, 0, 0).active());
        assertTrue(random.active());
    }
    @Test void headOnlyPerPlayerPermissionsAndTurnRateParse() {
        LookClose look = LookClose.parse(definition("{\"headOnly\":true,\"perPlayer\":true,\"permissions\":[\"town.vip\"],\"turnRate\":{\"yaw\":5}}", false)).orElseThrow();
        assertTrue(look.headOnly()); assertTrue(look.perPlayer());
        assertEquals(List.of("town.vip"), look.permissions());
        assertEquals(new LookClose.TurnRate(5, 10), look.turnRate().orElseThrow());
        assertTrue(look.admits("town.vip"::equals)); assertFalse(look.admits((String node) -> false));
        assertTrue(failure("{\"permissions\":[\"a b\"]}").contains("permissions"));
        assertTrue(failure("{\"turnRate\":{\"yaw\":400}}").contains("turnRate.yaw"));
        assertTrue(failure("{\"headOnly\":1}").contains("headOnly"));
    }
    @Test void aTurnRateTurnsTheShortWayAndStopsAtTheTarget() {
        LookClose slow = LookClose.parse(definition("{\"turnRate\":{\"yaw\":4,\"pitch\":0}}", false)).orElseThrow();
        assertEquals(new LookClose.Facing(-20, 0), slow.turn(0, 0, new LookClose.Facing(-90, 30), 5), "4 degrees a tick for 5 ticks, pitch locked");
        assertEquals(new LookClose.Facing(190, 0), slow.turn(170, 0, new LookClose.Facing(-150, 0), 5), "across the wrap, not the long way round");
        assertEquals(new LookClose.Facing(12, 0), slow.turn(10, 0, new LookClose.Facing(12, 0), 5));
        assertEquals(new LookClose.Facing(-90, 30), new LookClose(10, false, false).turn(0, 0, new LookClose.Facing(-90, 30), 5), "no rate turns at once");
    }
}
