package art.arcane.animus.core.text;

import art.arcane.animus.core.AnimusSplash;
import art.arcane.animus.core.Platform;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.ChoiceOption;
import art.arcane.animus.core.behavior.builtin.DialogueFormat;
import art.arcane.animus.core.config.ShopSettings;
import art.arcane.animus.core.hotload.HotloadDiff;
import art.arcane.animus.core.hotload.HotloadMessages;
import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.DebugWatch;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PaletteTest {
    /** Dark purple, gold and aqua: the colours Animus used for its name, headers and keys before the pink palette. */
    private static final List<Integer> OLD_BRAND = List.of(0xAA00AA, 0xFFAA00, 0x55FFFF);

    @TempDir Path directory;

    @Test void pinksAreHexAndGreysKeepVanillaCodes() {
        assertEquals("&#FF5FA8", Palette.PINK.code());
        assertEquals("&#E0408C", Palette.PINK_DEEP.code());
        assertEquals("&#FFB3D6", Palette.PINK_LIGHT.code());
        assertEquals("&7", Palette.GREY.code());
        assertEquals("&8", Palette.DARK_GREY.code());
        assertEquals("&0", Palette.BLACK.code());
        assertEquals(OptionalInt.of(0xFF5FA8), RichText.runs(Palette.PINK + "Animus").getFirst().rgb());
    }

    @Test void prefixReadsAnimusInPinkBetweenDarkGreyBrackets() {
        List<RichText.Run> runs = RichText.runs(Palette.PREFIX);
        assertEquals("[Animus]: ", RichText.plain(Palette.PREFIX));
        assertEquals(List.of("[", "Animus", "]", ": "), runs.stream().map(RichText.Run::text).toList());
        assertEquals(List.of(0x555555, 0xFF5FA8, 0x555555, 0xAAAAAA), runs.stream().map((RichText.Run run) -> run.rgb().getAsInt()).toList());
    }

    @Test void legacyFallbackMapsEachPinkToItsDeclaredCode() {
        assertEquals("§dA§5B§dC§7D", RichText.legacy(RichText.colorize(Palette.PINK + "A" + Palette.PINK_DEEP + "B" + Palette.PINK_LIGHT + "C&7D")));
        assertEquals("§cred", RichText.legacy(RichText.colorize("&#FE5050red")), "an unknown hex falls back to the nearest vanilla colour");
        assertEquals('d', Palette.legacyOf(0xFF5FA8), "the nearest vanilla colour to the brand pink is red, so the declared fallback wins");
        assertEquals("plain", RichText.legacy("plain"));
    }

    @Test void replyColoursOnlyTheBrandLead() {
        String branded = Palette.reply("Animus navigation: no route to &atown");
        List<RichText.Run> runs = RichText.sectionRuns(branded);
        assertEquals("Animus navigation", runs.get(0).text());
        assertEquals(OptionalInt.of(0xFF5FA8), runs.get(0).rgb());
        assertEquals(":", runs.get(1).text());
        assertEquals(OptionalInt.of(0x555555), runs.get(1).rgb());
        assertTrue(branded.endsWith(" no route to &atown"), "the rest is left as written: " + branded);
        assertEquals("Animus navigation: no route to &atown", String.join("", runs.stream().map(RichText.Run::text).toList()));

        String running = Palette.reply("Animus is not running.");
        assertEquals("Animus", RichText.sectionRuns(running).getFirst().text());
        assertEquals("Animus is not running.", RichText.plain(running));
        assertEquals("Animus 1.0 | BUKKIT | Minecraft 26.3 | Hello: world", RichText.plain(Palette.reply("Animus 1.0 | BUKKIT | Minecraft 26.3 | Hello: world")));
        assertEquals("Animus", RichText.sectionRuns(Palette.reply("Animus 1.0 | BUKKIT | Minecraft 26.3 | Hello: world")).getFirst().text());
    }

    @Test void replyLeavesOtherMessagesAlone() {
        assertEquals("Selected guard", Palette.reply("Selected guard"));
        assertEquals("Animusless", Palette.reply("Animusless"));
        assertEquals("§cAnimus refused", Palette.reply("§cAnimus refused"));
        assertNull(Palette.reply(null));
        assertEquals("§dAnimus§8:§7 oops", RichText.legacy(Palette.reply("Animus: oops")));
    }

    @Test void brandTemplatesUsePinkAndNoOldBrandColour() {
        String hotload = HotloadMessages.applied("config.toml", List.of(new HotloadDiff.Change("statusMessage", null, null)), (String key) -> false).getFirst();
        String watch = RichText.colorize(DebugWatch.line(new AnimusMetrics(() -> 0L, () -> 1L).snapshot()));
        String speech = DialogueFormat.speech("Greeter", "Hello");
        String splash = AnimusSplash.colored(AnimusSplash.render(new RuntimeEnvironment(Platform.BUKKIT, "26.3", directory), "Example", true));
        String buttons = String.join("", DialogueFormat.buttons("animus", "t".repeat(32), List.of(new ChoiceOption("rumours", "Rumours"), new ChoiceOption("quests", "Quests"))).stream().map((art.arcane.animus.core.text.TextPart part) -> part.text()).toList());
        String shop = RichText.colorize(String.join("", ShopSettings.DEFAULT_MESSAGES.values()));
        for (String text : List.of(hotload, watch, speech, buttons, shop))
            for (RichText.Run run : RichText.sectionRuns(text))
                assertFalse(run.rgb().isPresent() && OLD_BRAND.contains(run.rgb().getAsInt()), "old brand colour left on \"" + run.text() + "\" in " + text);
        String pink = RichText.colorize(Palette.PINK.code());
        assertTrue(hotload.contains(pink + "Animus"), hotload);
        assertTrue(watch.startsWith(pink + "Animus"), watch);
        assertTrue(speech.contains(pink + "Greeter"), speech);
        assertTrue(buttons.contains(pink + "Rumours") && buttons.contains(RichText.colorize(Palette.PINK_LIGHT.code()) + "Quests"), buttons);
        assertTrue(splash.contains("§d█") && !splash.contains("§6") && !splash.contains("§x"), "the console splash uses legacy pink, no gold and no hex");
        assertTrue(splash.contains("§aREADY"), "READY stays green");
    }
}
