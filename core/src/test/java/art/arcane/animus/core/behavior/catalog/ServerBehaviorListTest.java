package art.arcane.animus.core.behavior.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ServerBehaviorListTest {
    @TempDir Path root;

    @Test void aMissingFileEnablesNothingAndAnInvalidOneReportsWhy() throws IOException {
        ServerBehaviorList missing = ServerBehaviorList.read(root, 1);
        assertTrue(missing.loaded());
        assertEquals(List.of(), missing.ids());
        assertEquals(List.of(), missing.problems());
        Files.writeString(root.resolve(ServerBehaviorList.PATH), "{\"schemaVersion\":1,\"behaviors\":[\"Bad Id\"]}");
        ServerBehaviorList invalid = ServerBehaviorList.read(root, 2);
        assertEquals(List.of(), invalid.ids());
        assertEquals("server-behaviors-invalid", invalid.problems().getFirst().code());
        Files.writeString(root.resolve(ServerBehaviorList.PATH), "{\"schemaVersion\":1,\"behaviors\":[\"denizen:world/a\",\"denizen:world/a\",\"town:clock\"]}");
        assertEquals(List.of("denizen:world/a", "town:clock"), ServerBehaviorList.read(root, 3).ids());
    }

    @Test void parsingIsStrictAndBounded() {
        assertThrows(IOException.class, () -> ServerBehaviorList.parse("{\"schemaVersion\":2,\"behaviors\":[]}"));
        assertThrows(IOException.class, () -> ServerBehaviorList.parse("{\"schemaVersion\":1,\"behaviors\":[],\"extra\":1}"));
        assertThrows(IOException.class, () -> ServerBehaviorList.parse("{\"schemaVersion\":1,\"behaviors\":[1]}"));
        StringBuilder many = new StringBuilder("{\"schemaVersion\":1,\"behaviors\":[");
        for (int index = 0; index <= ServerBehaviorList.MAXIMUM; index++) many.append(index == 0 ? "" : ",").append("\"a:b").append(index).append('"');
        assertThrows(IOException.class, () -> ServerBehaviorList.parse(many.append("]}").toString()));
    }

    @Test void appendingAddsEachIdOnceAndKeepsAnUnchangedFile() throws IOException {
        String created = ServerBehaviorList.append(null, List.of("denizen:world/a"));
        assertEquals(List.of("denizen:world/a"), ServerBehaviorList.parse(created));
        assertSame(created, ServerBehaviorList.append(created, List.of("denizen:world/a")));
        assertEquals(List.of("denizen:world/a", "denizen:world/b"), ServerBehaviorList.parse(ServerBehaviorList.append(created, List.of("denizen:world/b", "denizen:world/a"))));
    }
}
