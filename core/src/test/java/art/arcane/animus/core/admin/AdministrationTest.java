package art.arcane.animus.core.admin;

import art.arcane.animus.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class AdministrationTest {
    @TempDir Path root;
    @Test void commandEditsPersistAcrossRestartAndCanBeUndone() throws Exception {
        RuntimeEnvironment environment = new RuntimeEnvironment(Platform.BUKKIT, "26.3", root);
        try (AnimusRuntime runtime = new AnimusRuntime(environment)) {
            runtime.start(); Administration admin = new Administration(runtime);
            admin.execute(new String[]{"create", "guard", "minecraft:player", "world", "1", "64", "2", "town", "Town Guard"});
            String result = admin.execute(new String[]{"edit", "guard", "{\"name\":\"Captain\"}"}).getFirst();
            assertEquals("Captain", runtime.npcs().find("guard").orElseThrow().name());
            admin.execute(new String[]{"rollback", result.substring(result.lastIndexOf(' ') + 1)});
            assertEquals("Town Guard", runtime.npcs().find("guard").orElseThrow().name());
            admin.execute(new String[]{"clone", "guard", "deputy"});
            assertEquals(2, runtime.npcs().all().size());
            assertNotEquals(runtime.npcs().find("guard").orElseThrow().uuid(), runtime.npcs().find("deputy").orElseThrow().uuid());
            assertThrows(IOException.class, () -> admin.execute(new String[]{"edit", "guard", "{\"spawned\":true}"}));
            admin.execute(new String[]{"delete", "deputy"});
        }
        try (AnimusRuntime runtime = new AnimusRuntime(environment)) {
            runtime.start(); assertEquals(1, runtime.npcs().all().size());
            assertEquals("Town Guard", runtime.npcs().find("guard").orElseThrow().name());
        }
    }
    @Test void commandImportCannotTraverseOutOfInbox() throws Exception {
        try (AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.2", root))) {
            runtime.start(); Administration admin = new Administration(runtime);
            assertThrows(IOException.class, () -> admin.execute(new String[]{"import", "preview", "../../saves.yml"}));
        }
    }
}
