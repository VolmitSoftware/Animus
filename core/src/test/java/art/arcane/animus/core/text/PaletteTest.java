package art.arcane.animus.core.text;

import art.arcane.animus.core.behavior.builtin.BehaviorPlatform.ChoiceOption;
import art.arcane.animus.core.behavior.builtin.DialogueFormat;
import art.arcane.animus.core.config.ShopSettings;
import art.arcane.animus.core.hotload.HotloadDiff;
import art.arcane.animus.core.hotload.HotloadMessages;
import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.DebugWatch;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaletteTest {
    /** Dark purple, gold and aqua: the colours Animus used for its name, headers and keys before the pink palette. */
    private static final List<Integer> OLD_BRAND = List.of(0xAA00AA, 0xFFAA00, 0x55FFFF);

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

    @Test void everyReplyGetsTheOnePrefixAndAnOldAreaLeadFolds() {
        String branded = Palette.reply("Animus navigation: no route to &atown");
        assertEquals("[Animus]: Navigation: no route to town", RichText.plain(branded));
        assertTrue(branded.endsWith("Navigation: no route to &atown"), "the rest is left as written: " + branded);
        assertEquals("[Animus]: Animus is not running", RichText.plain(Palette.reply("Animus is not running")));
        assertEquals("[Animus]: oops", RichText.plain(Palette.reply("Animus: oops")));
    }

    @Test void replyPrefixesOtherPlainMessages() {
        assertEquals("[Animus]: Selected guard", RichText.plain(Palette.reply("Selected guard")));
        assertTrue(Palette.reply("Selected guard").endsWith("Selected guard"), "the text is left as written");
        assertEquals("[Animus]: Animusless", RichText.plain(Palette.reply("Animusless")));
        assertEquals("§cAnimus refused", Palette.reply("§cAnimus refused"));
        assertEquals(OptionalInt.of(0xFF5555), RichText.sectionRuns(Palette.error("No such NPC")).getLast().rgb(), "refusals are semantic red");
        assertEquals(OptionalInt.of(0x55FF55), RichText.sectionRuns(Palette.success("Saved")).getLast().rgb(), "changes are semantic green");
        assertNull(Palette.reply(null));
    }

    @Test void brandTemplatesUsePinkAndNoOldBrandColour() {
        String hotload = HotloadMessages.applied("config.toml", List.of(new HotloadDiff.Change("statusMessage", null, null)), (String key) -> false).getFirst();
        String watch = RichText.colorize(DebugWatch.line(new AnimusMetrics(() -> 0L, () -> 1L).snapshot()));
        String speech = DialogueFormat.speech("Greeter", "Hello");
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
    }
}
