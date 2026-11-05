package art.arcane.animus.core.behavior;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.BehaviorProgram.ScopedVariable;
import art.arcane.animus.core.behavior.BehaviorProgram.Value;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Variables shared beyond one execution. The engine owner calls this from its own thread; calls never touch files. */
public interface BehaviorState {
    /** The identity an execution resolves its scoped variables against. Each scope uses only the parts it names. */
    record Subject(String behavior,UUID npc,Optional<UUID> player){
        public Subject{Objects.requireNonNull(behavior);Objects.requireNonNull(npc);Objects.requireNonNull(player);}
    }

    /** An expected refusal with an exact field. Carries no stack trace: it can be frequent and is reported as a diagnostic. */
    final class Rejected extends RuntimeException {
        private final transient Outcome.Status status;
        private final transient Diagnostic diagnostic;
        public Rejected(Outcome.Status status,String code,String field,String message){
            super(message,null,false,false);
            this.status=Objects.requireNonNull(status);
            diagnostic=new Diagnostic(Diagnostic.Severity.ERROR,code,field,message);
        }
        public Outcome.Status status(){return status;}
        public Diagnostic diagnostic(){return diagnostic;}
    }

    /** Refuses every scoped variable. Programs that only use execution variables never call it. */
    BehaviorState UNAVAILABLE=new BehaviorState(){
        private Rejected unavailable(){return new Rejected(Outcome.Status.UNAVAILABLE,"behavior-state-unavailable","$.variables","This engine has no scoped variable service");}
        @Override public Value read(Subject subject,String variable,ScopedVariable declaration,Value initial){throw unavailable();}
        @Override public void write(Subject subject,Map<String,Value> writes,Map<String,ScopedVariable> declarations){throw unavailable();}
    };

    /** The stored value, or {@code initial} when the key was never written. A stored value of another type is refused, not coerced. */
    Value read(Subject subject,String variable,ScopedVariable declaration,Value initial);

    /** Applies every write or none of them. */
    void write(Subject subject,Map<String,Value> writes,Map<String,ScopedVariable> declarations);

    /** Applies every write or none of them; a key in {@code expiresAtMillis} reads as unset from that epoch millisecond on. */
    default void write(Subject subject,Map<String,Value> writes,Map<String,ScopedVariable> declarations,Map<String,Long> expiresAtMillis){if(!expiresAtMillis.isEmpty())throw new Rejected(art.arcane.animus.api.Outcome.Status.UNAVAILABLE,"behavior-state-expiry-unavailable","$","This state service does not store expiring values");write(subject,writes,declarations);}

    /** Cheap periodic owner-turn hook; durable implementations use it to queue a coalesced flush. */
    default void maintain(){}
}
