package art.arcane.animus.core.gui;

import java.util.List;

/** Checks one document the way the catalog will load it. An empty list means it would load. */
@FunctionalInterface
public interface GuiValidator {
    List<String> problems(GuiKind kind, String id, String json);
}
