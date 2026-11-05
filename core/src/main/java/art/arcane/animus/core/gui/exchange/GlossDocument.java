package art.arcane.animus.core.gui.exchange;

import art.arcane.animus.core.gui.GuiKind;
import java.util.Objects;

/** One document as Gloss names it: its kind, its Gloss id and its JSON text. */
public record GlossDocument(GuiKind kind, String id, String json) {
    public GlossDocument { Objects.requireNonNull(kind); Objects.requireNonNull(id); Objects.requireNonNull(json); }
}
