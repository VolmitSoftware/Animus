package art.arcane.animus.core.gui;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuiDocumentTest {
    /** A Gloss inventory document using only fields both plugins share. */
    static final String GLOSS = """
            {
              "schemaVersion": 1,
              "revision": 3,
              "title": "&8Shop &7- {{ viewer.name }}",
              "resolution": "9x6",
              "mask": ["#########", "#.......#", "#.......#", "#.......#", "#.......#", "#<##X##>#"],
              "keys": {
                "#": {"type": "decoration", "icon": {"type": "item", "item": "minecraft:gray_stained_glass_pane", "name": " "}},
                "<": {"type": "button", "icon": {"type": "item", "item": "minecraft:arrow", "name": "&7Previous"}, "actions": [{"type": "navigate", "mode": "page", "target": "prev"}]},
                ">": {"type": "button", "icon": {"type": "item", "item": "minecraft:arrow", "name": "&7Next"}, "actions": [{"type": "navigate", "mode": "page", "target": "next"}]},
                "X": {"type": "button", "icon": {"type": "item", "item": "minecraft:barrier", "name": "&cClose"}, "actions": [{"type": "close"}]}
              },
              "slots": {
                "10": {"type": "button", "show": "viewer.level >= 5", "icon": {"type": "item", "item": "minecraft:diamond_sword", "name": "&bSword", "lore": ["&7100 coins"]},
                       "actions": [{"type": "message", "message": "&aYou are level {{ viewer.level }}."}]}
              },
              "list": {"area": ".", "var": "entry", "source": "['stone','dirt','oak_log']", "pageSize": 28,
                       "template": {"type": "button", "icon": {"type": "item", "item": "minecraft:{{ entry }}", "name": "&f{{ entry }}"},
                                    "actions": [{"type": "message", "message": "&7You picked &f{{ entry }}&7."}]}},
              "closeOnTeleport": true,
              "variants": []
            }
            """;

    private static List<String> problems(String json) { return GuiDocument.parse("town:menu", json).problems(); }

    @Test void aGlossInventoryDocumentLoadsUnchanged() {
        GuiDocument.Result result = GuiDocument.parse("town:menu", GLOSS);
        assertEquals(List.of(), result.problems());
        GuiDocument document = result.document().orElseThrow();
        assertEquals(54, document.size());
        assertEquals(3, document.revision());
        assertTrue(document.list().isPresent());
        assertFalse(document.hasCommands());
    }

    @Test void glossOnlyActionsAreRefusedWithTheExactField() {
        String json = GLOSS.replace("[{\"type\": \"close\"}]", "[{\"type\": \"close\"}, {\"type\": \"camera\"}]");
        assertEquals(List.of("$.keys.X.actions[1].type: action 'camera' is not available in Animus"), problems(json));
    }

    @Test void unknownAnimusActionsAndUnknownTypesAreRefused() {
        assertTrue(problems(GLOSS.replace("{\"type\": \"close\"}", "{\"type\": \"animus:teleport\"}")).contains("$.keys.X.actions[0].type: unknown Animus action 'animus:teleport'"));
        assertTrue(problems(GLOSS.replace("{\"type\": \"close\"}", "{\"type\": \"explode\"}")).contains("$.keys.X.actions[0].type: unknown action 'explode'"));
    }

    @Test void animusActionsUseTheirNamespace() {
        assertEquals(List.of(), problems(GLOSS.replace("{\"type\": \"close\"}", "{\"type\": \"animus:open-shop\", \"shop\": \"town:baker\"}")));
        assertEquals(List.of("$.keys.X.actions[0].shop: expected a shop id such as town:baker"),
                problems(GLOSS.replace("{\"type\": \"close\"}", "{\"type\": \"animus:open-shop\", \"shop\": \"Baker\"}")));
    }

    @Test void newerSchemaVersionsAreRefusedByName() {
        assertEquals(List.of("$.schemaVersion: chest GUI schemaVersion 2 is not supported; Animus reads version 1"), problems(GLOSS.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")));
    }

    @Test void maskRowsMustMatchTheResolution() {
        assertTrue(problems(GLOSS.replace("\"#<##X##>#\"", "\"#<##X##>\"")).contains("$.mask[5]: row \"#<##X##>\" must be exactly 9 characters wide"));
    }

    @Test void unknownFieldsAndBadExpressionsAreReported() {
        assertTrue(problems(GLOSS.replace("\"closeOnTeleport\": true", "\"closeOnTeleport\": true, \"sparkle\": 1")).contains("$.sparkle: unknown field"));
        assertTrue(problems(GLOSS.replace("viewer.level >= 5", "wobble >= 5")).contains("$.slots.10.show: unknown variable 'wobble'"));
    }

    @Test void holographicComponentsAndIconsHaveNoChestMeaning() {
        assertTrue(problems(GLOSS.replace("\"type\": \"decoration\"", "\"type\": \"slider\"")).getFirst().startsWith("$.keys.#.type: component 'slider'"));
        assertTrue(problems(GLOSS.replace("\"type\": \"item\", \"item\": \"minecraft:barrier\"", "\"type\": \"entity\", \"item\": \"minecraft:barrier\""))
                .contains("$.keys.X.icon.type: icon 'entity' is a holographic menu icon and cannot be drawn in a chest window"));
    }

    @Test void callsMustExistAndMayNotLoop() {
        String calls = GLOSS.replace("\"closeOnTeleport\": true", "\"closeOnTeleport\": true, \"actions\": {\"a\": [{\"type\": \"call\", \"action\": \"b\"}], \"b\": [{\"type\": \"call\", \"action\": \"a\"}]}");
        assertTrue(problems(calls).stream().anyMatch((String problem) -> problem.endsWith("call cycle")), problems(calls).toString());
        String missing = GLOSS.replace("{\"type\": \"close\"}", "{\"type\": \"call\", \"action\": \"nowhere\"}");
        assertEquals(List.of("$.actions: call target 'nowhere' is not declared"), problems(missing));
    }

    @Test void presetsAndCutsceneSequenceFieldsAreRefused() {
        assertEquals(List.of("$.preset: Gloss presets are not available in Animus; inline the preset's fields"), problems(GLOSS.replace("\"revision\": 3", "\"revision\": 3, \"preset\": \"fancy\"")));
        String sequence = GLOSS.replace("{\"type\": \"close\"}", "{\"type\": \"sequence\", \"once\": \"intro\", \"steps\": [{\"type\": \"close\"}]}");
        assertEquals(List.of("$.keys.X.actions[0].once: sequence field 'once' belongs to Gloss cutscenes and is not available in Animus"), problems(sequence));
    }

    @Test void commandActionsAreDetectedAtAnyDepth() {
        String nested = GLOSS.replace("{\"type\": \"close\"}", "{\"type\": \"if\", \"when\": \"true\", \"then\": [{\"type\": \"command\", \"command\": \"say hi\"}]}");
        assertTrue(GuiDocument.parse("town:menu", nested).document().orElseThrow().hasCommands());
        assertEquals(List.of("$.keys.X.actions[0].command: write the command without a leading slash"),
                problems(GLOSS.replace("{\"type\": \"close\"}", "{\"type\": \"command\", \"command\": \"/say hi\"}")));
    }
}
