package art.arcane.animus.core.behavior;

import art.arcane.animus.api.*;
import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.behavior.BehaviorEngine.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static art.arcane.animus.core.behavior.BehaviorCompilerTest.*;

class BehaviorEngineTest {
    static final String ACTION="{\"type\":\"action\",\"capability\":\"sample:greet\"}";
    static Context context(){return new Context(new NpcHandle(UUID.randomUUID()),Optional.empty(),true);}
    static UUID start(BehaviorEngine engine,BehaviorProgram program){return engine.start(program,context()).value().orElseThrow();}
    static Limits limits(int executions,int fibers,int calls,int work,int steps,int lifetime,int depth,int trace,int history){return new Limits(executions,fibers,calls,work,steps,lifetime,depth,trace,history);}
    static Report finish(BehaviorEngine engine,UUID id){for(int i=0;i<1000;i++){var report=engine.inspect(id).orElseThrow();if(report.state()!=State.RUNNING)return report;engine.tick();}throw new AssertionError("Execution did not finish");}
    static final class Fake implements BehaviorInvoker {
        final List<Invocation> actions=new ArrayList<>(),conditions=new ArrayList<>();final AtomicInteger cancellations=new AtomicInteger();boolean available=true;
        Function<Invocation,Call<String>> action=i->new Call<>(CompletableFuture.completedFuture(Outcome.ok("{}")),cancellations::incrementAndGet);
        Function<Invocation,Call<Boolean>> condition=i->new Call<>(CompletableFuture.completedFuture(Outcome.ok(true)),cancellations::incrementAndGet);
        public boolean available(Capabilities.Key key,Capabilities.Kind kind){return available;}
        public Call<String> action(Invocation invocation){actions.add(invocation);return action.apply(invocation);}
        public Call<Boolean> condition(Invocation invocation){conditions.add(invocation);return condition.apply(invocation);}
    }
    @Test void sequenceWaitRepeatRoutineAndTypedAssignmentCompleteDeterministically(){
        Fake invoker=new Fake();var engine=new BehaviorEngine(Limits.defaults(),invoker,ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);
        String vars="{\"visits\":{\"type\":\"integer\",\"initial\":0}}";
        String main="{\"type\":\"sequence\",\"steps\":[{\"type\":\"repeat\",\"count\":3,\"body\":{\"type\":\"call\",\"routine\":\"greet\"}},{\"type\":\"set\",\"variable\":\"visits\",\"value\":1}]}";
        String greet="{\"type\":\"sequence\",\"steps\":["+ACTION+",{\"type\":\"wait\",\"ticks\":2}]}";
        UUID id=start(engine,compile(document(vars,"\"main\":"+main+",\"greet\":"+greet)));var report=finish(engine,id);
        assertEquals(State.COMPLETED,report.state());assertEquals(3,invoker.actions.size());assertEquals(1L,report.variables().get("visits").value());
        assertTrue(report.trace().stream().anyMatch(t->t.event().startsWith("wait-until:")));assertTrue(invoker.actions.stream().allMatch(BehaviorInvoker.Invocation::preview));
    }
    @Test void waitAndAsyncConditionRemainVisibleUntilTheirOwnerTick(){
        Fake invoker=new Fake();CompletableFuture<Outcome<Boolean>> answer=new CompletableFuture<>();invoker.condition=i->new BehaviorInvoker.Call<>(answer,invoker.cancellations::incrementAndGet);
        var engine=new BehaviorEngine(Limits.defaults(),invoker,ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);String vars="{\"flag\":{\"type\":\"boolean\",\"initial\":false}}";
        String conditional="{\"type\":\"condition\",\"capability\":\"sample:allowed\",\"then\":{\"type\":\"set\",\"variable\":\"flag\",\"value\":true},\"else\":{\"type\":\"wait\",\"ticks\":3}}";
        UUID id=start(engine,compile(document(vars,"\"main\":"+conditional)));engine.tick();assertEquals(1,engine.status().callsInFlight());assertFalse(engine.inspect(id).orElseThrow().waits().isEmpty());
        answer.complete(Outcome.ok(false));assertEquals(1,engine.status().callsInFlight());engine.tick();assertEquals(0,engine.status().callsInFlight());
        var report=finish(engine,id);assertEquals(State.COMPLETED,report.state());assertEquals(false,report.variables().get("flag").value());assertTrue(engine.status().tick()>=5);
    }
    @Test void parallelJoinMergesOnlyIndependentTypedWrites(){
        Fake invoker=new Fake();var engine=new BehaviorEngine(Limits.defaults(),invoker,ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);String vars="{\"a\":{\"type\":\"integer\",\"initial\":0},\"b\":{\"type\":\"integer\",\"initial\":0}}";
        String parallel="{\"type\":\"parallel\",\"branches\":[{\"type\":\"set\",\"variable\":\"a\",\"value\":1},{\"type\":\"set\",\"variable\":\"b\",\"value\":2}]}";
        var report=finish(engine,start(engine,compile(document(vars,"\"main\":"+parallel))));assertEquals(State.COMPLETED,report.state());assertEquals(1L,report.variables().get("a").value());assertEquals(2L,report.variables().get("b").value());
        var conflict=finish(engine,start(engine,compile(document(vars,"\"main\":"+parallel.replace("\"variable\":\"b\"","\"variable\":\"a\"")))));
        assertEquals(State.FAILED,conflict.state());assertTrue(conflict.reason().contains("parallel-write-conflict"));assertEquals(0L,conflict.variables().get("a").value());
    }
    @Test void cancellationRetainsCallCapacityAndDiscardsLateSuccess(){
        Fake invoker=new Fake();CompletableFuture<Outcome<String>> pending=new CompletableFuture<>();invoker.action=i->new BehaviorInvoker.Call<>(pending,invoker.cancellations::incrementAndGet);
        var engine=new BehaviorEngine(limits(4,8,1,8,100,100,4,8,8),invoker,ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);var program=compile(document(ACTION));UUID first=start(engine,program);engine.tick();assertTrue(engine.cancel(first,"despawn"));
        assertEquals(1,invoker.cancellations.get());assertEquals(1,engine.status().retiredCalls());UUID second=start(engine,program);engine.tick();assertEquals(1,invoker.actions.size());assertEquals(0,engine.inspect(second).orElseThrow().steps());
        pending.complete(Outcome.ok("{}"));engine.tick();assertEquals(2,invoker.actions.size());assertEquals(State.CANCELLED,engine.inspect(first).orElseThrow().state());assertEquals(State.COMPLETED,finish(engine,second).state());
    }
    @Test void parallelFailureCancelsPendingSiblingWithoutBreakingTickQueue(){
        Fake invoker=new Fake();CompletableFuture<Outcome<String>> pending=new CompletableFuture<>();invoker.action=i->{if(invoker.actions.size()==2)throw new AssertionError("synthetic module failure");return new BehaviorInvoker.Call<>(pending,invoker.cancellations::incrementAndGet);};
        var engine=new BehaviorEngine(Limits.defaults(),invoker,ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);UUID id=start(engine,compile(document("{\"type\":\"parallel\",\"branches\":["+ACTION+","+ACTION+"]}")));
        engine.tick();engine.tick();assertEquals(State.FAILED,engine.inspect(id).orElseThrow().state());assertEquals(1,invoker.cancellations.get());assertEquals(1,engine.status().retiredCalls());pending.complete(Outcome.ok("{}"));engine.tick();assertEquals(0,engine.status().callsInFlight());
    }
    @Test void recursionStepLifetimeAndFiberBudgetsAreDistinct(){
        var recurse=new BehaviorEngine(limits(2,8,2,8,100,100,2,8,8),new Fake(),ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);var recursive=finish(recurse,start(recurse,compile(document("{\"type\":\"call\",\"routine\":\"main\"}"))));assertEquals("routine-depth",recursive.reason());
        var steps=new BehaviorEngine(limits(2,8,2,8,3,100,4,8,8),new Fake(),ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);assertEquals("execution-step-budget",finish(steps,start(steps,compile(document("{\"type\":\"repeat\",\"count\":128,\"body\":"+EMPTY+"}")))).reason());
        var lifetime=new BehaviorEngine(limits(2,8,2,8,100,3,4,8,8),new Fake(),ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);assertEquals("execution-lifetime",finish(lifetime,start(lifetime,compile(document("{\"type\":\"wait\",\"ticks\":100}")))).reason());
        var fibers=new BehaviorEngine(limits(2,2,2,8,100,100,4,8,8),new Fake(),ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);assertEquals("parallel-fiber-budget",finish(fibers,start(fibers,compile(document("{\"type\":\"parallel\",\"branches\":["+EMPTY+","+EMPTY+"]}")))).reason());
    }
    @Test void completionAtExpiredLifetimeCannotReviveExecution(){
        Fake invoker=new Fake();CompletableFuture<Outcome<String>> pending=new CompletableFuture<>();invoker.action=i->new BehaviorInvoker.Call<>(pending,invoker.cancellations::incrementAndGet);
        var engine=new BehaviorEngine(limits(2,8,2,8,100,2,4,8,8),invoker,ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);UUID id=start(engine,compile(document(ACTION)));engine.tick();pending.complete(Outcome.ok("{}"));engine.tick();
        assertEquals(State.BUDGET_EXHAUSTED,engine.inspect(id).orElseThrow().state());assertEquals(1,invoker.cancellations.get());assertEquals(0,engine.status().callsInFlight());
    }
    @Test void boundedRoundRobinMakesProgressAcrossExecutions(){
        Fake invoker=new Fake();var engine=new BehaviorEngine(limits(4,8,4,1,100,100,4,8,8),invoker,ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);List<UUID> ids=new ArrayList<>();for(int i=0;i<4;i++)ids.add(start(engine,compile(document(ACTION))));
        for(int i=0;i<8;i++){var tick=engine.tick();assertTrue(tick.work()<=1);assertTrue(tick.instructions()<=1);assertTrue(tick.completions()<=4);}
        assertEquals(4,invoker.actions.size());assertTrue(ids.stream().allMatch(id->engine.inspect(id).orElseThrow().state()==State.COMPLETED));
    }
    @Test void missingCapabilityAndMalformedResultDoNotRunFollowingActions(){
        Fake missing=new Fake();missing.available=false;var engine=new BehaviorEngine(Limits.defaults(),missing,ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);assertEquals(State.UNAVAILABLE,finish(engine,start(engine,compile(document(ACTION)))).state());assertTrue(missing.actions.isEmpty());
        Fake invalid=new Fake();invalid.action=i->new BehaviorInvoker.Call<>(CompletableFuture.completedFuture(Outcome.ok("[]")),()->{});var other=new BehaviorEngine(Limits.defaults(),invalid,ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);
        assertEquals(State.FAILED,finish(other,start(other,compile(document("{\"type\":\"sequence\",\"steps\":["+ACTION+","+ACTION+"]}")))).state());assertEquals(1,invalid.actions.size());
    }
    @Test void rejectedInvocationRetainsOnlyBoundedImmutableFieldDiagnosticsAndNodeContext(){
        List<Diagnostic> diagnostics=new ArrayList<>();
        for(int i=0;i<40;i++)diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR,"schema-type","$/field"+i,"Expected an integer"));
        Fake invoker=new Fake();invoker.action=i->new BehaviorInvoker.Call<>(CompletableFuture.completedFuture(new Outcome<String>(Outcome.Status.INVALID,Optional.empty(),diagnostics)),()->{});
        var engine=new BehaviorEngine(Limits.defaults(),invoker,ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);var report=finish(engine,start(engine,compile(document(ACTION))));
        assertEquals(State.FAILED,report.state());assertEquals(16,report.diagnostics().size());assertEquals("$/field0",report.diagnostics().getFirst().field());
        assertTrue(report.trace().stream().anyMatch(t->t.path().equals("$.routines.main")&&t.event().equals("diagnostic:schema-type")));
        assertThrows(UnsupportedOperationException.class,()->report.diagnostics().clear());diagnostics.clear();assertEquals(16,report.diagnostics().size());
    }
    @Test void ownerAuthorityAndOffThreadCompletionAreSeparated()throws Exception{
        Fake invoker=new Fake();CompletableFuture<Outcome<String>> pending=new CompletableFuture<>();invoker.action=i->new BehaviorInvoker.Call<>(pending,()->{});var engine=new BehaviorEngine(Limits.defaults(),invoker,ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);UUID id=start(engine,compile(document(ACTION)));engine.tick();
        CompletableFuture.runAsync(()->pending.complete(Outcome.ok("{}"))).get(1,TimeUnit.SECONDS);assertEquals(State.RUNNING,engine.inspect(id).orElseThrow().state());
        var rejected=CompletableFuture.runAsync(engine::tick);assertThrows(ExecutionException.class,()->rejected.get(1,TimeUnit.SECONDS));engine.tick();assertEquals(State.COMPLETED,engine.inspect(id).orElseThrow().state());
    }
    @Test void admissionHistoryTraceAndCloseAreBounded(){
        Fake invoker=new Fake();var engine=new BehaviorEngine(limits(1,8,2,8,100,100,4,3,2),invoker,ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);var program=compile(document("{\"type\":\"repeat\",\"count\":5,\"body\":"+ACTION+"}"));UUID old=start(engine,program);
        assertEquals(Outcome.Status.BUSY,engine.start(program,context()).status());assertEquals(3,finish(engine,old).trace().size());
        finish(engine,start(engine,compile(document(EMPTY))));finish(engine,start(engine,compile(document(EMPTY))));assertTrue(engine.inspect(old).isEmpty());assertEquals(2,engine.status().history());
        engine.close();assertEquals(Outcome.Status.UNAVAILABLE,engine.start(program,context()).status());
    }
    @Test void reentrantCancellationDuringInvokerAdmissionCancelsNewlyReturnedHandle(){
        Fake invoker=new Fake();AtomicReference<BehaviorEngine> reference=new AtomicReference<>();AtomicReference<UUID> id=new AtomicReference<>();CompletableFuture<Outcome<String>> pending=new CompletableFuture<>();
        invoker.action=i->{reference.get().cancel(id.get(),"replaced during admission");return new BehaviorInvoker.Call<>(pending,invoker.cancellations::incrementAndGet);};
        var engine=new BehaviorEngine(Limits.defaults(),invoker,ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);reference.set(engine);id.set(start(engine,compile(document(ACTION))));engine.tick();assertEquals(State.CANCELLED,engine.inspect(id.get()).orElseThrow().state());assertEquals(1,invoker.cancellations.get());assertEquals(1,engine.status().retiredCalls());
        pending.complete(Outcome.ok("{}"));engine.tick();assertEquals(0,engine.status().callsInFlight());
    }
    @Test void cancellationHookFailureIsVisibleAndDoesNotReleaseUnsettledCall(){
        Fake invoker=new Fake();CompletableFuture<Outcome<String>> pending=new CompletableFuture<>();invoker.action=i->new BehaviorInvoker.Call<>(pending,()->{throw new AssertionError("cancel failed");});var engine=new BehaviorEngine(Limits.defaults(),invoker,ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);
        UUID id=start(engine,compile(document(ACTION)));engine.tick();engine.close();assertEquals(1,engine.status().cancellationFailures());assertEquals(1,engine.status().retiredCalls());assertTrue(engine.inspect(id).orElseThrow().trace().stream().anyMatch(t->t.event().startsWith("cancel-hook-failed")));
        pending.complete(Outcome.ok("{}"));engine.tick();assertEquals(0,engine.status().callsInFlight());
    }
    @Test void invalidPreparedEntryIsRejectedBeforeAllocatingExecution(){
        var engine=new BehaviorEngine(Limits.defaults(),new Fake(),ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);
        var program=new BehaviorProgram("example:bad","missing",Map.of(),Map.of(),Map.of("main",new BehaviorProgram.Wait("$",1)),1,List.of());
        assertEquals(Outcome.Status.INVALID,engine.start(program,context()).status());assertEquals(0,engine.status().activeExecutions());assertEquals(0,engine.status().fibers());engine.tick();engine.close();
    }
    @Test void completionObserverFailureCannotReleaseUnknownAdmittedWork(){
        for(boolean installFirst:new boolean[]{false,true}){
            class Unobservable extends CompletableFuture<Outcome<String>> {
                @Override public CompletableFuture<Outcome<String>> whenComplete(java.util.function.BiConsumer<? super Outcome<String>,? super Throwable> observer){if(installFirst)super.whenComplete(observer);throw new UnsupportedOperationException("observer registration failed");}
            }
            Unobservable raw=new Unobservable();Fake invoker=new Fake();invoker.action=i->new BehaviorInvoker.Call<>(raw,invoker.cancellations::incrementAndGet);
            var engine=new BehaviorEngine(limits(4,8,1,8,100,100,4,8,8),invoker,ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);UUID id=start(engine,compile(document(ACTION)));engine.tick();
            assertEquals(State.FAILED,engine.inspect(id).orElseThrow().state());assertEquals("completion-observer-failed",engine.inspect(id).orElseThrow().reason());assertEquals(1,engine.status().unobservedCalls());assertEquals(1,invoker.cancellations.get());
            raw.complete(Outcome.ok("{}"));engine.tick();assertEquals(installFirst?0:1,engine.status().callsInFlight());
            if(!installFirst){UUID blocked=start(engine,compile(document(ACTION)));engine.tick();assertEquals(1,invoker.actions.size());assertTrue(engine.inspect(blocked).orElseThrow().waits().contains("capacity:invocation"));}
            engine.close();engine.tick();assertEquals(installFirst?0:1,engine.status().callsInFlight());
        }
    }
    @Test void cancellationHooksCannotTickReadySiblingsOrStartReplacementDuringLifecycleMutation(){
        for(boolean close:new boolean[]{false,true}){
            Fake invoker=new Fake();CompletableFuture<Outcome<String>> pending=new CompletableFuture<>();AtomicReference<BehaviorEngine> ref=new AtomicReference<>();AtomicReference<UUID> other=new AtomicReference<>();AtomicInteger callbacks=new AtomicInteger();
            invoker.action=i->new BehaviorInvoker.Call<>(pending,()->{
                callbacks.incrementAndGet();assertEquals(0,ref.get().status().fibers()-1,"All cancelled sibling authority must retire before callback; only the unrelated execution remains");
                assertThrows(IllegalStateException.class,()->ref.get().tick());
                assertEquals(close?Outcome.Status.UNAVAILABLE:Outcome.Status.BUSY,ref.get().start(compile(document(EMPTY)),context()).status());
                assertTrue(ref.get().cancel(other.get(),"nested cancellation remains allowed"));
            });
            var engine=new BehaviorEngine(limits(4,8,2,1,100,100,4,8,8),invoker,ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);ref.set(engine);
            UUID id=start(engine,compile(document("{\"type\":\"parallel\",\"branches\":["+ACTION+","+ACTION+"]}")));engine.tick();
            other.set(start(engine,compile(document("{\"type\":\"wait\",\"ticks\":100}"))));engine.tick();assertEquals(1,invoker.actions.size());
            if(close)engine.close();else assertTrue(engine.cancel(id,"cancel outside tick"));
            assertEquals(1,callbacks.get());assertEquals(1,invoker.actions.size());assertEquals(0,engine.status().fibers());assertEquals(State.CANCELLED,engine.inspect(id).orElseThrow().state());
            pending.complete(Outcome.ok("{}"));engine.tick();assertEquals(0,engine.status().callsInFlight());
        }
    }
    @Test void deliveredResultDoesNotReleaseAnExplicitlyUnquiescentAdapter(){
        Fake invoker=new Fake();AtomicBoolean quiet=new AtomicBoolean();invoker.action=i->new BehaviorInvoker.Call<>(CompletableFuture.completedFuture(Outcome.ok("{}")),()->{},quiet::get);
        var engine=new BehaviorEngine(limits(4,8,1,8,100,100,4,8,8),invoker,ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);UUID first=start(engine,compile(document(ACTION)));engine.tick();engine.tick();
        assertEquals(State.COMPLETED,engine.inspect(first).orElseThrow().state());assertEquals(1,engine.status().retiredCalls());
        UUID second=start(engine,compile(document(ACTION)));engine.tick();assertEquals(1,invoker.actions.size());assertTrue(engine.inspect(second).orElseThrow().waits().contains("capacity:invocation"));
        quiet.set(true);engine.tick();assertEquals(2,invoker.actions.size());assertEquals(State.COMPLETED,finish(engine,second).state());
    }
    @Test void brokenQuiescenceObservationIsReportedWithoutReleasingUnknownWork(){
        Fake invoker=new Fake();invoker.action=i->new BehaviorInvoker.Call<>(CompletableFuture.completedFuture(Outcome.ok("{}")),()->{},()->{throw new IllegalStateException("unknown settlement");});
        var engine=new BehaviorEngine(Limits.defaults(),invoker,ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);UUID id=start(engine,compile(document(ACTION)));engine.tick();engine.tick();
        assertEquals(State.COMPLETED,engine.inspect(id).orElseThrow().state());assertEquals(1,engine.status().quiescenceFailures());assertEquals(1,engine.status().unobservedCalls());
        engine.close();engine.tick();assertEquals(1,engine.status().callsInFlight());assertEquals(1,engine.status().quiescenceFailures());
    }
}
