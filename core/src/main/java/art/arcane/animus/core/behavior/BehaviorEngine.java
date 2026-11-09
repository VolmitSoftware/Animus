package art.arcane.animus.core.behavior;

import art.arcane.animus.api.*;
import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.behavior.BehaviorProgram.*;
import art.arcane.animus.core.storage.JsonDocuments;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

/** Manual, single-owner first-gate interpreter. Invoker callbacks only publish into bounded atomic completion slots. */
public final class BehaviorEngine implements AutoCloseable {
    public record Limits(int executions,int fibers,int inFlight,int workPerTick,int totalSteps,int lifetimeTicks,
                         int callDepth,int traceEntries,int historyEntries){
        public Limits{bound(executions,128);bound(fibers,512);bound(inFlight,64);bound(workPerTick,4096);bound(totalSteps,1_000_000);bound(lifetimeTicks,72000);bound(callDepth,32);bound(traceEntries,256);bound(historyEntries,256);}
        private static void bound(int value,int maximum){if(value<1||value>maximum)throw new IllegalArgumentException("Invalid behavior budget");}
        public static Limits defaults(){return new Limits(64,128,16,256,4096,20000,8,64,64);}
    }
    /** {@code event} holds the starting event's values (Boolean, Long, Double, String, List of String, Map of String to String), at most 32 keys, read as context.* by expressions. */
    public record Context(NpcHandle npc,Optional<UUID> player,boolean preview,java.util.function.BooleanSupplier sourceCurrent,Map<String,Object> event){
        public static final int MAX_EVENT_KEYS=32;
        public Context{
            Objects.requireNonNull(npc);Objects.requireNonNull(player);Objects.requireNonNull(sourceCurrent);
            if(event.size()>MAX_EVENT_KEYS)throw new IllegalArgumentException("At most "+MAX_EVENT_KEYS+" event values");
            for(Object value:event.values())if(!(value instanceof Boolean||value instanceof Long||value instanceof Double||value instanceof String||value instanceof List<?>||value instanceof Map<?,?>))throw new IllegalArgumentException("Unsupported event value");
            event=Map.copyOf(event);
        }
        public Context(NpcHandle npc,Optional<UUID> player,boolean preview,java.util.function.BooleanSupplier sourceCurrent){this(npc,player,preview,sourceCurrent,Map.of());}
        public Context(NpcHandle npc,Optional<UUID> player,boolean preview){this(npc,player,preview,()->true);}
    }
    public enum State { RUNNING, COMPLETED, CANCELLED, FAILED, UNAVAILABLE, BUDGET_EXHAUSTED }
    public record Trace(long tick,String path,String event){}
    public record Report(UUID id,String behavior,State state,String reason,long steps,Map<String,Value> variables,List<String> waits,List<Trace> trace,List<Diagnostic> diagnostics){
        public Report{variables=Map.copyOf(variables);waits=List.copyOf(waits);trace=List.copyOf(trace);diagnostics=List.copyOf(diagnostics);}
    }
    public record Status(long tick,int activeExecutions,int fibers,int callsInFlight,int retiredCalls,int unobservedCalls,int history,long cancellationFailures,long quiescenceFailures,long terminalObserverFailures,boolean closed){}
    public record Tick(int work,int instructions,int completions,Status status){}
    private final Thread owner=Thread.currentThread();private Limits limits;private final BehaviorInvoker invoker;
    private final Consumer<Report> terminalObserver;private final BehaviorState state;private final BehaviorFacts facts;private final RandomGenerator random;
    private final Map<UUID,Execution> executions=new LinkedHashMap<>();private final Map<Long,Fiber> fibers=new LinkedHashMap<>();
    private final ArrayDeque<Fiber> order=new ArrayDeque<>();private final Set<Pending> calls=new LinkedHashSet<>();
    private final LinkedHashMap<UUID,Report> history=new LinkedHashMap<>();
    private long tick,nextFiber,cancellationFailures,quiescenceFailures,terminalObserverFailures;private boolean closed,advancing;private int lifecycleDepth;
    /** Engine-owned quick observation only; never a module callback or public completion listener. */
    public BehaviorEngine(Limits limits,BehaviorInvoker invoker,Consumer<Report> terminalObserver,BehaviorState state){this(limits,invoker,terminalObserver,state,BehaviorFacts.NONE);}
    public BehaviorEngine(Limits limits,BehaviorInvoker invoker,Consumer<Report> terminalObserver,BehaviorState state,BehaviorFacts facts){this(limits,invoker,terminalObserver,state,facts,new SplittableRandom());}
    /** {@code random} serves random, random_decimal, chance and pick; it is used only on the owner thread. */
    public BehaviorEngine(Limits limits,BehaviorInvoker invoker,Consumer<Report> terminalObserver,BehaviorState state,BehaviorFacts facts,RandomGenerator random){
        this.limits=Objects.requireNonNull(limits);this.invoker=Objects.requireNonNull(invoker);this.terminalObserver=Objects.requireNonNull(terminalObserver);this.state=Objects.requireNonNull(state);
        this.facts=Objects.requireNonNull(facts);this.random=Objects.requireNonNull(random);
    }
    /**
     * Owner only. Admission, fiber, in-flight, work and history caps apply from the next tick. A running execution keeps the
     * step, lifetime, call-depth and trace budgets it started with, so a lowered cap never cuts it short.
     */
    public void limits(Limits next){owner();limits=Objects.requireNonNull(next);}
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
        BehaviorState.Subject subject=program.scoped().isEmpty()?null:new BehaviorState.Subject(program.id(),context.npc().uuid(),context.player());
        Map<String,Value> initial=program.initial();
        if(subject!=null){
            try{initial=scopedInitial(program,subject);}
            catch(BehaviorState.Rejected rejected){return new Outcome<>(rejected.status(),Optional.empty(),List.of(rejected.diagnostic()));}
        }
        Execution execution=new Execution(UUID.randomUUID(),program,context,subject);executions.put(execution.id,execution);
        Fiber first=new Fiber(execution,initial,null,-1);execution.root=first;push(first,root);add(first);trace(first,"$","started",null);return Outcome.ok(execution.id);
    }
    /** Admission refuses a missing player, an invalid stored document and a stored value of another type before any node runs. */
    private Map<String,Value> scopedInitial(BehaviorProgram program,BehaviorState.Subject subject){
        Map<String,Value> initial=new HashMap<>(program.initial());
        for(Map.Entry<String,ScopedVariable> variable:program.scoped().entrySet()){
            String name=variable.getKey();
            if(variable.getValue().scope().requiresPlayer()&&subject.player().isEmpty())
                throw new BehaviorState.Rejected(Outcome.Status.INVALID,"behavior-state-player-required","$.variables."+name+".scope","This variable belongs to a player, and this execution has none");
            initial.put(name,state.read(subject,name,variable.getValue(),program.initial().get(name),program.declared().get(name)));
        }
        return initial;
    }
    public Tick tick(){
        owner();if(advancing||lifecycleDepth>0)throw new IllegalStateException("Recursive behavior ticks/lifecycle entry are unavailable");advancing=true;
        try{return advance();}finally{advancing=false;}
    }
    private Tick advance(){
        tick=Math.incrementExact(tick);int instructions=0,work=0;
        for(Execution execution:List.copyOf(executions.values()))if(tick-execution.started>=execution.budget.lifetimeTicks())terminate(execution,State.BUDGET_EXHAUSTED,"execution-lifetime");
        int completions=resolveCalls();
        int visits=Math.min(order.size(),limits.workPerTick());
        for(int i=0;i<visits&&!order.isEmpty();i++){
            Fiber fiber=order.removeFirst();work++;
            if(!fibers.containsKey(fiber.id))continue;
            if(ready(fiber)){
                if(fiber.execution.steps>=fiber.execution.budget.totalSteps())terminate(fiber.execution,State.BUDGET_EXHAUSTED,"execution-step-budget");
                else {fiber.execution.steps++;instructions++;try{step(fiber);}catch(BehaviorExpression.Failed failed){fail(fiber.execution,fiber.stack.isEmpty()?"$":fiber.stack.peek().node.path(),failed.code(),failed.field(),failed.getMessage(),"expression-failed:"+failed.field()+":"+failed.getMessage());}catch(BehaviorState.Rejected rejected){refused(fiber.execution,fiber.stack.isEmpty()?"$":fiber.stack.peek().node.path(),rejected);}catch(RuntimeException | AssertionError | LinkageError failure){terminate(fiber.execution,State.FAILED,"engine-or-invoker: "+failure.getClass().getSimpleName());}}
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
            case Wait wait->{fiber.stack.pop();fiber.wakeTick=tick+wait.ticks();fiber.waitPath=node.path();trace(fiber,node.path(),"wait-until:",fiber.wakeTick);}
            case SetValue set->{
                Value value=operand(fiber,set.value(),declared(fiber,set.variable()),node.path()+".value");
                assign(fiber,set.variable(),value);fiber.stack.pop();trace(fiber,node.path(),"set:",set.variable());
            }
            case ExpressionCondition test->{
                if(!(test.expression().evaluate(environment(fiber)) instanceof Boolean branch))throw new BehaviorExpression.Failed("behavior-expression-type",test.expression().field(),"a condition expression must be true or false");
                fiber.stack.pop();trace(fiber,node.path(),"condition-result:",branch);push(fiber,branch?test.thenNode():test.elseNode());
            }
            case Repeat repeat->{
                if(!frame.started){
                    frame.started=true;frame.limit=repeat.count();
                    if(repeat.computed()!=null){
                        long count=(Long)operand(fiber,repeat.computed(),Type.INTEGER,node.path()+".count").value();
                        if(count>repeat.maximum()){loopBound(fiber,node,"count "+count+" exceeds maximum "+repeat.maximum());return;}
                        frame.limit=Math.max(0,count);
                    }
                }
                if(frame.index<frame.limit){
                    if(repeat.index()!=null)assign(fiber,repeat.index(),BehaviorValues.coerce((double)frame.index,declared(fiber,repeat.index())));
                    frame.index++;push(fiber,repeat.body());
                }else fiber.stack.pop();
            }
            case Foreach loop->{
                if(!frame.started){
                    frame.started=true;Value source=operand(fiber,loop.list(),Type.ANY,node.path()+".list");
                    if(source.type()==Type.LIST){List<Value> items=new ArrayList<>();for(Object item:(List<?>)source.value())items.add((Value)item);frame.items=items;}
                    else if(source.type()==Type.MAP){List<Value> items=new ArrayList<>();List<String> keys=new ArrayList<>();for(Map.Entry<?,?> entry:((Map<?,?>)source.value()).entrySet()){keys.add((String)entry.getKey());items.add((Value)entry.getValue());}frame.items=items;frame.keys=keys;}
                    else throw new BehaviorExpression.Failed("behavior-expression-type",node.path()+".list","foreach needs a list or map, got "+BehaviorValues.name(source.type()));
                    if(frame.items.size()>loop.maximum()){loopBound(fiber,node,frame.items.size()+" items exceed maximum "+loop.maximum());return;}
                }
                if(frame.index<frame.items.size()){
                    int pass=frame.index++;
                    assign(fiber,loop.as(),coerce(frame.items.get(pass),declared(fiber,loop.as()),node.path()+".as"));
                    if(loop.index()!=null)assign(fiber,loop.index(),BehaviorValues.coerce((double)pass,declared(fiber,loop.index())));
                    if(loop.key()!=null&&frame.keys!=null)assign(fiber,loop.key(),new Value(Type.STRING,frame.keys.get(pass)));
                    push(fiber,loop.body());
                }else fiber.stack.pop();
            }
            case While loop->{
                if(!(loop.expression().evaluate(environment(fiber)) instanceof Boolean again))throw new BehaviorExpression.Failed("behavior-expression-type",loop.expression().field(),"a while expression must be true or false");
                if(!again)fiber.stack.pop();
                else if(frame.index>=loop.maximum())loopBound(fiber,node,"more than "+loop.maximum()+" passes");
                else{frame.index++;push(fiber,loop.body());}
            }
            case Break ignored->{unwindToLoop(fiber);fiber.stack.pop();trace(fiber,node.path(),"break",null);}
            case Continue ignored->{unwindToLoop(fiber);trace(fiber,node.path(),"continue",null);}
            case Return ret->{
                Value value=ret.value()==null?null:operand(fiber,ret.value(),null,node.path()+".value");
                fiber.stack.pop();
                while(!fiber.stack.isEmpty()&&!(fiber.stack.peek().node instanceof Call&&fiber.stack.peek().index==1))fiber.stack.pop();
                trace(fiber,node.path(),"return",null);
                if(!fiber.stack.isEmpty()){
                    Call call=(Call)fiber.stack.peek().node;
                    if(value!=null&&call.result()!=null)assign(fiber,call.result(),coerce(value,declared(fiber,call.result()),call.path()+".result"));
                    fiber.callDepth--;fiber.stack.pop();
                }
            }
            case Call call->{
                if(frame.index==0){
                    for(Map.Entry<String,Operand> argument:call.arguments().entrySet())assign(fiber,argument.getKey(),operand(fiber,argument.getValue(),declared(fiber,argument.getKey()),node.path()+".arguments."+argument.getKey()));
                    if(++fiber.callDepth>fiber.execution.budget.callDepth()){terminate(fiber.execution,State.BUDGET_EXHAUSTED,"routine-depth");return;}frame.index=1;trace(fiber,node.path(),"call:",call.routine());push(fiber,fiber.execution.program.routines().get(call.routine()));
                }else{fiber.callDepth--;fiber.stack.pop();}
            }
            case Action action->invoke(fiber,action.capability(),action.arguments(),false);
            case Condition condition->{if(frame.branch==null)invoke(fiber,condition.capability(),condition.arguments(),true);else{fiber.stack.pop();push(fiber,frame.branch?condition.thenNode():condition.elseNode());}}
            case Parallel parallel->{
                if(frame.join==null){
                    if(fibers.size()+parallel.branches().size()>limits.fibers()){terminate(fiber.execution,State.BUDGET_EXHAUSTED,"parallel-fiber-budget");return;}
                    refresh(fiber);
                    frame.join=new Join(fiber,parallel.branches().size());
                    for(int i=0;i<parallel.branches().size();i++){Fiber child=new Fiber(fiber.execution,fiber.variables,frame.join,i);child.callDepth=fiber.callDepth;push(child,parallel.branches().get(i));add(child);}trace(fiber,node.path(),"parallel-start",null);
                }else{
                    Set<String> writes=new HashSet<>();for(Branch branch:frame.join.results)for(String name:branch.writes)if(!writes.add(name)){terminate(fiber.execution,State.FAILED,"parallel-write-conflict:"+name);return;}
                    Map<String,Value> published=new HashMap<>();
                    for(Branch branch:frame.join.results)for(String name:branch.writes)if(publishes(fiber,name))published.put(name,branch.variables.get(name));
                    if(!published.isEmpty())state.write(fiber.execution.subject,published,fiber.execution.program.scoped());
                    for(Branch branch:frame.join.results)for(String name:branch.writes){fiber.variables.put(name,branch.variables.get(name));fiber.writes.add(name);}
                    fiber.stack.pop();trace(fiber,node.path(),"parallel-joined",null);
                }
            }
        }
        if(fibers.containsKey(fiber.id)&&fiber.stack.isEmpty()&&fiber.awaiting==null&&fiber.wakeTick<=tick)finishFiber(fiber);
    }
    private void invoke(Fiber fiber,Capabilities.Key key,ArgumentTemplate template,boolean condition){
        Capabilities.Kind kind=condition?Capabilities.Kind.CONDITION:Capabilities.Kind.ACTION;
        boolean available=invoker.available(key,kind,fiber.execution.program.id());if(!current(fiber))return;
        if(!available){terminate(fiber.execution,State.UNAVAILABLE,"capability-unavailable:"+key);return;}
        refresh(fiber);
        String arguments;
        try{arguments=template.resolve(fiber.variables,environment(fiber),invoker.holes(key,fiber.execution.program.id()));}
        catch(ArgumentTemplate.Invalid invalid){terminate(fiber.execution,State.FAILED,"arguments-invalid:"+invalid.field()+":"+invalid.getMessage());return;}
        BehaviorInvoker.Invocation invocation=new BehaviorInvoker.Invocation(key,fiber.execution.context.npc(),fiber.execution.context.player(),arguments,fiber.variables,fiber.execution.context.preview(),fiber.execution.context.sourceCurrent(),fiber.execution.program.id());
        BehaviorInvoker.Call<?> call=condition?invoker.condition(invocation):invoker.action(invocation);
        Pending pending=new Pending(fiber.execution.id,fiber.id,condition,Objects.requireNonNull(call));calls.add(pending);fiber.awaiting=pending;
        trace(fiber,fiber.stack.peek().node.path(),"invoking:",key);
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
            for(Diagnostic diagnostic:fiber.execution.diagnostics)trace(fiber,fiber.stack.peek().node.path(),"diagnostic:",diagnostic.code());
            State state=switch(result.status()){case UNAVAILABLE->State.UNAVAILABLE;case CANCELLED->State.CANCELLED;default->State.FAILED;};
            terminate(fiber.execution,state,"invocation-"+result.status().name().toLowerCase(Locale.ROOT));return;
        }
        Object value=result.value().orElse(null);
        if(pending.condition){if(!(value instanceof Boolean branch)){terminate(fiber.execution,State.FAILED,"condition-result-type");return;}fiber.stack.peek().branch=branch;trace(fiber,fiber.stack.peek().node.path(),"condition-result:",branch);}
        else{
            if(!(value instanceof String json)||!validResult(json)){terminate(fiber.execution,State.FAILED,"action-result-type");return;}
            trace(fiber,fiber.stack.peek().node.path(),"action-complete",null);fiber.stack.pop();if(fiber.stack.isEmpty())finishFiber(fiber);
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
            trace(execution,"$",state.name().toLowerCase(Locale.ROOT)+":",bounded(reason));
            Report terminal=report(execution,state,bounded(reason));
            history.put(execution.id,terminal);while(history.size()>limits.historyEntries())history.remove(history.keySet().iterator().next());
            try{terminalObserver.accept(terminal);}catch(RuntimeException | AssertionError | LinkageError failure){terminalObserverFailures++;}
        }finally{lifecycleDepth--;}
    }
    private void cancelCall(Pending pending,Execution execution){if(pending.cancelled)return;pending.cancelled=true;try{pending.call.cancel().run();}catch(RuntimeException | AssertionError | LinkageError failure){cancellationFailures++;trace(execution,"$","cancel-hook-failed:",failure.getClass().getSimpleName());}}
    private void add(Fiber fiber){fibers.put(fiber.id,fiber);fiber.execution.fibers.add(fiber.id);order.addLast(fiber);}
    private void finishFiber(Fiber fiber){
        fibers.remove(fiber.id);fiber.execution.fibers.remove(fiber.id);order.remove(fiber);
        if(fiber.parent==null)terminate(fiber.execution,State.COMPLETED,"complete");
        else{fiber.parent.results[fiber.branchIndex]=new Branch(Map.copyOf(fiber.variables),Set.copyOf(fiber.writes));fiber.parent.remaining--;}
    }
    private static void push(Fiber fiber,Node node){fiber.stack.push(new Frame(Objects.requireNonNull(node)));}
    private void trace(Fiber fiber,String path,String event,Object detail){trace(fiber.execution,path,event,detail);}
    private void trace(Execution execution,String path,String event,Object detail){execution.trace.add(tick,path,event,detail);}
    private void refused(Execution execution,String path,BehaviorState.Rejected rejected){
        Diagnostic diagnostic=rejected.diagnostic();execution.diagnostics=List.of(diagnostic);
        trace(execution,path,"diagnostic:",diagnostic.code());terminate(execution,State.FAILED,diagnostic.code());
    }
    /** Only the root fiber of a non-preview execution shares scoped state. Branches and previews work on private copies. */
    private static boolean live(Fiber fiber){return fiber.parent==null&&fiber.execution.subject!=null&&!fiber.execution.context.preview();}
    private static boolean publishes(Fiber fiber,String name){return live(fiber)&&fiber.execution.program.scoped().containsKey(name);}
    private Value current(Fiber fiber,String name){
        if(publishes(fiber,name))fiber.variables.put(name,state.read(fiber.execution.subject,name,fiber.execution.program.scoped().get(name),fiber.execution.program.initial().get(name)));
        return fiber.variables.get(name);
    }
    private void refresh(Fiber fiber){if(live(fiber))for(String name:fiber.execution.program.scoped().keySet())current(fiber,name);}
    private static Type declared(Fiber fiber,String name){return fiber.execution.program.declared().get(name);}
    /** Writes a declared variable as set does: scoped values of the live root fiber publish at once. */
    private void assign(Fiber fiber,String name,Value value){
        if(publishes(fiber,name))state.write(fiber.execution.subject,Map.of(name,value),fiber.execution.program.scoped());
        fiber.variables.put(name,value);fiber.writes.add(name);
    }
    /** The operand's value converted to {@code target}; a null target keeps the value's own type. */
    private Value operand(Fiber fiber,Operand operand,Type target,String field){
        Type type=target==null?Type.ANY:target;
        return switch(operand){
            case Literal literal->coerce(literal.value(),type,field);
            case Variable variable->coerce(current(fiber,variable.name()),type,field+".variable");
            case Expression expression->{
                Object result=expression.expression().evaluate(environment(fiber));
                try{yield BehaviorValues.coerce(result,type);}catch(IllegalArgumentException invalid){throw new BehaviorExpression.Failed("behavior-expression-type",expression.field(),invalid.getMessage());}
            }
            case Template template->coerce(new Value(Type.STRING,limited(template.template().evaluate(environment(fiber)),template.field())),type,template.field());
        };
    }
    private static String limited(String text,String field){
        try{new Value(Type.STRING,text);return text;}catch(IllegalArgumentException invalid){throw new BehaviorExpression.Failed("behavior-expression-type",field,"the text exceeds "+BehaviorValues.MAX_STRING_BYTES+" UTF-8 bytes");}
    }
    private static Value coerce(Value value,Type type,String field){
        try{return BehaviorValues.coerce(value,type);}catch(IllegalArgumentException invalid){throw new BehaviorExpression.Failed("behavior-expression-type",field,invalid.getMessage());}
    }
    /** Pops frames above the innermost loop; the compiler guarantees one exists in this fiber. */
    private static void unwindToLoop(Fiber fiber){
        while(!fiber.stack.isEmpty()&&!(fiber.stack.peek().node instanceof Repeat||fiber.stack.peek().node instanceof Foreach||fiber.stack.peek().node instanceof While))fiber.stack.pop();
    }
    private void loopBound(Fiber fiber,Node node,String message){fail(fiber.execution,node.path(),"behavior-loop-bound",node.path(),message,"behavior-loop-bound:"+node.path());}
    private void fail(Execution execution,String path,String code,String field,String message,String reason){
        Diagnostic diagnostic=new Diagnostic(Diagnostic.Severity.ERROR,code,field,bounded(message));execution.diagnostics=List.of(diagnostic);
        trace(execution,path,"diagnostic:",code);terminate(execution,State.FAILED,reason);
    }
    private BehaviorExpression.Environment environment(Fiber fiber){return new FiberEnvironment(fiber);}
    /** Expression reads for one fiber, on the owner thread. Flags and quests read this behavior namespace's state. */
    private final class FiberEnvironment implements BehaviorExpression.Environment {
        private final Fiber fiber;
        FiberEnvironment(Fiber fiber){this.fiber=fiber;}
        @Override public Value variable(String name){return fiber.execution.program.initial().containsKey(name)?current(fiber,name):null;}
        @Override public BehaviorFacts facts(){return facts;}
        @Override public Optional<UUID> player(){return fiber.execution.context.player();}
        @Override public UUID npc(){return fiber.execution.context.npc().uuid();}
        @Override public Map<String,Object> context(){return fiber.execution.context.event();}
        @Override public Optional<Value> flag(String scope,String name){
            Value value=state.read(subject(BehaviorFlags.FLAGS,scope),name,BehaviorFlags.declaration(scope),UNSET,Type.ANY);
            return value==UNSET?Optional.empty():Optional.of(value);
        }
        @Override public long quest(String scope,String name){return (Long)state.read(subject(BehaviorFlags.QUESTS,scope),name,BehaviorFlags.declaration(scope),UNSET_STAGE,Type.INTEGER).value();}
        @Override public RandomGenerator random(){return random;}
        @Override public Optional<Value> playerFlag(UUID player,String name){
            Value value=state.read(BehaviorFlags.subject(BehaviorFlags.FLAGS,fiber.execution.program.id(),fiber.execution.context.npc().uuid(),Optional.of(player)),name,BehaviorFlags.declaration("player"),UNSET,Type.ANY);
            return value==UNSET?Optional.empty():Optional.of(value);
        }
        private BehaviorState.Subject subject(String kind,String scope){
            if(BehaviorFlags.declaration(scope).scope().requiresPlayer()&&fiber.execution.context.player().isEmpty())throw new BehaviorExpression.Failed("behavior-expression-failed","$","a "+scope+" flag needs a player, and this run has none");
            return BehaviorFlags.subject(kind,fiber.execution.program.id(),fiber.execution.context.npc().uuid(),fiber.execution.context.player());
        }
    }
    /** Identity-compared marker the state returns for an unset or expired flag. */
    private static final Value UNSET=new Value(Type.STRING,"unset"),UNSET_STAGE=new Value(Type.INTEGER,0L);
    private static String bounded(String value){if(value==null)return "unspecified";return value.length()>256?value.substring(0,256):value;}
    private Report report(Execution execution,State state,String reason){
        List<String> waits=new ArrayList<>();for(long id:execution.fibers){
            Fiber fiber=fibers.get(id);
            if(fiber.awaiting!=null)waits.add(awaited(fiber.stack.peek().node));
            else if(fiber.wakeTick>tick)waits.add("tick:"+fiber.wakeTick+"@"+fiber.waitPath);
            else if(!fiber.stack.isEmpty()){
                Frame frame=fiber.stack.peek();
                if(frame.join!=null&&frame.join.remaining>0)waits.add("parallel:"+fiber.id);
                else if(calls.size()>=limits.inFlight()&&(frame.node instanceof Action||frame.node instanceof Condition&&frame.branch==null))waits.add("capacity:invocation");
            }
        }
        try{refresh(execution.root);}catch(BehaviorState.Rejected unreadable){/* The report keeps the root fiber's last read; the failing node reports the refusal. */}
        return new Report(execution.id,execution.program.id(),state,reason,execution.steps,execution.root.variables,waits,execution.trace.snapshot(),execution.diagnostics);
    }
    private static String awaited(Node node){
        return switch(node){
            case Action action->"action:"+action.capability()+"@"+action.path();
            case Condition condition->"condition:"+condition.capability()+"@"+condition.path();
            default->"call:"+node.path();
        };
    }
    private final class Execution {
        final UUID id;final BehaviorProgram program;final Context context;final BehaviorState.Subject subject;final long started=tick;final Limits budget=limits;final Set<Long> fibers=new LinkedHashSet<>();final TraceRing trace=new TraceRing(budget.traceEntries());List<Diagnostic> diagnostics=List.of();Fiber root;long steps;
        Execution(UUID id,BehaviorProgram program,Context context,BehaviorState.Subject subject){this.id=id;this.program=program;this.context=context;this.subject=subject;}
    }
    private final class Fiber {
        final long id=++nextFiber;final Execution execution;final Map<String,Value> variables;final Set<String> writes=new HashSet<>();final ArrayDeque<Frame> stack=new ArrayDeque<>();final Join parent;final int branchIndex;
        int callDepth;long wakeTick;String waitPath;Pending awaiting;
        Fiber(Execution execution,Map<String,Value> variables,Join parent,int branchIndex){this.execution=execution;this.variables=new HashMap<>(variables);this.parent=parent;this.branchIndex=branchIndex;}
    }
    /** Fixed slots with text assembled only when someone reads the trace, so an unobserved step allocates almost nothing. */
    private static final class TraceRing {
        private final long[] ticks;private final String[] paths,events;private final Object[] details;private int next,size;
        TraceRing(int capacity){ticks=new long[capacity];paths=new String[capacity];events=new String[capacity];details=new Object[capacity];}
        void add(long tick,String path,String event,Object detail){
            ticks[next]=tick;paths[next]=path;events[next]=event;details[next]=detail;
            next=(next+1)%ticks.length;if(size<ticks.length)size++;
        }
        List<Trace> snapshot(){
            List<Trace> result=new ArrayList<>(size);
            for(int offset=size;offset>0;offset--){
                int slot=Math.floorMod(next-offset,ticks.length);
                result.add(new Trace(ticks[slot],paths[slot],bounded(details[slot]==null?events[slot]:events[slot]+details[slot])));
            }
            return List.copyOf(result);
        }
    }
    /** {@code index} counts loop passes; loops fix their pass count or items when first visited. */
    private static final class Frame {final Node node;int index;Boolean branch;Join join;boolean started;long limit;List<Value> items;List<String> keys;Frame(Node node){this.node=node;}}
    private static final class Join {final Fiber parent;final Branch[] results;int remaining;Join(Fiber parent,int count){this.parent=parent;results=new Branch[count];remaining=count;}}
    private record Branch(Map<String,Value> variables,Set<String> writes){}
    private record Resolved(Outcome<?> value,Throwable failure){}
    private static final class Pending {
        final UUID execution;final long fiber;final boolean condition;final BehaviorInvoker.Call<?> call;final AtomicReference<Resolved> resolved=new AtomicReference<>();boolean cancelled,observationFailed,outcomeHandled,quiescenceFailed;
        Pending(UUID execution,long fiber,boolean condition,BehaviorInvoker.Call<?> call){this.execution=execution;this.fiber=fiber;this.condition=condition;this.call=call;}
    }
}
