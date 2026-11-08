package art.arcane.animus.core.gui;

import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.presentation.DisplayEntity;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HologramDocumentTest {
    /** A Gloss hologram using only fields both plugins share. */
    static final String GLOSS = """
            {
              "schemaVersion": 3,
              "revision": 2,
              "anchor": {"world": "world", "position": [10.5, 65, -4.5]},
              "lines": ["&6Welcome &f{{ viewer.name }}", {"item": {"type": "item", "item": "minecraft:diamond"}, "scale": 0.5}, "&7Click me"],
              "actions": [{"type": "message", "message": "&aHi"}],
              "style": {"billboard": "center", "backgroundArgb": "#40000000", "scaleY": 1},
              "viewDistance": 32,
              "refreshTicks": 20
            }
            """;

    private static List<String> problems(String json) { return HologramDocument.parse("town:sign", json).problems(); }

    @Test void aGlossHologramLoadsUnchanged() {
        HologramDocument.Result result = HologramDocument.parse("town:sign", GLOSS);
        assertEquals(List.of(), result.problems());
        HologramDocument hologram = result.document().orElseThrow();
        assertEquals("world", hologram.world());
        assertEquals(3, hologram.lines().size());
        assertEquals(32, hologram.viewDistance());
        assertEquals(20, hologram.contentTicks());
        assertEquals(0x40000000, hologram.style().background());
        assertEquals(DisplayEntity.Billboard.CENTER, hologram.style().billboard());
        assertTrue(hologram.style().seeThrough(), "Gloss holograms are seen through blocks by default");
    }

    @Test void textRowsShareOneDisplayAndObjectsTakeBlankRows() {
        HologramDocument hologram = HologramDocument.parse("town:sign", GLOSS).document().orElseThrow();
        GuiExpression.Scope scope = (String name) -> name.equals("viewer.name") ? "Alex" : null;
        HologramRender.Render render = HologramRender.build(hologram, hologram.lines(), hologram.style(), scope, UnaryOperator.identity(), 10.5, 65, -4.5);
        assertEquals(4, render.rows(), "one text row, two blank rows for a 0.5 block item, one text row");
        DisplayEntity.Text text = (DisplayEntity.Text) render.entities().get("text").content();
        assertEquals("Welcome Alex\n \n \nClick me", RichText.plain(text.text()));
        DisplayEntity item = render.entities().get("line:0");
        assertInstanceOf(DisplayEntity.Item.class, item.content());
        assertEquals(65 + 0.25 * 4 - 0.25 * 2, item.y(), 1.0E-9, "the item sits in the middle of its two rows");
        HologramRender.Box box = render.boxes().get("hitbox");
        assertNotNull(box);
        assertEquals(1.2, box.width());
        assertTrue(box.hit(new double[]{10.5, 65.5, -8}, new double[]{0, 0, 1}, 8, 0.35), "looking at the hologram hits its box");
        assertFalse(box.hit(new double[]{10.5, 65.5, -8}, new double[]{0, 0, -1}, 8, 0.35), "looking away misses");
        assertFalse(box.hit(new double[]{10.5, 65.5, -20}, new double[]{0, 0, 1}, 8, 0.35), "out of reach misses");
    }

    @Test void textLinesAreFilledWithTheViewersPlaceholdersBeforeColouring() {
        HologramDocument hologram = HologramDocument.parse("town:sign", GLOSS.replace("\"&7Click me\"", "\"Online: %server_online%\"")).document().orElseThrow();
        GuiExpression.Scope scope = (String name) -> name.equals("viewer.name") ? "Alex" : null;
        HologramRender.Render render = HologramRender.build(hologram, hologram.lines(), hologram.style(), scope,
                (String text) -> text.replace("%server_online%", "&a12"), 0, 0, 0);
        DisplayEntity.Text text = (DisplayEntity.Text) render.entities().get("text").content();
        assertTrue(text.text().endsWith("§a12"), text.text());
        assertEquals("Welcome Alex\n \n \nOnline: 12", RichText.plain(text.text()));
    }

    @Test void perLineBoxesAndHiddenLines() {
        String json = GLOSS.replace("\"&7Click me\"", "{\"text\": \"secret\", \"show\": \"viewer.level > 5\"}")
                .replace("\"viewDistance\": 32", "\"hitbox\": {\"perLine\": true, \"height\": 0.25}");
        HologramDocument hologram = HologramDocument.parse("town:sign", json).document().orElseThrow();
        HologramRender.Render render = HologramRender.build(hologram, hologram.lines(), hologram.style(), (String name) -> name.equals("viewer.level") ? 1.0 : "Alex", UnaryOperator.identity(), 0, 0, 0);
        assertEquals(4, render.rows(), "a hidden text line keeps its row while object lines exist");
        assertEquals(4, render.boxes().size());
    }

    @Test void pagesVariantsAndPageNavigationParse() {
        String json = """
                {"schemaVersion": 3, "revision": 1, "anchor": {"world": "world", "position": [0, 64, 0]},
                 "pages": [{"id": "one", "lines": ["first"]}, {"id": "two", "lines": ["second"], "show": "viewer.level >= 1"}],
                 "actions": [{"type": "navigate", "mode": "page", "target": "next", "trigger": "right_click"}],
                 "variants": [{"id": "night", "priority": 5, "when": "time.hour >= 20", "presentation": {"lines": ["&8Closed"]}}]}
                """;
        HologramDocument.Result result = HologramDocument.parse("town:board", json);
        assertEquals(List.of(), result.problems());
        assertEquals(2, result.document().orElseThrow().pages().size());
        assertEquals("night", result.document().orElseThrow().orderedVariants().getFirst().id());
    }

    @Test void unsupportedFieldsAreRefusedByName() {
        assertEquals(List.of("$.schemaVersion: hologram schemaVersion 4 is not supported; Animus reads version 3"), problems(GLOSS.replace("\"schemaVersion\": 3", "\"schemaVersion\": 4")));
        assertEquals(List.of("$.box.enabled: box panels are not available in Animus; use style.backgroundArgb"), problems(GLOSS.replace("\"viewDistance\": 32", "\"box\": {\"enabled\": true}")));
        assertEquals(List.of("$.particleLayers: particle layers are not available in Animus"), problems(GLOSS.replace("\"viewDistance\": 32", "\"particleLayers\": [{}]")));
        assertEquals(List.of("$.actions[0].mode: holograms only move between pages; navigate mode 'push' needs a menu or window"),
                problems(GLOSS.replace("{\"type\": \"message\", \"message\": \"&aHi\"}", "{\"type\": \"navigate\", \"mode\": \"push\", \"target\": \"x\"}")));
        assertEquals(List.of("$.actions[0].type: action 'camera' is not available in Animus"), problems(GLOSS.replace("{\"type\": \"message\", \"message\": \"&aHi\"}", "{\"type\": \"camera\"}")));
        assertEquals(List.of("$.pages: declare lines or pages, not both"), problems(GLOSS.replace("\"viewDistance\": 32", "\"pages\": [{\"id\": \"a\", \"lines\": [\"x\"]}]")));
        assertEquals(List.of("$.lines[1].item.type: only item icons draw on hologram lines in Animus"), problems(GLOSS.replace("\"type\": \"item\", \"item\"", "\"type\": \"custom\", \"item\"")));
    }

    @Test void textAnimationsCycleOnOneWallClock() {
        TextAnimation.Result result = TextAnimation.parse("town:blink", """
                {"schemaVersion": 1, "revision": 1, "mode": "ascend_descend", "frameIntervalMs": 100, "frames": ["a", "b", "{{ viewer.name }}"]}
                """);
        assertEquals(List.of(), result.problems());
        TextAnimation animation = result.animation().orElseThrow();
        assertEquals(List.of(0, 1, 2, 2, 1, 0, 0), List.of(animation.frame(0), animation.frame(100), animation.frame(200), animation.frame(300),
                animation.frame(400), animation.frame(500), animation.frame(600)));
        assertEquals("Alex", animation.render(250, (String name) -> "Alex"));
        assertEquals(List.of("$.frames[0]: a frame cannot show another animation"),
                TextAnimation.parse("town:x", "{\"schemaVersion\":1,\"mode\":\"ascend\",\"frameIntervalMs\":5,\"frames\":[\"|animation.y|\"]}").problems());
        assertEquals(List.of("$.mode: expected one of [ascend, ascend_descend, descend, random]"),
                TextAnimation.parse("town:x", "{\"schemaVersion\":1,\"mode\":\"bounce\",\"frameIntervalMs\":5,\"frames\":[\"a\"]}").problems());
    }

    @Test void animationTokensRenderFromTheScopeAndAreRefusedInChestWindows() {
        GuiTemplate text = GuiTemplate.parse("&b|animation.blink| &f{{ viewer.name }}", HologramDocument.ROOTS);
        assertEquals(List.of("blink"), text.animations());
        Map<String, Object> scope = Map.of("animation.blink", "*", "viewer.name", "Alex");
        assertEquals("&b* &fAlex", text.render(scope::get));
        assertTrue(GuiDocument.parse("town:menu", GuiDocumentTest.GLOSS.replace("&8Shop", "|animation.blink|")).problems().getFirst()
                .contains("text animations work in holograms and holographic menus"));
    }

    @Test void glossTextAnimationFunctionsGiveGlossResults() {
        Map<String, String> cases = Map.of(
                "marquee('GLOSS', 3, 0)", "GLO", "marquee('AB', 2, 2)", "  ", "typewriter('ABC', 2, 1)", "AB", "typewriter('ABC', 4, 1)", "ABC",
                "flash('a', 'b', 1)", "b", "odometer(0, 100, 0.5, 4)", "0050", "wave('ab', ['&a', '&b'], 0)", "&aa&bb&a",
                "wipe('ABCD', 6)", "AB  ", "timeline([['one', 1], ['two', 2]], 2.5)", "two", "scanner('abc', '&7', '&f', 1)", "&7a&fb&7c");
        for (Map.Entry<String, String> entry : cases.entrySet())
            assertEquals(entry.getValue(), GuiExpression.text(GuiExpression.parse(entry.getKey(), java.util.Set.of()).evaluate((String name) -> null)), entry.getKey());
        assertThrows(GuiExpression.Invalid.class, () -> GuiExpression.parse("marquee('&aX', 3, 0)", java.util.Set.of()).evaluate((String name) -> null));
    }
}
