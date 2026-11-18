package art.arcane.animus.core.command;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandActorTest {
    @Test void aFullFileQueueNamesTheSettingAndTellsScriptsToSendAgain() {
        assertTrue(CommandActor.BUSY.contains("[files] maximumOperations"), CommandActor.BUSY);
        assertTrue(CommandActor.BUSY.endsWith("send it again"), CommandActor.BUSY);
    }

    @Test void aFailureReadsAsASentenceWithoutPathsOrJavaNames() {
        assertEquals("No file imports/inbox/nosuch.yml", CommandGroup.reason(new java.nio.file.NoSuchFileException("/srv/mc/plugins/Animus/imports/inbox/nosuch.yml")));
        assertEquals("Animus may not read or write npcs/guard.json; check the folder's permissions",
                CommandGroup.reason(new java.nio.file.AccessDeniedException("/srv/mc/plugins/Animus/npcs/guard.json")));
        assertEquals("That failed; the server log has the cause", CommandGroup.reason(new IllegalStateException()));
        assertEquals("speed: must be from 1 to 10", CommandGroup.reason(new IllegalArgumentException("$.speed: must be within 1.0..10.0")));
    }

    @Test void aPageSendsItsLinesAndPushesNoBlankLinesInFrontOfThem() {
        List<String> sent = new ArrayList<>();
        AnimusDirectorTest.Actor player = new AnimusDirectorTest.Actor(true, AnimusDirector.ADMIN) {
            @Override public void miniMessage(String line) { sent.add(line); }
            @Override public void plain(String line) { sent.add("plain:" + line); }
        };
        player.page(List.of("<#FF5FA8>banner</#FF5FA8>", "", "  ", "<#AAAAAA>row</#AAAAAA>"));
        assertEquals(List.of("<#FF5FA8>banner</#FF5FA8>", "<#AAAAAA>row</#AAAAAA>"), sent, "each line once, no blank line before or between");
        ReplyActor replies = new ReplyActor(player, false, new RecentChanges());
        sent.clear();
        replies.page(List.of("a", "b"));
        assertEquals(List.of("a", "b"), sent, "the command's actor passes the page through unchanged");
    }
}
