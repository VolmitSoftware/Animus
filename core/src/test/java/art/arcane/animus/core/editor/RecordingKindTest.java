package art.arcane.animus.core.editor;

import art.arcane.animus.core.hotload.HotloadTargets;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecordingKindTest {
    @Test void recordingsLiveUnderTheirFolderWithRouteStyleIds() throws EditorError {
        assertEquals(Optional.of(DocumentKind.RECORDING), DocumentKind.parse("recording"));
        assertEquals(DocumentKind.ROUTE.ordinal() + 1, DocumentKind.RECORDING.ordinal());
        assertEquals(EditorScope.APPLY_NPCS, DocumentKind.RECORDING.applyScope());
        assertEquals(Optional.of(DocumentKind.RECORDING), DocumentKind.forPath("recordings/market-loop.json"));
        assertEquals(Optional.of("market-loop"), EditorDocuments.idFor(DocumentKind.RECORDING, "recordings/market-loop.json"));
        assertEquals(Optional.empty(), EditorDocuments.idFor(DocumentKind.RECORDING, "recordings/town/market.json"));
        assertEquals(Optional.empty(), EditorDocuments.idFor(DocumentKind.RECORDING, "recordings/Market.json"));
        assertEquals("recordings/market-loop.json", EditorDocuments.pathFor(DocumentKind.RECORDING, "market-loop"));
        assertThrows(EditorError.class, () -> EditorDocuments.pathFor(DocumentKind.RECORDING, "town:market"));
        assertTrue(EditorDocuments.REVISIONED.contains(DocumentKind.RECORDING));
        assertTrue(HotloadTargets.FOLDERS.contains("recordings"));
        assertEquals(Optional.of(DocumentKind.RECORDING), HotloadTargets.of("recordings/market-loop.json").orElseThrow().kind());
    }

    @Test void validationUsesTheRecordingParserAndTheConfiguredCap() throws IOException {
        JsonObject source = JsonDocuments.object(SharedFixtures.text(SharedFixtures.fixture("recordings/market-loop.json")));
        assertEquals(new JsonArray(), EditorDocuments.validate(DocumentKind.RECORDING, "market-loop", source, 1_048_576));
        JsonArray mismatch = EditorDocuments.validate(DocumentKind.RECORDING, "other", source, 1_048_576);
        assertEquals("$.id", mismatch.get(0).getAsJsonObject().get("path").getAsString());
        assertEquals("id-mismatch", mismatch.get(0).getAsJsonObject().get("code").getAsString());
        JsonArray large = EditorDocuments.validate(DocumentKind.RECORDING, "market-loop", source, 256);
        assertTrue(EditorDocuments.hasErrors(large));
        assertTrue(large.get(0).getAsJsonObject().get("message").getAsString().startsWith("recording is "), large.toString());
        JsonObject broken = source.deepCopy();
        broken.addProperty("sampleTicks", 0);
        JsonArray refused = EditorDocuments.validate(DocumentKind.RECORDING, "market-loop", broken, 1_048_576);
        assertEquals("$.sampleTicks", refused.get(0).getAsJsonObject().get("path").getAsString());
    }
}
