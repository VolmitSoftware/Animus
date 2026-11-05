package art.arcane.animus.core.behavior;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.BehaviorProgram.ScopedVariable;
import art.arcane.animus.core.behavior.BehaviorProgram.Type;
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

    /** The stored value, or {@code initial} when the key was never written or has expired. A stored value of another type is refused, not coerced. */
    Value read(Subject subject,String variable,ScopedVariable declaration,Value initial);

    /**
     * As {@link #read(Subject,String,ScopedVariable,Value)} for a variable declared {@code declared}: {@code ANY} accepts a
     * stored value of every type. When the key is unset or expired the returned object is {@code initial} itself.
     */
    default Value read(Subject subject,String variable,ScopedVariable declaration,Value initial,Type declared){return read(subject,variable,declaration,initial);}

    /** Applies every write or none of them. A written key loses any expiry it had. */
    void write(Subject subject,Map<String,Value> writes,Map<String,ScopedVariable> declarations);

    /**
     * Applies every write or none of them; a key named in {@code expiresAtMillis} reads as unset from that epoch
     * millisecond on, other written keys lose any expiry. Services without expiry refuse expiring writes.
     */
    default void write(Subject subject,Map<String,Value> writes,Map<String,ScopedVariable> declarations,Map<String,Long> expiresAtMillis){
        if(expiresAtMillis.isEmpty()){write(subject,writes,declarations);return;}
        throw new Rejected(Outcome.Status.UNAVAILABLE,"behavior-state-expiry-unavailable","$.variables","This state service keeps no expiry");
    }

    /** Cheap periodic owner-turn hook; durable implementations use it to queue a coalesced flush. */
    default void maintain(){}
}
