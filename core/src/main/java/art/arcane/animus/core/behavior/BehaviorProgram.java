package art.arcane.animus.core.behavior;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.behavior.trigger.TriggerBinding;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Immutable compiled syntax and declared typed values. {@code initial} holds every variable's declared
 * initial value; {@code scoped} lists the variables shared beyond one execution. No platform objects or mutable runtime state.
 */
public record BehaviorProgram(String id,String entry,Map<String,Value> initial,Map<String,ScopedVariable> scoped,Map<String,Node> routines,int nodes,List<TriggerBinding> triggers,boolean server) {
    public BehaviorProgram{initial=Map.copyOf(initial);scoped=Map.copyOf(scoped);routines=Map.copyOf(routines);triggers=List.copyOf(triggers);}
    /** An NPC behavior. {@code server} marks a server-wide behavior: it runs with {@link ServerNpc#HANDLE} instead of an NPC. */
    public BehaviorProgram(String id,String entry,Map<String,Value> initial,Map<String,ScopedVariable> scoped,Map<String,Node> routines,int nodes,List<TriggerBinding> triggers){this(id,entry,initial,scoped,routines,nodes,triggers,false);}
    /** The same compiled program started from another declared routine. Scoped variables stay shared with every other entry. */
    public BehaviorProgram withEntry(String routine){return new BehaviorProgram(id,routine,initial,scoped,routines,nodes,triggers,server);}
    public enum Type { BOOLEAN, INTEGER, DECIMAL, STRING }
    public enum Scope {
        NPC(false),PLAYER(true),RELATIONSHIP(true),GLOBAL(false);
        private final boolean requiresPlayer;
        Scope(boolean requiresPlayer){this.requiresPlayer=requiresPlayer;}
        public boolean requiresPlayer(){return requiresPlayer;}
    }
    public enum Lifetime { TRANSIENT, PERSISTENT }
    public record ScopedVariable(Scope scope,Lifetime lifetime){public ScopedVariable{Objects.requireNonNull(scope);Objects.requireNonNull(lifetime);}}
    /** A player or relationship variable can only be resolved by an execution that carries a player. */
    public boolean requiresPlayer(){
        for(ScopedVariable variable:scoped.values())if(variable.scope().requiresPlayer())return true;
        return false;
    }
    public record Value(Type type,Object value){
        public Value{
            Objects.requireNonNull(type);Objects.requireNonNull(value);
            boolean valid=switch(type){case BOOLEAN->value instanceof Boolean;case INTEGER->value instanceof Long;
                case DECIMAL->value instanceof Double n && Double.isFinite(n);case STRING->value instanceof String s && s.length()<=4096 && StandardCharsets.UTF_8.newEncoder().canEncode(s) && s.getBytes(StandardCharsets.UTF_8).length<=4096;};
            if(!valid)throw new IllegalArgumentException("Invalid typed value");
        }
    }
    public sealed interface Operand permits Literal,Variable {}
    public record Literal(Value value)implements Operand{}
    public record Variable(String name)implements Operand{}
    public sealed interface Node permits Sequence,Action,Condition,Wait,Repeat,Parallel,Call,SetValue {String path();}
    public record Sequence(String path,List<Node> steps)implements Node{public Sequence{steps=List.copyOf(steps);}}
    public record Action(String path,Capabilities.Key capability,ArgumentTemplate arguments)implements Node{}
    public record Condition(String path,Capabilities.Key capability,ArgumentTemplate arguments,Node thenNode,Node elseNode)implements Node{}
    public record Wait(String path,int ticks)implements Node{}
    public record Repeat(String path,int count,Node body)implements Node{}
    public record Parallel(String path,List<Node> branches)implements Node{public Parallel{branches=List.copyOf(branches);}}
    public record Call(String path,String routine)implements Node{}
    public record SetValue(String path,String variable,Operand value)implements Node{}
}
