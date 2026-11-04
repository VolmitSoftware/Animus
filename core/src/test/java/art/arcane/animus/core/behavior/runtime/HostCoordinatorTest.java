package art.arcane.animus.core.behavior.runtime;

import art.arcane.animus.api.*;
import art.arcane.animus.api.Capabilities.*;
import art.arcane.animus.core.behavior.*;
import art.arcane.animus.core.extension.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static art.arcane.animus.core.behavior.runtime.ManualBehaviorRuntimeTest.*;

class HostCoordinatorTest {
    private static final DefinitionGateway DEFINITIONS=new DefinitionGateway(){
        public DefinitionService scoped(java.util.function.BooleanSupplier available){return this;}
        public CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc){throw new UnsupportedOperationException();}
        public CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit){throw new UnsupportedOperationException();}
        public CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit){throw new UnsupportedOperationException();}
    };
    private static TrustedModule module(java.util.function.Consumer<ModuleContext> enable){return new TrustedModule(){
        public ModuleManifest manifest(){return new ModuleManifest("sample",new Version(1,0,0),new ApiRange(1,1),Set.of(Platform.FOLIA),List.of());}
        public void enable(ModuleContext context){enable.accept(context);}public void disable(){}
    };}
    @Test void realHostCancellationRetainsBothLayersUntilRawWorkAndDeliveryActuallyFinish()throws Exception {
        Sources sources=new Sources(ACTION);CompletableFuture<String> raw=new CompletableFuture<>();AtomicReference<Cancellation> token=new AtomicReference<>();AtomicReference<Thread> handlerThread=new AtomicReference<>();AtomicReference<Invocation> invocation=new AtomicReference<>();
        try(ModuleHost host=new ModuleHost(Platform.FOLIA,DEFINITIONS,2,Duration.ofSeconds(3))){
            assertEquals(Outcome.Status.OK,host.install(module(context->context.registrations().action(new Descriptor(new Key("sample","act"),Kind.ACTION,"Test","{}",Set.of(Platform.FOLIA),false),call->{token.set(call.cancellation());handlerThread.set(Thread.currentThread());invocation.set(call);return raw;}))).status());
            UUID player=UUID.randomUUID();var runtime=new ManualBehaviorRuntime(ManualBehaviorRuntime.Limits.defaults(),BehaviorEngine.Limits.defaults(),sources.npcs::get,sources.catalog::get,new HostBehaviorInvoker(host));
            try{
                UUID id=await(runtime.run("test","example:manual",Optional.of(player))).value().orElseThrow();eventually(()->token.get()!=null);
                assertNotEquals("animus-behavior-owner",handlerThread.get().getName());assertFalse(invocation.get().preview());assertEquals(Optional.of(player),invocation.get().player());
                runtime.close();eventually(()->token.get().cancelled());assertFalse(runtime.awaitStopped(Duration.ofMillis(125)));
                assertEquals(1,host.outstandingInvocations());assertEquals(1,runtime.status().engine().callsInFlight());assertEquals(ModuleHost.State.ACTIVE,host.modules().getFirst().state());
                assertEquals(BehaviorEngine.State.CANCELLED,runtime.inspect(id).orElseThrow().report().state());
                raw.complete("{}");assertTrue(runtime.awaitStopped(Duration.ofSeconds(4)));assertEquals(0,host.outstandingInvocations());
            }finally{raw.complete("{}");stop(runtime);}
        }
    }
    @Test void privilegedModuleActionIsUnavailableWithoutImplicitGrant()throws Exception {
        Sources sources=new Sources(ACTION);AtomicInteger entered=new AtomicInteger();
        try(ModuleHost host=new ModuleHost(Platform.FOLIA,DEFINITIONS,2,Duration.ofSeconds(3))){
            host.install(module(context->context.registrations().action(new Descriptor(new Key("sample","act"),Kind.ACTION,"Privileged","{}",Set.of(Platform.FOLIA),true),call->{entered.incrementAndGet();return CompletableFuture.completedFuture("{}");})));
            var runtime=new ManualBehaviorRuntime(ManualBehaviorRuntime.Limits.defaults(),BehaviorEngine.Limits.defaults(),sources.npcs::get,sources.catalog::get,new HostBehaviorInvoker(host));
            try{UUID id=await(runtime.run("test","example:manual")).value().orElseThrow();eventually(()->runtime.inspect(id).orElseThrow().report().state()==BehaviorEngine.State.UNAVAILABLE);assertEquals(0,entered.get());}
            finally{stop(runtime);}
        }
    }
    @Test void queuedHostWorkerChecksChangedSourceBeforeCallbackWithoutWaitingForCoordinatorTick()throws Exception {
        for(boolean replaceProgram:new boolean[]{false,true}){
            Sources sources=new Sources(ACTION);CountDownLatch held=new CountDownLatch(2),release=new CountDownLatch(1),ownerHeld=new CountDownLatch(1),ownerRelease=new CountDownLatch(1);AtomicInteger entered=new AtomicInteger();AtomicBoolean holdOwner=new AtomicBoolean();
            try(ModuleHost host=new ModuleHost(Platform.FOLIA,DEFINITIONS,4,Duration.ofSeconds(10))){
                host.install(module(context->{
                    context.registrations().action(new Descriptor(new Key("sample","block"),Kind.ACTION,"Worker blocker","{}",Set.of(Platform.FOLIA),false),call->{held.countDown();latch(release);return CompletableFuture.completedFuture("{}");});
                    context.registrations().action(new Descriptor(new Key("sample","act"),Kind.ACTION,"Guarded","{}",Set.of(Platform.FOLIA),false),call->{entered.incrementAndGet();return CompletableFuture.completedFuture("{}");});
                }));
                NpcHandle npc=new NpcHandle(sources.npcs.get().get("test").uuid());
                var first=host.action(new Key("sample","block"),npc,Optional.empty(),"{}",false,false);var second=host.action(new Key("sample","block"),npc,Optional.empty(),"{}",false,false);latch(held);
                var runtime=new ManualBehaviorRuntime(new ManualBehaviorRuntime.Limits(8,8,50,20),BehaviorEngine.Limits.defaults(),()->{
                    if(Thread.currentThread().getName().equals("animus-behavior-owner")&&holdOwner.compareAndSet(true,false)){ownerHeld.countDown();latch(ownerRelease);}return sources.npcs.get();
                },sources.catalog::get,new HostBehaviorInvoker(host));
                try{
                    UUID id=await(runtime.run("test","example:manual")).value().orElseThrow();assertEquals(3,host.outstandingInvocations());holdOwner.set(true);latch(ownerHeld);long tick=runtime.status().engine().tick();
                    if(replaceProgram)sources.replaceProgram(ACTION,2);else sources.npcs.set(Map.of());
                    release.countDown();await(first);await(second);eventually(()->host.outstandingInvocations()==0);
                    assertEquals(tick,runtime.status().engine().tick(),"Host worker must reject before the coordinator's next periodic cancellation");assertEquals(0,entered.get());ownerRelease.countDown();
                    eventually(()->runtime.inspect(id).orElseThrow().report().state()!=BehaviorEngine.State.RUNNING);
                }finally{release.countDown();ownerRelease.countDown();stop(runtime);}
            }finally{release.countDown();ownerRelease.countDown();}
        }
    }
    @Test void heldHandlerCancellationObservesAndLatchesSourceRevocationWithoutNextTick()throws Exception {
        Sources sources=new Sources(ACTION);CompletableFuture<String> raw=new CompletableFuture<>();AtomicReference<Cancellation> token=new AtomicReference<>();AtomicBoolean holdOwner=new AtomicBoolean();CountDownLatch ownerHeld=new CountDownLatch(1),ownerRelease=new CountDownLatch(1);
        try(ModuleHost host=new ModuleHost(Platform.FOLIA,DEFINITIONS,2,Duration.ofMillis(500))){
            host.install(module(context->context.registrations().action(new Descriptor(new Key("sample","act"),Kind.ACTION,"Held","{}",Set.of(Platform.FOLIA),false),call->{token.set(call.cancellation());return raw;})));
            var runtime=new ManualBehaviorRuntime(new ManualBehaviorRuntime.Limits(8,8,50,20),BehaviorEngine.Limits.defaults(),()->{
                if(Thread.currentThread().getName().equals("animus-behavior-owner")&&holdOwner.compareAndSet(true,false)){ownerHeld.countDown();latch(ownerRelease);}return sources.npcs.get();
            },sources.catalog::get,new HostBehaviorInvoker(host));
            try{
                UUID id=await(runtime.run("test","example:manual")).value().orElseThrow();eventually(()->token.get()!=null);assertFalse(token.get().cancelled());holdOwner.set(true);latch(ownerHeld);long tick=runtime.status().engine().tick();var original=sources.npcs.get();
                sources.npcs.set(Map.of());assertTrue(token.get().cancelled());sources.npcs.set(original);assertTrue(token.get().cancelled(),"Cancellation must remain latched after observed revocation");assertEquals(tick,runtime.status().engine().tick());
                Thread.sleep(700);assertEquals(ModuleHost.State.ACTIVE,host.modules().getFirst().state());assertEquals(0,host.invocationStatus().timedOutHandlers());assertEquals(1,host.outstandingInvocations());raw.complete("{}");eventually(()->host.outstandingInvocations()==0);ownerRelease.countDown();
                eventually(()->runtime.inspect(id).orElseThrow().report().state()!=BehaviorEngine.State.RUNNING);assertEquals(BehaviorEngine.State.CANCELLED,runtime.inspect(id).orElseThrow().report().state());assertEquals("invocation-cancelled",runtime.inspect(id).orElseThrow().report().reason());
            }finally{raw.complete("{}");ownerRelease.countDown();stop(runtime);}
        }
    }
}
