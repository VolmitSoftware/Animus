package art.arcane.animus.core.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.gui.GuiIds;
import art.arcane.animus.core.gui.GuiKind;
import art.arcane.animus.core.gui.TextAnimation;
import art.arcane.animus.core.gui.exchange.GlossIds;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The hologram and text-animation id contract in schema/fixtures/gui, which animus-web checks too. */
class GuiIdContractTest {
    private static GuiKind guiKind(String kind) {
        return switch (kind) {
            case "hologram" -> GuiKind.HOLOGRAM;
            case "text-animation" -> GuiKind.ANIMATION;
            case "menu" -> GuiKind.MENU;
            default -> GuiKind.INVENTORY;
        };
    }

    @Test
    void locations_mapPathsToNamespacedIdsAndBack() throws Exception {
        JsonObject fixture = SharedFixtures.object(SharedFixtures.fixture("gui/ids.json"));
        int checked = 0;
        for (JsonElement element : fixture.getAsJsonArray("locations")) {
            JsonObject entry = element.getAsJsonObject();
            DocumentKind kind = DocumentKind.parse(entry.get("kind").getAsString()).orElseThrow();
            String path = entry.get("path").getAsString();
            Optional<String> expected = entry.get("id").isJsonNull() ? Optional.empty() : Optional.of(entry.get("id").getAsString());
            assertEquals(expected, EditorDocuments.idFor(kind, path), path);
            if (expected.isPresent()) assertEquals(path, EditorDocuments.pathFor(kind, expected.get()), path);
            checked++;
        }
        assertTrue(checked >= 8);
    }

    @Test
    void references_resolveRelativeToTheReferencingNamespace() throws Exception {
        JsonObject fixture = SharedFixtures.object(SharedFixtures.fixture("gui/ids.json"));
        for (JsonElement element : fixture.getAsJsonArray("references")) {
            JsonObject entry = element.getAsJsonObject();
            assertEquals(entry.get("resolved").getAsString(), GuiIds.relative(entry.get("from").getAsString(), entry.get("token").getAsString()));
        }
    }

    @Test
    void gloss_exportAndImportIdsMatchTheFixture() throws Exception {
        JsonObject fixture = SharedFixtures.object(SharedFixtures.fixture("gui/ids.json"));
        for (JsonElement element : fixture.getAsJsonArray("gloss")) {
            JsonObject entry = element.getAsJsonObject();
            assertEquals(Optional.of(entry.get("gloss").getAsString()), GlossIds.toGloss(guiKind(entry.get("kind").getAsString()), entry.get("animus").getAsString()));
        }
        for (JsonElement element : fixture.getAsJsonArray("glossImport")) {
            JsonObject entry = element.getAsJsonObject();
            assertEquals(Optional.of(entry.get("animus").getAsString()), GlossIds.toAnimus(entry.get("gloss").getAsString(), GlossIds.DEFAULT_NAMESPACE));
        }
    }

    @Test
    void migration_targetsAreValidServerLocations() throws Exception {
        JsonObject fixture = SharedFixtures.object(SharedFixtures.fixture("gui/ids.json"));
        for (JsonElement element : fixture.getAsJsonArray("migration")) {
            JsonObject entry = element.getAsJsonObject();
            DocumentKind kind = DocumentKind.parse(entry.get("kind").getAsString()).orElseThrow();
            assertEquals(Optional.of(entry.get("migratedId").getAsString()), EditorDocuments.idFor(kind, entry.get("migratedPath").getAsString()));
        }
    }

    @Test
    void textAnimations_chooseTheFixtureFrames() throws Exception {
        JsonObject fixture = SharedFixtures.object(SharedFixtures.fixture("gui/text-animation-frames.json"));
        int samples = 0;
        for (JsonElement element : fixture.getAsJsonArray("animations")) {
            JsonObject entry = element.getAsJsonObject();
            String id = entry.get("id").getAsString();
            TextAnimation.Result parsed = TextAnimation.parse(id, entry.getAsJsonObject("document"));
            assertTrue(parsed.problems().isEmpty(), () -> id + " " + parsed.problems());
            TextAnimation animation = parsed.animation().orElseThrow();
            for (JsonElement sample : entry.getAsJsonArray("samples")) {
                JsonArray pair = sample.getAsJsonArray();
                assertEquals(pair.get(1).getAsInt(), animation.frame(pair.get(0).getAsLong()), id + " at " + pair.get(0));
                samples++;
            }
        }
        assertTrue(samples >= 30);
    }
}
