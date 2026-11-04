package art.arcane.animus.core.control;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RideSettingsTest {
    private static JsonObject document(String module, String extra) throws IOException {
        return JsonDocuments.object("""
                {"schemaVersion":1,"revision":1,"uuid":"00000000-0000-0000-0000-000000000001","id":"horse",
                 "group":"test","name":"Horse","type":"minecraft:horse","spawned":false,
                 "position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0},
                 "modules":{"animus:riding":%s}%s}
                """.formatted(module, extra));
    }
    @Test void ridingIsOptInAndAbsentOrDisabledModulesDeclareNothing() throws IOException {
        assertEquals(Optional.empty(), RideSettings.parse(document("{\"enabled\":false}", "")));
        assertEquals(Optional.empty(), RideSettings.parse(document("{}", "")));
        JsonObject withoutModules = document("{}", ""); withoutModules.remove("modules");
        assertEquals(Optional.empty(), RideSettings.parse(withoutModules));
    }
    @Test void enabledModuleUsesBoundedDefaults() throws IOException {
        RideSettings settings = RideSettings.parse(document("{\"enabled\":true}", "")).orElseThrow();
        assertEquals("animus.ride", settings.permission());
        assertEquals(RideSettings.DEFAULT_SPEED, settings.speed());
        assertEquals(RideSettings.DEFAULT_LEASH, settings.leash());
        assertEquals(RideSettings.DEFAULT_LIFETIME_SECONDS, settings.lifetimeSeconds());
    }
    @Test void explicitValuesAreRespectedInsideTheirBounds() throws IOException {
        RideSettings settings = RideSettings.parse(document("{\"enabled\":true,\"permission\":\"animus.ride.town\",\"speed\":8,\"leash\":256,\"lifetimeSeconds\":3600}", "")).orElseThrow();
        assertEquals("animus.ride.town", settings.permission());
        assertEquals(8, settings.speed()); assertEquals(256, settings.leash()); assertEquals(3600, settings.lifetimeSeconds());
    }
    @Test void outOfBoundsTypeConfusedAndUnknownFieldsAreRejectedWithTheirField() throws IOException {
        for (String bad : List.of("{\"enabled\":\"true\"}", "{\"enabled\":true,\"speed\":0.1}", "{\"enabled\":true,\"speed\":9}", "{\"enabled\":true,\"speed\":\"4\"}",
                "{\"enabled\":true,\"leash\":1}", "{\"enabled\":true,\"leash\":257}", "{\"enabled\":true,\"lifetimeSeconds\":29}", "{\"enabled\":true,\"lifetimeSeconds\":3601}",
                "{\"enabled\":true,\"lifetimeSeconds\":60.5}", "{\"enabled\":true,\"permission\":\"Bad Node\"}", "{\"enabled\":true,\"seats\":3}", "4")) {
            List<String> errors = NpcDefinition.validate(document(bad, ""));
            assertTrue(errors.stream().anyMatch(error -> error.startsWith("$.modules.animus:riding")), bad + " -> " + errors);
        }
    }
    @Test void importedDefinitionsRequireLiteralReviewAcceptance() throws IOException {
        String imported = ",\"migration\":{\"importId\":\"x\"}";
        assertTrue(NpcDefinition.validate(document("{\"enabled\":true}", imported)).stream().anyMatch(error -> error.contains("reviewAccepted")));
        assertTrue(NpcDefinition.validate(document("{\"enabled\":true,\"reviewAccepted\":\"true\"}", imported)).stream().anyMatch(error -> error.contains("reviewAccepted")));
        assertEquals(List.of(), NpcDefinition.validate(document("{\"enabled\":true,\"reviewAccepted\":true}", imported)));
        assertTrue(RideSettings.parse(document("{\"enabled\":true,\"reviewAccepted\":true}", imported)).isPresent());
    }
}
