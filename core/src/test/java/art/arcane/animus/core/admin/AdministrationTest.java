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
            assertTrue(admin.status().getFirst().contains(" on Bukkit 26.3 | "), admin.status().getFirst());
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
            IOException unknown = assertThrows(IOException.class, () -> admin.edit("guard", "{\"x\": 1}"));
            assertEquals("$.x: unknown field", unknown.getMessage());
            String hash = "ab".repeat(32);
            IOException missing = assertThrows(IOException.class, () -> admin.edit("guard", "{\"skin\": \"" + hash + "\"}"));
            assertEquals("$.skin: no skin file skins/" + hash + ".json", missing.getMessage());
            assertEquals(java.util.Optional.empty(), Administration.patchProblem(runtime.documents(), com.google.gson.JsonParser.parseString("{\"migration\": null, \"name\": \"A\"}").getAsJsonObject()));
            List.of(admin.delete("deputy"));
        }
        try (AnimusRuntime runtime = new AnimusRuntime(environment)) {
            runtime.start(); assertEquals(1, runtime.npcs().all().size());
            assertEquals("Town Guard", runtime.npcs().find("guard").orElseThrow().name());
        }
    }
    @Test void aPlayerCreatedNpcIsOwnedByThatPlayer() throws Exception {
        try (AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", root))) {
            runtime.start(); Administration admin = new Administration(runtime);
            java.util.UUID player = java.util.UUID.fromString("0000000a-0000-4000-8000-0000000000b2");
            List.of(admin.create("smith", "minecraft:player", "world", 1, 64, 2, "", "Smith", Optional.of(player)));
            assertEquals(Optional.of(player), runtime.npcs().find("smith").orElseThrow().owner());
            List.of(admin.create("console", "minecraft:player", "world", 1, 64, 2, "", "Console"));
            assertEquals(Optional.empty(), runtime.npcs().find("console").orElseThrow().owner(), "the console leaves the owner unset");
            List.of(admin.edit("smith", "{\"owner\":null}"));
            assertEquals(Optional.empty(), runtime.npcs().find("smith").orElseThrow().owner(), "the owner stays editable");
        }
    }
    @Test void anEditTurnsAPlayerIntoAHorseByDroppingItsSkinAndPose() throws Exception {
        RuntimeEnvironment environment = new RuntimeEnvironment(Platform.BUKKIT, "26.3", root);
        try (AnimusRuntime runtime = new AnimusRuntime(environment)) {
            runtime.start(); Administration admin = new Administration(runtime);
            List.of(admin.create("steed", "minecraft:player", "world", 1, 64, 2, "town", "Steed"));
            String skin = "{\"textureRaw\":\"e30=\"}";
            runtime.documents().commit(List.of(new art.arcane.animus.core.storage.DocumentStore.Change("skins/" + art.arcane.animus.core.storage.JsonDocuments.hash(skin) + ".json", "missing", skin)));
            List.of(admin.edit("steed", "{\"skin\":\"" + art.arcane.animus.core.storage.JsonDocuments.hash(skin) + "\",\"pose\":\"sitting\"}"));
            List.of(admin.edit("steed", "{\"type\":\"minecraft:horse\",\"skin\":null,\"pose\":null,\"horse\":{\"color\":\"black\"}}"));
            com.google.gson.JsonObject json = runtime.npcs().find("steed").orElseThrow().json();
            assertEquals("minecraft:horse", json.get("type").getAsString());
            assertFalse(json.has("skin"));
            assertFalse(json.has("pose"));
            assertEquals("black", json.getAsJsonObject("horse").get("color").getAsString());
        }
    }
    @Test void aRefusedTypeChangeSaysHowToDropWhatTheNewBodyCannotShow() {
        com.google.gson.JsonObject retype = com.google.gson.JsonParser.parseString("{\"type\":\"minecraft:horse\"}").getAsJsonObject();
        assertEquals("Cannot save steed: $.pose: sitting is available only for minecraft:player bodies. Set a field to null in the same edit to remove it, such as {\"type\":\"minecraft:horse\",\"pose\":null}",
                Administration.refusal("steed", retype, "$.pose: sitting is available only for minecraft:player bodies"));
        com.google.gson.JsonObject rename = com.google.gson.JsonParser.parseString("{\"name\":\"\"}").getAsJsonObject();
        assertEquals("Cannot save steed: $.name: expected text", Administration.refusal("steed", rename, "$.name: expected text"));
    }
    @Test void anEmptyGroupCreatesTheNpcDirectlyInNpcs() throws Exception {
        try (AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", root))) {
            runtime.start(); Administration admin = new Administration(runtime);
            List.of(admin.create("loose", "minecraft:player", "world", 1, 64, 2, "", "Loose"));
            assertEquals("npcs/loose.json", runtime.npcs().find("loose").orElseThrow().path());
            assertEquals("", runtime.npcs().find("loose").orElseThrow().group());
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
            assertTrue(staged.get(1).startsWith("Review imports/") && staged.get(1).endsWith(", then /animus import plan " + original + " " + application), staged.get(1));
            List<String> installed = admin.applyPlan(original, application);
            assertTrue(RichText.plain(installed.getFirst()).startsWith("[Animus]: Installed dormant NPCs; tx "), installed.getFirst());
            initialTransaction = transaction(installed.getFirst());
            assertEquals(List.of("Already installed; nothing changed, /animus list shows the NPCs"), admin.applyPlan(original, application));
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
            assertTrue(RichText.plain(admin.rollback(replacementTransaction).getFirst()).matches("\\[Animus]: Rolled back " + replacementTransaction.substring(0, 8) + "; tx [0-9a-f-]{36}"));
            assertEquals("Guard", runtime.npcs().find("citizens-1").orElseThrow().name());
            assertEquals(1, runtime.npcs().find("citizens-1").orElseThrow().revision());
            admin.rollback(copyTransaction);
            admin.rollback(initialTransaction);
            assertTrue(runtime.npcs().all().isEmpty());
        }
        try (AnimusRuntime runtime = new AnimusRuntime(environment)) { runtime.start(); assertTrue(runtime.npcs().all().isEmpty()); }
    }

    private static String transaction(String receipt) {
        int start = receipt.lastIndexOf("tx ") + 3;
        return receipt.substring(start, Math.min(receipt.length(), start + 36));
    }

    /** The id after {@code lead} in a {@code <lead><id>: ...} receipt. */
    private static String id(String lead, String receipt) {
        assertTrue(receipt.startsWith(lead), receipt);
        return receipt.substring(lead.length(), receipt.indexOf(':'));
    }
}
