package art.arcane.animus.core.behavior;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.behavior.trigger.TriggerBinding;
import java.util.*;

/**
 * Immutable compiled syntax and declared typed values. {@code initial} holds every variable's declared
 * initial value and {@code declared} its declared type ({@code any} accepts every concrete value); {@code scoped} lists
 * the variables shared beyond one execution. No platform objects or mutable runtime state.
 */
public record BehaviorProgram(String id,String entry,Map<String,Value> initial,Map<String,Type> declared,Map<String,ScopedVariable> scoped,Map<String,Node> routines,int nodes,List<TriggerBinding> triggers) {
    public BehaviorProgram{
        initial=Map.copyOf(initial);scoped=Map.copyOf(scoped);routines=Map.copyOf(routines);triggers=List.copyOf(triggers);
        Map<String,Type> types=new HashMap<>();for(Map.Entry<String,Value> variable:initial.entrySet())types.put(variable.getKey(),declared.getOrDefault(variable.getKey(),variable.getValue().type()));declared=Map.copyOf(types);
    }
    /** Every declared type is the type of its initial value. */
    public BehaviorProgram(String id,String entry,Map<String,Value> initial,Map<String,ScopedVariable> scoped,Map<String,Node> routines,int nodes,List<TriggerBinding> triggers){this(id,entry,initial,Map.of(),scoped,routines,nodes,triggers);}
    /** The same compiled program started from another declared routine. Scoped variables stay shared with every other entry. */
    public BehaviorProgram withEntry(String routine){return new BehaviorProgram(id,routine,initial,declared,scoped,routines,nodes,triggers);}
    /** Value types. A runtime value never has type {@code ANY}; it is a declaration type only. */
    public enum Type { BOOLEAN, INTEGER, DECIMAL, STRING, LIST, MAP, ANY }
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
    /** Booleans, longs, finite doubles, strings, immutable lists of values and immutable insertion-ordered maps of values, within {@link BehaviorValues} bounds. */
    public record Value(Type type,Object value){
        public Value{Objects.requireNonNull(type);Objects.requireNonNull(value);value=BehaviorValues.checked(type,value);}
    }
    public sealed interface Operand permits Literal,Variable,Expression,Template {}
    public record Literal(Value value)implements Operand{}
    public record Variable(String name)implements Operand{}
    public record Expression(String field,BehaviorExpression expression)implements Operand{}
    public record Template(String field,BehaviorTemplate template)implements Operand{}
    public sealed interface Node permits Sequence,Action,Condition,ExpressionCondition,Wait,Repeat,Foreach,While,Break,Continue,Parallel,Call,Return,SetValue {String path();}
    public record Sequence(String path,List<Node> steps)implements Node{public Sequence{steps=List.copyOf(steps);}}
    public record Action(String path,Capabilities.Key capability,ArgumentTemplate arguments)implements Node{}
    public record Condition(String path,Capabilities.Key capability,ArgumentTemplate arguments,Node thenNode,Node elseNode)implements Node{}
    public record ExpressionCondition(String path,BehaviorExpression expression,Node thenNode,Node elseNode)implements Node{}
    public record Wait(String path,int ticks)implements Node{}
    /** {@code computed} null means the literal {@code count}; {@code index} null means no pass variable. */
    public record Repeat(String path,int count,Operand computed,int maximum,String index,Node body)implements Node{
        public Repeat(String path,int count,Node body){this(path,count,null,BehaviorCompiler.MAX_REPEAT,null,body);}
    }
    /** {@code index} and {@code key} are null when not declared. */
    public record Foreach(String path,Operand list,String as,String index,String key,int maximum,Node body)implements Node{}
    public record While(String path,BehaviorExpression expression,int maximum,Node body)implements Node{}
    public record Break(String path)implements Node{}
    public record Continue(String path)implements Node{}
    public record Parallel(String path,List<Node> branches)implements Node{public Parallel{branches=List.copyOf(branches);}}
    /** Arguments are assigned in order before the routine runs; {@code result} is null when nothing receives the return value. */
    public record Call(String path,String routine,Map<String,Operand> arguments,String result)implements Node{
        public Call{arguments=Collections.unmodifiableMap(new LinkedHashMap<>(arguments));}
        public Call(String path,String routine){this(path,routine,Map.of(),null);}
    }
    /** {@code value} is null for a return without a value. */
    public record Return(String path,Operand value)implements Node{}
    public record SetValue(String path,String variable,Operand value)implements Node{}
}
