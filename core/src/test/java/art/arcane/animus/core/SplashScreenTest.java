package art.arcane.animus.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SplashScreenTest {
    private static final SplashScreen.Details READY = new SplashScreen.Details("0.1.0", "Paper 26.3-12-abc1234", true, 25, "2026-11-07");

    @Test void colorizeSwitchesOnlyWhenTheGlyphKindChanges() {
        assertEquals("[F]██[E]╗ [F]█[E]╔═╝", SplashScreen.colorize("██╗ █╔═╝", "[F]", "[E]"));
        assertEquals("  ", SplashScreen.colorize("  ", "[F]", "[E]"));
    }

    @Test void theArtRowsShareOneWidth() {
        for (String row : SplashScreen.ART) assertEquals(SplashScreen.ART.getFirst().length(), row.length(), row);
    }

    @Test void theColumnNamesVersionEditorStatusAndSupport() {
        List<String> lines = SplashScreen.lines(READY, SplashScreen.Colors.PLAIN);
        assertEquals(6, lines.size());
        assertEquals(SplashScreen.ART.getFirst(), lines.getFirst());
        assertTrue(lines.get(1).endsWith("   Animus, Virtual NPCs, behaviors & shops [0.1 RELEASE]"), lines.get(1));
        assertTrue(lines.get(2).endsWith("   Version: 0.1.0"), lines.get(2));
        assertTrue(lines.get(3).endsWith("   By: Volmit Software (Arcane Arts) | Web Editor: https://animus-editor.web.app | Startup: READY"), lines.get(3));
        assertTrue(lines.get(4).endsWith("   Server: Paper 26.3-12-abc1234 | MC Support: " + BuildIdentity.MINECRAFT_VERSIONS.getFirst() + " - "
                + BuildIdentity.MINECRAFT_VERSIONS.getLast()), lines.get(4));
        assertTrue(lines.get(5).endsWith("   Java: 25 | Date: 2026-11-07"), lines.get(5));
        assertTrue(SplashScreen.render(new SplashScreen.Details("0.1.0", "Fabric 26.3", false, 25, "2026-11-07"), SplashScreen.Colors.PLAIN)
                .contains("| Startup: DEGRADED\n"));
    }

    @Test void miniMessageTagsUseThePinkPaletteAndGreenOnlyForReady() {
        String banner = SplashScreen.render(READY, SplashScreen.Colors.MINIMESSAGE);
        assertTrue(banner.contains("<#b0185f>█"), "deep pink fill");
        assertTrue(banner.contains("<#ff4fa3>╗"), "bright pink edge");
        assertTrue(banner.contains("<#55ff55>READY"));
        assertFalse(banner.contains("<#ff5555>"), "red is for a degraded start");
        assertTrue(SplashScreen.render(new SplashScreen.Details("0.1.0", "Paper", false, 25, "2026-11-07"), SplashScreen.Colors.MINIMESSAGE)
                .contains("<#ff5555>DEGRADED"));
        assertTrue(SplashScreen.render(new SplashScreen.Details("0.1.0", "Odd <red>build", true, 25, "2026-11-07"), SplashScreen.Colors.MINIMESSAGE)
                .contains("Odd \\<red>build"), "server text is never read as a tag");
    }

    @Test void ansiLinesUseTrueColourAndResetAtTheEnd() {
        for (String line : SplashScreen.lines(READY, SplashScreen.Colors.ANSI)) {
            assertTrue(line.stripLeading().startsWith("\u001b[38;2;"), line);
            assertTrue(line.endsWith("\u001b[0m"), line);
        }
        assertTrue(SplashScreen.render(READY, SplashScreen.Colors.ANSI).contains("\u001b[38;2;255;79;163m╗"));
        assertFalse(String.join("", SplashScreen.lines(READY, SplashScreen.Colors.PLAIN)).contains("\u001b"));
        assertFalse(String.join("", SplashScreen.lines(READY, SplashScreen.Colors.PLAIN)).contains("<#"));
    }

    @Test void onlyAnInteractiveTerminalWithoutNoColorGetsAnsi() {
        assertEquals(SplashScreen.Colors.ANSI, SplashScreen.Colors.forTerminal(true, null));
        assertEquals(SplashScreen.Colors.ANSI, SplashScreen.Colors.forTerminal(true, ""));
        assertEquals(SplashScreen.Colors.PLAIN, SplashScreen.Colors.forTerminal(true, "1"));
        assertEquals(SplashScreen.Colors.PLAIN, SplashScreen.Colors.forTerminal(false, null));
    }
}
