package art.arcane.animus.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** Replies name files from the Animus folder and fields as written, never a machine path or a schema path. */
class ReplyTextTest {
    @Test void anAbsolutePathBecomesThePathInsideTheAnimusFolder() {
        assertEquals("imports/inbox/nosuch.yml", ReplyText.plain("/Users/alex/server/plugins/Animus/imports/inbox/nosuch.yml"));
        assertEquals("Shop import: Not a dtlTraders data folder: imports/inbox/dock-shops",
                ReplyText.plain("Shop import: Not a dtlTraders data folder: /srv/mc/plugins/Animus/imports/inbox/dock-shops"));
        assertEquals("npcs/town/guard.json exceeds 16 bytes", ReplyText.plain("C:\\srv\\plugins\\Animus\\npcs\\town\\guard.json exceeds 16 bytes"));
        assertEquals("Cannot write board.json", ReplyText.plain("Cannot write /tmp/junit42/board.json"), "outside the Animus folder, the file name");
        assertEquals("cert.pem holds no PEM certificate", ReplyText.plain("/opt/mc/config/animus/cert.pem holds no PEM certificate"));
    }

    /** Shop buyer and owner lines and shop file problems read as written: slots from 1, nothing taken out. */
    @Test void shopLinesPassThroughUnchanged() {
        for (String line : java.util.List.of("Bakery is out of Bread: the chest is empty.", "You need 2 Emerald.", "Bakery is closed: Back at noon.",
                "Bakery sold 1 Bread to Steve for 2 Emerald", "Bakery bought 8 Wheat from Steve for 1 Emerald",
                art.arcane.animus.core.shop.ShopDocument.readable("$.pages[0].trade.0.book: a book shop's trade copies a written book"),
                "Slot 28 is outside a 3-row page (slots 1 to 27)"))
            assertEquals(line, ReplyText.plain(line));
        assertEquals("Slot 1 on page 1, book: a book shop's trade copies a written book",
                art.arcane.animus.core.shop.ShopDocument.readable("$.pages[0].trade.0.book: a book shop's trade copies a written book"));
    }

    @Test void commandsLinksAndFolderNamesStayAsTheyAre() {
        for (String kept : new String[]{"Next: /animus spawn guard", "Open https://docs.volmitsoftware.com/animus/02-commands-permissions#tools",
                "Cannot undo 1a2b3c4d: npcs/guard.json changed after it", "Put it in imports/inbox/ and run /animus import preview <file>",
                "Link: http://127.0.0.1:25621/editor/pair"})
            assertEquals(kept, ReplyText.plain(kept));
    }

    @Test void anExceptionsNameNeverReachesAReply() {
        assertEquals("Cannot read nosuch.schem: no schematic named nosuch.schem", ReplyText.plain("Cannot read nosuch.schem: IOException: no schematic named nosuch.schem"));
        assertEquals("unavailable: NPC runtime requires the owner thread", ReplyText.plain("unavailable: java.lang.IllegalStateException: NPC runtime requires the owner thread"));
        assertEquals("MineSkin could not sign the skin: it timed out", ReplyText.plain("MineSkin could not sign the skin: HttpTimeoutException"));
        assertEquals("Could not reach Mojang to look up Alex (it could not connect)", ReplyText.plain("Could not reach Mojang to look up Alex (ConnectException)"));
        assertEquals("Errors: 2", ReplyText.plain("Errors: 2"), "a word that only looks like one stays");
    }

    @Test void aSchemaPathReadsAsTheFieldAndARangeAsWords() {
        assertEquals("modules.animus:health.maximumHealth: must be from 1 to 2048", ReplyText.plain("$.modules.animus:health.maximumHealth: must be within 1.0..2048.0"));
        assertEquals("Cannot save guard: id: expected 1-64 lowercase letters", ReplyText.plain("Cannot save guard: $.id: expected 1-64 lowercase letters"));
        assertFalse(ReplyText.plain("$.modules.animus:mount.range: expected 1-32 blocks").contains("$."));
        assertEquals("speed must be from 0.5 to 10", ReplyText.plain("speed must be within 0.5..10.0"));
        assertEquals("active; position=1.5 64 -3.5; viewers=1", ReplyText.plain("active; position=Vec3[x=1.5, y=64.0, z=-3.5]; viewers=1"), "a point, not a Java record");
    }
}
