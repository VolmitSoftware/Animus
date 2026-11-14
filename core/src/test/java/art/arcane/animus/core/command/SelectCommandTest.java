package art.arcane.animus.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import art.arcane.animus.core.gui.FakeNpcRuntime;
import art.arcane.animus.core.runtime.SelectionPick;
import art.arcane.animus.core.text.ChatLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@code /animus select}: the picked or named NPC is selected and glows for the selecting player; nothing in range is a refusal. */
class SelectCommandTest {
    /** A runtime whose pick is fixed, recording selections and glows. */
    private static final class Picking extends FakeNpcRuntime<Object> {
        final Optional<String> picked;
        final List<String> selected = new ArrayList<>(), glowed = new ArrayList<>();
        Picking(Optional<String> picked) { super((UUID id) -> Optional.empty()); this.picked = picked; }
        @Override public Optional<String> pick(UUID viewer) { return picked; }
        @Override public void select(UUID viewer, String id) { selected.add(id); }
        @Override public boolean highlight(UUID viewer, String id, int ticks) { glowed.add(id + " " + ticks); return true; }
    }

    /** A player whose replies are kept. */
    private static final class Player extends AnimusDirectorTest.Actor {
        final List<ChatLine> rich = new ArrayList<>();
        Player() { super(true, AnimusDirector.ADMIN); }
        @Override public void rich(ChatLine line) { rich.add(line); }
    }

    private final List<String> toolkit = new ArrayList<>();
    private int cleared;

    /** The NPCs saved here: smith, and none when a test saves one by that id. */
    private final List<String> saved = new ArrayList<>(List.of("smith"));

    /** Names an NPC as every command does: here only the saved ones, smith also as world/town/smith, and @selected for mayor. */
    private Optional<String> named(CommandActor actor, String typed) {
        if (typed.equals("world/town/smith") || saved.contains(typed)) return Optional.of(typed.equals("world/town/smith") ? "smith" : typed);
        if (typed.equals("@selected")) return Optional.of("mayor");
        actor.tell(art.arcane.animus.core.text.Palette.error(CommandGroup.unknownNpc(typed)));
        return Optional.empty();
    }

    private void select(Player player, Picking living, String npc) {
        AnimusCommandTree.select(player, living, npc, (String typed) -> named(player, typed), saved::contains, toolkit::add, () -> cleared++);
    }

    @Test void withoutAnIdThePickedNpcIsSelectedAndGlows() {
        Picking living = new Picking(Optional.of("guard"));
        Player player = new Player();
        select(player, living, "");
        assertEquals(List.of("guard"), living.selected);
        assertEquals(List.of("guard"), toolkit, "the wand selection becomes the same NPC");
        assertEquals(List.of("guard " + SelectionPick.GLOW_TICKS), living.glowed);
        assertEquals("[Animus]: Selected guard", player.rich.getFirst().toString());
    }

    @Test void aNamedNpcAlsoGlows() {
        Picking living = new Picking(Optional.empty());
        Player player = new Player();
        select(player, living, "smith");
        assertEquals(List.of("smith"), living.selected);
        assertEquals(List.of("smith " + SelectionPick.GLOW_TICKS), living.glowed);
    }

    @Test void anIdIsReadAsEveryCommandReadsIt() {
        Picking living = new Picking(Optional.empty());
        Player player = new Player();
        select(player, living, "world/town/smith");
        select(player, living, "@selected");
        assertEquals(List.of("smith", "mayor"), living.selected, "folder ids and @selected name an NPC");
        select(player, living, "ghost");
        assertEquals(List.of("smith", "mayor"), toolkit, "an unknown id selects nothing");
        assertEquals("[Animus]: No NPC named ghost; /animus list shows ids", player.lines.getLast());
    }

    @Test void noneClearsTheSelection() {
        Picking living = new Picking(Optional.of("guard"));
        Player player = new Player();
        select(player, living, "none");
        assertEquals(1, cleared);
        assertTrue(living.selected.isEmpty() && living.glowed.isEmpty() && toolkit.isEmpty(), "nothing is picked");
        assertEquals("[Animus]: Selection cleared", player.lines.getLast());
    }

    @Test void anNpcSavedAsNoneIsSelectedRatherThanClearingTheSelection() {
        saved.add("none");
        Picking living = new Picking(Optional.empty());
        Player player = new Player();
        select(player, living, "none");
        assertEquals(0, cleared);
        assertEquals(List.of("none"), living.selected);
        assertEquals("[Animus]: Selected none", player.rich.getFirst().toString());
    }

    @Test void nothingInRangeIsARefusal() {
        Picking living = new Picking(Optional.empty());
        Player player = new Player();
        select(player, living, "");
        assertTrue(living.selected.isEmpty() && living.glowed.isEmpty() && toolkit.isEmpty());
        assertEquals("[Animus]: No NPC in view or within 16 blocks.", player.lines.getFirst());
    }

    @Test void theSelectionReplyNamesEveryWandPick() {
        assertEquals("[Animus]: Selected 2 NPCs: baker, mayor", AnimusCommandTree.selection(List.of("baker", "mayor")).orElseThrow().toString());
        assertEquals("[Animus]: Selected mayor", AnimusCommandTree.selection(List.of("mayor")).orElseThrow().toString());
        assertTrue(AnimusCommandTree.selection(List.of()).isEmpty(), "a cleared wand selection shows nothing, not an older pick");
    }

    @Test void atSelectedIsTheOneSelectedNpcOrARefusal() {
        Player player = new Player();
        assertEquals(Optional.of("mayor"), CommandGroup.selected(player, List.of("mayor")));
        assertEquals(Optional.empty(), CommandGroup.selected(player, List.of()));
        assertEquals("[Animus]: No NPC selected; /animus select <npc>, or click one with the wand (/animus tools wand)", player.lines.getLast());
        assertEquals(Optional.empty(), CommandGroup.selected(player, List.of("baker", "mayor", "smith")));
        assertEquals("[Animus]: 3 NPCs are selected; name one, or /animus select <npc> to keep only it", player.lines.getLast());
    }
}
