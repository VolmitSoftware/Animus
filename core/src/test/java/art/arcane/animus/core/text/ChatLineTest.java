package art.arcane.animus.core.text;

import art.arcane.animus.core.behavior.schema.RepositoryFiles;
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

    @Test void aTransactionCopiesItsFullIdAndUndoRunsTheUndoCommand() {
        ChatLine line = ChatLine.parse(Palette.success("Deleted guard; tx " + TX));
        assertEquals("[Animus]: Deleted guard; tx 0f1e2d3c [Undo]", plain(line.component()));
        assertEquals(List.of("copy_to_clipboard " + TX, "run_command /animus undo " + TX.substring(0, 8)), clicks(line.component()));
        assertEquals("[Animus]: Deleted guard; tx " + TX, RichText.plain(line.console()));
        assertEquals(List.of(TX), line.transactions());
        assertEquals("Deleted guard", line.lead(), "what the change was, for /animus undo");
    }

    @Test void withoutTransactionIdsOnlyUndoStays() {
        ChatLine line = ChatLine.parse(Palette.success("Deleted guard; tx " + TX)).withoutTransactionIds();
        assertEquals("[Animus]: Deleted guard [Undo]", plain(line.component()));
        assertEquals(List.of("run_command /animus undo " + TX.substring(0, 8)), clicks(line.component()));
        assertEquals("[Animus]: Deleted guard", RichText.plain(line.console()));
        assertEquals(List.of(TX), line.transactions(), "the change is still known");
        ChatLine middle = ChatLine.parse(Palette.success("Spawning guard; tx " + TX + "; it becomes active once its chunk loads")).withoutTransactionIds();
        assertEquals("[Animus]: Spawning guard [Undo]; it becomes active once its chunk loads", plain(middle.component()));
        assertEquals("Spawning guard [Undo]; it becomes active once its chunk loads", middle.visible().substring("[Animus]: ".length()));
    }

    @Test void aBuiltChangeLinePlacesTheIdAndUndoWhereAsked() {
        ChatLine made = ChatLine.success("Made guard").transaction(TX).run("Edit", "/animus tools", "Open the Animus menu").undo(TX);
        assertEquals("[Animus]: Made guard; tx 0f1e2d3c [Edit] [Undo]", plain(made.component()));
        assertEquals("[Animus]: Made guard [Edit] [Undo]", plain(made.withoutTransactionIds().component()));
        assertEquals("Made guard", made.lead());
        assertEquals(List.of("copy_to_clipboard " + TX, "run_command /animus tools", "run_command /animus undo 0f1e2d3c"), clicks(made.component()));
    }

    @Test void aRefusalIsALineThatStartsRed() {
        assertTrue(ChatLine.error("No NPC named ghost").refusal());
        assertTrue(ChatLine.parse(Palette.error("Disk full")).refusal());
        assertFalse(ChatLine.reply("Selected guard").refusal());
        assertFalse(ChatLine.success("Saved").refusal());
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

    @Test void aCommandInASentenceEndsAtItsLastValueAndTheSentenceStaysText() {
        ChatLine line = ChatLine.parse(Palette.error("No NPC named ghost; /animus list shows ids"));
        assertEquals("[Animus]: No NPC named ghost; /animus list shows ids", plain(line.component()));
        assertEquals(List.of("suggest_command /animus list"), clicks(line.component()));
        assertEquals("[Animus]: No NPC named ghost; /animus list shows ids", RichText.plain(line.console()));
        assertEquals(List.of("/animus list"), chips(line.component()));
        assertEquals(List.of("/animus list"), chips(ChatLine.parse(Palette.reply("Next: /animus list shows ids")).component()), "a Next: line reads the same");
        assertEquals(List.of("/animus spawn guard"), chips(ChatLine.parse(Palette.reply("Runtime guard: despawned; /animus spawn guard shows it")).component()));
        assertEquals(List.of("/animus gui list menu"), chips(ChatLine.parse(Palette.reply("No chest GUI named x; /animus gui list menu shows them")).component()));
        assertEquals(List.of("/animus gui"), chips(ChatLine.parse(Palette.reply("Ready. /animus gui opens the menu")).component()));
        assertEquals(List.of("/animus record stop"), chips(ChatLine.parse(Palette.reply("Already recording; /animus record stop or cancel first")).component()));
    }

    @Test void aCommandKeepsTheValuesItTakesAndEndsAtPunctuation() {
        assertEquals(List.of("/animus npc health guard killable on"), chips(ChatLine.parse(Palette.reply("guard: not killable; /animus npc health guard killable on makes it killable")).component()));
        assertEquals(List.of("/animus combat mob guard stop"), chips(ChatLine.parse(Palette.reply("guard now leads that mob; /animus combat mob guard stop lets it go")).component()));
        assertEquals(List.of("/animus npc disguise guard \"zombie setBaby true\""), chips(ChatLine.parse(Palette.reply("guard has no disguise; /animus npc disguise guard \"zombie setBaby true\" sets one")).component()));
        assertEquals(List.of("/animus tools wand"), chips(ChatLine.parse(Palette.reply("select one with the wand (/animus tools wand)")).component()));
        assertEquals(List.of("/animus reload"), chips(ChatLine.parse(Palette.reply("Fix the file, then /animus reload.")).component()));
        assertEquals(List.of("/animus help all"), chips(ChatLine.parse(Palette.reply("/animus help all lists every command")).component()));
        ChatLine colour = ChatLine.parse(RichText.colorize(Palette.reply("&7More: &f/animus debug network&7, &fperformance")));
        assertEquals(List.of("/animus debug network"), chips(colour.component()));
        assertFalse(ChatLine.parse(Palette.reply("Only GET /animus/v1/events upgrades")).interactive(), "a path under /animus is not a command");
    }

    @Test void aPlaceholderStaysInTheChipAndTheClickStopsBeforeIt() {
        ChatLine release = ChatLine.parse(Palette.reply("Next: read the behavior, then /animus import denizen release <behavior>"));
        assertEquals(List.of("suggest_command /animus import denizen release "), clicks(release.component()));
        assertEquals(List.of("/animus import denizen release <behavior>"), chips(release.component()));
        ChatLine build = ChatLine.parse(Palette.reply("No builds; /animus build start <npc> schematic=<file> starts one"));
        assertEquals(List.of("/animus build start <npc> schematic=<file>"), chips(build.component()));
        assertEquals(List.of("suggest_command /animus build start "), clicks(build.component()));
        ChatLine anchor = ChatLine.parse(Palette.reply("guard has no anchors; /animus npc anchor guard add <name> saves one where you stand"));
        assertEquals(List.of("/animus npc anchor guard add <name>"), chips(anchor.component()));
        assertEquals(List.of("suggest_command /animus npc anchor guard add "), clicks(anchor.component()));
        ChatLine assign = ChatLine.parse(Palette.reply("No NPC lists town; /animus groups assign town ids=<npc> adds one"));
        assertEquals(List.of("suggest_command /animus groups assign town ids="), clicks(assign.component()));
    }

    @Test void severalCommandsInOneLineEachGetTheirOwnChip() {
        ChatLine line = ChatLine.parse(Palette.reply("an apply was interrupted; /animus import citizens apply finishes it, /animus import citizens rollback undoes it"));
        assertEquals(List.of("/animus import citizens apply", "/animus import citizens rollback"), chips(line.component()));
        ChatLine next = ChatLine.parse(Palette.reply("Next: /animus import citizens review, then /animus import citizens apply"));
        assertEquals(List.of("suggest_command /animus import citizens review", "suggest_command /animus import citizens apply"), clicks(next.component()));
        ChatLine rollback = ChatLine.parse(Palette.reply("Next: /animus rollback 0f1e2d3c, then /animus import citizens rollback"));
        assertEquals(List.of("/animus rollback 0f1e2d3c", "/animus import citizens rollback"), chips(rollback.component()));
    }

    @Test void aLongIdInACommandShowsTwelveCharactersAndTheClickKeepsItWhole() {
        String id = "de14bc7b40fae6f816c27297122ab341732aa8b44952290cb2446a2cbff248ca";
        ChatLine line = ChatLine.parse(Palette.reply("Review the report, then /animus import apply " + id + " " + DocsLink.IMPORTS.url()));
        assertEquals("[Animus]: Review the report, then /animus import apply de14bc7b40fa [Docs]", plain(line.component()));
        assertEquals(List.of("suggest_command /animus import apply " + id, "open_url " + DocsLink.IMPORTS.url()), clicks(line.component()));
        assertEquals("[Animus]: Review the report, then /animus import apply " + id + " " + DocsLink.IMPORTS.url(), RichText.plain(line.console()));
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
            Path page = RepositoryFiles.wikiPage(link.page() + ".md");
            assumeTrue(Files.isRegularFile(page), "docs checkout not beside the repository");
            List<String> anchors = Files.readAllLines(page).stream().filter((String row) -> row.startsWith("#"))
                    .map((String heading) -> heading.replaceFirst("^#+\\s*", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 -]", "").strip().replace(' ', '-')).toList();
            assertTrue(anchors.contains(link.anchor()), link + " points to a missing heading: " + link.anchor());
        }
    }

    private static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }

    /** The text of each chip that puts a command in the chat box, in order. */
    private static List<String> chips(Component root) {
        List<String> found = new ArrayList<>();
        ClickEvent click = root.clickEvent();
        if (click != null && click.action() == ClickEvent.Action.SUGGEST_COMMAND) found.add(plain(root));
        for (Component child : root.children()) found.addAll(chips(child));
        return found;
    }

    private static List<String> clicks(Component root) {
        List<String> found = new ArrayList<>();
        ClickEvent click = root.clickEvent();
        if (click != null) found.add(click.action().toString() + " " + ((ClickEvent.Payload.Text) click.payload()).value());
        for (Component child : root.children()) found.addAll(clicks(child));
        return found;
    }
}
