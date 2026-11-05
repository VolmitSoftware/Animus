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
        assertEquals(MovementProfile.Mode.SWIM,parsed.mode());assertEquals(3.5,parsed.speed());assertTrue(parsed.loop());assertEquals(40,parsed.waitTicks());
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
        assertEquals(WaypointSettings.Order.SEQUENCE,plain.order());assertEquals(20,plain.waitTicks());assertNull(plain.wander());
        var random=WaypointSettings.parse(definition("""
                {"enabled":true,"order":"random","linkDistance":12.5,"points":[{"x":0,"y":1,"z":0,"waitTicks":90}],"guides":[{"x":4,"y":1,"z":0}]}
                """,false)).orElseThrow();
        assertEquals(12.5,random.linkDistance());assertEquals(1,random.guides().size());assertEquals(90,random.waitAfter(0));assertTrue(random.endless());
        var defaults=WaypointSettings.parse(definition("{\"enabled\":true,\"order\":\"random\",\"points\":[{\"x\":0,\"y\":1,\"z\":0}]}",false)).orElseThrow();
        assertEquals(32,defaults.linkDistance());assertEquals(20,defaults.waitAfter(0));
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
        assertThrows(IllegalArgumentException.class,()->new WaypointSettings(MovementProfile.Mode.WALK,2,WaypointSettings.Order.RANDOM,false,20,
                java.util.Collections.nCopies(257,new WaypointSettings.Waypoint(art.arcane.animus.core.virtual.Vec3.ZERO)),
                java.util.Collections.nCopies(1024,art.arcane.animus.core.virtual.Vec3.ZERO),32,null));
    }
}
