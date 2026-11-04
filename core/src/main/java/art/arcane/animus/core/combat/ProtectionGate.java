package art.arcane.animus.core.combat;

import java.util.Objects;
import java.util.function.Consumer;

/** Fail-closed front of a protection adapter, shared by the engine and the platform's damage path. */
public final class ProtectionGate {
    private final ProtectionAdapter adapter;
    private final Consumer<String> diagnostics;
    private long denials;
    private long failures;

    public ProtectionGate(ProtectionAdapter adapter, Consumer<String> diagnostics) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public boolean allows(ProtectionAdapter.Request request) {
        ProtectionAdapter.Decision decision;
        try {
            decision = adapter.decide(request);
        } catch (RuntimeException | LinkageError failure) {
            failures++;
            diagnostics.accept("Combat protection failed closed: " + failure);
            denials++;
            return false;
        }
        if (decision == null) {
            failures++;
            diagnostics.accept("Combat protection returned no decision; failed closed");
        }
        if (decision == ProtectionAdapter.Decision.ALLOW) return true;
        denials++;
        return false;
    }
    public long denials() { return denials; }
    public long failures() { return failures; }
}
