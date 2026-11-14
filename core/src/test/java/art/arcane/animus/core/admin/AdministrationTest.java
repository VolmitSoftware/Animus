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
            assertTrue(admin.status("127.0.0.1:9797").getFirst().contains(" on Bukkit 26.3 | "), admin.status("127.0.0.1:9797").getFirst());
            assertTrue(admin.status("127.0.0.1:9797").stream().anyMatch((String line) -> line.startsWith("Settings in effect:") && line.contains("packetsPerTick=512")));
            art.arcane.animus.core.config.SettingsEdit.apply(runtime, "npcs", (com.google.gson.JsonObject npcs) -> npcs.addProperty("packetsPerTick", 12));
            art.arcane.animus.core.config.SettingsEdit.apply(runtime, "editor", (com.google.gson.JsonObject editor) -> editor.addProperty("port", 9811));
            String line = admin.status("not bound: cannot bind 127.0.0.1:9811 (Address already in use)").stream().filter((String text) -> text.startsWith("Settings in effect:")).findFirst().orElseThrow();
            assertTrue(line.contains("packetsPerTick=12"), line);
            assertTrue(line.contains("editor listener not bound: cannot bind 127.0.0.1:9811 (Address already in use)"), line);
        }
    }
    @Test void reloadUpgradesVersionOneFilesCopiedInWhileRunning() throws Exception {
        Path before = art.arcane.animus.core.editor.SharedFixtures.fixture("upgrades/before"), after = art.arcane.animus.core.editor.SharedFixtures.fixture("upgrades/after");
        List<String> paths = List.of("groups/guards.json", "npcs/town/citizens-9.json");
        try (AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", root))) {
            runtime.start(); Administration admin = new Administration(runtime);
            for (String path : paths) {
                Files.createDirectories(root.resolve(path).getParent());
                Files.writeString(root.resolve(path), Files.readString(before.resolve(path)));
            }
            List<String> lines = admin.reload();
            assertEquals(2, lines.size(), lines.toString());
            assertTrue(lines.getFirst().matches("Upgraded 2 files to schemaVersion 2; rollback [a-f0-9-]{36}"), lines.getFirst());
            assertEquals("Reloaded; 1 saved, 1 spawned", lines.get(1));
            for (String path : paths) assertEquals(Files.readString(after.resolve(path)), Files.readString(root.resolve(path)), path);
            assertEquals(List.of("Reloaded; 1 saved, 1 spawned"), admin.reload(), "nothing is left to upgrade");
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
            assertEquals(java.util.Optional.empty(), Administration.patchProblem(runtime, com.google.gson.JsonParser.parseString("{\"migration\": null, \"name\": \"A\"}").getAsJsonObject()));
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
    @Test void anEditMergesNestedObjectsAndSaysWhatChanged() throws Exception {
        try (AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", root))) {
            runtime.start(); Administration admin = new Administration(runtime);
            List.of(admin.create("guard", "minecraft:player", "world", 1, 64, 2, "town", "Guard"));
            admin.edit("guard", "{\"modules\":{\"animus:look-close\":{\"range\":6},\"animus:shop\":{\"shop\":\"town:bakery\"}}}");
            String reply = admin.edit("guard", "{\"name\":\"Captain\",\"modules\":{\"animus:look-close\":{\"range\":8}}}");
            com.google.gson.JsonObject modules = runtime.npcs().find("guard").orElseThrow().json().getAsJsonObject("modules");
            assertEquals("town:bakery", modules.getAsJsonObject("animus:shop").get("shop").getAsString(), "editing one module keeps the others");
            assertEquals(8, modules.getAsJsonObject("animus:look-close").get("range").getAsInt());
            assertTrue(reply.startsWith("Saved guard: set name, modules.animus:look-close.range; tx "), reply);
            String removed = admin.edit("guard", "{\"modules\":{\"animus:shop\":null}}");
            assertTrue(removed.startsWith("Saved guard: removed modules.animus:shop; tx "), removed);
            assertFalse(runtime.npcs().find("guard").orElseThrow().json().getAsJsonObject("modules").has("animus:shop"));
            assertTrue(runtime.npcs().find("guard").orElseThrow().json().getAsJsonObject("modules").has("animus:look-close"));
        }
    }
    @Test void mergeFollowsJsonMergePatch() throws Exception {
        com.google.gson.JsonObject document = com.google.gson.JsonParser.parseString("{\"a\":{\"b\":1,\"c\":[1,2]},\"d\":\"x\",\"e\":5}").getAsJsonObject();
        List<String> changes = Administration.merge(document, com.google.gson.JsonParser.parseString("{\"a\":{\"b\":1,\"c\":[3]},\"d\":{\"f\":null,\"g\":2},\"e\":null,\"h\":null}").getAsJsonObject());
        assertEquals(com.google.gson.JsonParser.parseString("{\"a\":{\"b\":1,\"c\":[3]},\"d\":{\"g\":2}}"), document);
        assertEquals(List.of("set a.c", "set d", "removed e"), changes, "unchanged values and absent removals are not reported");
        assertEquals("set a.c, d; removed e", Administration.describe(changes));
        assertEquals("no change", Administration.describe(List.of()));
    }
    @Test void aDuplicateIdOnCreateOrCloneSaysTheNpcExists() throws Exception {
        try (AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", root))) {
            runtime.start(); Administration admin = new Administration(runtime);
            List.of(admin.create("bob", "minecraft:player", "world", 1, 64, 2, "", "Bob"));
            List.of(admin.create("smith", "minecraft:player", "world", 1, 64, 2, "town", "Smith"));
            String exists = "An NPC named bob already exists; /animus inspect bob shows it, or pick another id";
            assertEquals(exists, assertThrows(IOException.class, () -> admin.create("bob", "minecraft:player", "world", 1, 64, 2, "", "Bob")).getMessage());
            assertEquals(exists, assertThrows(IOException.class, () -> admin.create("bob", "minecraft:player", "world", 1, 64, 2, "elsewhere", "Bob")).getMessage());
            assertEquals(exists, assertThrows(IOException.class, () -> admin.duplicate("smith", "bob")).getMessage());
        }
    }
    @Test void aNewNpcCanSpawnFacingTheCreatorsWay() throws Exception {
        try (AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", root))) {
            runtime.start(); Administration admin = new Administration(runtime);
            Administration.Creation creation = admin.creation("bob", "minecraft:player", "world", new art.arcane.animus.core.virtual.Vec3(1.5, 64, -2.25), 91.256f, "", "Bob", Optional.empty(), true);
            assertTrue(creation.definition().spawned());
            com.google.gson.JsonObject position = creation.draft().document().getAsJsonObject("position");
            assertEquals(91.26, position.get("yaw").getAsDouble(), 1e-6);
            assertEquals(-2.25, position.get("z").getAsDouble());
            runtime.npcs().apply(creation.draft());
            assertTrue(runtime.npcs().find("bob").orElseThrow().spawned());
        }
    }
    @Test void aTemplateLaysItsLookOverTheNewNpcButKeepsAGivenName() throws Exception {
        try (AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", root))) {
            runtime.start(); Administration admin = new Administration(runtime);
            Administration.Creation creation = admin.creation("king", "minecraft:armor_stand", "world", new art.arcane.animus.core.virtual.Vec3(0, 64, 0), 0, "", "king", Optional.empty(), true);
            com.google.gson.JsonObject fields = com.google.gson.JsonParser.parseString("""
                    {"type":"minecraft:armor_stand","name":"&6Skeleton King","glowing":true,
                     "equipment":{"head":{"id":"minecraft:golden_helmet"}},"modules":{"mythic:mob":{"mob":"SkeletonKing"}}}""").getAsJsonObject();
            Administration.Creation shown = admin.templated(creation, fields, false);
            com.google.gson.JsonObject document = shown.draft().document();
            assertEquals("&6Skeleton King", shown.definition().name());
            assertTrue(document.get("glowing").getAsBoolean());
            assertEquals("minecraft:golden_helmet", document.getAsJsonObject("equipment").getAsJsonObject("head").get("id").getAsString());
            assertEquals("SkeletonKing", document.getAsJsonObject("modules").getAsJsonObject("mythic:mob").get("mob").getAsString());
            assertEquals(creation.definition().uuid(), shown.definition().uuid());
            assertEquals("king", admin.templated(creation, fields, true).definition().name());
            com.google.gson.JsonObject broken = com.google.gson.JsonParser.parseString("{\"equipment\":{\"wings\":{\"id\":\"minecraft:elytra\"}}}").getAsJsonObject();
            assertTrue(assertThrows(java.io.IOException.class, () -> admin.templated(creation, broken, false)).getMessage().contains("$.equipment.wings"));
        }
    }
    @Test void renameAndMoveRewriteTheDefinition() throws Exception {
        try (AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", root))) {
            runtime.start(); Administration admin = new Administration(runtime);
            List.of(admin.create("bob", "minecraft:player", "world", 1, 64, 2, "", "Bob"));
            assertTrue(admin.rename("bob", "&6Bob the Baker").startsWith("Renamed bob to &6Bob the Baker; tx "));
            assertEquals("&6Bob the Baker", runtime.npcs().find("bob").orElseThrow().name());
            String moved = admin.move("bob", new art.arcane.animus.core.virtual.WorldPosition("world_nether", new art.arcane.animus.core.virtual.Vec3(10.5, 70, -3)), -45f);
            assertTrue(moved.startsWith("Moved bob to world_nether 10.5 70 -3; tx "), moved);
            com.google.gson.JsonObject position = runtime.npcs().find("bob").orElseThrow().json().getAsJsonObject("position");
            assertEquals("world_nether", position.get("world").getAsString());
            assertEquals(-45, position.get("yaw").getAsDouble());
            assertEquals(0, position.get("pitch").getAsDouble());
        }
    }
    @Test void aBodyAnimusCannotShowIsRefusedAtCreateAndEdit() throws Exception {
        try (AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", root))) {
            runtime.start(); Administration admin = new Administration(runtime);
            String bodies = String.join(", ", art.arcane.animus.core.presentation.BodyRules.TYPES.stream().sorted().toList());
            assertTrue(bodies.contains("minecraft:painting") && bodies.contains("minecraft:zombie"), bodies);
            assertEquals("marker cannot be shown; the bodies Animus shows are " + bodies,
                    assertThrows(IOException.class, () -> admin.create("v", "marker", "world", 1, 64, 2, "", "V")).getMessage());
            List.of(admin.create("steed", "llama", "world", 1, 64, 2, "", "Steed"));
            assertEquals("minecraft:llama", runtime.npcs().find("steed").orElseThrow().json().get("type").getAsString(), "a bare type is saved namespaced");
            assertEquals("$.type: minecraft:marker cannot be shown; the bodies Animus shows are " + bodies,
                    assertThrows(IOException.class, () -> admin.edit("steed", "{\"type\":\"minecraft:marker\"}")).getMessage());
        }
    }
    @Test void plainRepliesForBadJsonUnknownIdsAndRepeatedRollbacks() throws Exception {
        try (AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", root))) {
            runtime.start(); Administration admin = new Administration(runtime);
            List.of(admin.create("guard", "minecraft:player", "world", 1, 64, 2, "", "Guard"));
            assertEquals("The patch is not a JSON object; write it like {\"name\": \"Captain\"}, with double quotes",
                    assertThrows(IOException.class, () -> admin.edit("guard", "{bad json")).getMessage());
            assertEquals("No NPC named ghost; /animus list shows ids", assertThrows(IOException.class, () -> admin.edit("ghost", "{}")).getMessage());
            String saved = admin.edit("guard", "{\"name\":\"Captain\"}");
            String tx = saved.substring(saved.lastIndexOf(' ') + 1);
            admin.rollback(tx);
            assertEquals("Change " + art.arcane.animus.core.text.ChatLine.shortId(tx) + " is already undone; nothing changed",
                    assertThrows(IOException.class, () -> admin.rollback(tx)).getMessage());
            String first = admin.edit("guard", "{\"name\":\"One\"}");
            admin.edit("guard", "{\"name\":\"Two\"}");
            String early = first.substring(first.lastIndexOf(' ') + 1);
            assertEquals("Cannot undo " + art.arcane.animus.core.text.ChatLine.shortId(early) + ": npcs/guard.json changed after it; undo the later change first",
                    assertThrows(IOException.class, () -> admin.rollback(early)).getMessage());
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
            assertTrue(RichText.plain(installed.getFirst()).startsWith("[Animus]: Installed NPCs; tx "), installed.getFirst());
            initialTransaction = transaction(installed.getFirst());
            assertEquals(List.of("Already installed; nothing changed, /animus list shows the NPCs"), admin.applyPlan(original, application));
            assertEquals(1, runtime.npcs().all().size());
            assertTrue(runtime.npcs().find("citizens-1").orElseThrow().json().get("spawned").getAsBoolean(), "the NPC spawns as Citizens had it");
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

    /** create and edit take every body the server's transport shows, such as any living entity on Bukkit. */
    @Test void createAndEditTakeTheBodiesTheServerShows() throws Exception {
        try (AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", root))) {
            runtime.start(); Administration admin = new Administration(runtime);
            java.util.Set<String> shown = new java.util.HashSet<>(art.arcane.animus.core.presentation.BodyRules.TYPES);
            shown.remove("minecraft:zombie");
            runtime.bodyTypes(shown);
            IOException refused = assertThrows(IOException.class, () -> admin.create("walker", "minecraft:zombie", "world", 1, 64, 2, "", "Walker"));
            assertTrue(refused.getMessage().startsWith("minecraft:zombie cannot be shown"), refused.getMessage());
            shown.add("minecraft:zombie");
            runtime.bodyTypes(shown);
            admin.create("walker", "minecraft:zombie", "world", 1, 64, 2, "", "Walker");
            assertEquals("minecraft:zombie", runtime.npcs().find("walker").orElseThrow().json().get("type").getAsString());
            admin.edit("walker", "{\"type\":\"minecraft:villager\"}");
            assertEquals("minecraft:villager", runtime.npcs().find("walker").orElseThrow().json().get("type").getAsString());
            assertTrue(Administration.patchProblem(runtime, com.google.gson.JsonParser.parseString("{\"type\":\"minecraft:marker\"}").getAsJsonObject()).isPresent());
        }
    }

    /** Chat shows ids cut to 12 characters with the full id on click; a typed short id answers as the full one. */
    @Test void importCommandsTakeTheShortIdChatShows() throws Exception {
        try (AnimusRuntime runtime = new AnimusRuntime(new RuntimeEnvironment(Platform.BUKKIT, "26.3", root))) {
            runtime.start(); Administration admin = new Administration(runtime);
            Path input = runtime.documents().resolve("imports/inbox/sample.yml"); Files.createDirectories(input.getParent());
            Files.writeString(input, "{\"npc\":{\"1\":{\"uuid\":\"00000000-0000-0000-0000-000000000001\",\"name\":\"Guard\",\"traits\":{\"type\":\"PLAYER\",\"location\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0}}}}}");
            String preview = id("Preview ", admin.preview("sample.yml", Optional.empty(), Map.of()).getFirst());
            String application = id("Application ", admin.stage(preview.substring(0, 12), "reject", Optional.empty()).getFirst());
            assertTrue(RichText.plain(admin.applyPlan(preview.substring(0, 12), application.substring(0, 12)).getFirst()).startsWith("[Animus]: Installed NPCs; tx "));
            Files.writeString(input, Files.readString(input).replace("000000000001", "000000000002").replace("\"1\"", "\"2\""));
            String second = id("Preview ", admin.preview("sample.yml", Optional.empty(), Map.of()).getFirst());
            assertTrue(RichText.plain(admin.apply(second.substring(0, 12)).getFirst()).startsWith("[Animus]: Installed"), "apply takes the short id too");
            assertEquals(2, runtime.npcs().all().size());
            IOException unknown = assertThrows(IOException.class, () -> admin.apply("0123456789ab"));
            assertEquals("No import id starts with 0123456789ab", unknown.getMessage());
            IOException tooShort = assertThrows(IOException.class, () -> admin.apply("abc"));
            assertEquals("Give the import id from the preview, at least its first 8 characters", tooShort.getMessage());
        }
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
