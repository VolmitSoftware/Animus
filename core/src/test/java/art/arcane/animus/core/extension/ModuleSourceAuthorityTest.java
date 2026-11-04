package art.arcane.animus.core.extension;

import art.arcane.animus.api.*;
import art.arcane.animus.api.Capabilities.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModuleSourceAuthorityTest {
    private static final NpcHandle NPC=new NpcHandle(UUID.randomUUID());
    private static final DefinitionGateway DEFINITIONS=new DefinitionGateway(){
        public DefinitionService scoped(java.util.function.BooleanSupplier available){return this;}
        public CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc){throw new UnsupportedOperationException();}
        public CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit){throw new UnsupportedOperationException();}
        public CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit){throw new UnsupportedOperationException();}
    };
    private static Descriptor descriptor(Kind kind){return new Descriptor(new Key("source","check"),kind,"Source authority test","{}",Set.of(Platform.PAPER),false);}
    private static TrustedModule module(java.util.function.Consumer<ModuleContext> enable){return new TrustedModule(){
        public ModuleManifest manifest(){return new ModuleManifest("source",new Version(1,0,0),new ApiRange(1,1),Set.of(Platform.PAPER),List.of());}
        public void enable(ModuleContext context){enable.accept(context);}public void disable(){}
    };}
    private static <T> T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(3,TimeUnit.SECONDS);}
    private static void eventually(java.util.function.BooleanSupplier ready)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!ready.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(1);assertTrue(ready.getAsBoolean());
    }
    @Test void failedSourceObservationCancelsConditionWithoutEnteringItsHandler()throws Exception{
        AtomicInteger callbacks=new AtomicInteger();
        try(var host=new ModuleHost(Platform.PAPER,DEFINITIONS,4,Duration.ofSeconds(2))){
            host.install(module(context->context.registrations().condition(descriptor(Kind.CONDITION),invocation->{callbacks.incrementAndGet();return CompletableFuture.completedFuture(true);})));
            var call=host.conditionHandle(new Key("source","check"),NPC,Optional.empty(),"{}",false,()->{throw new IllegalStateException("Source is unavailable");});
            var result=await(call.result());assertEquals(Outcome.Status.CANCELLED,result.status());assertEquals("source-authority-revoked",result.diagnostics().getFirst().code());
            eventually(call::isQuiescent);assertEquals(0,callbacks.get());assertEquals(ModuleHost.State.ACTIVE,host.modules().getFirst().state());
        }
    }
    @Test void heldConditionSeesLatchedSourceCancellationAndRetainsItsRealStage()throws Exception{
        AtomicBoolean current=new AtomicBoolean(true);AtomicReference<Cancellation> token=new AtomicReference<>();CompletableFuture<Boolean> raw=new CompletableFuture<>();
        try(var host=new ModuleHost(Platform.PAPER,DEFINITIONS,4,Duration.ofSeconds(2))){
            host.install(module(context->context.registrations().condition(descriptor(Kind.CONDITION),invocation->{token.set(invocation.cancellation());return raw;})));
            var call=host.conditionHandle(new Key("source","check"),NPC,Optional.empty(),"{}",false,current::get);eventually(()->token.get()!=null);
            current.set(false);assertTrue(token.get().cancelled());assertEquals(Outcome.Status.CANCELLED,await(call.result()).status());
            current.set(true);assertTrue(token.get().cancelled());assertFalse(call.isQuiescent());assertFalse(raw.isDone());assertEquals(1,host.outstandingInvocations());
            raw.complete(true);eventually(call::isQuiescent);assertEquals(ModuleHost.State.ACTIVE,host.modules().getFirst().state());
        }finally{raw.complete(false);}
    }
    @Test void completionRechecksSourceEvenWhenHandlerNeverChecksItsToken()throws Exception{
        AtomicBoolean current=new AtomicBoolean(true);CountDownLatch entered=new CountDownLatch(1);CompletableFuture<String> raw=new CompletableFuture<>();
        try(var host=new ModuleHost(Platform.PAPER,DEFINITIONS,4,Duration.ofSeconds(2))){
            host.install(module(context->context.registrations().action(descriptor(Kind.ACTION),invocation->{entered.countDown();return raw;})));
            var call=host.actionHandle(new Key("source","check"),NPC,Optional.empty(),"{}",false,false,current::get);assertTrue(entered.await(3,TimeUnit.SECONDS));
            current.set(false);raw.complete("{}");var result=await(call.result());assertEquals(Outcome.Status.CANCELLED,result.status());assertEquals("source-authority-revoked",result.diagnostics().getFirst().code());
            eventually(call::isQuiescent);assertEquals(0,host.outstandingInvocations());
        }finally{raw.complete("{}");}
    }
}
