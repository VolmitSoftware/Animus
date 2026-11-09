package art.arcane.animus.core.text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ChatLineTest {
    private static final String TX = "0f1e2d3c-4b5a-4968-8776-655443322110";
    private static final String LINK = "https://editor.example/#/pair/ANM1.abc";

    @Test void aLinkOpensAndCopiesForPlayersAndPrintsOnceForTheConsole() {
        ChatLine line = ChatLine.reply("Pairing link ready.").link("Open editor", LINK);
        assertTrue(line.interactive());
        assertEquals("[Animus]: Pairing link ready. [Open editor] [Click to copy]", plain(line.component()));
        assertEquals(List.of("open_url " + LINK, "copy_to_clipboard " + LINK), clicks(line.component()));
        assertEquals("[Animus]: Pairing link ready. " + LINK, RichText.plain(line.console()));
    }

    @Test void aTransactionCopiesItsFullIdAndSuggestsTheRollback() {
        ChatLine line = ChatLine.parse(Palette.success("Deleted guard; tx " + TX));
        assertEquals("[Animus]: Deleted guard; tx 0f1e2d3c [Undo]", plain(line.component()));
        assertEquals(List.of("copy_to_clipboard " + TX, "suggest_command /animus rollback " + TX.substring(0, 8)), clicks(line.component()));
        assertEquals("[Animus]: Deleted guard; tx " + TX, RichText.plain(line.console()));
    }

    @Test void parsedTextKeepsItsColourAcrossAChip() {
        Component line = ChatLine.parse(Palette.success("Saved; tx " + TX + " now")).component();
        List<Integer> green = new ArrayList<>();
        line.children().stream().filter((Component child) -> PlainTextComponentSerializer.plainText().serialize(child).contains("now"))
                .forEach((Component child) -> green.add(child.color().value()));
        assertEquals(List.of(0x55FF55), green);
    }

    @Test void wikiLinksBecomeADocsChipAndOtherLinksOpenAndCopy() {
        ChatLine docs = ChatLine.parse("See " + DocsLink.PAIRING.url() + ".");
        assertEquals("See [Docs].", plain(docs.component()));
        assertEquals(List.of("open_url " + DocsLink.PAIRING.url()), clicks(docs.component()));
        ChatLine other = ChatLine.parse("Report at https://example.com/x");
        assertEquals("Report at [Open] [Click to copy]", plain(other.component()));
        assertEquals("Report at https://example.com/x", RichText.plain(other.console()));
    }

    @Test void aLongIdShowsTwelveCharactersInlineAndCopiesTheFullId() {
        String id = "de14bc7b40fae6f816c27297122ab341732aa8b44952290cb2446a2cbff248ca";
        ChatLine line = ChatLine.parse(Palette.reply("Review imports/" + id + "/report.json"));
        assertEquals("[Animus]: Review imports/de14bc7b40fa/report.json", plain(line.component()));
        assertEquals(List.of("copy_to_clipboard " + id), clicks(line.component()));
        assertEquals("[Animus]: Review imports/" + id + "/report.json", RichText.plain(line.console()));
        ChatLine twice = ChatLine.parse(Palette.reply("Review " + id + ", then apply " + id));
        assertEquals("[Animus]: Review " + id + ", then apply " + id, RichText.plain(twice.console()), "a repeated value is printed each time");
    }

    @Test void aRunChipRunsItsCommandAndTheConsoleSeesTheCommand() {
        ChatLine line = ChatLine.reply("No NPCs yet").run("Setup", "/animus setup", "Show the first steps");
        assertEquals("[Animus]: No NPCs yet [Setup]", plain(line.component()));
        assertEquals(List.of("run_command /animus setup"), clicks(line.component()));
        assertEquals("[Animus]: No NPCs yet /animus setup", RichText.plain(line.console()));
    }

    @Test void commandsAfterNextBecomeSuggestionsAndAPlaceholderIsLeftForThePlayer() {
        ChatLine line = ChatLine.parse(Palette.reply("Next: /animus import citizens accept <categories|all>; accept commands lets them run"));
        assertEquals("[Animus]: Next: /animus import citizens accept <categories|all>; accept commands lets them run", plain(line.component()));
        assertEquals(List.of("suggest_command /animus import citizens accept "), clicks(line.component()));
        assertEquals("[Animus]: Next: /animus import citizens accept <categories|all>; accept commands lets them run", RichText.plain(line.console()));
        ChatLine two = ChatLine.parse(Palette.reply("Next: place NPCs with /animus tools; edit them with /animus editor pair"));
        assertEquals(List.of("suggest_command /animus tools", "suggest_command /animus editor pair"), clicks(two.component()));
        ChatLine list = ChatLine.parse(Palette.reply("Next: /animus import citizens review, then /animus import citizens accept shops,spawn"));
        assertEquals(List.of("suggest_command /animus import citizens review", "suggest_command /animus import citizens accept shops,spawn"), clicks(list.component()));
        ChatLine before = ChatLine.parse(Palette.reply("Run /animus status first"));
        assertFalse(before.interactive(), "only a Next: line makes its commands clickable");
    }

    @Test void aLineWithoutChipsIsPlainText() {
        ChatLine line = ChatLine.parse(Palette.reply("Selected guard"));
        assertFalse(line.interactive());
        assertEquals(Palette.reply("Selected guard"), line.console());
    }

    @Test void aCopyableValueShowsItselfAndCopiesItself() {
        ChatLine line = ChatLine.reply("Selected ").copyable("town/guard");
        assertEquals("[Animus]: Selected town/guard", plain(line.component()));
        assertEquals(List.of("copy_to_clipboard town/guard"), clicks(line.component()));
    }

    @Test void valuesAreNotReadAsColourCodes() {
        ChatLine line = ChatLine.parse(Palette.reply("Name a&lb <bold>c"));
        assertEquals("[Animus]: Name a&lb <bold>c", plain(line.component()));
    }

    /** Every {@code [Docs]} target is a heading on its wiki page, slugged as Wiki.js does. */
    @Test void everyDocsLinkNamesAHeadingOnItsPage() throws Exception {
        for (DocsLink link : DocsLink.values()) {
            Path page = wiki().resolve(link.page() + ".md");
            assumeTrue(Files.isRegularFile(page), "docs checkout not beside the repository");
            List<String> anchors = Files.readAllLines(page).stream().filter((String row) -> row.startsWith("#"))
                    .map((String heading) -> heading.replaceFirst("^#+\\s*", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 -]", "").strip().replace(' ', '-')).toList();
            assertTrue(anchors.contains(link.anchor()), link + " points to a missing heading: " + link.anchor());
        }
    }

    private static Path wiki() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle"))) current = current.getParent();
        return current.resolveSibling("docs").resolve("animus");
    }

    private static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }

    private static List<String> clicks(Component root) {
        List<String> found = new ArrayList<>();
        ClickEvent click = root.clickEvent();
        if (click != null) found.add(click.action().toString() + " " + ((ClickEvent.Payload.Text) click.payload()).value());
        for (Component child : root.children()) found.addAll(clicks(child));
        return found;
    }
}
