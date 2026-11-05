package art.arcane.animus.core.gui;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MenuDocumentTest {
    /** A Gloss holographic menu using only fields both plugins share. */
    static final String GLOSS = """
            {
              "offset": [0, 1.4, 2.5],
              "followPlayer": false,
              "maxDistance": 6,
              "closeOnTeleport": true,
              "vars": {"tab": "'shop'"},
              "actions": {"thanks": [{"type": "message", "message": "&aThanks!"}]},
              "components": [
                {"id": "title", "offset": [0, 0.8, 0], "data": {"type": "decoration", "icon": {"type": "text", "text": "&6&lMarket", "style": {"billboard": "fixed", "scaleX": 1.5, "scaleY": 1.5}}}},
                {"id": "tabs", "offset": [0, 0.4, 0], "data": {"type": "tabs", "var": "tab", "tabs": [{"id": "shop", "label": "Shop"}, {"id": "bank", "label": "Bank"}]}},
                {"id": "goods", "offset": [-1, 0, 0], "show": "session.tab == 'shop'", "data": {"type": "list", "var": "item", "source": "['bread', 'apple']",
                  "pageSize": 4, "flow": {"columns": 2, "spacingX": 0.8, "spacingY": 0.6},
                  "template": {"type": "button", "icon": {"type": "item", "item": "minecraft:{{ item }}", "name": "&f{{ item }}"},
                               "actions": [{"type": "call", "action": "thanks"}]}}},
                {"id": "next", "offset": [1, -0.6, 0], "motion": {"y": "sin(time.millis / 500) * 0.05", "ticks": 2},
                 "data": {"type": "button", "highlightModifier": 0.08, "hoverEasing": "back_out", "hitbox": {"width": 0.6, "height": 0.4},
                          "tooltip": {"delayTicks": 5, "lines": ["&7Next page"]},
                          "icon": {"type": "block", "block": "minecraft:oak_planks"}, "actions": [{"type": "navigate", "mode": "page", "target": "next"}]}},
                {"id": "pet", "offset": [0, -0.6, 0], "data": {"type": "decoration", "icon": {"type": "entity", "entity": "fox", "height": 0.7}}}
              ]
            }
            """;

    private static List<String> problems(String json) { return MenuDocument.parse("town:market", json).problems(); }

    @Test void aGlossMenuLoadsUnchanged() {
        MenuDocument.Result result = MenuDocument.parse("town:market", GLOSS);
        assertEquals(List.of(), result.problems());
        MenuDocument menu = result.document().orElseThrow();
        assertEquals(5, menu.components().size());
        assertEquals("shop", menu.vars().get("tab"));
        assertEquals(64, menu.hash().length(), "Gloss menus are identified by content hash");
        assertInstanceOf(MenuDocument.ListData.class, menu.components().get(2).data());
        assertEquals(2, ((MenuDocument.ListData) menu.components().get(2).data()).flow().columns());
        assertTrue(menu.components().get(3).motion().moving());
        assertEquals("minecraft:fox", ((MenuDocument.MobIcon) ((MenuDocument.Decoration) menu.components().get(4).data()).icon()).type());
    }

    @Test void glossOnlyMenuFeaturesAreRefusedByName() {
        assertEquals(List.of("$.components[0].data.icon.type: icon 'textImage' needs Gloss's image glyphs and is not available in Animus"),
                problems(GLOSS.replace("\"type\": \"text\", \"text\": \"&6&lMarket\"", "\"type\": \"textImage\", \"path\": \"logo.png\"").replace(", \"style\": {\"billboard\": \"fixed\", \"scaleX\": 1.5, \"scaleY\": 1.5}", "")).subList(0, 1));
        assertEquals(List.of("$.actions: call names missing action list 'gone'"), problems(GLOSS.replace("\"action\": \"thanks\"", "\"action\": \"gone\"")));
        assertEquals(List.of("$.particleLayers: particle layers are not available in Animus"), problems(GLOSS.replace("\"followPlayer\": false", "\"particleLayers\": [{}]")));
        assertEquals(List.of("$.components[3].data.tooltip.box.enabled: box panels are not available in Animus; use style.backgroundArgb"),
                problems(GLOSS.replace("\"delayTicks\": 5", "\"delayTicks\": 5, \"box\": {\"enabled\": true}")));
        assertTrue(problems(GLOSS.replace("\"template\": {\"type\": \"button\"", "\"template\": {\"type\": \"list\"")).contains("$.components[2].data.template.type: a list template cannot be another list"));
    }

    @Test void listTemplatesSeeTheirEntryVariable() {
        assertTrue(problems(GLOSS.replace("minecraft:{{ item }}", "minecraft:{{ thing }}")).stream().anyMatch((String problem) -> problem.startsWith("$.components[2].data.template.icon.item")));
    }
}
