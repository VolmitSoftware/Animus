package art.arcane.animus.core.navigation;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaypointSettingsTest {
    private static JsonObject definition(String nativeSettings, boolean imported) {
        return JsonParser.parseString("""
                {"schemaVersion":1,"revision":1,"uuid":"00000000-0000-0000-0000-000000000001","id":"patrol","group":"test",
                 "name":"Guard","type":"minecraft:player","spawned":true,
                 "position":{"world":"world","x":0,"y":1,"z":0,"yaw":0,"pitch":0},
                 "modules":{"citizens:review":{"waypoints":{"enabled":true}}%s}%s}
                """.formatted(nativeSettings==null?"":",\"animus:navigation\":"+nativeSettings,imported?",\"migration\":{}":"")).getAsJsonObject();
    }
    @Test void preservedImportsNeverBecomeExecutableAndNativeRoutesNeedExplicitEnablement() throws Exception {
        assertTrue(WaypointSettings.parse(definition(null,true)).isEmpty());
        assertTrue(WaypointSettings.parse(definition("{\"points\":[{\"x\":1,\"y\":2,\"z\":3}]}",false)).isEmpty());
        assertThrows(IllegalArgumentException.class,()->WaypointSettings.parse(definition("{\"enabled\":true,\"points\":[{\"x\":1,\"y\":2,\"z\":3}]}",true)));
    }
    @Test void reviewedNativeRouteKeepsLoopWaitModeAndCoordinates() throws Exception {
        var parsed=WaypointSettings.parse(definition("""
                {"enabled":true,"reviewAccepted":true,"mode":"swim","speed":3.5,"loop":true,"waitTicks":40,
                 "points":[{"x":1.25,"y":62.5,"z":-2}]}
                """,true)).orElseThrow();
        assertEquals(MovementProfile.Mode.SWIM,parsed.mode());assertEquals(3.5,parsed.speed());assertTrue(parsed.loop());assertEquals(WaypointSettings.Wait.fixed(40),parsed.waitTicks());
        assertEquals(62.5,parsed.points().getFirst().position().y());assertThrows(UnsupportedOperationException.class,()->parsed.points().clear());
    }
    @Test void rejectsMalformedUnboundedAndUnsupportedPhysicsSettings() {
        for(String setting:new String[]{
                "{\"enabled\":true,\"points\":[]}",
                "{\"enabled\":true,\"mode\":[\"walk\"],\"points\":[{\"x\":0,\"y\":1,\"z\":0}]}",
                "{\"enabled\":true,\"speed\":6,\"points\":[{\"x\":0,\"y\":1,\"z\":0}]}",
                "{\"enabled\":true,\"points\":[{\"x\":0,\"y\":1.5,\"z\":0}]}",
                "{\"enabled\":true,\"waitTicks\":\"4\",\"points\":[{\"x\":0,\"y\":1,\"z\":0}]}",
                "{\"enabled\":true,\"points\":[{\"x\":1e309,\"y\":1,\"z\":0}]}"})
            assertThrows(IllegalArgumentException.class,()->WaypointSettings.parse(definition(setting,false)),setting);
        String oversized="{\"enabled\":true,\"points\":["+String.join(",",java.util.Collections.nCopies(257,"{\"x\":0,\"y\":1,\"z\":0}"))+"]}";
        assertThrows(IllegalArgumentException.class,()->WaypointSettings.parse(definition(oversized,false)));
    }
    @Test void reusableReferenceRequiresSafeIdentifierAndUnambiguousSettings() throws Exception {
        var reference=definition("{\"enabled\":true,\"route\":\"town_patrol\"}",false);
        assertTrue(WaypointSettings.parse(reference).isEmpty());assertEquals("town_patrol",WaypointSettings.reference(reference));
        assertThrows(IllegalArgumentException.class,()->WaypointSettings.reference(definition("{\"enabled\":true,\"route\":\"../secret\"}",false)));
        assertThrows(IllegalArgumentException.class,()->WaypointSettings.reference(definition("{\"enabled\":true,\"route\":\"patrol\",\"speed\":2}",false)));
        assertThrows(IllegalArgumentException.class,()->WaypointSettings.reference(definition("{\"enabled\":true,\"route\":\"patrol\"}",true)));
    }
    @Test void reusableDocumentRequiresExactSchemaAndUtf8ByteBound() {
        String route="{\"schemaVersion\":1,\"points\":[{\"x\":0,\"y\":1,\"z\":0}]}";
        assertThrows(IllegalArgumentException.class,()->WaypointSettings.document(route.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0000000000000000001")));
        String oversized=route.substring(0,route.length()-1)+",\"note\":\""+"漢".repeat(22000)+"\"}";
        assertTrue(oversized.length()<65536);
        assertThrows(java.io.IOException.class,()->WaypointSettings.document(oversized));
    }
    private static String field(String setting) {
        return assertThrows(IllegalArgumentException.class,()->WaypointSettings.parse(definition(setting,false)),setting).getMessage();
    }
    private static String points(int count) {
        return String.join(",",java.util.Collections.nCopies(count,"{\"x\":0,\"y\":1,\"z\":0}"));
    }
    @Test void ordersDefaultToSequenceAndKeepTheirOwnSettings() throws Exception {
        var plain=WaypointSettings.parse(definition("{\"enabled\":true,\"points\":[{\"x\":0,\"y\":1,\"z\":0}]}",false)).orElseThrow();
        assertEquals(WaypointSettings.Order.SEQUENCE,plain.order());assertEquals(WaypointSettings.Wait.fixed(20),plain.waitTicks());assertNull(plain.wander());
        var random=WaypointSettings.parse(definition("""
                {"enabled":true,"order":"random","linkDistance":12.5,"points":[{"x":0,"y":1,"z":0,"waitTicks":90}],"guides":[{"x":4,"y":1,"z":0}]}
                """,false)).orElseThrow();
        assertEquals(12.5,random.linkDistance());assertEquals(1,random.guides().size());assertEquals(WaypointSettings.Wait.fixed(90),random.waitAfter(0));assertTrue(random.endless());
        var defaults=WaypointSettings.parse(definition("{\"enabled\":true,\"order\":\"random\",\"points\":[{\"x\":0,\"y\":1,\"z\":0}]}",false)).orElseThrow();
        assertEquals(32,defaults.linkDistance());assertEquals(WaypointSettings.Wait.fixed(20),defaults.waitAfter(0));
        var wander=WaypointSettings.parse(definition("{\"enabled\":true,\"order\":\"wander\"}",false)).orElseThrow();
        assertEquals(new WaypointSettings.Wander(25,3),wander.wander());assertTrue(wander.points().isEmpty());
        var tuned=WaypointSettings.parse(definition("{\"enabled\":true,\"order\":\"wander\",\"wander\":{\"radius\":32,\"height\":0},\"points\":["+points(64)+"]}",false)).orElseThrow();
        assertEquals(new WaypointSettings.Wander(32,0),tuned.wander());
        assertTrue(WaypointSettings.parse(definition("{\"enabled\":true,\"order\":\"pingpong\",\"points\":["+points(256)+"]}",false)).isPresent());
        assertFalse(WaypointSettings.parse(definition("{\"enabled\":true,\"order\":\"pingpong\",\"points\":[{\"x\":0,\"y\":1,\"z\":0}]}",false)).orElseThrow().endless());
    }
    @Test void orderSpecificFieldsAreRejectedElsewhereWithFieldDiagnostics() {
        String one="\"points\":[{\"x\":0,\"y\":1,\"z\":0}]";
        assertTrue(field("{\"enabled\":true,\"order\":\"zigzag\","+one+"}").contains("$.modules.animus:navigation: order"));
        assertTrue(field("{\"enabled\":true,\"order\":\"pingpong\",\"loop\":true,"+one+"}").contains("loop"));
        assertTrue(field("{\"enabled\":true,\"order\":\"random\",\"loop\":false,"+one+"}").contains("loop"));
        assertTrue(field("{\"enabled\":true,\"guides\":[],"+one+"}").contains("guides"));
        assertTrue(field("{\"enabled\":true,\"order\":\"wander\",\"guides\":[]}").contains("guides"));
        assertTrue(field("{\"enabled\":true,\"linkDistance\":4,"+one+"}").contains("linkDistance"));
        assertTrue(field("{\"enabled\":true,\"order\":\"random\",\"linkDistance\":0.5,"+one+"}").contains("linkDistance"));
        assertTrue(field("{\"enabled\":true,\"order\":\"random\",\"linkDistance\":257,"+one+"}").contains("linkDistance"));
        assertTrue(field("{\"enabled\":true,\"wander\":{},"+one+"}").contains("wander"));
        assertTrue(field("{\"enabled\":true,\"order\":\"wander\",\"wander\":{\"radius\":33}}").contains("wander.radius"));
        assertTrue(field("{\"enabled\":true,\"order\":\"wander\",\"wander\":{\"height\":9}}").contains("wander.height"));
        assertTrue(field("{\"enabled\":true,\"order\":\"wander\",\"wander\":{\"radius\":2.5}}").contains("wander.radius"));
        assertTrue(field("{\"enabled\":true,\"order\":\"wander\",\"points\":["+points(65)+"]}").contains("points"));
        assertTrue(field("{\"enabled\":true,\"order\":\"random\",\"points\":[]}").contains("points"));
        assertTrue(field("{\"enabled\":true,\"points\":["+points(257)+"]}").contains("points"));
        assertTrue(field("{\"enabled\":true,\"points\":[{\"x\":0,\"y\":1,\"z\":0,\"waitTicks\":12001}]}").contains("points[0].waitTicks"));
        assertTrue(field("{\"enabled\":true,\"waitTicks\":-1,"+one+"}").contains("waitTicks"));
        assertTrue(field("{\"enabled\":true,\"order\":\"random\","+one+",\"guides\":[{\"x\":0,\"y\":1.5,\"z\":0}]}").contains("guides[0].y"));
        assertTrue(field("{\"enabled\":true,\"order\":\"random\",\"points\":["+points(256)+"],\"guides\":["+points(1025)+"]}").contains("guides"));
        assertTrue(field("{\"enabled\":true,\"order\":\"random\",\"points\":["+points(257)+"],\"guides\":["+points(1000)+"]}").contains("points"));
        assertTrue(field("{\"enabled\":true,\"route\":\"patrol\",\"order\":\"random\"}").contains("order"));
    }
    @Test void pointsAndGuidesTogetherStayWithinTheNodeCap() throws Exception {
        assertTrue(WaypointSettings.document("{\"schemaVersion\":1,\"order\":\"random\",\"points\":["+points(256)+"],\"guides\":["+points(1024)+"]}").guides().size()==1024);
        assertThrows(IllegalArgumentException.class,()->new WaypointSettings(MovementProfile.Mode.WALK,2,WaypointSettings.Order.RANDOM,false,WaypointSettings.Wait.fixed(20),
                java.util.Collections.nCopies(257,new WaypointSettings.Waypoint(art.arcane.animus.core.virtual.Vec3.ZERO)),
                java.util.Collections.nCopies(1024,art.arcane.animus.core.virtual.Vec3.ZERO),32,null,
                WaypointSettings.Seed.NPC,WaypointSettings.StartOffset.FIRST,0));
    }
    @Test void sharedRouteRandomnessFieldsParseWithDefaults() throws Exception {
        WaypointSettings plain=WaypointSettings.document("{\"schemaVersion\":1,\"points\":[{\"x\":0,\"y\":1,\"z\":0}]}");
        assertEquals(WaypointSettings.Seed.NPC,plain.seed());assertEquals(WaypointSettings.StartOffset.FIRST,plain.startOffset());assertEquals(0,plain.jitter());
        assertEquals(0,plain.jitterAt(0));assertEquals(1,plain.points().getFirst().weight());assertTrue(plain.points().getFirst().next().isEmpty());
        WaypointSettings shared=WaypointSettings.document("""
                {"schemaVersion":1,"seed":"random","startOffset":"random","waitTicks":[20,60],"jitter":2,
                 "points":[{"x":0,"y":1,"z":0,"waitTicks":[0,5],"jitter":0,"next":[{"to":1,"weight":3},{"to":2}]},
                           {"x":4,"y":1,"z":0,"waitTicks":7},{"x":8,"y":1,"z":0}]}
                """);
        assertEquals(WaypointSettings.Seed.RANDOM,shared.seed());assertEquals(WaypointSettings.StartOffset.RANDOM,shared.startOffset());
        assertEquals(new WaypointSettings.Wait(20,60),shared.waitTicks());assertEquals(new WaypointSettings.Wait(0,5),shared.waitAfter(0));
        assertEquals(WaypointSettings.Wait.fixed(7),shared.waitAfter(1));assertEquals(new WaypointSettings.Wait(20,60),shared.waitAfter(2));
        assertEquals(0,shared.jitterAt(0));assertEquals(2,shared.jitterAt(1));
        assertEquals(java.util.List.of(new WaypointSettings.Junction(1,3),new WaypointSettings.Junction(2,1)),shared.points().getFirst().next());
        WaypointSettings weighted=WaypointSettings.document("{\"schemaVersion\":1,\"order\":\"random\",\"points\":[{\"x\":0,\"y\":1,\"z\":0,\"weight\":1000},{\"x\":4,\"y\":1,\"z\":0}]}");
        assertEquals(1000,weighted.points().getFirst().weight());
    }
    @Test void sharedRouteRandomnessErrorsNameTheField() {
        String three="{\"x\":0,\"y\":1,\"z\":0},{\"x\":1,\"y\":1,\"z\":0},{\"x\":2,\"y\":1,\"z\":0}";
        assertEquals("$.points[3].next[1].to: out of range",route("\"points\":["+three+",{\"x\":3,\"y\":1,\"z\":0,\"next\":[{\"to\":0},{\"to\":4}]}]"));
        assertEquals("$.points[2].waitTicks: min above max",route("\"points\":[{\"x\":0,\"y\":1,\"z\":0},{\"x\":1,\"y\":1,\"z\":0},{\"x\":2,\"y\":1,\"z\":0,\"waitTicks\":[9,3]}]"));
        assertEquals("$.waitTicks: min above max",route("\"waitTicks\":[5,4],\"points\":["+three+"]"));
        assertTrue(route("\"waitTicks\":[0,12001],\"points\":["+three+"]").startsWith("$.waitTicks"));
        assertTrue(route("\"waitTicks\":[1,2,3],\"points\":["+three+"]").startsWith("$.waitTicks"));
        assertEquals("$.points[0].weight is only allowed with order random",route("\"points\":[{\"x\":0,\"y\":1,\"z\":0,\"weight\":2}]"));
        assertEquals("$.points[0].next is only allowed with order sequence",route("\"order\":\"pingpong\",\"points\":[{\"x\":0,\"y\":1,\"z\":0,\"next\":[{\"to\":0}]}]"));
        assertTrue(route("\"order\":\"random\",\"points\":[{\"x\":0,\"y\":1,\"z\":0,\"weight\":0}]").startsWith("$.points[0].weight"));
        assertTrue(route("\"order\":\"random\",\"points\":[{\"x\":0,\"y\":1,\"z\":0,\"weight\":1001}]").startsWith("$.points[0].weight"));
        assertTrue(route("\"points\":[{\"x\":0,\"y\":1,\"z\":0,\"next\":[]}]").startsWith("$.points[0].next"));
        assertTrue(route("\"points\":[{\"x\":0,\"y\":1,\"z\":0,\"next\":["+String.join(",",java.util.Collections.nCopies(9,"{\"to\":0}"))+"]}]").startsWith("$.points[0].next"));
        assertTrue(route("\"points\":[{\"x\":0,\"y\":1,\"z\":0,\"next\":[{\"to\":0,\"weight\":1001}]}]").startsWith("$.points[0].next[0].weight"));
        assertTrue(route("\"points\":[{\"x\":0,\"y\":1,\"z\":0,\"next\":[{\"to\":0,\"via\":1}]}]").startsWith("$.points[0].next[0].via"));
        assertTrue(route("\"jitter\":9,\"points\":["+three+"]").startsWith("$.jitter"));
        assertTrue(route("\"points\":[{\"x\":0,\"y\":1,\"z\":0,\"jitter\":-1}]").startsWith("$.points[0].jitter"));
        assertTrue(route("\"order\":\"wander\",\"jitter\":2").startsWith("$.jitter is not allowed with order wander"));
        assertTrue(route("\"seed\":\"clock\",\"points\":["+three+"]").startsWith("$.seed"));
        assertTrue(route("\"startOffset\":\"last\",\"points\":["+three+"]").startsWith("$.startOffset"));
        assertTrue(field("{\"enabled\":true,\"points\":[{\"x\":0,\"y\":1,\"z\":0,\"next\":[{\"to\":1}]}]}").contains("$.modules.animus:navigation: points[0].next[0].to: out of range"));
        assertTrue(field("{\"enabled\":true,\"route\":\"patrol\",\"jitter\":1}").contains("jitter"));
    }
    @Test void junctionsWithoutAWayOutMakeTheSequenceEndless() throws Exception {
        String end="{\"x\":2,\"y\":1,\"z\":0}";
        assertFalse(WaypointSettings.document("{\"schemaVersion\":1,\"points\":[{\"x\":0,\"y\":1,\"z\":0,\"next\":[{\"to\":0},{\"to\":2}]},{\"x\":1,\"y\":1,\"z\":0},"+end+"]}").endless());
        assertTrue(WaypointSettings.document("{\"schemaVersion\":1,\"points\":[{\"x\":0,\"y\":1,\"z\":0},{\"x\":1,\"y\":1,\"z\":0,\"next\":[{\"to\":0}]},"+end+"]}").endless());
        assertTrue(WaypointSettings.document("{\"schemaVersion\":1,\"loop\":true,\"points\":[{\"x\":0,\"y\":1,\"z\":0,\"next\":[{\"to\":1}]},"+end+"]}").endless());
    }
    private static final String STEP="{\"recording\":\"walk\",\"fromTick\":40,\"toTick\":400,\"playback\":\"once\"}";
    @Test void aRecordingStepIsAPointWithoutAPositionAndTakesItsDefaults() throws Exception {
        WaypointSettings mixed=WaypointSettings.document("{\"schemaVersion\":1,\"loop\":true,\"jitter\":2,\"points\":[{\"x\":0,\"y\":1,\"z\":0},"+STEP
                +",{\"recording\":\"market-loop\",\"waitTicks\":[5,9]},{\"x\":4,\"y\":1,\"z\":0}]}");
        assertEquals(4,mixed.points().size());
        WaypointSettings.Waypoint step=mixed.points().get(1);
        assertNull(step.position());assertEquals(new WaypointSettings.Step("walk",40,400),step.step());
        assertEquals(new WaypointSettings.Step("market-loop",0,-1),mixed.points().get(2).step());
        assertEquals(new WaypointSettings.Wait(5,9),mixed.waitAfter(2));
        assertEquals(0,mixed.jitterAt(1),"a step has no jitter, not even the route's");assertEquals(2,mixed.jitterAt(0));
        assertEquals(java.util.List.of("walk","market-loop"),java.util.List.copyOf(mixed.recordings()));
        assertEquals(1200,mixed.points().get(2).step().end(1200));assertEquals(400,step.step().end(1200));
        assertTrue(WaypointSettings.document("{\"schemaVersion\":1,\"order\":\"random\",\"points\":[{\"x\":0,\"y\":1,\"z\":0},{\"recording\":\"walk\",\"weight\":7}]}")
                .points().get(1).weight()==7);
        assertTrue(WaypointSettings.document("{\"schemaVersion\":1,\"order\":\"pingpong\",\"points\":[{\"x\":0,\"y\":1,\"z\":0},"+STEP+"]}").endless());
        assertEquals(1,WaypointSettings.document("{\"schemaVersion\":1,\"points\":[{\"recording\":\"walk\",\"next\":[{\"to\":1}]},{\"x\":0,\"y\":1,\"z\":0}]}")
                .points().getFirst().next().getFirst().to());
        assertTrue(WaypointSettings.parse(definition("{\"enabled\":true,\"points\":["+STEP+"]}",false)).orElseThrow().points().getFirst().step()!=null,
                "inline routes take steps too");
        assertTrue(WaypointSettings.document("{\"schemaVersion\":1,\"mode\":\"walk\",\"points\":["+STEP+",{\"x\":0,\"y\":1,\"z\":0}]}").points().size()==2,
                "a step needs no whole-block height");
    }
    @Test void recordingStepShapeErrorsNameTheField() {
        String one="{\"x\":0,\"y\":1,\"z\":0}";
        assertEquals("$.points[1].fromTick: must be below toTick",route("\"points\":["+one+",{\"recording\":\"walk\",\"fromTick\":40,\"toTick\":40}]"));
        assertTrue(route("\"points\":["+one+",{\"recording\":\"walk\",\"fromTick\":-1}]").startsWith("$.points[1].fromTick"));
        assertTrue(route("\"points\":["+one+",{\"recording\":\"walk\",\"toTick\":2.5}]").startsWith("$.points[1].toTick"));
        assertTrue(route("\"points\":["+one+",{\"recording\":\"walk\",\"toTick\":72001}]").startsWith("$.points[1].toTick"));
        assertTrue(route("\"points\":[{\"recording\":\"walk\",\"playback\":\"loop\"}]").startsWith("$.points[0].playback: a recording step plays once"));
        assertTrue(route("\"points\":[{\"recording\":\"walk\",\"playback\":\"pingpong\"}]").startsWith("$.points[0].playback"));
        assertTrue(route("\"points\":[{\"recording\":\"../walk\"}]").startsWith("$.points[0].recording"));
        assertTrue(route("\"points\":[{\"recording\":\"walk\",\"x\":0}]").startsWith("$.points[0].x: a recording step has no position"));
        assertEquals("$.points[0].jitter: a recording step has no jitter",route("\"points\":[{\"recording\":\"walk\",\"jitter\":2}]"));
        assertEquals("$.points[0].toTick: requires recording",route("\"points\":[{\"x\":0,\"y\":1,\"z\":0,\"toTick\":4}]"));
        assertEquals("$.points[0].speed: unknown field",route("\"points\":[{\"recording\":\"walk\",\"speed\":2}]"));
        assertTrue(route("\"order\":\"wander\",\"points\":[{\"recording\":\"walk\"}]").startsWith("$.points[0].recording: a recording step is not allowed with order wander"));
        assertTrue(route("\"points\":[{\"recording\":\"walk\",\"weight\":2}]").startsWith("$.points[0].weight is only allowed with order random"));
    }
    @Test void recordingStepsCountTowardsThePointAndNodeCaps() throws Exception {
        String steps=String.join(",",java.util.Collections.nCopies(128,"{\"recording\":\"walk\"}"));
        assertEquals(256,WaypointSettings.document("{\"schemaVersion\":1,\"order\":\"random\",\"points\":["+steps+","+points(128)+"],\"guides\":["+points(1024)+"]}").points().size());
        assertTrue(route("\"points\":["+steps+","+points(129)+"]").startsWith("$.points requires"));
    }
    @Test void aStepRangeIsCheckedAgainstTheRecording() {
        WaypointSettings.Step step=new WaypointSettings.Step("walk",40,400);
        assertTrue(step.problem(1,400).isEmpty());
        assertEquals("$.points[1].toTick: 400 is past the durationTicks 300 of recording walk",step.problem(1,300).orElseThrow());
        assertEquals("$.points[2].fromTick: 40 is not below the durationTicks 40 of recording walk",new WaypointSettings.Step("walk",40,-1).problem(2,40).orElseThrow());
        assertEquals("$.points[2].fromTick: 50 is not below the durationTicks 40 of recording walk",new WaypointSettings.Step("walk",50,-1).problem(2,40).orElseThrow());
    }
    private static String route(String fields) {
        return assertThrows(IllegalArgumentException.class,()->WaypointSettings.document("{\"schemaVersion\":1,"+fields+"}"),fields).getMessage();
    }
}
