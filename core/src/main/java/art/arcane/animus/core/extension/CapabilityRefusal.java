package art.arcane.animus.core.extension;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.Outcome;
import java.util.List;
import java.util.Optional;

/** Expected engine-private rejection from a built-in handler. Keeps its status instead of becoming a handler failure. */
public final class CapabilityRefusal extends RuntimeException {
    private final Outcome.Status status;
    private final List<Diagnostic> diagnostics;

    public CapabilityRefusal(Outcome.Status status, String code, String message) {
        super(message, null, false, false);
        if (status == Outcome.Status.OK) throw new IllegalArgumentException("A refusal cannot report success");
        this.status = status;
        diagnostics = List.of(new Diagnostic(Diagnostic.Severity.ERROR, code, "$", message));
    }

    public Outcome.Status status() { return status; }
    public List<Diagnostic> diagnostics() { return diagnostics; }
    <T> Outcome<T> outcome() { return new Outcome<>(status, Optional.empty(), diagnostics); }
}
