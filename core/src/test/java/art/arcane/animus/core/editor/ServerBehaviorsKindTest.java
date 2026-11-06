package art.arcane.animus.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.behavior.catalog.ServerBehaviorList;
import art.arcane.animus.core.hotload.HotloadTargets;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ServerBehaviorsKindTest {
    @Test
    void theRootFileIsTheSingleServerBehaviorsDocument() throws EditorError {
        assertEquals(Optional.of(DocumentKind.SERVER_BEHAVIORS), DocumentKind.forPath(ServerBehaviorList.PATH));
        assertEquals(Optional.of("server-behaviors"), EditorDocuments.idFor(DocumentKind.SERVER_BEHAVIORS, ServerBehaviorList.PATH));
        assertEquals(ServerBehaviorList.PATH, EditorDocuments.pathFor(DocumentKind.SERVER_BEHAVIORS, "server-behaviors"));
        assertEquals(EditorScope.APPLY_BEHAVIORS, DocumentKind.SERVER_BEHAVIORS.applyScope());
        assertFalse(EditorDocuments.REVISIONED.contains(DocumentKind.SERVER_BEHAVIORS));
        assertEquals(Optional.of(DocumentKind.BEHAVIOR), DocumentKind.forPath("behaviors/town/greeter.json"));
        assertTrue(DocumentKind.forPath("config.toml").isEmpty());
    }

    @Test
    void hotloadWatchesTheFileWithoutScanningTheWholeDataFolder() {
        assertFalse(HotloadTargets.FOLDERS.contains(""));
        assertEquals(Optional.of(DocumentKind.SERVER_BEHAVIORS), HotloadTargets.of(ServerBehaviorList.PATH).flatMap(HotloadTargets.Target::kind));
    }

    @Test
    void validationUsesTheStrictParser() {
        JsonArray valid = EditorDocuments.validate(DocumentKind.SERVER_BEHAVIORS, "server-behaviors",
                JsonParser.parseString("{\"schemaVersion\":1,\"behaviors\":[\"town:welcome\"]}").getAsJsonObject());
        assertTrue(valid.isEmpty(), valid::toString);
        JsonArray revisioned = EditorDocuments.validate(DocumentKind.SERVER_BEHAVIORS, "server-behaviors",
                JsonParser.parseString("{\"schemaVersion\":1,\"revision\":2,\"behaviors\":[]}").getAsJsonObject());
        assertEquals(1, revisioned.size(), revisioned::toString);
    }
}
