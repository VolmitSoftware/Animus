package art.arcane.animus.core.mount;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MountDeclarationTest {
    private static final UUID VEHICLE = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static JsonObject json(String extra) throws IOException {
        return JsonDocuments.object("""
                {"schemaVersion":2,"revision":1,"uuid":"00000000-0000-0000-0000-000000000001","id":"rider",
                 "group":"test","name":"Rider","type":"minecraft:player","spawned":false,
                 "position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0}%s}
                """.formatted(extra));
    }
    private static String reference(String status, String more) {
        return """
                ,"migration":{"references":[{"source":"/traits/sentinel/guardedNPC","kind":"guard","original":3,"status":"resolved","npcUuid":"00000000-0000-0000-0000-000000000009","npcId":"citizens-3","citizensId":3},
                {"source":"/traits/mounttrait/mountedon","kind":"mount","original":"00000000-0000-0000-0000-000000000002","status":"%s"%s}]}
                """.formatted(status, more);
    }

    @Test void definitionWithoutAnyRelationDeclaresNothing() throws IOException {
        assertEquals(Optional.empty(), MountDeclaration.parse(json("")));
    }

    @Test void nativeModuleNamesItsVehicleById() throws IOException {
        MountReference reference = MountDeclaration.parse(json(",\"modules\":{\"animus:mount\":{\"vehicle\":\"horse-1\"}}")).orElseThrow();
        assertEquals("horse-1", reference.vehicleId());
        assertNull(reference.vehicleUuid());
        assertEquals(MountReference.Status.DECLARED, reference.status());
        assertEquals("$.modules.animus:mount.vehicle", reference.field());
    }

    @Test void resolvedImportedReferenceCarriesItsRemappedIdentityAndExactField() throws IOException {
        MountReference reference = MountDeclaration.parse(json(reference("resolved", ",\"npcUuid\":\"00000000-0000-0000-0000-000000000002\",\"npcId\":\"citizens-2\",\"citizensId\":2"))).orElseThrow();
        assertEquals("citizens-2", reference.vehicleId());
        assertEquals(VEHICLE, reference.vehicleUuid());
        assertEquals(MountReference.Status.DECLARED, reference.status());
        assertEquals("$.migration.references[1]", reference.field());
    }

    @Test void unresolvedImportedReferencesStayPreservedAndReportTheirStatus() throws IOException {
        assertEquals(MountReference.Status.MISSING, MountDeclaration.parse(json(reference("missing", ""))).orElseThrow().status());
        assertEquals(MountReference.Status.AMBIGUOUS, MountDeclaration.parse(json(reference("ambiguous", ""))).orElseThrow().status());
        MountReference invalid = MountDeclaration.parse(json(reference("invalid", ""))).orElseThrow();
        assertEquals(MountReference.Status.INVALID, invalid.status());
        assertEquals("00000000-0000-0000-0000-000000000002", invalid.original());
        assertEquals(Optional.empty(), MountDeclaration.parse(json(reference("none", ""))));
    }

    @Test void importedRecordWithBrokenIdentityIsReportedAndNeverThrows() throws IOException {
        assertEquals(MountReference.Status.INVALID, MountDeclaration.parse(json(reference("resolved", ",\"npcUuid\":\"not-a-uuid\",\"npcId\":\"citizens-2\""))).orElseThrow().status());
        assertEquals(MountReference.Status.INVALID, MountDeclaration.parse(json(reference("resolved", ""))).orElseThrow().status());
        assertEquals(Optional.empty(), MountDeclaration.parse(json(",\"migration\":{\"references\":\"bad\"}")));
        assertEquals(Optional.empty(), MountDeclaration.parse(json(",\"migration\":{\"references\":[7]}")));
    }

    @Test void nativeModuleOverridesAndDisabledModuleSuppressesAnImportedRelation() throws IOException {
        String imported = reference("resolved", ",\"npcUuid\":\"00000000-0000-0000-0000-000000000002\",\"npcId\":\"citizens-2\"");
        assertEquals("other", MountDeclaration.parse(json(imported + ",\"modules\":{\"animus:mount\":{\"vehicle\":\"other\"}}")).orElseThrow().vehicleId());
        assertEquals(Optional.empty(), MountDeclaration.parse(json(imported + ",\"modules\":{\"animus:mount\":{\"enabled\":false}}")));
    }

    @Test void aRealEntityMountNamesAUuidOrTheNearestOfAType() throws IOException {
        UUID horse = UUID.fromString("3f1c7a52-0000-4000-8000-00000000abcd");
        MountDeclaration.EntityMount byUuid = MountDeclaration.entity(json(",\"modules\":{\"animus:mount\":{\"entity\":\"" + horse + "\"}}")).orElseThrow();
        assertEquals(horse, byUuid.uuid());
        assertNull(byUuid.type());
        assertEquals("$.modules.animus:mount.entity", byUuid.field());
        MountDeclaration.EntityMount nearest = MountDeclaration.entity(json(",\"modules\":{\"animus:mount\":{\"entity\":\"minecraft:horse\",\"range\":12}}")).orElseThrow();
        assertNull(nearest.uuid());
        assertEquals("minecraft:horse", nearest.type());
        assertEquals(12, nearest.range());
        assertEquals(MountDeclaration.DEFAULT_RANGE, MountDeclaration.entity(json(",\"modules\":{\"animus:mount\":{\"entity\":\"minecraft:boat\"}}")).orElseThrow().range());
        assertEquals(Optional.empty(), MountDeclaration.parse(json(",\"modules\":{\"animus:mount\":{\"entity\":\"minecraft:horse\"}}")), "no NPC relation");
        assertEquals(Optional.empty(), MountDeclaration.entity(json(",\"modules\":{\"animus:mount\":{\"entity\":\"minecraft:horse\",\"enabled\":false}}")));
        assertEquals(Optional.empty(), MountDeclaration.entity(json(",\"modules\":{\"animus:mount\":{\"vehicle\":\"horse-1\"}}")));
    }

    @Test void definitionsRejectMalformedNativeModulesWithTheExactField() throws IOException {
        for (String bad : List.of("{}", "{\"vehicle\":7}", "{\"vehicle\":\"Not An Id\"}", "{\"vehicle\":\"a\",\"seat\":2}", "{\"enabled\":\"true\",\"vehicle\":\"a\"}", "3",
                "{\"vehicle\":\"a\",\"entity\":\"minecraft:horse\"}", "{\"entity\":\"Horse\"}", "{\"entity\":\"minecraft:player\"}", "{\"entity\":\"minecraft:horse\",\"range\":40}",
                "{\"entity\":\"3f1c7a52-0000-4000-8000-00000000abcd\",\"range\":4}", "{\"vehicle\":\"a\",\"range\":4}")) {
            JsonObject document = json(",\"modules\":{\"animus:mount\":" + bad + "}");
            List<String> errors = NpcDefinition.validate(document);
            assertTrue(errors.stream().anyMatch(error -> error.startsWith("$.modules.animus:mount")), bad + " -> " + errors);
        }
        assertEquals(List.of(), NpcDefinition.validate(json(",\"modules\":{\"animus:mount\":{\"vehicle\":\"horse-1\"}}")));
        assertEquals(List.of(), NpcDefinition.validate(json(",\"modules\":{\"animus:mount\":{\"entity\":\"minecraft:horse\",\"range\":4}}")));
    }
}
