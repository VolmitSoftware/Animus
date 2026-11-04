package art.arcane.animus.core.behavior;

import art.arcane.animus.api.*;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/** Engine adapter seam. Result delivery and actual adapter quiescence are distinct observations. */
public interface BehaviorInvoker {
    record Invocation(Capabilities.Key capability,NpcHandle npc,Optional<UUID> player,String argumentsJson,
                      Map<String,BehaviorProgram.Value> variables,boolean preview,BooleanSupplier sourceCurrent){
        public Invocation{variables=Map.copyOf(variables);Objects.requireNonNull(sourceCurrent);}
        /** Existing engine callers have no additional immutable-source authority. */
        public Invocation(Capabilities.Key capability,NpcHandle npc,Optional<UUID> player,String argumentsJson,
                          Map<String,BehaviorProgram.Value> variables,boolean preview){this(capability,npc,player,argumentsJson,variables,preview,()->true);}
    }
    record Call<T>(CompletionStage<Outcome<T>> result,Runnable cancel,BooleanSupplier isQuiescent){
        public Call{Objects.requireNonNull(result);Objects.requireNonNull(cancel);Objects.requireNonNull(isQuiescent);}
        /** Fake/manual adapters with no work beyond the returned stage may use this result-only contract. */
        public Call(CompletionStage<Outcome<T>> result,Runnable cancel){this(result,cancel,()->true);}
    }
    boolean available(Capabilities.Key capability,Capabilities.Kind kind);
    Call<String> action(Invocation invocation);
    Call<Boolean> condition(Invocation invocation);
}
