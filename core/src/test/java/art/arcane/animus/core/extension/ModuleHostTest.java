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
import static art.arcane.animus.core.extension.MutationLaneTest.*;

class ModuleHostTest {
    private static final class ThrowingObserverStage<T> extends CompletableFuture<T> {
        final boolean register;
        ThrowingObserverStage(boolean register){this.register=register;}
        @Override public CompletableFuture<T> whenComplete(java.util.function.BiConsumer<? super T,? super Throwable> observer){
            if(register)super.whenComplete(observer);
            throw new IllegalStateException("Broken observer registration");
        }
    }
    @Test void observerRegistrationFailureCannotReleaseUnobservedWorkOrAdmitUnlimitedHandlers()throws Exception {
        ThrowingObserverStage<String> raw=new ThrowingObserverStage<>(false);
        try(ModuleHost host=new ModuleHost(Platform.PAPER,DEFINITIONS,1,Duration.ofSeconds(1))){
            host.install(module("broken",c->c.registrations().action(descriptor("broken","run",Kind.ACTION),i->raw)));
            host.install(module("other",c->c.registrations().action(descriptor("other","run",Kind.ACTION),i->CompletableFuture.completedFuture("{}"))));
            assertEquals(Outcome.Status.FAILED,await(action(host,"broken","run")).status());
            assertEquals(ModuleHost.State.SUSPENDED,host.modules().getFirst().state());
            assertEquals(1,host.invocationStatus().unobservedHandlers());
            assertEquals(Outcome.Status.BUSY,await(action(host,"other","run")).status());
            raw.complete("{}");host.close();
            assertEquals(1,host.outstandingInvocations(),"Unobservable work must not be silently declared finished");
        }
    }
    @Test void registerThenThrowPublishesFailureOnceAndRetainsUntilActualLateCallback()throws Exception {
        ThrowingObserverStage<String> raw=new ThrowingObserverStage<>(true);
        try(ModuleHost host=host()){
            host.install(module("broken",c->c.registrations().action(descriptor("broken","run",Kind.ACTION),i->raw)));
            var result=action(host,"broken","run");AtomicInteger publications=new AtomicInteger();result.thenAccept(value->publications.incrementAndGet());
            assertEquals(Outcome.Status.FAILED,await(result).status());assertEquals(1,host.invocationStatus().unobservedHandlers());
            raw.complete("{}");eventually(()->host.outstandingInvocations()==0);
            assertEquals(Outcome.Status.FAILED,await(result).status());assertEquals(1,publications.get());
        }
    }
    @Test void synchronousActualCompletionBeforeObserverThrowKeepsItsOutcome()throws Exception {
        ThrowingObserverStage<String> raw=new ThrowingObserverStage<>(true);raw.complete("{\"settled\":true}");
        try(ModuleHost host=host()){
            host.install(module("settled",c->c.registrations().action(descriptor("settled","run",Kind.ACTION),i->raw)));
            var result=await(action(host,"settled","run"));assertEquals(Outcome.Status.OK,result.status());
            assertEquals("{\"settled\":true}",result.value().orElseThrow());
            eventually(()->host.outstandingInvocations()==0);assertEquals(ModuleHost.State.ACTIVE,host.modules().getFirst().state());
        }
    }
    private static final class RecordingSink implements art.arcane.animus.core.behavior.trigger.TriggerSink {
        final List<art.arcane.animus.core.behavior.trigger.TriggerEvent> events=new CopyOnWriteArrayList<>();volatile boolean available=true;
        public boolean available(){return available;}
        public String availability(){return available?"available":"NPC runtime unavailable on Folia";}
        public Outcome<Boolean> publish(art.arcane.animus.core.behavior.trigger.TriggerEvent event){events.add(event);return Outcome.ok(true);}
    }
    @Test void modulesPublishOnlyTheirOwnRegisteredTriggers()throws Exception {
        AtomicReference<ModuleContext> context=new AtomicReference<>();RecordingSink sink=new RecordingSink();
        try(ModuleHost host=host()){
            host.connectTriggers(sink);
            host.install(module("sample",c->{context.set(c);c.registrations().trigger(descriptor("sample","door",Kind.TRIGGER));c.registrations().action(descriptor("sample","act",Kind.ACTION),i->CompletableFuture.completedFuture("{}"));}));
            host.install(module("other",c->c.registrations().trigger(descriptor("other","door",Kind.TRIGGER))));
            NpcHandle npc=new NpcHandle(UUID.randomUUID());UUID player=UUID.randomUUID();
            assertTrue(host.capabilityAvailable(new Key("sample","door"),Kind.TRIGGER,false,false));
            assertTrue(host.capabilities().stream().filter(a->a.descriptor().key().equals(new Key("sample","door"))).findFirst().orElseThrow().available());
            assertEquals(Outcome.ok(true),context.get().triggers().publish(new Key("sample","door"),npc,Optional.of(player)));
            assertEquals(List.of(new art.arcane.animus.core.behavior.trigger.TriggerEvent.Module(npc.uuid(),Optional.of(player),new Key("sample","door"))),sink.events);
            assertEquals(Outcome.Status.NOT_FOUND,context.get().triggers().publish(new Key("other","door"),npc,Optional.empty()).status());
            assertEquals(Outcome.Status.NOT_FOUND,context.get().triggers().publish(new Key("sample","act"),npc,Optional.empty()).status());
            assertEquals(Outcome.Status.NOT_FOUND,context.get().triggers().publish(new Key("sample","missing"),npc,Optional.empty()).status());
            assertEquals(1,sink.events.size());
        }
    }
    @Test void unavailableDispatchIsReportedThroughThePublisherAndCapabilityListing()throws Exception {
        AtomicReference<ModuleContext> context=new AtomicReference<>();RecordingSink sink=new RecordingSink();sink.available=false;
        try(ModuleHost host=host()){
            host.connectTriggers(sink);host.install(module("sample",c->{context.set(c);c.registrations().trigger(descriptor("sample","door",Kind.TRIGGER));}));
            var listed=host.capabilities().getFirst();assertFalse(listed.available());assertTrue(listed.reason().contains("Folia"));
            Outcome<Boolean> outcome=context.get().triggers().publish(new Key("sample","door"),new NpcHandle(UUID.randomUUID()),Optional.empty());
            assertEquals(Outcome.Status.UNAVAILABLE,outcome.status());assertTrue(outcome.diagnostics().getFirst().message().contains("Folia"));assertEquals(List.of(),sink.events);
            assertTrue(host.capabilityAvailable(new Key("sample","door"),Kind.TRIGGER,false,false),"manual behaviors naming the trigger stay runnable");
        }
    }
    @Test void revokedHostRefusesRetainedPublishers()throws Exception {
        AtomicReference<ModuleContext> context=new AtomicReference<>();RecordingSink sink=new RecordingSink();
        ModuleHost host=host();
        try{
            host.connectTriggers(sink);host.install(module("sample",c->{context.set(c);c.registrations().trigger(descriptor("sample","door",Kind.TRIGGER));}));
            host.revoke();
            assertEquals(Outcome.Status.UNAVAILABLE,context.get().triggers().publish(new Key("sample","door"),new NpcHandle(UUID.randomUUID()),Optional.empty()).status());assertEquals(List.of(),sink.events);
        }finally{host.close();}
    }
    @Test void revocationDoesNotWaitForTrustedEnableAndRejectsRetainedSchedulerAndActions()throws Exception {
        CountDownLatch enabling=new CountDownLatch(1),finishEnable=new CountDownLatch(1),called=new CountDownLatch(1);
        List<String> cleanup=Collections.synchronizedList(new ArrayList<>());
        AtomicReference<ModuleContext> context=new AtomicReference<>();
        AtomicReference<Cancellation> token=new AtomicReference<>();
        CompletableFuture<String> raw=new CompletableFuture<>();
        ModuleHost host=host();
        CompletableFuture<Outcome<ModuleHost.ModuleStatus>> installing=null;
        try {
            host.install(module(manifest("first"),c->{
                context.set(c);c.onClose(()->cleanup.add("first-resource"));
                c.registrations().action(descriptor("first","hold",Kind.ACTION),i->{token.set(i.cancellation());called.countDown();return raw;});
            },()->cleanup.add("first-disable")));
            var pending=action(host,"first","hold");release(called);
            installing=CompletableFuture.supplyAsync(()->host.install(module(manifest("second"),c->{
                c.onClose(()->cleanup.add("second-resource"));
                c.registrations().trigger(descriptor("second","never",Kind.TRIGGER));
                enabling.countDown();release(finishEnable);
            },()->cleanup.add("second-disable"))));
            release(enabling);await(CompletableFuture.runAsync(host::revoke));
            assertTrue(host.invocationStatus().closed());assertTrue(token.get().cancelled());
            assertEquals(Outcome.Status.CANCELLED,await(pending).status());
            assertEquals(Outcome.Status.UNAVAILABLE,await(action(host,"first","hold")).status());
            assertEquals(Outcome.Status.UNAVAILABLE,await(context.get().scheduler().submit(cancelled->"{}")).status());
            assertEquals(Outcome.Status.UNAVAILABLE,host.install(module("late",c->fail("Revoked host enabled a module"))).status());
            finishEnable.countDown();assertEquals(Outcome.Status.FAILED,await(installing).status());
            assertTrue(host.capabilities().stream().noneMatch(c->c.descriptor().key().namespace().equals("second")));
            host.close();host.close();
            assertEquals(List.of("second-disable","second-resource","first-disable","first-resource"),cleanup);
            assertEquals(1,host.outstandingInvocations());raw.complete("{}");eventually(()->host.outstandingInvocations()==0);
        }finally{finishEnable.countDown();raw.complete("{}");if(installing!=null)await(installing);host.close();}
    }
    private static final NpcHandle NPC=new NpcHandle(UUID.randomUUID());
    private static final DefinitionGateway DEFINITIONS=new DefinitionGateway(){
        public DefinitionService scoped(java.util.function.BooleanSupplier available){return this;}
        public CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc){return CompletableFuture.completedFuture(Outcome.rejected(Outcome.Status.NOT_FOUND,"missing","Missing"));}
        public CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit){return CompletableFuture.completedFuture(Outcome.rejected(Outcome.Status.UNAVAILABLE,"offline","Offline"));}
        public CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit){return CompletableFuture.completedFuture(Outcome.rejected(Outcome.Status.UNAVAILABLE,"offline","Offline"));}
    };
    private static ModuleManifest manifest(String id,String...dependencies){return new ModuleManifest(id,new Version(1,0,0),new ApiRange(1,1),Set.of(Platform.PAPER),Arrays.stream(dependencies).map(dep->new ModuleManifest.Dependency(dep,new Version(1,0,0),new Version(2,0,0))).toList());}
    private static TrustedModule module(ModuleManifest manifest,Consumer<ModuleContext> enable,Runnable disable){return new TrustedModule(){public ModuleManifest manifest(){return manifest;}public void enable(ModuleContext c){enable.accept(c);}public void disable(){disable.run();}};}
    private static TrustedModule module(String id,Consumer<ModuleContext> enable){return module(manifest(id),enable,()->{});}
    private static Descriptor descriptor(String namespace,String name,Kind kind){return new Descriptor(new Key(namespace,name),kind,"Example","{\"type\":\"object\"}",Set.of(Platform.PAPER),false);}
    private static ModuleHost host(){return new ModuleHost(Platform.PAPER,DEFINITIONS,8,Duration.ofSeconds(2));}
    private static CompletionStage<Outcome<String>> action(ModuleHost host,String module,String name){return host.action(new Key(module,name),NPC,Optional.empty(),"{}",false,false);}
    private static void eventually(java.util.function.BooleanSupplier predicate)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!predicate.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(2);assertTrue(predicate.getAsBoolean());
    }
    @Test void compatibleModulesCommitTypedActionsConditionsAndMetadata()throws Exception{
        try(var host=host()){
            assertEquals(Outcome.Status.OK,host.install(module("sample",c->{
                c.registrations().action(descriptor("sample","hello",Kind.ACTION),i->CompletableFuture.completedFuture("{\"ok\":true}"));
                c.registrations().condition(descriptor("sample","false",Kind.CONDITION),i->CompletableFuture.completedFuture(false));
                c.registrations().trigger(descriptor("sample","arrival",Kind.TRIGGER));
            })).status());
            assertEquals(Outcome.Status.OK,await(action(host,"sample","hello")).status());
            assertEquals(false,await(host.condition(new Key("sample","false"),NPC,Optional.empty(),"{}",false)).value().orElseThrow());
            assertEquals(3,host.capabilities().size());
            var trigger=host.capabilities().stream().filter(a->a.descriptor().kind()==Kind.TRIGGER).findFirst().orElseThrow();
            assertFalse(trigger.available());assertEquals("trigger-dispatch-unwired",trigger.reason());
            assertThrows(UnsupportedOperationException.class,()->host.capabilities().clear());
        }
    }
    @Test void incompatibleApiPlatformDependenciesAndReservedIdentityNeverEnable(){
        AtomicInteger enabled=new AtomicInteger();
        try(var host=host()){
            for(var manifest:List.of(
                    new ModuleManifest("future",new Version(1,0,0),new ApiRange(2,2),Set.of(Platform.PAPER),List.of()),
                    new ModuleManifest("fabric",new Version(1,0,0),new ApiRange(1,1),Set.of(Platform.FABRIC),List.of()),
                    manifest("dependent","missing"))){
                assertEquals(Outcome.Status.UNAVAILABLE,host.install(module(manifest,c->enabled.incrementAndGet(),()->{})).status());
            }
            assertEquals(Outcome.Status.INVALID,host.install(module("animus",c->enabled.incrementAndGet())).status());
            assertEquals(0,enabled.get());assertTrue(host.capabilities().isEmpty());
        }
    }
    @Test void startupFailureRollsBackAllStagedCapabilitiesAndResources(){
        List<String> cleanup=new ArrayList<>();
        try(var host=host()){
            var result=host.install(module(manifest("broken"),c->{
                c.onClose(()->cleanup.add("resource1"));c.onClose(()->cleanup.add("resource2"));
                c.registrations().action(descriptor("broken","valid",Kind.ACTION),i->CompletableFuture.completedFuture("{}"));
                c.registrations().action(descriptor("foreign","bad",Kind.ACTION),i->CompletableFuture.completedFuture("{}"));
            },()->cleanup.add("disable")));
            assertEquals(Outcome.Status.FAILED,result.status());assertTrue(host.capabilities().isEmpty());
            assertEquals(List.of("disable","resource2","resource1"),cleanup);
            assertEquals(Outcome.Status.INVALID,host.install(module("broken",c->{})).status());
        }
        assertEquals(3,cleanup.size(),"Failed module cleanup must run only once");
    }
    @Test void duplicateAndMalformedSchemaRegistrationAreAtomicFailures(){
        try(var host=host()){
            assertEquals(Outcome.Status.FAILED,host.install(module("dupe",c->{
                var descriptor=descriptor("dupe","same",Kind.ACTION);c.registrations().action(descriptor,i->CompletableFuture.completedFuture("{}"));c.registrations().action(descriptor,i->CompletableFuture.completedFuture("{}"));
            })).status());
            assertEquals(Outcome.Status.FAILED,host.install(module("schema",c->c.registrations().trigger(new Descriptor(new Key("schema","bad"),Kind.TRIGGER,"Bad","[]",Set.of(Platform.PAPER),false)))).status());
            assertTrue(host.capabilities().isEmpty());
        }
    }
    @Test void registrarAndResourcesSealWhenEnableReturns(){
        AtomicReference<ModuleContext> context=new AtomicReference<>();
        try(var host=host()){
            host.install(module("sealed",context::set));
            assertThrows(IllegalStateException.class,()->context.get().registrations().trigger(descriptor("sealed","late",Kind.TRIGGER)));
            assertThrows(IllegalStateException.class,()->context.get().onClose(()->{}));
        }
    }
    @Test void shutdownIsReverseDependencyOrderAndSurvivesCleanupFailures(){
        List<String> order=new ArrayList<>();var host=host();
        host.install(module(manifest("base"),c->c.onClose(()->order.add("base-resource")),()->order.add("base-disable")));
        host.install(module(manifest("dependent","base"),c->{c.onClose(()->order.add("dependent-resource"));c.onClose(()->{throw new LinkageError("unlinked");});},()->{order.add("dependent-disable");throw new IllegalStateException();}));
        host.close();host.close();
        assertEquals(List.of("dependent-disable","dependent-resource","base-disable","base-resource"),order);
        assertTrue(host.modules().stream().allMatch(m->m.state()==ModuleHost.State.STOPPED));
        assertTrue(host.capabilities().isEmpty());assertTrue(host.modules().getLast().reason().contains("cleanup-failures=2"));
    }
    @Test void lifecycleCallbacksDoNotHoldRegistryMonitor(){
        try(var host=host()){
            Consumer<ModuleContext> callback=c->{
                try{assertEquals(1,CompletableFuture.supplyAsync(()->host.modules().size()).get(1,TimeUnit.SECONDS));}
                catch(Exception failure){throw new AssertionError("Registry monitor held during enable",failure);}
            };
            assertEquals(Outcome.Status.OK,host.install(module(manifest("reader"),callback,()->{
                try{CompletableFuture.supplyAsync(host::capabilities).get(1,TimeUnit.SECONDS);}catch(Exception failure){throw new AssertionError(failure);}
            })).status());
        }
    }
    @Test void malformedArgumentsDoNotReachHandlerAndPayloadErrorsStayPrivate()throws Exception{
        AtomicInteger invoked=new AtomicInteger();
        try(var host=host()){
            host.install(module("safe",c->c.registrations().action(descriptor("safe","run",Kind.ACTION),i->{invoked.incrementAndGet();throw new IllegalArgumentException("private secret");})));
            for(String json:List.of("[]","{bad","{\"a\":1,\"a\":2}","\uD800"))assertEquals(Outcome.Status.INVALID,await(host.action(new Key("safe","run"),NPC,Optional.empty(),json,false,false)).status());
            assertEquals(0,invoked.get());var failure=await(action(host,"safe","run"));assertEquals(Outcome.Status.FAILED,failure.status());assertFalse(failure.toString().contains("private secret"));
        }
    }
    @Test void privilegedPreviewAndPlatformGatesNeverInvoke()throws Exception{
        AtomicInteger invoked=new AtomicInteger();
        try(var host=host()){
            host.install(module("gated",c->{
                c.registrations().action(new Descriptor(new Key("gated","console"),Kind.ACTION,"Console","{}",Set.of(Platform.PAPER),true),i->{invoked.incrementAndGet();return CompletableFuture.completedFuture("{}");});
                c.registrations().action(new Descriptor(new Key("gated","fabric"),Kind.ACTION,"Fabric","{}",Set.of(Platform.FABRIC),false),i->{invoked.incrementAndGet();return CompletableFuture.completedFuture("{}");});
            }));
            assertEquals(Outcome.Status.UNAVAILABLE,await(action(host,"gated","console")).status());
            assertEquals(Outcome.Status.UNAVAILABLE,await(host.action(new Key("gated","console"),NPC,Optional.empty(),"{}",true,true)).status());
            assertEquals(Outcome.Status.UNAVAILABLE,await(action(host,"gated","fabric")).status());
            assertEquals(Outcome.Status.UNAVAILABLE,await(action(host,"missing","action")).status());assertEquals(0,invoked.get());
            assertEquals(Outcome.Status.OK,await(host.action(new Key("gated","console"),NPC,Optional.empty(),"{}",false,true)).status());assertEquals(1,invoked.get());
        }
    }
    @Test void malformedHandlerResultIsRejected()throws Exception{
        try(var host=host()){
            host.install(module("result",c->c.registrations().action(descriptor("result","run",Kind.ACTION),i->CompletableFuture.completedFuture("[]"))));
            assertEquals(Outcome.Status.INVALID,await(action(host,"result","run")).status());
        }
    }
    @Test void nonfatalAssertionFailuresAreIsolatedDuringEnableAndInvocation()throws Exception{
        AtomicInteger cleanup=new AtomicInteger();
        try(var host=host()){
            assertEquals(Outcome.Status.FAILED,host.install(module(manifest("assertion"),c->{c.onClose(cleanup::incrementAndGet);throw new AssertionError("private assertion");},()->{})).status());
            assertEquals(1,cleanup.get());
            host.install(module("running",c->c.registrations().action(descriptor("running","run",Kind.ACTION),i->{throw new AssertionError("private assertion");})));
            var failure=await(action(host,"running","run"));assertEquals(Outcome.Status.FAILED,failure.status());
            assertFalse(failure.toString().contains("private assertion"));eventually(()->host.outstandingInvocations()==0);
        }
    }
    @Test void timeoutSuspendsDependentsAndRetainsSlotUntilUnderlyingStageFinishes()throws Exception{
        CompletableFuture<String> raw=new CompletableFuture<>();AtomicReference<Cancellation> token=new AtomicReference<>();
        try(var host=new ModuleHost(Platform.PAPER,DEFINITIONS,1,Duration.ofMillis(150))){
            host.install(module("base",c->c.registrations().action(descriptor("base","hang",Kind.ACTION),i->{token.set(i.cancellation());return raw;})));
            host.install(module(manifest("middle","base"),c->{},()->{}));
            host.install(module(manifest("leaf","middle"),c->c.registrations().action(descriptor("leaf","run",Kind.ACTION),i->CompletableFuture.completedFuture("{}")),()->{}));
            var call=action(host,"base","hang");eventually(()->token.get()!=null);
            assertEquals(Outcome.Status.BUSY,await(action(host,"leaf","run")).status());
            assertEquals(Outcome.Status.TIMED_OUT,await(call).status());assertTrue(token.get().cancelled());
            assertEquals(1,host.outstandingInvocations());assertFalse(raw.isDone());
            assertEquals(Outcome.Status.UNAVAILABLE,await(action(host,"base","hang")).status());
            assertEquals(Outcome.Status.UNAVAILABLE,await(action(host,"leaf","run")).status());
            assertEquals(Outcome.Status.UNAVAILABLE,host.install(module(manifest("newleaf","middle"),c->{},()->{})).status());
            raw.complete("{}");eventually(()->host.outstandingInvocations()==0);
            assertEquals(Outcome.Status.TIMED_OUT,await(call).status());
        }
    }
    @Test void closeCancelsPublicResultButDoesNotClaimToStopTrustedStage()throws Exception{
        CompletableFuture<String> raw=new CompletableFuture<>();CountDownLatch started=new CountDownLatch(1);var host=host();
        host.install(module("pending",c->c.registrations().action(descriptor("pending","run",Kind.ACTION),i->{started.countDown();return raw;})));
        var call=action(host,"pending","run");release(started);host.close();
        assertEquals(Outcome.Status.CANCELLED,await(call).status());assertFalse(raw.isDone());assertEquals(1,host.outstandingInvocations());
        raw.complete("{}");eventually(()->host.outstandingInvocations()==0);
    }
    @Test void completedCallsCannotLaterSuspendModule()throws Exception{
        try(var host=new ModuleHost(Platform.PAPER,DEFINITIONS,8,Duration.ofMillis(100))){
            host.install(module("fast",c->c.registrations().action(descriptor("fast","run",Kind.ACTION),i->CompletableFuture.completedFuture("{}"))));
            for(int i=0;i<100;i++)assertEquals(Outcome.Status.OK,await(action(host,"fast","run")).status());
            Thread.sleep(150);assertEquals(ModuleHost.State.ACTIVE,host.modules().getFirst().state());assertEquals(0,host.outstandingInvocations());
        }
    }
    @Test void schedulerUsesTheSameBoundsAndRejectsStartupAndLateWork()throws Exception{
        AtomicReference<ModuleScheduler> scheduler=new AtomicReference<>();var host=host();
        host.install(module("schedule",c->{scheduler.set(c.scheduler());try{assertEquals(Outcome.Status.UNAVAILABLE,await(scheduler.get().submit(t->"{}" )).status());}catch(Exception failure){throw new AssertionError(failure);}}));
        assertEquals(Outcome.Status.OK,await(scheduler.get().submit(t->"{}")).status());host.close();
        assertEquals(Outcome.Status.UNAVAILABLE,await(scheduler.get().submit(t->"{}")).status());
    }
    @Test void blockedActionAndConditionListenersRetainBudgetWithoutBlockingHandlersOrDeadlines()throws Exception{
        CompletableFuture<String> actionRaw=new CompletableFuture<>(),lateRaw=new CompletableFuture<>();CompletableFuture<Boolean> conditionRaw=new CompletableFuture<>();
        CountDownLatch actionEntered=new CountDownLatch(1),conditionEntered=new CountDownLatch(1),lateEntered=new CountDownLatch(1),actionListener=new CountDownLatch(1),conditionListener=new CountDownLatch(1),unblock=new CountDownLatch(1);
        ModuleHost host=new ModuleHost(Platform.PAPER,DEFINITIONS,3,Duration.ofMillis(200),Duration.ofMillis(20));
        try{
            host.install(module("delivery",c->{
                c.registrations().action(descriptor("delivery","action",Kind.ACTION),i->{actionEntered.countDown();return actionRaw;});
                c.registrations().condition(descriptor("delivery","condition",Kind.CONDITION),i->{conditionEntered.countDown();return conditionRaw;});
                c.registrations().action(descriptor("delivery","late",Kind.ACTION),i->{lateEntered.countDown();return lateRaw;});
            }));
            var action=action(host,"delivery","action");release(actionEntered);action.thenRun(()->{actionListener.countDown();release(unblock);});
            assertTrue(await(CompletableFuture.supplyAsync(()->actionRaw.complete("{}"))));release(actionListener);
            var condition=host.condition(new Key("delivery","condition"),NPC,Optional.empty(),"{}",false);release(conditionEntered);
            condition.thenRun(()->{conditionListener.countDown();release(unblock);});assertTrue(await(CompletableFuture.supplyAsync(()->conditionRaw.complete(false))));release(conditionListener);
            var late=action(host,"delivery","late");release(lateEntered);
            assertEquals(Outcome.Status.BUSY,await(action(host,"delivery","action")).status());
            eventually(()->host.invocationStatus().timedOutHandlers()==1&&host.invocationStatus().queuedDeliveries()==1&&host.invocationStatus().overBudgetDeliveries()==2);
            assertEquals(ModuleHost.State.SUSPENDED,host.modules().getFirst().state());assertFalse(late.toCompletableFuture().isDone());
            await(CompletableFuture.runAsync(host::close));lateRaw.complete("{}");
            assertEquals(3,host.outstandingInvocations(),"Finished handlers still retain their blocked/queued delivery permits");
            assertEquals(0,host.invocationStatus().handlersOutstanding());
            unblock.countDown();assertEquals(Outcome.Status.OK,await(action).status());assertEquals(false,await(condition).value().orElseThrow());
            assertEquals(Outcome.Status.TIMED_OUT,await(late).status());eventually(()->host.outstandingInvocations()==0);
            assertTrue(host.invocationStatus().completedSlowDeliveries()>=2);
        }finally{unblock.countDown();actionRaw.complete("{}");conditionRaw.complete(false);lateRaw.complete("{}");host.close();}
    }
    @Test void blockingTimeoutListenerCannotBlockAnotherModulesDeadlineAndLateHandlersKeepSlots()throws Exception{
        CompletableFuture<String> firstRaw=new CompletableFuture<>(),secondRaw=new CompletableFuture<>();CountDownLatch listener=new CountDownLatch(1),unblock=new CountDownLatch(1);
        ModuleHost host=new ModuleHost(Platform.PAPER,DEFINITIONS,2,Duration.ofMillis(100),Duration.ofMillis(20));
        try{
            host.install(module("first",c->c.registrations().action(descriptor("first","wait",Kind.ACTION),i->firstRaw)));
            host.install(module("second",c->c.registrations().action(descriptor("second","wait",Kind.ACTION),i->secondRaw)));
            var first=action(host,"first","wait");first.thenRun(()->{listener.countDown();release(unblock);});release(listener);
            var second=action(host,"second","wait");assertEquals(Outcome.Status.TIMED_OUT,await(second).status());
            assertEquals(2,host.invocationStatus().timedOutHandlers());assertTrue(host.modules().stream().allMatch(m->m.state()==ModuleHost.State.SUSPENDED));
            secondRaw.complete("{}");eventually(()->host.outstandingInvocations()==1);
            firstRaw.complete("{}");assertEquals(1,host.outstandingInvocations(),"Returning a timed-out handler cannot release a still-blocked delivery slot");
            await(CompletableFuture.runAsync(host::close));assertEquals(0,host.invocationStatus().handlersOutstanding());
            unblock.countDown();assertEquals(Outcome.Status.TIMED_OUT,await(first).status());eventually(()->host.outstandingInvocations()==0);
        }finally{unblock.countDown();firstRaw.complete("{}");secondRaw.complete("{}");host.close();}
    }
    @Test void shutdownCancellationListenerDoesNotBlockCloseOrForgeHandlerTermination()throws Exception{
        CompletableFuture<String> raw=new CompletableFuture<>();CountDownLatch entered=new CountDownLatch(1),listener=new CountDownLatch(1),unblock=new CountDownLatch(1);
        var host=host();
        try{
            host.install(module("shutdown",c->c.registrations().action(descriptor("shutdown","wait",Kind.ACTION),i->{entered.countDown();return raw;})));
            var call=action(host,"shutdown","wait");release(entered);call.thenRun(()->{listener.countDown();release(unblock);});
            var closed=CompletableFuture.runAsync(host::close);release(listener);await(closed);
            assertEquals(1,host.invocationStatus().handlersOutstanding());assertEquals(1,host.outstandingInvocations());assertFalse(raw.isDone());
            raw.complete("{}");assertEquals(1,host.outstandingInvocations());assertEquals(0,host.invocationStatus().handlersOutstanding());
            unblock.countDown();assertEquals(Outcome.Status.CANCELLED,await(call).status());eventually(()->host.outstandingInvocations()==0);
        }finally{unblock.countDown();raw.complete("{}");host.close();}
    }
    @Test void publicFutureCopyCannotForgeHostOutcomeOrReleaseInvocationBudget()throws Exception{
        CompletableFuture<String> raw=new CompletableFuture<>();CountDownLatch entered=new CountDownLatch(1);
        try(var host=new ModuleHost(Platform.PAPER,DEFINITIONS,1,Duration.ofSeconds(1))){
            host.install(module("copy",c->c.registrations().action(descriptor("copy","wait",Kind.ACTION),i->{entered.countDown();return raw;})));
            var call=action(host,"copy","wait");release(entered);assertTrue(call.toCompletableFuture().complete(Outcome.ok("forged")));
            assertEquals(1,host.outstandingInvocations());assertEquals(Outcome.Status.BUSY,await(action(host,"copy","wait")).status());
            raw.complete("{}");assertEquals("{}",await(call).value().orElseThrow());eventually(()->host.outstandingInvocations()==0);
        }
    }
    @Test void concurrentCloseAndHandlerCompletionNeverStrandAcceptedAcknowledgment()throws Exception{
        for(int i=0;i<40;i++){
            CompletableFuture<String> raw=new CompletableFuture<>();CountDownLatch entered=new CountDownLatch(1),race=new CountDownLatch(1);
            var host=new ModuleHost(Platform.PAPER,DEFINITIONS,1,Duration.ofMillis(50));
            try{
                host.install(module("race",c->c.registrations().action(descriptor("race","run",Kind.ACTION),invocation->{entered.countDown();return raw;})));
                var result=action(host,"race","run");release(entered);
                var close=CompletableFuture.runAsync(()->{release(race);host.close();});
                var finish=CompletableFuture.runAsync(()->{release(race);raw.complete("{}");});race.countDown();await(close);await(finish);
                assertTrue(Set.of(Outcome.Status.OK,Outcome.Status.CANCELLED,Outcome.Status.TIMED_OUT).contains(await(result).status()));
                eventually(()->host.outstandingInvocations()==0);assertTrue(host.invocationStatus().closed());
            }finally{race.countDown();raw.complete("{}");host.close();}
        }
    }
}
