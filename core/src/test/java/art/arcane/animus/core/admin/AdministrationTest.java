package art.arcane.animus.core.admin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import art.arcane.animus.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class AdministrationTest {
    @TempDir Path root;
    @Test void statusShowsTheBudgetsInEffectAfterAHotload() throws Exception {
        try (AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", root))) {
            runtime.start(); Administration admin = new Administration(runtime);
            assertTrue(admin.status().stream().anyMatch((String line) -> line.startsWith("Settings in effect:") && line.contains("packetsPerTick=32")));
            art.arcane.animus.core.config.SettingsEdit.apply(runtime, "npcs", (com.google.gson.JsonObject npcs) -> npcs.addProperty("packetsPerTick", 12));
            art.arcane.animus.core.config.SettingsEdit.apply(runtime, "editor", (com.google.gson.JsonObject editor) -> editor.addProperty("port", 9811));
            String line = admin.status().stream().filter((String text) -> text.startsWith("Settings in effect:")).findFirst().orElseThrow();
            assertTrue(line.contains("packetsPerTick=12"), line);
            assertTrue(line.contains("127.0.0.1:9811"), line);
        }
    }
    @Test void commandEditsPersistAcrossRestartAndCanBeUndone() throws Exception {
        RuntimeEnvironment environment = new RuntimeEnvironment(Platform.BUKKIT, "26.3", root);
        try (AnimusRuntime runtime = new AnimusRuntime(environment)) {
            runtime.start(); Administration admin = new Administration(runtime);
            List.of(admin.create("guard", "minecraft:player", "world", 1, 64, 2, "town", "Town Guard"));
            String result = List.of(admin.edit("guard", "{\"name\":\"Captain\"}")).getFirst();
            assertEquals("Captain", runtime.npcs().find("guard").orElseThrow().name());
            admin.rollback(result.substring(result.lastIndexOf(' ') + 1));
            assertEquals("Town Guard", runtime.npcs().find("guard").orElseThrow().name());
            List.of(admin.duplicate("guard", "deputy"));
            assertEquals(2, runtime.npcs().all().size());
            assertNotEquals(runtime.npcs().find("guard").orElseThrow().uuid(), runtime.npcs().find("deputy").orElseThrow().uuid());
            assertThrows(IOException.class, () -> List.of(admin.edit("guard", "{\"spawned\":\"true\"}")));
            List.of(admin.delete("deputy"));
        }
        try (AnimusRuntime runtime = new AnimusRuntime(environment)) {
            runtime.start(); assertEquals(1, runtime.npcs().all().size());
            assertEquals("Town Guard", runtime.npcs().find("guard").orElseThrow().name());
        }
    }
    @Test void desiredSpawnStatePersistsAndClonesRemainDormant() throws Exception {
        RuntimeEnvironment environment = new RuntimeEnvironment(Platform.BUKKIT, "26.3", root);
        try (AnimusRuntime runtime = new AnimusRuntime(environment)) {
            runtime.start(); Administration admin = new Administration(runtime);
            List.of(admin.create("guard", "minecraft:player", "world", 1, 64, 2, "town", "Guard"));
            assertFalse(runtime.npcs().find("guard").orElseThrow().json().get("spawned").getAsBoolean());
            List.of(admin.spawned("guard", true));
            assertTrue(runtime.npcs().find("guard").orElseThrow().json().get("spawned").getAsBoolean());
            List.of(admin.duplicate("guard", "copy"));
            assertFalse(runtime.npcs().find("copy").orElseThrow().json().get("spawned").getAsBoolean());
        }
        try (AnimusRuntime runtime = new AnimusRuntime(environment)) {
            runtime.start(); Administration admin = new Administration(runtime);
            assertTrue(runtime.npcs().find("guard").orElseThrow().json().get("spawned").getAsBoolean());
            List.of(admin.spawned("guard", false));
            assertEquals(3, runtime.npcs().find("guard").orElseThrow().revision());
            assertFalse(runtime.npcs().find("guard").orElseThrow().json().get("spawned").getAsBoolean());
            assertThrows(IOException.class, () -> List.of(admin.spawned("missing", true)));
        }
    }
    @Test void commandImportCannotTraverseOutOfInbox() throws Exception {
        try (AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.2", root))) {
            runtime.start(); Administration admin = new Administration(runtime);
            assertThrows(IOException.class, () -> admin.preview("../../saves.yml", Optional.empty(), Map.of()));
        }
    }

    @Test void reviewedImportCommandsApplyDuplicateReplaceRestartAndRollback() throws Exception {
        RuntimeEnvironment environment = new RuntimeEnvironment(Platform.BUKKIT, "26.3", root);
        String initialTransaction, copyTransaction, replacementTransaction;
        String source = """
                {"npc":{"1":{"uuid":"00000000-0000-0000-0000-000000000001","name":"Guard",
                "traits":{"type":"PLAYER","location":{"world":"world","x":0,"y":64,"z":0},
                "sentinel":{"health":40},"unknown":{"value":"preserved"}}}}}
                """;
        try (AnimusRuntime runtime = new AnimusRuntime(environment)) {
            runtime.start(); Administration admin = new Administration(runtime);
            Path input = runtime.documents().resolve("imports/inbox/sample.yml"); Files.createDirectories(input.getParent()); Files.writeString(input, source);
            String original = admin.preview("sample.yml", Optional.empty(), Map.of()).getFirst().substring("Preview ".length());
            String application = admin.stage(original, "reject", Optional.empty()).getFirst().substring("Application ".length());
            assertTrue(runtime.npcs().all().isEmpty());
            assertEquals("Application " + application, admin.stage(original, "reject", Optional.empty()).getFirst());
            initialTransaction = transaction(admin.applyPlan(original, application).getFirst());
            assertEquals("already-applied", transaction(admin.applyPlan(original, application).getFirst()));
            assertEquals(1, runtime.npcs().all().size());
            assertFalse(runtime.npcs().find("citizens-1").orElseThrow().json().get("spawned").getAsBoolean());
            String copy = admin.stage(original, "duplicate", Optional.of("backup")).getFirst().substring("Application ".length());
            copyTransaction = transaction(admin.applyPlan(original, copy).getFirst());
            assertEquals(2, runtime.npcs().all().size());
            assertNotEquals(runtime.npcs().find("citizens-1").orElseThrow().uuid(), runtime.npcs().find("citizens-1-backup").orElseThrow().uuid());
            Files.writeString(input, source.replace("Guard", "Captain"));
            String updated = admin.preview("sample.yml", Optional.empty(), Map.of()).getFirst().substring("Preview ".length());
            assertThrows(IOException.class, () -> admin.stage(updated, "reject", Optional.empty()));
            String replace = admin.stage(updated, "replace", Optional.empty()).getFirst().substring("Application ".length());
            assertEquals("Guard", runtime.npcs().find("citizens-1").orElseThrow().name());
            replacementTransaction = transaction(admin.applyPlan(updated, replace).getFirst());
            assertEquals("Captain", runtime.npcs().find("citizens-1").orElseThrow().name());
            assertEquals(2, runtime.npcs().find("citizens-1").orElseThrow().revision());
        }
        try (AnimusRuntime runtime = new AnimusRuntime(environment)) {
            runtime.start(); Administration admin = new Administration(runtime);
            assertEquals("Captain", runtime.npcs().find("citizens-1").orElseThrow().name());
            admin.rollback(replacementTransaction);
            assertEquals("Guard", runtime.npcs().find("citizens-1").orElseThrow().name());
            assertEquals(1, runtime.npcs().find("citizens-1").orElseThrow().revision());
            admin.rollback(copyTransaction);
            admin.rollback(initialTransaction);
            assertTrue(runtime.npcs().all().isEmpty());
        }
        try (AnimusRuntime runtime = new AnimusRuntime(environment)) { runtime.start(); assertTrue(runtime.npcs().all().isEmpty()); }
    }

    private static String transaction(String receipt) { return receipt.substring(receipt.lastIndexOf(' ') + 1); }
}
