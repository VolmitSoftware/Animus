package art.arcane.animus.core.extension;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.Diagnostic;
import java.util.List;
import java.util.Optional;

/** Quick, thread-safe registry observations supplied by engine-owned capabilities. Never a module callback. */
public interface BuiltinPolicy {
    /** Present when the capability stays registered but cannot currently run on this platform. */
    Optional<String> unavailable(Capabilities.Key key);
    /** Checks the schema subset cannot express, such as identifier syntax. Runs only after the schema passes. */
    List<Diagnostic> validate(Capabilities.Key key, String argumentsJson);
}
