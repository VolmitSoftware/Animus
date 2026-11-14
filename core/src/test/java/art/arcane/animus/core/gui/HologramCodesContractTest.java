package art.arcane.animus.core.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.editor.SharedFixtures;
import art.arcane.animus.core.npc.BodyTraits;
import art.arcane.animus.core.npc.HologramStyle;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.presentation.BodyLook;
import art.arcane.animus.core.presentation.DisplayEntity;
import art.arcane.animus.core.text.RichText;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * NPC hologram lines and hologram documents against schema/fixtures/text/display-rows.json, which animus-web's
 * previews check too: the text each display entity is sent, read as the platforms read it and split into the rows the
 * client draws.
 */
final class HologramCodesContractTest {
    private static final UUID ID = UUID.fromString("0a1b2c3d-0000-4000-8000-00000000abcd");

    @Test
    void npcAndHologramRowsMatchEveryVector() throws IOException {
        JsonObject index = SharedFixtures.object(SharedFixtures.fixture("index.json"));
        JsonObject fixture = SharedFixtures.object(SharedFixtures.fixture(index.get("textRows").getAsString()));
        List<String> mismatches = new ArrayList<>();
        int checked = 0;
        for (JsonElement element : fixture.getAsJsonArray("vectors")) {
            JsonObject vector = element.getAsJsonObject();
            String name = vector.get("name").getAsString();
            List<String> lines = new ArrayList<>();
            vector.getAsJsonArray("lines").forEach((JsonElement line) -> lines.add(line.getAsString()));
            JsonArray expected = vector.getAsJsonArray("rows");
            boolean separate = vector.get("separate").getAsBoolean();
            compare(mismatches, name + " (NPC)", expected, npcRows(lines, separate));
            if (!separate) compare(mismatches, name + " (hologram)", expected, hologramRows(lines));
            checked++;
        }
        assertTrue(mismatches.isEmpty(), () -> String.join("\n", mismatches));
        assertTrue(checked >= 10, "expected every vector to be checked");
    }

    /** An NPC's hologram lines, one display or one per line, without a name row. */
    private static JsonArray npcRows(List<String> lines, boolean separate) {
        HologramStyle style = separate
                ? new HologramStyle("center", false, false, OptionalDouble.empty(), OptionalDouble.of(0.3), 0, 0, Collections.nCopies(lines.size(), HologramStyle.Margin.NONE))
                : HologramStyle.DEFAULT;
        NpcAppearance appearance = new NpcAppearance(Map.of(), lines, false, NpcPose.STANDING, Optional.empty(), style, BodyTraits.DEFAULT);
        BodyLook look = BodyLook.of(ID, "minecraft:horse", "Old Bess", appearance);
        assertEquals(separate ? lines.size() : 1, look.rows().size());
        JsonArray rows = new JsonArray();
        for (BodyLook.TextRow row : look.rows()) rows.addAll(drawn(row.text()));
        return rows;
    }

    /** A hologram document's text lines, all in its one text display. */
    private static JsonArray hologramRows(List<String> lines) {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 3);
        json.addProperty("revision", 1);
        JsonObject anchor = new JsonObject();
        anchor.addProperty("world", "world");
        JsonArray position = new JsonArray();
        position.add(0); position.add(64); position.add(0);
        anchor.add("position", position);
        json.add("anchor", anchor);
        JsonArray array = new JsonArray();
        lines.forEach(array::add);
        json.add("lines", array);
        HologramDocument.Result result = HologramDocument.parse("test:codes", json.toString());
        assertEquals(List.of(), result.problems());
        HologramDocument hologram = result.document().orElseThrow();
        HologramRender.Render render = HologramRender.build(hologram, hologram.lines(), hologram.style(), (String name) -> null, UnaryOperator.identity(), 0, 64, 0);
        return drawn(((DisplayEntity.Text) render.entities().get("text").content()).text());
    }

    /** One display's text as the platforms turn it into a component, split at each newline into the client's rows. */
    private static JsonArray drawn(String text) {
        JsonArray rows = new JsonArray();
        JsonArray row = new JsonArray();
        rows.add(row);
        for (RichText.Run run : RichText.runs(text)) {
            String[] parts = run.text().split("\n", -1);
            for (int index = 0; index < parts.length; index++) {
                if (index > 0) { row = new JsonArray(); rows.add(row); }
                if (parts[index].isEmpty()) continue;
                JsonObject part = new JsonObject();
                part.addProperty("text", parts[index]);
                part.add("color", run.rgb().isPresent() ? new JsonPrimitive(String.format("#%06x", run.rgb().getAsInt())) : JsonNull.INSTANCE);
                part.addProperty("bold", run.bold());
                part.addProperty("italic", run.italic());
                part.addProperty("underline", run.underlined());
                part.addProperty("strikethrough", run.strikethrough());
                part.addProperty("obfuscated", run.obfuscated());
                row.add(part);
            }
        }
        return rows;
    }

    private static void compare(List<String> mismatches, String label, JsonArray expected, JsonArray actual) {
        if (!expected.equals(actual)) mismatches.add(label + ": expected " + expected + " but drew " + actual);
    }
}
