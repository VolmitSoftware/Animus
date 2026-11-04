package art.arcane.animus.api;

import java.util.*;

/** Expected rejection and unavailable capability are distinct from execution failure. */
public record Outcome<T>(Status status,Optional<T> value,List<Diagnostic> diagnostics) {
    public enum Status { OK, INVALID, NOT_FOUND, CONFLICT, UNAVAILABLE, BUSY, CANCELLED, FAILED, TIMED_OUT }
    public Outcome {Objects.requireNonNull(status);value=Objects.requireNonNull(value);diagnostics=List.copyOf(diagnostics);if(status!=Status.OK && value.isPresent())throw new IllegalArgumentException("Rejected outcome cannot carry a value");}
    public static <T> Outcome<T> ok(T value){return new Outcome<>(Status.OK,Optional.of(value),List.of());}
    public static <T> Outcome<T> rejected(Status status,String code,String message){
        if(status==Status.OK)throw new IllegalArgumentException("Use ok");
        return new Outcome<>(status,Optional.empty(),List.of(new Diagnostic(Diagnostic.Severity.ERROR,code,"$",message)));
    }
}
