package art.arcane.animus.core.toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ToolkitSelectionTest {
    private final UUID alex = UUID.randomUUID(), sam = UUID.randomUUID();

    @Test void onlyReplacesAndToggleAddsOrRemovesInOrder() {
        ToolkitSelection selection = new ToolkitSelection();
        selection.only(alex, "guard");
        assertEquals(List.of("guard"), selection.of(alex));
        assertTrue(selection.toggle(alex, "baker"));
        assertTrue(selection.toggle(alex, "smith"));
        assertEquals(List.of("guard", "baker", "smith"), selection.of(alex));
        assertFalse(selection.toggle(alex, "baker"));
        assertEquals(List.of("guard", "smith"), selection.of(alex));
        selection.only(alex, "baker");
        assertEquals(List.of("baker"), selection.of(alex));
        assertTrue(selection.of(sam).isEmpty(), "selections are per player");
    }

    @Test void clearAndQuitForgetThePlayer() {
        ToolkitSelection selection = new ToolkitSelection();
        selection.only(alex, "guard");
        selection.only(sam, "baker");
        assertEquals(Set.of(alex, sam), selection.players());
        selection.clear(alex);
        assertTrue(selection.of(alex).isEmpty());
        assertEquals(Set.of(sam), selection.players());
        selection.clear(sam);
        assertTrue(selection.players().isEmpty());
    }

    @Test void retainDropsDeletedNpcsAndTheSetIsCapped() {
        ToolkitSelection selection = new ToolkitSelection();
        selection.only(alex, "guard");
        selection.toggle(alex, "gone");
        selection.retain(alex, (String id) -> !id.equals("gone"));
        assertEquals(List.of("guard"), selection.of(alex));
        for (int i = 1; i < ToolkitSelection.MAXIMUM; i++) selection.toggle(alex, "npc-" + i);
        assertEquals(ToolkitSelection.MAXIMUM, selection.of(alex).size());
        assertThrows(IllegalStateException.class, () -> selection.toggle(alex, "one-more"));
        assertFalse(selection.toggle(alex, "npc-1"), "removing still works at the cap");
    }
}
