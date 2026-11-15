package art.arcane.animus.api;

import java.util.Objects;

/**
 * One problem with a field: severity, a stable code, the JSON path and a message for people.
 *
 * @param severity how much it matters
 * @param code stable machine-readable code
 * @param field JSON path of the field, {@code $} for the whole document
 * @param message what is wrong, for people
 */
public record Diagnostic(Severity severity,String code,String field,String message) {
    /** How much a diagnostic matters; only ERROR refuses. */
    public enum Severity { INFO, WARNING, ERROR }
    public Diagnostic {Objects.requireNonNull(severity);ApiText.text(code,128,"code");ApiText.text(field,1024,"field");ApiText.text(message,4096,"message");}
}
