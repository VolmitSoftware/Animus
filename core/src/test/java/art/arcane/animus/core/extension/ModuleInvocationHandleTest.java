package art.arcane.animus.core.extension;

import art.arcane.animus.api.*;
import art.arcane.animus.api.Capabilities.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModuleInvocationHandleTest {
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
    @Test void cancellingActionIsIdempotentCooperativeAndDoesNotSuspendModule()throws Exception{
        CompletableFuture<String> raw=new CompletableFuture<>();AtomicReference<Cancellation> token=new AtomicReference<>();
        try(var host=host(1,2000)){
            host.install(module(c->{c.registrations().action(descriptor("wait",Kind.ACTION),i->{token.set(i.cancellation());return raw;});c.registrations().action(descriptor("fast",Kind.ACTION),i->CompletableFuture.completedFuture("{}"));}));
            var handle=action(host,"wait");eventually(()->token.get()!=null);assertTrue(handle.cancel());assertFalse(handle.cancel());assertTrue(token.get().cancelled());
            assertEquals(Outcome.Status.CANCELLED,await(handle.result()).status());assertFalse(handle.isQuiescent());assertFalse(raw.isDone());assertEquals(ModuleHost.State.ACTIVE,host.modules().getFirst().state());
            var rejected=action(host,"fast");assertEquals(Outcome.Status.BUSY,await(rejected.result()).status());assertTrue(rejected.isQuiescent());assertFalse(rejected.cancel());
            raw.complete("{}");eventually(handle::isQuiescent);assertEquals(0,host.outstandingInvocations());
            assertEquals(Outcome.Status.OK,await(host.action(new Key("sample","fast"),NPC,Optional.empty(),"{}",true,false)).status());
        }finally{raw.complete("{}");}
    }
    @Test void conditionCancellationUsesSameTokenAndCancelsDeadlineWithoutSuspension()throws Exception{
        CompletableFuture<Boolean> raw=new CompletableFuture<>();AtomicReference<Cancellation> token=new AtomicReference<>();
        try(var host=host(1,300)){
            host.install(module(c->c.registrations().condition(descriptor("wait",Kind.CONDITION),i->{token.set(i.cancellation());return raw;})));
            var handle=host.conditionHandle(new Key("sample","wait"),NPC,Optional.empty(),"{}",true);eventually(()->token.get()!=null);assertTrue(handle.cancel());assertTrue(token.get().cancelled());
            assertEquals(Outcome.Status.CANCELLED,await(handle.result()).status());Thread.sleep(350);assertEquals(ModuleHost.State.ACTIVE,host.modules().getFirst().state());assertFalse(handle.isQuiescent());
            raw.complete(false);eventually(handle::isQuiescent);assertEquals(Outcome.Status.CANCELLED,await(handle.result()).status());
        }finally{raw.complete(false);}
    }
    @Test void queuedCancellationPreventsInvocationAndCanBecomeQuiescentBeforeRunningHandlers()throws Exception{
        CountDownLatch entered=new CountDownLatch(2),unblock=new CountDownLatch(1);AtomicInteger invoked=new AtomicInteger();
        try(var host=host(3,2000)){
            host.install(module(c->{c.registrations().action(descriptor("block",Kind.ACTION),i->{entered.countDown();latch(unblock);return CompletableFuture.completedFuture("{}");});c.registrations().condition(descriptor("queued",Kind.CONDITION),i->{invoked.incrementAndGet();return CompletableFuture.completedFuture(true);});}));
            var first=action(host,"block");var second=action(host,"block");latch(entered);
            var queued=host.conditionHandle(new Key("sample","queued"),NPC,Optional.empty(),"{}",true);assertTrue(queued.cancel());assertEquals(Outcome.Status.CANCELLED,await(queued.result()).status());eventually(queued::isQuiescent);
            assertEquals(0,invoked.get());assertFalse(first.isQuiescent());assertFalse(second.isQuiescent());unblock.countDown();eventually(()->first.isQuiescent()&&second.isQuiescent());
        }finally{unblock.countDown();}
    }
    @Test void finishedHandlerWithBlockedResultListenerIsNotQuiescent()throws Exception{
        CompletableFuture<String> raw=new CompletableFuture<>();CountDownLatch entered=new CountDownLatch(1),listener=new CountDownLatch(1),unblock=new CountDownLatch(1);
        try(var host=host(1,2000)){
            host.install(module(c->c.registrations().action(descriptor("wait",Kind.ACTION),i->{entered.countDown();return raw;})));
            var handle=action(host,"wait");latch(entered);handle.result().thenRun(()->{listener.countDown();latch(unblock);});raw.complete("{}");latch(listener);
            assertFalse(handle.isQuiescent());assertEquals(0,host.invocationStatus().handlersOutstanding());assertEquals(1,host.outstandingInvocations());assertFalse(handle.cancel());
            unblock.countDown();eventually(handle::isQuiescent);assertEquals(Outcome.Status.OK,await(handle.result()).status());
        }finally{unblock.countDown();raw.complete("{}");}
    }
    @Test void timeoutResultStillRetainsUnderlyingHandlerUntilActualSettlement()throws Exception{
        CompletableFuture<String> raw=new CompletableFuture<>();
        try(var host=host(1,75)){
            host.install(module(c->c.registrations().action(descriptor("wait",Kind.ACTION),i->raw)));var handle=action(host,"wait");
            assertEquals(Outcome.Status.TIMED_OUT,await(handle.result()).status());assertFalse(handle.isQuiescent());assertFalse(handle.cancel());assertEquals(ModuleHost.State.SUSPENDED,host.modules().getFirst().state());
            raw.complete("{}");eventually(handle::isQuiescent);assertEquals(Outcome.Status.TIMED_OUT,await(handle.result()).status());
        }finally{raw.complete("{}");}
    }
    @Test void brokenObserverHandleCannotClaimQuiescenceFromAnUnobservedRawStage()throws Exception{
        CompletableFuture<String> raw=new CompletableFuture<>(){
            @Override public CompletableFuture<String> whenComplete(java.util.function.BiConsumer<? super String,? super Throwable> callback){throw new UnsupportedOperationException("no terminal observation");}
        };
        try(var host=host(1,1000)){
            host.install(module(c->c.registrations().action(descriptor("wait",Kind.ACTION),i->raw)));var handle=action(host,"wait");
            assertEquals(Outcome.Status.FAILED,await(handle.result()).status());assertFalse(handle.isQuiescent());raw.complete("{}");assertFalse(handle.isQuiescent());host.close();assertFalse(handle.isQuiescent());assertEquals(1,host.invocationStatus().unobservedHandlers());
        }
    }
    @Test void cancelAndFinishRaceChoosesOneOutcomeAndNeverReleasesEarly()throws Exception{
        for(int i=0;i<40;i++){
            CompletableFuture<String> raw=new CompletableFuture<>();CountDownLatch entered=new CountDownLatch(1),race=new CountDownLatch(1);
            try(var host=host(1,2000)){
                host.install(module(c->c.registrations().action(descriptor("race",Kind.ACTION),invocation->{entered.countDown();return raw;})));var handle=action(host,"race");latch(entered);
                var cancel=CompletableFuture.supplyAsync(()->{latch(race);return handle.cancel();});var finish=CompletableFuture.runAsync(()->{latch(race);raw.complete("{}");});race.countDown();await(cancel);await(finish);
                assertTrue(Set.of(Outcome.Status.OK,Outcome.Status.CANCELLED).contains(await(handle.result()).status()));eventually(handle::isQuiescent);assertEquals(0,host.outstandingInvocations());assertEquals(ModuleHost.State.ACTIVE,host.modules().getFirst().state());
            }finally{raw.complete("{}");}
        }
    }
    @Test void singleCapabilityLookupKeepsKindPlatformPrivilegeAndRevocationGates(){
        try(var host=host(8,2000)){
            host.install(module(c->{
                c.registrations().action(descriptor("plain",Kind.ACTION),i->CompletableFuture.completedFuture("{}"));
                c.registrations().action(new Descriptor(new Key("sample","foreign"),Kind.ACTION,"Test","{}",Set.of(Platform.FABRIC),false),i->CompletableFuture.completedFuture("{}"));
                c.registrations().action(new Descriptor(new Key("sample","privileged"),Kind.ACTION,"Test","{}",Set.of(Platform.PAPER),true),i->CompletableFuture.completedFuture("{}"));
                c.registrations().condition(new Descriptor(new Key("sample","condition"),Kind.CONDITION,"Test","{}",Set.of(Platform.PAPER),true),i->CompletableFuture.completedFuture(true));
                c.registrations().trigger(descriptor("trigger",Kind.TRIGGER));
            }));
            assertTrue(host.capabilityAvailable(new Key("sample","plain"),Kind.ACTION,false,false));
            assertFalse(host.capabilityAvailable(new Key("sample","plain"),Kind.CONDITION,false,false));
            assertFalse(host.capabilityAvailable(new Key("sample","missing"),Kind.ACTION,false,false));
            assertFalse(host.capabilityAvailable(new Key("sample","foreign"),Kind.ACTION,false,false));
            assertFalse(host.capabilityAvailable(new Key("sample","trigger"),Kind.TRIGGER,false,false));
            assertFalse(host.capabilityAvailable(new Key("sample","privileged"),Kind.ACTION,false,false));
            assertFalse(host.capabilityAvailable(new Key("sample","privileged"),Kind.ACTION,true,true));
            assertTrue(host.capabilityAvailable(new Key("sample","privileged"),Kind.ACTION,false,true));
            assertFalse(host.capabilityAvailable(new Key("sample","condition"),Kind.CONDITION,false,true));
            host.revoke();assertFalse(host.capabilityAvailable(new Key("sample","plain"),Kind.ACTION,false,false));
        }
    }
    @Test void singleCapabilityLookupTracksTransitiveDependencySuspension()throws Exception{
        CompletableFuture<String> raw=new CompletableFuture<>();
        try(var host=host(8,75)){
            host.install(module(c->c.registrations().action(descriptor("fail",Kind.ACTION),i->raw)));
            for(String[] pair:List.of(new String[]{"middle","sample"},new String[]{"leaf","middle"})){
                host.install(new TrustedModule(){
                    public ModuleManifest manifest(){return new ModuleManifest(pair[0],new Version(1,0,0),new ApiRange(1,1),Set.of(Platform.PAPER),List.of(new ModuleManifest.Dependency(pair[1],new Version(1,0,0),new Version(2,0,0))));}
                    public void enable(ModuleContext c){c.registrations().action(new Descriptor(new Key(pair[0],"run"),Kind.ACTION,"Test","{}",Set.of(Platform.PAPER),false),i->CompletableFuture.completedFuture("{}"));}
                    public void disable(){}
                });
            }
            assertTrue(host.capabilityAvailable(new Key("leaf","run"),Kind.ACTION,false,false));
            assertEquals(Outcome.Status.TIMED_OUT,await(action(host,"fail").result()).status());
            assertFalse(host.capabilityAvailable(new Key("leaf","run"),Kind.ACTION,false,false));
            assertEquals(Outcome.Status.UNAVAILABLE,await(host.action(new Key("leaf","run"),NPC,Optional.empty(),"{}",false,false)).status());
        }finally{raw.complete("{}");}
    }
}
