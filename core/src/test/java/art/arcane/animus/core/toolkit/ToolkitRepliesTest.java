package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.command.RecentChanges;
import art.arcane.animus.core.gui.GuiModel.Click;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.Palette;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Menu replies answer as command replies do: Simple hides the change id and keeps [Undo], and each change is kept for
 * {@code /animus undo} by the player who made it. The click ticks quietly; only a refusal adds the failure tone.
 */
class ToolkitRepliesTest extends ToolkitHarness {
    static final String SUCCESS = "minecraft:block.respawn_anchor.charge@0.125", FAILURE = "minecraft:block.beacon.deactivate@0.2";
    static final String TX = "0f1e2d3c-4b5a-4968-8776-655443322110";

    @BeforeEach void setUp() throws IOException {
        start(List.of(npcFile("guard", GUARD, ""), npcFile("baker", BAKER, "")), true);
        active.put(GUARD, 3.0);
        toolkit.selectOnly(alex.id, "guard");
    }

    /** What each chip runs, such as {@code /animus undo 1a2b3c4d}. */
    static List<String> runs(Component root) {
        List<String> found = new ArrayList<>();
        ClickEvent click = root.clickEvent();
        if (click != null && click.action() == ClickEvent.Action.RUN_COMMAND) found.add(((ClickEvent.Payload.Text) click.payload()).value());
        for (Component child : root.children()) found.addAll(runs(child));
        return found;
    }

    @Test void aSimplePlayerSeesUndoWithoutTheIdAndTheChangeIsTheirs() {
        toolkit.open(alex.id);
        click("Copy", Click.LEFT);
        drain();
        ChatLine line = alex.lines.getLast();
        assertEquals("[Animus]: Copied guard, despawned [Undo]", line.visible());
        RecentChanges.Change change = toolkit.changes().latest(alex.id).orElseThrow();
        assertEquals("Copied guard, despawned", change.what());
        assertEquals(List.of(ChatLine.UNDO + ChatLine.shortId(change.transaction())), runs(line.component()), "[Undo] runs /animus undo for this change");
        assertEquals(List.of(Toolkit.CLICK_SOUND + "@0.3"), alex.sounds, "the quiet click only");
    }

    @Test void anAdvancedPlayerKeepsTheId() {
        toolkit.mode(alex.id, ToolkitMode.ADVANCED);
        toolkit.open(alex.id);
        click("Copy", Click.LEFT);
        drain();
        String transaction = toolkit.changes().latest(alex.id).orElseThrow().transaction();
        assertEquals("[Animus]: Copied guard, despawned; tx " + ChatLine.shortId(transaction) + " [Undo]", alex.lines.getLast().visible());
    }

    @Test void aRefusalPlaysTheFailureToneAndIsNoChange() {
        alex.permissions.clear();
        alex.permissions.add(PermissionNodes.TOOLS);
        toolkit.open(alex.id);
        toolkit.route(alex.id, "add", "");
        drain();
        assertTrue(alex.lines.getLast().refusal());
        assertTrue(alex.sounds.contains(FAILURE), alex.sounds.toString());
        assertFalse(alex.sounds.contains(SUCCESS), alex.sounds.toString());
        assertTrue(toolkit.changes().latest(alex.id).isEmpty());
    }

    @Test void aCommandTheMenuRunsAnswersTheSameWay() {
        toolkit.actor(alex).tell(Palette.success("Revived guard; tx " + TX));
        assertEquals("[Animus]: Revived guard [Undo]", alex.lines.getLast().visible());
        assertEquals(TX, toolkit.changes().latest(alex.id).orElseThrow().transaction());
        assertTrue(alex.sounds.isEmpty(), "a menu reply adds no success sound: " + alex.sounds);
    }

    @Test void clearEmptiesTheSelection() {
        toolkit.selection().toggle(alex.id, "baker");
        toolkit.clear(alex.id);
        assertTrue(toolkit.selected(alex.id).isEmpty());
    }
}
