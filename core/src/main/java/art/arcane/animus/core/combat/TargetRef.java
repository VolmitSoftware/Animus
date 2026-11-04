package art.arcane.animus.core.combat;

import java.util.Objects;
import java.util.UUID;

/** Stable identity of a combat participant. Every use is revalidated against the current world. */
public record TargetRef(TargetKind kind, UUID id) {
    public TargetRef {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }
}
