package art.arcane.animus.core.onboarding;

import art.arcane.animus.core.migration.CitizensDropIn;
import java.util.Objects;

/** What {@code /animus setup} reads: how many NPCs exist, where a migration stands and whether the web editor is on and paired. */
public record SetupState(int npcs, CitizensDropIn.Progress migration, boolean editorEnabled, int editorTokens) {
    public SetupState {
        Objects.requireNonNull(migration);
        if (npcs < 0 || editorTokens < 0) throw new IllegalArgumentException("counts are not negative");
    }
}
