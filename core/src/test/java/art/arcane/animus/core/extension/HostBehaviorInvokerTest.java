package art.arcane.animus.core.extension;

import art.arcane.animus.api.*;
import art.arcane.animus.api.Capabilities.*;
import art.arcane.animus.core.behavior.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HostBehaviorInvokerTest {
    private static final NpcHandle NPC=new NpcHandle(UUID.randomUUID());
    private static final DefinitionGateway DEFINITIONS=new DefinitionGateway(){
        public DefinitionService scoped(java.util.function.BooleanSupplier available){return this;}
        public CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc){throw new UnsupportedOperationException();}
        public CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit){throw new UnsupportedOperationException();}
        public CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit){throw new UnsupportedOperationException();}
    };
    private static <T> T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(3,TimeUnit.SECONDS);}
    private static void latch(CountDownLatch latch){try{assertTrue(latch.await(3,TimeUnit.SECONDS));}catch(InterruptedException failure){throw new AssertionError(failure);}}
    private static void eventually(java.util.function.BooleanSupplier check)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!check.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(2);assertTrue(check.getAsBoolean());}
    private static Descriptor descriptor(String name,Kind kind){return new Descriptor(new Key("sample",name),kind,"Test","{}",Set.of(Platform.PAPER),false);}
    private static TrustedModule module(Consumer<ModuleContext> enable){return new TrustedModule(){
        public ModuleManifest manifest(){return new ModuleManifest("sample",new Version(1,0,0),new ApiRange(1,1),Set.of(Platform.PAPER),List.of());}
        public void enable(ModuleContext context){enable.accept(context);}public void disable(){}
    };}
    private static ModuleHost host(int limit,long deadlineMillis){return new ModuleHost(Platform.PAPER,DEFINITIONS,limit,Duration.ofMillis(deadlineMillis));}
    private static ModuleHost.InvocationHandle<String> action(ModuleHost host,String name){return host.actionHandle(new Key("sample",name),NPC,Optional.empty(),"{}",true,false);}
    private static BehaviorProgram program(){return BehaviorCompiler.compile("{\"schemaVersion\":1,\"id\":\"example:adapter\",\"entry\":\"main\",\"routines\":{\"main\":{\"type\":\"action\",\"capability\":\"sample:wait\"}}}").program().orElseThrow();}
    @Test void behaviorCancellationUsesRealHostTokenAndRetainsBothLayersUntilQuiescent()throws Exception{
        CompletableFuture<String> raw=new CompletableFuture<>();AtomicReference<Cancellation> token=new AtomicReference<>();
        try(var host=host(2,2000)){
            host.install(module(c->c.registrations().action(descriptor("wait",Kind.ACTION),i->{token.set(i.cancellation());return raw;})));
            var engine=new BehaviorEngine(BehaviorEngine.Limits.defaults(),new HostBehaviorInvoker(host),ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);UUID id=engine.start(program(),new BehaviorEngine.Context(NPC,Optional.empty(),true)).value().orElseThrow();engine.tick();eventually(()->token.get()!=null);
            assertTrue(engine.cancel(id,"npc despawn simulation"));assertTrue(token.get().cancelled());eventually(()->host.invocationStatus().queuedDeliveries()==0);engine.tick();
            assertEquals(1,engine.status().callsInFlight());assertEquals(1,host.outstandingInvocations());assertEquals(BehaviorEngine.State.CANCELLED,engine.inspect(id).orElseThrow().state());assertEquals(ModuleHost.State.ACTIVE,host.modules().getFirst().state());
            raw.complete("{}");eventually(()->host.outstandingInvocations()==0);engine.tick();assertEquals(0,engine.status().callsInFlight());engine.close();
        }finally{raw.complete("{}");}
    }
    @Test void behaviorCanObserveTimeoutWhileRetainingUnquiescentHostWork()throws Exception{
        CompletableFuture<String> raw=new CompletableFuture<>();
        try(var host=host(1,75)){
            host.install(module(c->c.registrations().action(descriptor("wait",Kind.ACTION),i->raw)));
            var engine=new BehaviorEngine(BehaviorEngine.Limits.defaults(),new HostBehaviorInvoker(host),ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);UUID id=engine.start(program(),new BehaviorEngine.Context(NPC,Optional.empty(),true)).value().orElseThrow();engine.tick();
            eventually(()->host.modules().getFirst().state()==ModuleHost.State.SUSPENDED);
            for(int i=0;i<100&&engine.inspect(id).orElseThrow().state()==BehaviorEngine.State.RUNNING;i++){engine.tick();Thread.sleep(2);}
            assertEquals(BehaviorEngine.State.FAILED,engine.inspect(id).orElseThrow().state());assertEquals(1,engine.status().retiredCalls());assertEquals(1,host.outstandingInvocations());
            raw.complete("{}");eventually(()->host.outstandingInvocations()==0);engine.tick();assertEquals(0,engine.status().callsInFlight());engine.close();
        }finally{raw.complete("{}");}
    }
    @Test void copiedHostHandlersReceiveResolvedTypedArgumentsWithoutImplicitVariableInjection()throws Exception{
        AtomicReference<String> conditionArgs=new AtomicReference<>(),actionArgs=new AtomicReference<>();
        try(var host=host(4,2000)){
            host.install(module(c->{
                c.registrations().condition(descriptor("check",Kind.CONDITION),i->{conditionArgs.set(i.argumentsJson());return CompletableFuture.completedFuture(art.arcane.animus.core.storage.JsonDocuments.object(i.argumentsJson()).get("allowed").getAsBoolean());});
                c.registrations().action(descriptor("record",Kind.ACTION),i->{actionArgs.set(i.argumentsJson());return CompletableFuture.completedFuture("{}");});
            }));
            String source="""
                {"schemaVersion":1,"id":"example:quest/binding","entry":"main",
                 "variables":{"allowed":{"type":"boolean","initial":true},"count":{"type":"integer","initial":9223372036854775807},"label":{"type":"string","initial":"Garde Étoile"},"secret":{"type":"string","initial":"private"}},
                 "routines":{"main":{"type":"condition","capability":"sample:check","arguments":{"allowed":null},"bindings":{"/allowed":{"variable":"allowed"}},
                   "then":{"type":"action","capability":"sample:record","arguments":{"count":null,"label":null},"bindings":{"/count":{"variable":"count"},"/label":{"variable":"label"}}}}}}
                """;
            var prepared=BehaviorCompiler.compile(source);assertTrue(prepared.program().isPresent(),prepared.diagnostics().toString());
            var engine=new BehaviorEngine(BehaviorEngine.Limits.defaults(),new HostBehaviorInvoker(host),ignored->{},art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE);UUID id=engine.start(prepared.program().orElseThrow(),new BehaviorEngine.Context(NPC,Optional.empty(),true)).value().orElseThrow();
            for(int i=0;i<1000&&engine.inspect(id).orElseThrow().state()==BehaviorEngine.State.RUNNING;i++){engine.tick();Thread.sleep(2);}
            assertEquals(BehaviorEngine.State.COMPLETED,engine.inspect(id).orElseThrow().state());
            var condition=art.arcane.animus.core.storage.JsonDocuments.object(conditionArgs.get());var action=art.arcane.animus.core.storage.JsonDocuments.object(actionArgs.get());
            assertEquals(Set.of("allowed"),condition.keySet());assertTrue(condition.getAsJsonPrimitive("allowed").isBoolean());assertTrue(condition.get("allowed").getAsBoolean());
            assertEquals(Set.of("count","label"),action.keySet());assertEquals(Long.MAX_VALUE,action.get("count").getAsBigDecimal().longValueExact());assertEquals("Garde Étoile",action.get("label").getAsString());
            eventually(()->host.outstandingInvocations()==0);engine.tick();assertEquals(0,engine.status().callsInFlight());engine.close();
        }
    }
}
