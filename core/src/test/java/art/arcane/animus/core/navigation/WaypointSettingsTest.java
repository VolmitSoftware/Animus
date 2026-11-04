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
        assertEquals(62.5,parsed.points().getFirst().y());assertThrows(UnsupportedOperationException.class,()->parsed.points().clear());
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
        String oversized="{\"enabled\":true,\"points\":["+String.join(",",java.util.Collections.nCopies(65,"{\"x\":0,\"y\":1,\"z\":0}"))+"]}";
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
}
