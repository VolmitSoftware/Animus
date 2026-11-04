package art.arcane.animus.core.behavior;

import art.arcane.animus.api.*;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/** Engine adapter seam. Result delivery and actual adapter quiescence are distinct observations. */
public interface BehaviorInvoker {
    /** Behavior identity comes from the accepted program, so a privileged grant cannot be claimed by arguments. */
    record Invocation(Capabilities.Key capability,NpcHandle npc,Optional<UUID> player,String argumentsJson,
                      Map<String,BehaviorProgram.Value> variables,boolean preview,BooleanSupplier sourceCurrent,String behavior){
        public Invocation{variables=Map.copyOf(variables);Objects.requireNonNull(sourceCurrent);Objects.requireNonNull(behavior);}
    }
    record Call<T>(CompletionStage<Outcome<T>> result,Runnable cancel,BooleanSupplier isQuiescent){
        public Call{Objects.requireNonNull(result);Objects.requireNonNull(cancel);Objects.requireNonNull(isQuiescent);}
        /** Fake/manual adapters with no work beyond the returned stage may use this result-only contract. */
        public Call(CompletionStage<Outcome<T>> result,Runnable cancel){this(result,cancel,()->true);}
    }
    boolean available(Capabilities.Key capability,Capabilities.Kind kind);
    /** Identity-aware admission hint for capabilities granted per behavior; actual invocation rechecks authority. */
    default boolean available(Capabilities.Key capability,Capabilities.Kind kind,String behavior){return available(capability,kind);}
    Call<String> action(Invocation invocation);
    Call<Boolean> condition(Invocation invocation);
}
