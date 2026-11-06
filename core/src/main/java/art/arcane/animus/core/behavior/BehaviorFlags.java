package art.arcane.animus.core.behavior;

import art.arcane.animus.core.behavior.BehaviorProgram.Lifetime;
import art.arcane.animus.core.behavior.BehaviorProgram.Scope;
import art.arcane.animus.core.behavior.BehaviorProgram.ScopedVariable;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Where Animus flags and quest stages live in behavior state: one reserved identity per kind and behavior namespace,
 * so a pack's behaviors share them, packs stay apart, and the state budgets and purge commands apply. Flags are
 * declared {@code any}; quest stages are integers.
 */
public final class BehaviorFlags {
    public static final String FLAGS="animus:flags/",QUESTS="animus:quests/";
    private BehaviorFlags(){}

    /** The state subject of flags or quests ({@link #FLAGS} or {@link #QUESTS}) for the namespace of {@code behavior}. */
    public static BehaviorState.Subject subject(String kind,String behavior,UUID npc,Optional<UUID> player){
        int colon=behavior.indexOf(':');
        if(colon<1)throw new IllegalArgumentException("Expected a namespace:path behavior id");
        return new BehaviorState.Subject(kind+behavior.substring(0,colon),npc,player);
    }
    /** {@code player}, {@code npc}, {@code relationship} or {@code global}. */
    public static ScopedVariable declaration(String scope){return new ScopedVariable(Scope.valueOf(scope.toUpperCase(Locale.ROOT)),Lifetime.PERSISTENT);}
}
