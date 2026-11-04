package art.arcane.animus.api;

import java.util.Objects;

public record Diagnostic(Severity severity,String code,String field,String message) {
    public enum Severity { INFO, WARNING, ERROR }
    public Diagnostic {Objects.requireNonNull(severity);ApiText.text(code,128,"code");ApiText.text(field,1024,"field");ApiText.text(message,4096,"message");}
}
