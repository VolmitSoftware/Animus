package art.arcane.animus.core.admin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import art.arcane.animus.core.*;
import art.arcane.animus.core.text.RichText;
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
            List<String> previewed = admin.preview("sample.yml", Optional.empty(), Map.of());
            String original = id("Preview ", previewed.getFirst());
            assertEquals("Preview " + original + ": 1 records, 1 valid, 0 skins; nothing installed", previewed.getFirst());
            assertTrue(previewed.get(1).startsWith("Review imports/" + original + "/report.json, then /animus import apply " + original + " https://docs."), previewed.get(1));
            List<String> staged = admin.stage(original, "reject", Optional.empty());
            String application = id("Application ", staged.getFirst());
            assertTrue(runtime.npcs().all().isEmpty());
            assertEquals("Application " + application + ": reject, 1 records, 0 replacements", admin.stage(original, "reject", Optional.empty()).getFirst());
            assertTrue(staged.get(1).startsWith("Review imports/") && staged.get(1).endsWith(", then /animus import apply-plan " + original + " " + application), staged.get(1));
            List<String> installed = admin.applyPlan(original, application);
            assertTrue(RichText.plain(installed.getFirst()).startsWith("[Animus]: Installed dormant NPCs; tx "), installed.getFirst());
            initialTransaction = transaction(installed.getFirst());
            assertEquals(List.of("Already installed; nothing changed"), admin.applyPlan(original, application));
            assertEquals(1, runtime.npcs().all().size());
            assertFalse(runtime.npcs().find("citizens-1").orElseThrow().json().get("spawned").getAsBoolean());
            String copy = id("Application ", admin.stage(original, "duplicate", Optional.of("backup")).getFirst());
            copyTransaction = transaction(admin.applyPlan(original, copy).getFirst());
            assertEquals(2, runtime.npcs().all().size());
            assertNotEquals(runtime.npcs().find("citizens-1").orElseThrow().uuid(), runtime.npcs().find("citizens-1-backup").orElseThrow().uuid());
            Files.writeString(input, source.replace("Guard", "Captain"));
            String updated = id("Preview ", admin.preview("sample.yml", Optional.empty(), Map.of()).getFirst());
            assertThrows(IOException.class, () -> admin.stage(updated, "reject", Optional.empty()));
            String replace = id("Application ", admin.stage(updated, "replace", Optional.empty()).getFirst());
            assertEquals("Guard", runtime.npcs().find("citizens-1").orElseThrow().name());
            replacementTransaction = transaction(admin.applyPlan(updated, replace).getFirst());
            assertEquals("Captain", runtime.npcs().find("citizens-1").orElseThrow().name());
            assertEquals(2, runtime.npcs().find("citizens-1").orElseThrow().revision());
        }
        try (AnimusRuntime runtime = new AnimusRuntime(environment)) {
            runtime.start(); Administration admin = new Administration(runtime);
            assertEquals("Captain", runtime.npcs().find("citizens-1").orElseThrow().name());
            assertTrue(RichText.plain(admin.rollback(replacementTransaction).getFirst()).matches("\\[Animus]: Rolled back " + replacementTransaction + "; tx [0-9a-f-]{36}"));
            assertEquals("Guard", runtime.npcs().find("citizens-1").orElseThrow().name());
            assertEquals(1, runtime.npcs().find("citizens-1").orElseThrow().revision());
            admin.rollback(copyTransaction);
            admin.rollback(initialTransaction);
            assertTrue(runtime.npcs().all().isEmpty());
        }
        try (AnimusRuntime runtime = new AnimusRuntime(environment)) { runtime.start(); assertTrue(runtime.npcs().all().isEmpty()); }
    }

    private static String transaction(String receipt) { return receipt.substring(receipt.lastIndexOf("tx ") + 3); }

    /** The id after {@code lead} in a {@code <lead><id>: ...} receipt. */
    private static String id(String lead, String receipt) {
        assertTrue(receipt.startsWith(lead), receipt);
        return receipt.substring(lead.length(), receipt.indexOf(':'));
    }
}
