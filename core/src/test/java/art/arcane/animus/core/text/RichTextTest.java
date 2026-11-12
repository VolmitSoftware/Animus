package art.arcane.animus.core.text;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RichTextTest {
    @Test void ampersandHexMiniMessageAndSectionCodesAllColorize() {
        assertEquals("§aHi", RichText.colorize("&aHi"));
        assertEquals("§x§f§f§0§0§a§aHi", RichText.colorize("&#FF00aaHi"));
        assertEquals("§x§1§2§3§4§5§6Hi", RichText.colorize("&x&1&2&3&4&5&6Hi"));
        assertEquals("§x§1§2§3§4§5§6Hi", RichText.colorize("<#123456>Hi"));
        assertEquals("§c§lHi§r§c!", RichText.colorize("<red><bold>Hi</bold>!"));
        assertEquals("§6Gold", RichText.colorize("§6Gold"));
        assertEquals("Tom & Jerry <3", RichText.colorize("Tom & Jerry <3"));
    }

    @Test void glossBracketHexShorthandColorizes() {
        assertEquals("§x§f§f§0§0§0§0Red", RichText.colorize("[FF0000]Red"));
        assertEquals("a §x§1§2§3§4§5§6b", RichText.colorize("a [123456]b"));
        assertEquals("[12345] [GGGGGG] [1234567]", RichText.colorize("[12345] [GGGGGG] [1234567]"));
    }

    @Test void unicodeEscapesAndLiteralSymbolsSurvive() {
        assertEquals("§aok", RichText.colorize("\\u00a7aok"));
        assertEquals("§c❤ love", RichText.colorize("&c\\u2764 love"));
        assertEquals("§e★ ✔ » ⚔", RichText.colorize("&e★ ✔ » ⚔"));
        assertEquals("a\\b", RichText.colorize("a\\b"));
    }

    @Test void runsCarryColourAndStyleAndColourResetsStyle() {
        List<RichText.Run> runs = RichText.runs("&l&aBold green&#336699blue&othen");
        assertEquals(3, runs.size());
        assertEquals(new RichText.Run("Bold green", OptionalInt.of(0x55FF55), false, false, false, false, false), runs.get(0));
        assertEquals(new RichText.Run("blue", OptionalInt.of(0x336699), false, false, false, false, false), runs.get(1));
        assertEquals(new RichText.Run("then", OptionalInt.of(0x336699), false, true, false, false, false), runs.get(2));
        assertEquals(List.of(new RichText.Run("plain", OptionalInt.empty(), false, false, false, false, false)), RichText.runs("plain"));
        List<RichText.Run> styled = RichText.runs("&c&lHot");
        assertTrue(styled.getFirst().bold());
        assertEquals(OptionalInt.of(0xFF5555), styled.getFirst().rgb());
    }

    @Test void plainAndFormattedReadThroughEveryNotation() {
        assertEquals("Hi there", RichText.plain("<gold>Hi &#ffffffthere"));
        assertTrue(RichText.formatted("&#123456x"));
        assertTrue(RichText.formatted("<red>x"));
        assertFalse(RichText.formatted("Tom & Jerry"));
    }

    @Test void miniMessageGradientColoursEachLetter() {
        assertEquals("§x§f§f§0§0§0§0a§x§8§0§0§0§8§0b§x§0§0§0§0§f§fc§r", RichText.colorize("<gradient:#ff0000:#0000ff>abc</gradient>"));
        assertEquals("§x§f§f§5§5§5§5a§x§5§5§5§5§f§fb§r", RichText.colorize("<gradient:red:blue>ab</gradient>"));
        assertEquals("§x§0§0§0§0§0§0§la§x§f§f§f§f§f§f§lb§r after", RichText.colorize("<gradient:#000000:#ffffff><bold>ab</bold></gradient> after"));
        assertEquals("Charts & Clocks", RichText.plain("<gradient:#ff0000:#0000ff>Charts & Clocks</gradient>"));
        assertEquals("<gradient:#ff0000>x</gradient>", RichText.colorize("<gradient:#ff0000>x</gradient>"));
    }

    @Test void perLetterHexGradientNamesColourEveryLetter() {
        List<RichText.Run> runs = RichText.runs("&#1d976cT&#259e72h&#2ea577e &#36ac7cC");
        assertEquals("The C", RichText.plain("&#1d976cT&#259e72h&#2ea577e &#36ac7cC"));
        assertEquals(OptionalInt.of(0x1d976c), runs.getFirst().rgb());
        assertEquals(OptionalInt.of(0x36ac7c), runs.getLast().rgb());
    }

    @Test void colourNamesCodesAndHexResolve() {
        assertEquals(Optional.of("§a"), RichText.colourCode("green"));
        assertEquals(Optional.of("§c"), RichText.colourCode("c"));
        assertEquals(Optional.of("§x§a§b§c§d§e§f"), RichText.colourCode("#ABCDEF"));
        assertEquals(Optional.empty(), RichText.colourCode("bold"));
        assertEquals(Optional.empty(), RichText.colourCode("chartreuse"));
    }
}
