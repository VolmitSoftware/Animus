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

    @Test void withoutAnIdThePickedNpcIsSelectedAndGlows() {
        Picking living = new Picking(Optional.of("guard"));
        Player player = new Player();
        AnimusCommandTree.select(player, living, "");
        assertEquals(List.of("guard"), living.selected);
        assertEquals(List.of("guard " + SelectionPick.GLOW_TICKS), living.glowed);
        assertEquals("[Animus]: Selected guard", player.rich.getFirst().toString());
    }

    @Test void aNamedNpcAlsoGlows() {
        Picking living = new Picking(Optional.empty());
        Player player = new Player();
        AnimusCommandTree.select(player, living, "smith");
        assertEquals(List.of("smith"), living.selected);
        assertEquals(List.of("smith " + SelectionPick.GLOW_TICKS), living.glowed);
    }

    @Test void nothingInRangeIsARefusal() {
        Picking living = new Picking(Optional.empty());
        Player player = new Player();
        AnimusCommandTree.select(player, living, "");
        assertTrue(living.selected.isEmpty() && living.glowed.isEmpty());
        assertEquals("[Animus]: No NPC in view or within 16 blocks.", player.lines.getFirst());
    }
}
