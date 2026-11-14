package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Replies by menu mode: Simple players see [Undo] without the transaction id, and every player's changes are remembered for undo. */
class ReplyActorTest {
    private static final String TX = "0f1e2d3c-4b5a-4968-8776-655443322110";

    /** Keeps what reached the platform: plain tells and chip lines apart. */
    private static final class Platform extends AnimusDirectorTest.Actor {
        final List<String> told = new ArrayList<>();
        final List<ChatLine> rich = new ArrayList<>();
        final List<CommandSound> sounds = new ArrayList<>();
        Platform(boolean player) { super(player, AnimusDirector.ADMIN); }
        @Override public void tell(String message) { told.add(message); }
        @Override public void rich(ChatLine line) { rich.add(line); }
        @Override public void play(CommandSound sound) { sounds.add(sound); }
    }

    @Test void aSimplePlayerSeesUndoWithoutTheId() {
        Platform platform = new Platform(true);
        RecentChanges changes = new RecentChanges();
        new ReplyActor(platform, true, changes).tell(Palette.success("Saved guard: name; tx " + TX));
        assertTrue(platform.told.isEmpty(), platform.told.toString());
        assertEquals("[Animus]: Saved guard: name [Undo]", platform.rich.getFirst().visible());
        assertEquals(Optional.of(new RecentChanges.Change(TX, "Saved guard: name", "")), changes.latest(platform.player().orElseThrow()));
    }

    @Test void anAdvancedPlayerAndTheConsoleKeepTheIdAndOnlyPlayersRememberChanges() {
        Platform advanced = new Platform(true);
        RecentChanges changes = new RecentChanges();
        String reply = Palette.success("Deleted guard; tx " + TX);
        new ReplyActor(advanced, false, changes).tell(reply);
        assertEquals(List.of(reply), advanced.told, "the platform's own tell, unchanged");
        assertEquals(TX, changes.latest(advanced.player().orElseThrow()).orElseThrow().transaction());
        Platform console = new Platform(false);
        new ReplyActor(console, false, changes).tell(reply);
        assertEquals(List.of(reply), console.told);
        ChatLine built = ChatLine.success("Made guard").transaction(TX).undo(TX);
        new ReplyActor(advanced, false, changes).rich(built);
        assertEquals("[Animus]: Made guard; tx 0f1e2d3c [Undo]", advanced.rich.getLast().visible());
    }

    @Test void aChangeKeepsTheNodeItsCommandNeededExceptTheMenuNode() {
        Platform platform = new Platform(true);
        RecentChanges changes = new RecentChanges();
        ReplyActor command = new ReplyActor(platform, false, changes);
        command.needs(PermissionNodes.SHOPS);
        command.tell(Palette.success("Saved bakery; tx " + TX));
        assertEquals(PermissionNodes.SHOPS, changes.latest(platform.player().orElseThrow()).orElseThrow().node());
        ReplyActor capture = new ReplyActor(platform, false, changes);
        capture.needs(PermissionNodes.TOOLS);
        capture.tell(Palette.success("Saved route market; tx " + TX));
        assertEquals("", changes.latest(platform.player().orElseThrow()).orElseThrow().node(), "a menu command's change needs the node for what it wrote");
    }

    @Test void aChipLineFollowsTheModeToo() {
        Platform platform = new Platform(true);
        new ReplyActor(platform, true, new RecentChanges()).rich(ChatLine.success("Made guard").transaction(TX).undo(TX));
        assertEquals("[Animus]: Made guard [Undo]", platform.rich.getFirst().visible());
    }

    @Test void aLineWithoutAChangeIsPassedOnAsItIs() {
        Platform platform = new Platform(true);
        RecentChanges changes = new RecentChanges();
        new ReplyActor(platform, true, changes).tell("Selected guard");
        assertEquals(List.of("Selected guard"), platform.told);
        assertTrue(changes.latest(platform.player().orElseThrow()).isEmpty());
    }

    @Test void aMenuReplyIsKeptForUndoAndOnlyARefusalPlaysASound() {
        Platform platform = new Platform(true);
        RecentChanges changes = new RecentChanges();
        ReplyActor.menu(platform, true, changes).tell(Palette.success("Deleted guard; tx " + TX));
        assertEquals("[Animus]: Deleted guard [Undo]", platform.rich.getFirst().visible());
        assertEquals(List.of(), platform.sounds, "the click already ticked; no success ding on top");
        assertEquals(Optional.of(new RecentChanges.Change(TX, "Deleted guard", "")), AnimusCommandTree.undoing(platform, changes, ""), "/animus undo finds it; the menu leaves the node to what the change wrote");
        Platform refused = new Platform(true);
        CommandActor answer = ReplyActor.menu(refused, false, changes);
        answer.tell(Palette.error("No permission: animus.npc.create; ask an operator to grant it"));
        answer.tell("Selected guard");
        assertEquals(List.of(CommandSound.FAILURE), refused.sounds, "the first answer decides, once");
        assertTrue(changes.latest(refused.player().orElseThrow()).isEmpty());
    }
}
