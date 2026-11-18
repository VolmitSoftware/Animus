package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NpcCommandsTest {
    @TempDir Path root;

    private static final String GUARD = "{\"schemaVersion\":2,\"revision\":1,\"uuid\":\"12345678-1234-1234-1234-123456789abc\",\"id\":\"guard\",\"group\":\"town\",\"name\":\"N\","
            + "\"type\":\"minecraft:player\",\"spawned\":false,\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0},\"attributes\":{\"minecraft:gravity\":0.04},\"groups\":[\"giants\"]}";

    @Test void healthSettingsAnswerInTheWordsTheCommandTakes() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/town/guard.json", "missing", GUARD.replace(",\"groups\":[\"giants\"]", ""))));
            NpcRepository repository = new NpcRepository(store);
            assertTrue(repository.reload().isEmpty());
            assertTrue(HealthSetting.set(repository, "guard", "killable", "on").startsWith("guard: killable on; tx "));
            assertTrue(HealthSetting.set(repository, "guard", "after-death", "permadeath").startsWith("guard: after-death permadeath; tx "), "no JSON quotes");
            IOException tooMuch = assertThrows(IOException.class, () -> HealthSetting.set(repository, "guard", "maximum", "5000"));
            assertEquals("maximum must be from 1 to 2048, not 5000", tooMuch.getMessage(), "the setting as typed, never the schema path");
            IllegalArgumentException word = assertThrows(IllegalArgumentException.class, () -> HealthSetting.set(repository, "guard", "killable", "maybe"));
            assertEquals("killable takes on or off, not maybe", word.getMessage());
            IllegalArgumentException setting = assertThrows(IllegalArgumentException.class, () -> HealthSetting.set(repository, "guard", "speed", "1"));
            assertEquals("No health setting speed; the settings are after-death, delay, killable, maximum, regenerate and show-health", setting.getMessage());
            String shown = HealthSetting.show(repository, "guard", "");
            assertEquals("guard: killable on, maximum default, regenerate default, show-health default, after-death permadeath, delay default", shown, "the settings, not the module's JSON");
        }
    }

    @Test void aWrongValueIsNamedAsTypedBeforeAnythingElse() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/town/guard.json", "missing", GUARD.replace(",\"groups\":[\"giants\"]", ""))));
            NpcRepository repository = new NpcRepository(store);
            assertTrue(repository.reload().isEmpty());
            assertEquals("action takes list, add or remove, not fly", assertThrows(IllegalArgumentException.class,
                    () -> NpcCommands.anchor(repository, "guard", "fly", "", null)).getMessage(), "the action is wrong, not the anchor name");
            assertEquals("enabled takes on, off or toggle, not maybe", assertThrows(IllegalArgumentException.class, () -> NpcCommands.look(repository, "guard", "maybe")).getMessage());
            assertEquals("value takes a number or reset, not tall", assertThrows(IllegalArgumentException.class, () -> NpcCommands.attribute(repository, "guard", "scale", "tall")).getMessage());
            assertEquals("scale takes 0.0625 to 16, not 40", assertThrows(IllegalArgumentException.class, () -> NpcCommands.attribute(repository, "guard", "scale", "40")).getMessage());
            assertEquals("Anchor GATE is not a name: use 1-32 lowercase letters, digits, _ or -", assertThrows(IllegalArgumentException.class,
                    () -> NpcCommands.anchor(repository, "guard", "remove", "GATE", null)).getMessage());
        }
    }

    @Test void attributeSetsShowsAndResetsOneAttributeInTheFile() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(
                    new DocumentStore.Change("groups/giants.json", "missing", "{\"schemaVersion\":2,\"revision\":1,\"id\":\"giants\",\"values\":{\"attributes\":{\"scale\":3}}}"),
                    new DocumentStore.Change("npcs/town/guard.json", "missing", GUARD)));
            NpcRepository repository = new NpcRepository(store);
            assertTrue(repository.reload().isEmpty());

            String set = NpcCommands.attribute(repository, "guard", "minecraft:scale", "1.5");
            assertTrue(set.startsWith("guard: scale = 1.5; tx "), set);
            assertEquals(1.5, attributes(repository).get("scale").getAsDouble());

            String gravity = NpcCommands.attribute(repository, "guard", "gravity", "0.02");
            assertTrue(gravity.startsWith("guard: gravity = 0.02"), gravity);
            assertFalse(attributes(repository).has("minecraft:gravity"), "the namespaced spelling is replaced by the bare id");

            List<String> shown = NpcCommands.show(repository, "guard", "scale");
            assertEquals("guard: scale = 1.5 (npc); vanilla default 1, range 0.0625..16", shown.getFirst());

            String reset = NpcCommands.attribute(repository, "guard", "scale", "reset");
            assertTrue(reset.startsWith("guard: scale reset; tx "), reset);
            assertFalse(attributes(repository).has("scale"));
            assertEquals("guard: scale = 3 (giants); vanilla default 1, range 0.0625..16", NpcCommands.show(repository, "guard", "scale").getFirst(),
                    "a group's value shows through once the NPC's own is gone");
            NpcCommands.attribute(repository, "guard", "gravity", "reset");
            assertFalse(repository.draft("guard").document().has("attributes"), "an empty attributes object is removed");
        }
    }

    @Test void skinFileStaysInsideTheSkinsFolderAndNamesAPng() throws Exception {
        Path skins = java.nio.file.Files.createDirectories(root.resolve("skins/town"));
        java.nio.file.Files.write(skins.resolve("guard.png"), new byte[]{1});
        java.nio.file.Files.write(root.resolve("secret.png"), new byte[]{1});
        java.nio.file.Files.write(skins.resolve("notes.txt"), new byte[]{1});
        assertEquals(java.util.Optional.of(skins.resolve("guard.png").toAbsolutePath().normalize()), NpcCommands.skinFile(root.resolve("skins"), "town/guard.png"));
        assertTrue(NpcCommands.skinFile(root.resolve("skins"), "../secret.png").isEmpty(), "never outside skins/");
        assertTrue(NpcCommands.skinFile(root.resolve("skins"), "town/notes.txt").isEmpty(), "only a .png");
        assertTrue(NpcCommands.skinFile(root.resolve("skins"), "town/missing.png").isEmpty());
    }

    @Test void skinOptionsTakeTrueOrFalse() {
        assertEquals(java.util.Optional.of(true), NpcCommands.flag("true"));
        assertEquals(java.util.Optional.of(false), NpcCommands.flag(" OFF "));
        assertTrue(NpcCommands.flag("maybe").isEmpty());
        assertTrue(NpcCommands.flag("").isEmpty());
    }

    @Test void lookCloseTurnsOffAndOnKeepingItsSettings() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/town/guard.json", "missing",
                    GUARD.replace(",\"groups\":[\"giants\"]", ",\"modules\":{\"animus:look-close\":{\"range\":6}}"))));
            NpcRepository repository = new NpcRepository(store);
            assertTrue(repository.reload().isEmpty());
            String off = NpcCommands.look(repository, "guard", "off");
            assertTrue(off.startsWith("guard: look-close off; tx "), off);
            JsonObject look = repository.find("guard").orElseThrow().json().getAsJsonObject("modules").getAsJsonObject("animus:look-close");
            assertFalse(look.get("enabled").getAsBoolean());
            assertEquals(6, look.get("range").getAsInt(), "turning it off keeps the range");
            assertTrue(art.arcane.animus.core.control.LookClose.parse(repository.find("guard").orElseThrow().json()).isEmpty());
            assertTrue(NpcCommands.look(repository, "guard", "on").startsWith("guard: look-close on; tx "));
            assertTrue(art.arcane.animus.core.control.LookClose.parse(repository.find("guard").orElseThrow().json()).isPresent());
            assertTrue(NpcCommands.look(repository, "guard", "toggle").startsWith("guard: look-close off; tx "));
            assertEquals("enabled takes on, off or toggle, not maybe", assertThrows(IllegalArgumentException.class, () -> NpcCommands.look(repository, "guard", "maybe")).getMessage());
        }
    }

    @Test void unknownAttributesAndValuesOutOfRangeAreRefused() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/town/guard.json", "missing", GUARD.replace(",\"groups\":[\"giants\"]", ""))));
            NpcRepository repository = new NpcRepository(store);
            assertTrue(repository.reload().isEmpty());
            IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> NpcCommands.attribute(repository, "guard", "bogus", "1"));
            assertTrue(unknown.getMessage().startsWith("unknown attribute bogus"), unknown.getMessage());
            IllegalArgumentException range = assertThrows(IllegalArgumentException.class, () -> NpcCommands.attribute(repository, "guard", "scale", "40"));
            assertEquals("scale takes 0.0625 to 16, not 40", range.getMessage());
            assertThrows(IllegalArgumentException.class, () -> NpcCommands.attribute(repository, "guard", "scale", "big"));
            assertEquals("guard: step_height is not set; vanilla default 0.6, range 0..10", NpcCommands.show(repository, "guard", "step_height").getFirst());
        }
    }

    @Test void npcOwnerFailuresAnswerInRed() {
        assertEquals(art.arcane.animus.core.text.Palette.error("Revive: guard is despawned"), CommandGroup.failed("Revive", "guard is despawned"));
    }

    @Test void theAttributeCommandCompletesAttributeIds() {
        AnimusCommandTree tree = new AnimusCommandTree(null);
        assertTrue(tree.values("", "attribute", new AnimusDirectorTest.Actor(false, AnimusDirector.ADMIN), java.util.Map.of()).contains("scale"));
        assertTrue(tree.values("", "attribute", new AnimusDirectorTest.Actor(false, AnimusDirector.ADMIN), java.util.Map.of()).contains("movement_speed"));
    }

    private static JsonObject attributes(NpcRepository repository) throws IOException { return repository.draft("guard").document().getAsJsonObject("attributes"); }
    @Test void aMineSkinLinkIsReadNotSigned() {
        assertEquals("Reading the skin from MineSkin", NpcCommands.signingLine("https://minesk.in/e51d0e34"));
        assertEquals("Signing the skin through MineSkin", NpcCommands.signingLine("https://example.com/guard.png"));
    }

    @Test void aSkinByNameIsSavedAndWornAndOnlyPlayerBodiesTakeOne() throws Exception {
        try (DocumentStore store = new DocumentStore(root)) {
            store.commit(List.of(new DocumentStore.Change("npcs/town/guard.json", "missing", GUARD.replace(",\"groups\":[\"giants\"]", "")),
                    new DocumentStore.Change("npcs/town/cow.json", "missing", GUARD.replace(",\"groups\":[\"giants\"]", "").replace("guard", "cow")
                            .replace("minecraft:player", "minecraft:cow").replace("12345678-1234-1234-1234-123456789abc", "22345678-1234-1234-1234-123456789abc"))));
            NpcRepository repository = new NpcRepository(store);
            assertTrue(repository.reload().isEmpty());
            assertEquals(java.util.Optional.of("cow is a minecraft:cow; skins show on minecraft:player bodies only"), NpcCommands.skinProblem(repository, "cow", "Notch"));
            assertEquals(java.util.Optional.of("'no such' is not a Minecraft name (1-16 letters, digits or _)"), NpcCommands.skinProblem(repository, "guard", "no such"));
            assertEquals(java.util.Optional.of("No NPC named ghost; /animus list shows ids"), NpcCommands.skinProblem(repository, "ghost", "Notch"));
            assertEquals(java.util.Optional.empty(), NpcCommands.skinProblem(repository, "guard", "reset"));
            String texture = java.util.Base64.getEncoder().encodeToString("{\"textures\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            art.arcane.animus.core.toolkit.SkinFiles skins = new art.arcane.animus.core.toolkit.SkinFiles(repository, store);
            String worn = skins.wear(new art.arcane.animus.core.presentation.BodySkin(texture, "c2ln"), "Notch", java.util.Optional.of("Notch"), List.of("guard"));
            String hash = repository.find("guard").orElseThrow().json().get("skin").getAsString();
            assertTrue(worn.startsWith("Skin Notch on N; tx "), worn);
            assertTrue(skins.remove(List.of("guard")).startsWith("Skin removed on N; tx "));
            assertFalse(repository.find("guard").orElseThrow().json().has("skin"));
        }
    }
}
