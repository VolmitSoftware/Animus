package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** A page past the end of a paged list or of help says it shows the last page. */
class ListingPagesTest {
    private static final class Reader extends AnimusDirectorTest.Actor {
        final List<DirectorMiniMenu.ContentMenu> menus = new ArrayList<>();
        Reader() { super(true, AnimusDirector.ADMIN); }
        @Override public void menu(DirectorMiniMenu.ContentMenu menu) { menus.add(menu); }
    }

    @Test void aPagePastTheEndOfAListSaysItShowsTheLastPage() {
        List<String> rows = IntStream.range(0, 30).mapToObj((int index) -> "npc" + index + " | Npc " + index + " | revision 1").toList();
        Reader reader = new Reader();
        CommandGroup.listing(reader, "NPCs (30)", "/animus list", rows, 999, "No NPCs");
        assertEquals(1, reader.menus.size(), "the last page still shows");
        int pages = reader.menus.getFirst().page().pages();
        assertEquals(List.of("[Animus]: There is no page 999; this is the last page, " + pages), reader.lines);
        reader.lines.clear();
        CommandGroup.listing(reader, "NPCs (30)", "/animus list", rows, pages, "No NPCs");
        assertTrue(reader.lines.isEmpty(), "a page that exists needs no note: " + reader.lines);
    }

    @Test void aHelpPagePastTheEndSaysItShowsTheLastPage() {
        AnimusDirector real = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        AnimusDirectorTest.Actor admin = new AnimusDirectorTest.Actor(true, AnimusDirector.ADMIN);
        real.execute(admin, new String[]{"help", "999"});
        int pages = AnimusHelp.pageCount(admin.pages.getLast());
        assertEquals(List.of("[Animus]: There is no page 999; this is the last page, " + pages), admin.lines);
    }
}
