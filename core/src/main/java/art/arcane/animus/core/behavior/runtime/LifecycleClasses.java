package art.arcane.animus.core.behavior.runtime;

import art.arcane.animus.api.*;
import art.arcane.animus.core.behavior.*;
import java.util.*;

/** Resolve engine-owned shutdown types before a platform can close its plugin archive. */
final class LifecycleClasses {
    private static boolean prepared;
    private LifecycleClasses(){}
    static synchronized void prepare(){
        if(prepared)return;
        Set<Class<?>> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Class<?> root:List.of(BehaviorRuntime.class,BehaviorEngine.class,BehaviorInvoker.class,
                BehaviorProgram.class,BehaviorState.class,ArgumentTemplate.class,BehaviorValues.class,BehaviorFacts.class,BehaviorExpression.class,BehaviorTemplate.class,BehaviorFlags.class,Outcome.class,Diagnostic.class,NpcHandle.class,Capabilities.class,
                art.arcane.animus.core.behavior.trigger.TriggerDispatcher.class))load(root,seen);
        // Cancellation receipts use the API's private UTF-8 validation helper. Signature resolution
        // alone does not load method-body dependencies; construct one bounded receipt before close.
        Outcome.rejected(Outcome.Status.CANCELLED,"lifecycle-ready","Engine lifecycle types are ready");
        prepared=true;
    }
    private static void load(Class<?> type,Set<Class<?>> seen){
        if(!seen.add(type))return;
        try{Class.forName(type.getName(),true,type.getClassLoader());}
        catch(ClassNotFoundException impossible){throw new IllegalStateException(impossible);}
        // Definition alone need not resolve signatures. These are engine/API classes, never module classes.
        type.getDeclaredConstructors();type.getDeclaredMethods();type.getDeclaredFields();
        for(Class<?> nested:type.getDeclaredClasses())load(nested,seen);
    }
}
