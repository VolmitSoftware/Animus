package art.arcane.animus.core.behavior;

import art.arcane.animus.api.*;
import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.behavior.BehaviorProgram.*;
import art.arcane.animus.core.storage.JsonDocuments;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Manual, single-owner first-gate interpreter. Invoker callbacks only publish into bounded atomic completion slots. */
public final class BehaviorEngine implements AutoCloseable {
    public record Limits(int executions,int fibers,int inFlight,int workPerTick,int totalSteps,int lifetimeTicks,
                         int callDepth,int traceEntries,int historyEntries){
        public Limits{bound(executions,128);bound(fibers,512);bound(inFlight,64);bound(workPerTick,4096);bound(totalSteps,1_000_000);bound(lifetimeTicks,72000);bound(callDepth,32);bound(traceEntries,256);bound(historyEntries,256);}
        private static void bound(int value,int maximum){if(value<1||value>maximum)throw new IllegalArgumentException("Invalid behavior budget");}
        public static Limits defaults(){return new Limits(64,128,16,256,4096,20000,8,64,64);}
    }
    public record Context(NpcHandle npc,Optional<UUID> player,boolean preview){public Context{Objects.requireNonNull(npc);Objects.requireNonNull(player);}}
    public enum State { RUNNING, COMPLETED, CANCELLED, FAILED, UNAVAILABLE, BUDGET_EXHAUSTED }
    public record Trace(long tick,String path,String event){}
    public record Report(UUID id,String behavior,State state,String reason,long steps,Map<String,Value> variables,List<String> waits,List<Trace> trace,List<Diagnostic> diagnostics){
        public Report{variables=Map.copyOf(variables);waits=List.copyOf(waits);trace=List.copyOf(trace);diagnostics=List.copyOf(diagnostics);}
    }
    public record Status(long tick,int activeExecutions,int fibers,int callsInFlight,int retiredCalls,int unobservedCalls,int history,long cancellationFailures,long quiescenceFailures,long terminalObserverFailures,boolean closed){}
    public record Tick(int work,int instructions,int completions,Status status){}
    private final Thread owner=Thread.currentThread();private final Limits limits;private final BehaviorInvoker invoker;
    private final Consumer<Report> terminalObserver;
    private final Map<UUID,Execution> executions=new LinkedHashMap<>();private final Map<Long,Fiber> fibers=new LinkedHashMap<>();
    private final ArrayDeque<Fiber> order=new ArrayDeque<>();private final Set<Pending> calls=new LinkedHashSet<>();
    private final LinkedHashMap<UUID,Report> history=new LinkedHashMap<>();
    private long tick,nextFiber,cancellationFailures,quiescenceFailures,terminalObserverFailures;private boolean closed,advancing;private int lifecycleDepth;
    public BehaviorEngine(Limits limits,BehaviorInvoker invoker){this(limits,invoker,report->{});}
    /** Engine-owned quick observation only; never a module callback or public completion listener. */
    public BehaviorEngine(Limits limits,BehaviorInvoker invoker,Consumer<Report> terminalObserver){this.limits=Objects.requireNonNull(limits);this.invoker=Objects.requireNonNull(invoker);this.terminalObserver=Objects.requireNonNull(terminalObserver);}
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Behavior engine requires its designated owner thread");}
    public Outcome<UUID> start(BehaviorProgram program,Context context){
        owner();Objects.requireNonNull(program);Objects.requireNonNull(context);
        if(closed)return Outcome.rejected(Outcome.Status.UNAVAILABLE,"engine-closed","Behavior engine is closed");
        if(advancing||lifecycleDepth>0)return Outcome.rejected(Outcome.Status.BUSY,"engine-advancing","Start requests must be queued outside the current tick/lifecycle change");
        Node root;
        if(program.id()==null||program.entry()==null||program.nodes()<1||program.nodes()>BehaviorCompiler.MAX_NODES
                ||program.initial().size()>BehaviorCompiler.MAX_VARIABLES||program.routines().size()>BehaviorCompiler.MAX_ROUTINES
                ||(root=program.routines().get(program.entry()))==null)
            return Outcome.rejected(Outcome.Status.INVALID,"program-invalid","Expected a compiled program with a valid entry and bounded declarations");
        if(executions.size()>=limits.executions()||fibers.size()>=limits.fibers())return Outcome.rejected(Outcome.Status.BUSY,"behavior-capacity","Execution/fiber capacity reached");
        Execution execution=new Execution(UUID.randomUUID(),program,context);executions.put(execution.id,execution);
        Fiber first=new Fiber(execution,program.initial(),null,-1);execution.root=first;push(first,root);add(first);trace(first,"$","started");return Outcome.ok(execution.id);
    }
    public Tick tick(){
        owner();if(advancing||lifecycleDepth>0)throw new IllegalStateException("Recursive behavior ticks/lifecycle entry are unavailable");advancing=true;
        try{return advance();}finally{advancing=false;}
    }
    private Tick advance(){
        tick=Math.incrementExact(tick);int instructions=0,work=0;
        for(Execution execution:List.copyOf(executions.values()))if(tick-execution.started>=limits.lifetimeTicks())terminate(execution,State.BUDGET_EXHAUSTED,"execution-lifetime");
        int completions=resolveCalls();
        int visits=Math.min(order.size(),limits.workPerTick());
        for(int i=0;i<visits&&!order.isEmpty();i++){
            Fiber fiber=order.removeFirst();work++;
            if(!fibers.containsKey(fiber.id))continue;
            if(ready(fiber)){
                if(fiber.execution.steps>=limits.totalSteps())terminate(fiber.execution,State.BUDGET_EXHAUSTED,"execution-step-budget");
                else {fiber.execution.steps++;instructions++;try{step(fiber);}catch(RuntimeException | AssertionError | LinkageError failure){terminate(fiber.execution,State.FAILED,"engine-or-invoker: "+failure.getClass().getSimpleName());}}
            }
            if(fibers.containsKey(fiber.id))order.addLast(fiber);
        }
        return new Tick(work,instructions,completions,status());
    }
    private boolean ready(Fiber fiber){
        if(!current(fiber))return false;
        if(fiber.awaiting!=null||fiber.wakeTick>tick)return false;
        if(fiber.stack.isEmpty()){finishFiber(fiber);return false;}
        Frame frame=fiber.stack.peek();if(frame.join!=null&&frame.join.remaining>0)return false;
        return !(frame.node instanceof Action || frame.node instanceof Condition && frame.branch==null)||calls.size()<limits.inFlight();
    }
    private void step(Fiber fiber){
        Frame frame=fiber.stack.peek();Node node=frame.node;
        switch(node){
            case Sequence sequence->{if(frame.index<sequence.steps().size())push(fiber,sequence.steps().get(frame.index++));else fiber.stack.pop();}
            case Wait wait->{fiber.stack.pop();fiber.wakeTick=tick+wait.ticks();trace(fiber,node.path(),"wait-until:"+fiber.wakeTick);}
            case SetValue set->{Value value=set.value() instanceof Literal literal?literal.value():fiber.variables.get(((Variable)set.value()).name());fiber.variables.put(set.variable(),value);fiber.writes.add(set.variable());fiber.stack.pop();trace(fiber,node.path(),"set:"+set.variable());}
            case Repeat repeat->{if(frame.index<repeat.count()){frame.index++;push(fiber,repeat.body());}else fiber.stack.pop();}
            case Call call->{if(frame.index==0){if(++fiber.callDepth>limits.callDepth()){terminate(fiber.execution,State.BUDGET_EXHAUSTED,"routine-depth");return;}frame.index=1;push(fiber,fiber.execution.program.routines().get(call.routine()));}else{fiber.callDepth--;fiber.stack.pop();}}
            case Action action->invoke(fiber,action.capability(),action.arguments(),false);
            case Condition condition->{if(frame.branch==null)invoke(fiber,condition.capability(),condition.arguments(),true);else{fiber.stack.pop();push(fiber,frame.branch?condition.thenNode():condition.elseNode());}}
            case Parallel parallel->{
                if(frame.join==null){
                    if(fibers.size()+parallel.branches().size()>limits.fibers()){terminate(fiber.execution,State.BUDGET_EXHAUSTED,"parallel-fiber-budget");return;}
                    frame.join=new Join(fiber,parallel.branches().size());
                    for(int i=0;i<parallel.branches().size();i++){Fiber child=new Fiber(fiber.execution,fiber.variables,frame.join,i);child.callDepth=fiber.callDepth;push(child,parallel.branches().get(i));add(child);}trace(fiber,node.path(),"parallel-start");
                }else{
                    Set<String> writes=new HashSet<>();for(Branch branch:frame.join.results)for(String name:branch.writes)if(!writes.add(name)){terminate(fiber.execution,State.FAILED,"parallel-write-conflict:"+name);return;}
                    for(Branch branch:frame.join.results)for(String name:branch.writes){fiber.variables.put(name,branch.variables.get(name));fiber.writes.add(name);}
                    fiber.stack.pop();trace(fiber,node.path(),"parallel-joined");
                }
            }
        }
        if(fibers.containsKey(fiber.id)&&fiber.stack.isEmpty()&&fiber.awaiting==null&&fiber.wakeTick<=tick)finishFiber(fiber);
    }
    private void invoke(Fiber fiber,Capabilities.Key key,ArgumentTemplate template,boolean condition){
        Capabilities.Kind kind=condition?Capabilities.Kind.CONDITION:Capabilities.Kind.ACTION;
        boolean available=invoker.available(key,kind,fiber.execution.program.id());if(!current(fiber))return;
        if(!available){terminate(fiber.execution,State.UNAVAILABLE,"capability-unavailable:"+key);return;}
        String arguments;
        try{arguments=template.resolve(fiber.variables);}
        catch(ArgumentTemplate.Invalid invalid){terminate(fiber.execution,State.FAILED,"arguments-invalid:"+invalid.field()+":"+invalid.getMessage());return;}
        BehaviorInvoker.Invocation invocation=new BehaviorInvoker.Invocation(key,fiber.execution.context.npc(),fiber.execution.context.player(),arguments,fiber.variables,fiber.execution.context.preview(),()->true,fiber.execution.program.id());
        BehaviorInvoker.Call<?> call=condition?invoker.condition(invocation):invoker.action(invocation);
        Pending pending=new Pending(fiber.execution.id,fiber.id,condition,Objects.requireNonNull(call));calls.add(pending);fiber.awaiting=pending;
        trace(fiber,fiber.stack.peek().node.path(),"invoking:"+key);
        // No engine/variable/world mutation on completion threads. At most the admitted call count slots exist.
        try{call.result().whenComplete((value,failure)->pending.resolved.compareAndSet(null,new Resolved(value,failure)));}
        catch(RuntimeException | AssertionError | LinkageError failure){
            // Registration failure is not proof that the admitted operation terminated. Hold its slot unless an
            // observer that was installed before the throw actually reports a terminal outcome later.
            pending.observationFailed=true;terminate(fiber.execution,State.FAILED,"completion-observer-failed");
        }
        if(!current(fiber))cancelCall(pending,fiber.execution);
    }
    private boolean current(Fiber fiber){return fibers.get(fiber.id)==fiber&&executions.get(fiber.execution.id)==fiber.execution;}
    private int resolveCalls(){
        int completed=0;
        for(Pending pending:List.copyOf(calls)){
            Resolved resolved=pending.resolved.get();if(resolved==null)continue;
            if(!pending.outcomeHandled){pending.outcomeHandled=true;completed++;applyOutcome(pending,resolved);}
            if(!pending.quiescenceFailed){
                try{if(pending.call.isQuiescent().getAsBoolean())calls.remove(pending);}
                catch(RuntimeException | AssertionError | LinkageError failure){pending.quiescenceFailed=true;quiescenceFailures++;}
            }
        }
        return completed;
    }
    private void applyOutcome(Pending pending,Resolved resolved){
        Fiber fiber=fibers.get(pending.fiber);if(fiber==null||!current(fiber)||fiber.awaiting!=pending||pending.cancelled)return;fiber.awaiting=null;
        if(resolved.failure!=null||resolved.value==null){terminate(fiber.execution,State.FAILED,"invocation-failed");return;}
        Outcome<?> result=resolved.value;
        if(result.status()!=Outcome.Status.OK){
            // Preserve bounded field errors alongside the node trace, without retaining argument payloads.
            fiber.execution.diagnostics=List.copyOf(result.diagnostics().subList(0,Math.min(16,result.diagnostics().size())));
            for(Diagnostic diagnostic:fiber.execution.diagnostics)trace(fiber,fiber.stack.peek().node.path(),"diagnostic:"+diagnostic.code());
            State state=switch(result.status()){case UNAVAILABLE->State.UNAVAILABLE;case CANCELLED->State.CANCELLED;default->State.FAILED;};
            terminate(fiber.execution,state,"invocation-"+result.status().name().toLowerCase(Locale.ROOT));return;
        }
        Object value=result.value().orElse(null);
        if(pending.condition){if(!(value instanceof Boolean branch)){terminate(fiber.execution,State.FAILED,"condition-result-type");return;}fiber.stack.peek().branch=branch;}
        else{
            if(!(value instanceof String json)||!validResult(json)){terminate(fiber.execution,State.FAILED,"action-result-type");return;}
            trace(fiber,fiber.stack.peek().node.path(),"action-complete");fiber.stack.pop();if(fiber.stack.isEmpty())finishFiber(fiber);
        }
    }
    private static boolean validResult(String json){
        if(json.length()>65536||!StandardCharsets.UTF_8.newEncoder().canEncode(json)||json.getBytes(StandardCharsets.UTF_8).length>65536)return false;
        try{JsonDocuments.object(json);return true;}catch(java.io.IOException invalid){return false;}
    }
    public boolean cancel(UUID execution,String reason){owner();Execution found=executions.get(execution);if(found==null)return false;terminate(found,State.CANCELLED,bounded(reason));return true;}
    public int cancelNpc(NpcHandle npc){owner();int cancelled=0;for(Execution execution:List.copyOf(executions.values()))if(execution.context.npc().equals(npc)){terminate(execution,State.CANCELLED,"npc-cancelled");cancelled++;}return cancelled;}
    @Override public void close(){owner();if(closed)return;closed=true;for(Execution execution:List.copyOf(executions.values()))terminate(execution,State.CANCELLED,"engine-closed");}
    public Optional<Report> inspect(UUID execution){owner();Execution found=executions.get(execution);return found==null?Optional.ofNullable(history.get(execution)):Optional.of(report(found,State.RUNNING,"running"));}
    public Status status(){owner();int retired=0,unobserved=0;for(Pending call:calls){if(call.cancelled||call.outcomeHandled)retired++;if(call.observationFailed&&call.resolved.get()==null||call.quiescenceFailed)unobserved++;}return new Status(tick,executions.size(),fibers.size(),calls.size(),retired,unobserved,history.size(),cancellationFailures,quiescenceFailures,terminalObserverFailures,closed);}
    private void terminate(Execution execution,State state,String reason){
        if(executions.remove(execution.id)==null)return;
        lifecycleDepth++;
        try{
            // Retire all sibling authority before any adapter cancellation callback can reenter this engine.
            for(long id:List.copyOf(execution.fibers)){Fiber fiber=fibers.remove(id);if(fiber!=null)order.remove(fiber);}
            execution.fibers.clear();
            if(state!=State.COMPLETED)for(Pending pending:List.copyOf(calls))if(pending.execution.equals(execution.id))cancelCall(pending,execution);
            trace(execution,"$",state.name().toLowerCase(Locale.ROOT)+":"+bounded(reason));
            Report terminal=report(execution,state,bounded(reason));
            history.put(execution.id,terminal);while(history.size()>limits.historyEntries())history.remove(history.keySet().iterator().next());
            try{terminalObserver.accept(terminal);}catch(RuntimeException | AssertionError | LinkageError failure){terminalObserverFailures++;}
        }finally{lifecycleDepth--;}
    }
    private void cancelCall(Pending pending,Execution execution){if(pending.cancelled)return;pending.cancelled=true;try{pending.call.cancel().run();}catch(RuntimeException | AssertionError | LinkageError failure){cancellationFailures++;trace(execution,"$","cancel-hook-failed:"+failure.getClass().getSimpleName());}}
    private void add(Fiber fiber){fibers.put(fiber.id,fiber);fiber.execution.fibers.add(fiber.id);order.addLast(fiber);}
    private void finishFiber(Fiber fiber){
        fibers.remove(fiber.id);fiber.execution.fibers.remove(fiber.id);order.remove(fiber);
        if(fiber.parent==null)terminate(fiber.execution,State.COMPLETED,"complete");
        else{fiber.parent.results[fiber.branchIndex]=new Branch(Map.copyOf(fiber.variables),Set.copyOf(fiber.writes));fiber.parent.remaining--;}
    }
    private static void push(Fiber fiber,Node node){fiber.stack.push(new Frame(Objects.requireNonNull(node)));}
    private void trace(Fiber fiber,String path,String event){trace(fiber.execution,path,event);}
    private void trace(Execution execution,String path,String event){execution.trace.addLast(new Trace(tick,path,bounded(event)));while(execution.trace.size()>limits.traceEntries())execution.trace.removeFirst();}
    private static String bounded(String value){if(value==null)return "unspecified";return value.length()>256?value.substring(0,256):value;}
    private Report report(Execution execution,State state,String reason){
        List<String> waits=new ArrayList<>();for(long id:execution.fibers){
            Fiber fiber=fibers.get(id);
            if(fiber.awaiting!=null)waits.add((fiber.awaiting.condition?"condition:":"action:")+fiber.stack.peek().node.path());
            else if(fiber.wakeTick>tick)waits.add("tick:"+fiber.wakeTick);
            else if(!fiber.stack.isEmpty()){
                Frame frame=fiber.stack.peek();
                if(frame.join!=null&&frame.join.remaining>0)waits.add("parallel:"+fiber.id);
                else if(calls.size()>=limits.inFlight()&&(frame.node instanceof Action||frame.node instanceof Condition&&frame.branch==null))waits.add("capacity:invocation");
            }
        }
        return new Report(execution.id,execution.program.id(),state,reason,execution.steps,execution.root.variables,waits,List.copyOf(execution.trace),execution.diagnostics);
    }
    private final class Execution {
        final UUID id;final BehaviorProgram program;final Context context;final long started=tick;final Set<Long> fibers=new LinkedHashSet<>();final ArrayDeque<Trace> trace=new ArrayDeque<>();List<Diagnostic> diagnostics=List.of();Fiber root;long steps;
        Execution(UUID id,BehaviorProgram program,Context context){this.id=id;this.program=program;this.context=context;}
    }
    private final class Fiber {
        final long id=++nextFiber;final Execution execution;final Map<String,Value> variables;final Set<String> writes=new HashSet<>();final ArrayDeque<Frame> stack=new ArrayDeque<>();final Join parent;final int branchIndex;
        int callDepth;long wakeTick;Pending awaiting;
        Fiber(Execution execution,Map<String,Value> variables,Join parent,int branchIndex){this.execution=execution;this.variables=new HashMap<>(variables);this.parent=parent;this.branchIndex=branchIndex;}
    }
    private static final class Frame {final Node node;int index;Boolean branch;Join join;Frame(Node node){this.node=node;}}
    private static final class Join {final Fiber parent;final Branch[] results;int remaining;Join(Fiber parent,int count){this.parent=parent;results=new Branch[count];remaining=count;}}
    private record Branch(Map<String,Value> variables,Set<String> writes){}
    private record Resolved(Outcome<?> value,Throwable failure){}
    private static final class Pending {
        final UUID execution;final long fiber;final boolean condition;final BehaviorInvoker.Call<?> call;final AtomicReference<Resolved> resolved=new AtomicReference<>();boolean cancelled,observationFailed,outcomeHandled,quiescenceFailed;
        Pending(UUID execution,long fiber,boolean condition,BehaviorInvoker.Call<?> call){this.execution=execution;this.fiber=fiber;this.condition=condition;this.call=call;}
    }
}
