package art.arcane.animus.core.gui;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks one document the way the catalog will load it. An empty list means it would load. */
@FunctionalInterface
public interface GuiValidator {
    /** {@code pending} holds the ids, by kind, that the same import already accepted; a reference to one counts as loaded. */
    List<String> problems(GuiKind kind, String id, String json, Map<GuiKind, Set<String>> pending);

    default List<String> problems(GuiKind kind, String id, String json) { return problems(kind, id, json, Map.of()); }
}
