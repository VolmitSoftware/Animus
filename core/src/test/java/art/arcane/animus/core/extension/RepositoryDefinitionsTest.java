package art.arcane.animus.core.extension;

import art.arcane.animus.api.*;
import art.arcane.animus.core.npc.*;
import art.arcane.animus.core.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static art.arcane.animus.core.extension.MutationLaneTest.*;

class RepositoryDefinitionsTest {
    @TempDir Path root;
    private static final String PATH="npcs/town/guard.json";
    private static final String JSON="""
      {"schemaVersion":2,"revision":1,"uuid":"12345678-1234-1234-1234-123456789abc","id":"guard","group":"town","name":"Guard","type":"minecraft:player","spawned":false,"position":{"world":"world","x":0,"y":64,"z":0,"yaw":0,"pitch":0},"modules":{"absent:module":{"preserve":true}}}
      """;
    private static final NpcHandle NPC=new NpcHandle(UUID.fromString("12345678-1234-1234-1234-123456789abc"));
    private DocumentStore store;private NpcRepository repository;private MutationLane lane;private RepositoryDefinitions service;
    @BeforeEach void setup() throws Exception {
        store=new DocumentStore(root);store.commit(List.of(new DocumentStore.Change(PATH,"missing",JSON)));
        repository=new NpcRepository(store);repository.loadForStartup();lane=new MutationLane(8);service=new RepositoryDefinitions(repository,lane);
    }
    @AfterEach void cleanup()throws Exception{service.close();lane.close();await(lane.drained());store.close();}
    private DefinitionSnapshot snapshot()throws Exception{return await(service.find(NPC)).value().orElseThrow();}
    private static DefinitionEdit rename(DefinitionSnapshot snapshot,String name){return new DefinitionEdit(snapshot.npc(),snapshot.token(),snapshot.json().replace("\"Guard\"","\""+name+"\""));}
    @Test void applyPublishesNewRevisionPreservesMissingModuleAndRejectsStaleToken()throws Exception{
        var before=snapshot();assertEquals(JsonDocuments.hash(JSON),before.token().sourceSha256());
        assertNotEquals(before.token().sourceSha256(),JsonDocuments.hash(before.json()),"Source token refers to accepted raw bytes, not normalized snapshot");
        var edit=rename(before,"Watchman");assertEquals(Outcome.Status.OK,await(service.validate(edit)).status());
        var after=await(service.apply(edit));assertEquals(Outcome.Status.OK,after.status());
        assertEquals(2,after.value().orElseThrow().token().revision());assertTrue(after.value().orElseThrow().json().contains("absent:module"));
        assertEquals(Outcome.Status.CONFLICT,await(service.apply(edit)).status());
        assertEquals("Watchman",repository.find("guard").orElseThrow().name());
    }
    @Test void externalRawEditConflictsWithoutOverwritingOrPublishing()throws Exception{
        var before=snapshot();String handEdit=JSON+"  ";Files.writeString(root.resolve(PATH),handEdit);
        assertEquals(Outcome.Status.CONFLICT,await(service.apply(rename(before,"Watchman"))).status());
        assertEquals(handEdit,Files.readString(root.resolve(PATH)));assertEquals("Guard",repository.find("guard").orElseThrow().name());
    }
    @Test void fieldDiagnosticsAndActivationUnavailableAreDistinct()throws Exception{
        var before=snapshot();var badJson=JsonDocuments.object(before.json());badJson.getAsJsonObject("position").addProperty("y","bad");
        var invalid=await(service.validate(new DefinitionEdit(NPC,before.token(),JsonDocuments.write(badJson))));
        assertEquals(Outcome.Status.INVALID,invalid.status());assertEquals("$.position.y",invalid.diagnostics().getFirst().field());
        badJson=JsonDocuments.object(before.json());badJson.addProperty("spawned",true);
        assertEquals(Outcome.Status.UNAVAILABLE,await(service.apply(new DefinitionEdit(NPC,before.token(),JsonDocuments.write(badJson)))).status());
        assertEquals(1,repository.find("guard").orElseThrow().revision());
    }
    @Test void replacementCannotChangeIdentityOrRevision()throws Exception{
        var before=snapshot();
        for(String field:List.of("uuid","id","group","revision")){
            var json=JsonDocuments.object(before.json());
            if(field.equals("revision"))json.addProperty(field,2);else json.addProperty(field,field.equals("uuid")?UUID.randomUUID().toString():"other");
            assertEquals(Outcome.Status.INVALID,await(service.apply(new DefinitionEdit(NPC,before.token(),JsonDocuments.write(json)))).status());
        }
    }
    @Test void twoFacadesAndImportMutationShareWholeOperationLane()throws Exception{
        var before=snapshot();var peer=new RepositoryDefinitions(repository,lane);
        CountDownLatch validated=new CountDownLatch(1),continueImport=new CountDownLatch(1);
        var importJob=lane.submit(()->{
            try{synchronized(repository){
                var draft=repository.draft("guard");validated.countDown();release(continueImport);
                var json=draft.document();json.addProperty("name","Imported");
                repository.apply(new NpcRepository.Draft(draft.path(),draft.expectedHash(),draft.expectedRevision(),json));
                return Outcome.ok("import-published");
            }}catch(java.io.IOException failure){throw new IllegalStateException(failure);}
        });
        try{
            release(validated);var stale=service.apply(rename(before,"Lost"));var read=peer.find(NPC);
            assertFalse(stale.toCompletableFuture().isDone());assertFalse(read.toCompletableFuture().isDone());
            continueImport.countDown();assertEquals(Outcome.Status.OK,await(importJob).status());
            assertEquals(Outcome.Status.CONFLICT,await(stale).status());
            var snapshot=await(read).value().orElseThrow();assertEquals(2,snapshot.token().revision());assertTrue(snapshot.json().contains("Imported"));
            peer.close();assertEquals(Outcome.Status.OK,await(service.find(NPC)).status(),"Closing a facade must not close the engine-owned shared lane");
        }finally{continueImport.countDown();peer.close();}
    }
    @Test void missingHandleIsAnExpectedOutcome()throws Exception{
        assertEquals(Outcome.Status.NOT_FOUND,await(service.find(new NpcHandle(UUID.randomUUID()))).status());
    }
    private static TrustedModule module(String id,java.util.function.Consumer<ModuleContext> enable){return new TrustedModule(){
        public ModuleManifest manifest(){return new ModuleManifest(id,new Version(1,0,0),new ApiRange(1,1),Set.of(Platform.PAPER),List.of());}
        public void enable(ModuleContext context){enable.accept(context);}public void disable(){}
    };}
    @Test void failedStartupAndHostShutdownRevokeRetainedDefinitionScopes()throws Exception{
        var before=snapshot();AtomicReference<DefinitionService> leaked=new AtomicReference<>();
        try(var host=new ModuleHost(Platform.PAPER,service,8,Duration.ofSeconds(1))){
            host.install(module("failed",c->{leaked.set(c.definitions());throw new AssertionError("failed");}));
            assertEquals(Outcome.Status.UNAVAILABLE,await(leaked.get().apply(rename(before,"Bad"))).status());
            host.install(module("active",c->leaked.set(c.definitions())));
            assertEquals(Outcome.Status.OK,await(leaked.get().find(NPC)).status());host.close();
            assertEquals(Outcome.Status.UNAVAILABLE,await(leaked.get().apply(rename(before,"Bad"))).status());
            assertEquals(Outcome.Status.OK,await(service.find(NPC)).status());assertEquals(1,repository.find("guard").orElseThrow().revision());
        }
    }
    @Test void queuedModuleMutationRechecksScopeAfterShutdownBeforeCommit()throws Exception{
        var before=snapshot();AtomicReference<DefinitionService> scoped=new AtomicReference<>();
        CountDownLatch running=new CountDownLatch(1),resume=new CountDownLatch(1);
        try(var host=new ModuleHost(Platform.PAPER,service,8,Duration.ofSeconds(1))){
            host.install(module("active",c->scoped.set(c.definitions())));
            var first=lane.submit(()->{running.countDown();release(resume);return Outcome.ok("unrelated-import");});release(running);
            var queued=scoped.get().apply(rename(before,"Bad"));host.revoke();resume.countDown();await(first);
            assertEquals(Outcome.Status.UNAVAILABLE,await(queued).status());assertEquals(1,repository.find("guard").orElseThrow().revision());
        }finally{resume.countDown();}
    }
    @Test void invocationSuspensionRevokesRetainedDefinitionAccess()throws Exception{
        var before=snapshot();AtomicReference<DefinitionService> scoped=new AtomicReference<>();CompletableFuture<String> raw=new CompletableFuture<>();
        try(var host=new ModuleHost(Platform.PAPER,service,8,Duration.ofMillis(100))){
            host.install(module("slow",c->{scoped.set(c.definitions());c.registrations().action(new Capabilities.Descriptor(new Capabilities.Key("slow","wait"),Capabilities.Kind.ACTION,"Wait","{}",Set.of(Platform.PAPER),false),i->raw);}));
            assertEquals(Outcome.Status.TIMED_OUT,await(host.action(new Capabilities.Key("slow","wait"),NPC,Optional.empty(),"{}",false,false)).status());
            assertEquals(Outcome.Status.UNAVAILABLE,await(scoped.get().apply(rename(before,"Bad"))).status());assertEquals(1,repository.find("guard").orElseThrow().revision());
        }finally{raw.complete("{}");}
    }
    @Test void delayedAcknowledgmentAfterModuleRevocationKeepsDurableOutcomeAndRejectsLaterMutation()throws Exception{
        var before=snapshot();AtomicReference<DefinitionService> scoped=new AtomicReference<>();CountDownLatch unblock=new CountDownLatch(1);
        List<CountDownLatch> releases=new ArrayList<>();
        try(var host=new ModuleHost(Platform.PAPER,service,8,Duration.ofSeconds(1))){
            host.install(module("active",c->scoped.set(c.definitions())));
            for(int i=0;i<2;i++){
                var started=new CountDownLatch(1);var publish=new CountDownLatch(1);var callback=new CountDownLatch(1);releases.add(publish);
                var result=lane.submit(()->{started.countDown();release(publish);return Outcome.ok("other acknowledgment");});release(started);
                result.thenRun(()->{callback.countDown();release(unblock);});publish.countDown();release(callback);
            }
            var committed=scoped.get().apply(rename(before,"Durable"));
            eventually(()->repository.find("guard").orElseThrow().revision()==2);
            assertFalse(committed.toCompletableFuture().isDone());host.close();
            var late=scoped.get().apply(rename(before,"Forbidden"));lane.close();assertTrue(lane.awaitMutations(Duration.ofSeconds(1)));
            assertEquals("Durable",repository.find("guard").orElseThrow().name());
            unblock.countDown();assertEquals(Outcome.Status.OK,await(committed).status());
            // Close may cancel this still-queued operation first; either outcome must preserve the same durable definition.
            assertTrue(Set.of(Outcome.Status.UNAVAILABLE,Outcome.Status.CANCELLED).contains(await(late).status()));
            assertEquals(2,repository.find("guard").orElseThrow().revision());
        }finally{releases.forEach(CountDownLatch::countDown);unblock.countDown();}
    }
    @Test void commandExecutorPublicationPrecedesQueuedApiRevisionValidation()throws Exception{
        var before=snapshot();CountDownLatch prepared=new CountDownLatch(1),finish=new CountDownLatch(1);CompletableFuture<String> commandResult=new CompletableFuture<>();
        Executor commands=lane.executor((task,event,failure)->commandResult.completeExceptionally(failure==null?new CancellationException():failure));
        try{
            commands.execute(()->{
                try{synchronized(repository){
                    var draft=repository.draft("guard");prepared.countDown();release(finish);
                    var json=draft.document();json.addProperty("name","Command");
                    repository.apply(new NpcRepository.Draft(draft.path(),draft.expectedHash(),draft.expectedRevision(),json));commandResult.complete("published");
                }}catch(java.io.IOException failure){throw new IllegalStateException(failure);}
            });
            release(prepared);var stale=service.apply(rename(before,"Lost API edit"));assertFalse(stale.toCompletableFuture().isDone());finish.countDown();
            assertEquals("published",await(commandResult));assertEquals(Outcome.Status.CONFLICT,await(stale).status());
            assertEquals("Command",repository.find("guard").orElseThrow().name());assertEquals(2,snapshot().token().revision());
        }finally{finish.countDown();}
    }
    private static String npc(String uuid,String id,String group,boolean spawned){
        return JSON.replace("12345678-1234-1234-1234-123456789abc",uuid).replace("\"id\":\"guard\"","\"id\":\""+id+"\"")
                .replace("\"group\":\"town\"","\"group\":\""+group+"\"").replace("\"spawned\":false","\"spawned\":"+spawned);
    }
    @Test void resolveFindsAnNpcByIdAndReportsAMissingOne()throws Exception{
        assertEquals(NPC,await(service.resolve("guard")).value().orElseThrow());
        assertEquals(Outcome.Status.NOT_FOUND,await(service.resolve("nobody")).status());
        assertEquals(Outcome.Status.INVALID,await(service.resolve("Not An Id")).status());
        assertEquals(Outcome.Status.UNAVAILABLE,await(service.scoped(()->false).resolve("guard")).status());
    }
    @Test void npcsAtTheTopFolderOrInNestedFoldersHaveSnapshots()throws Exception{
        store.commit(List.of(new DocumentStore.Change("npcs/hermit.json","missing",npc("22345678-1234-1234-1234-123456789abc","hermit","",false)),
                new DocumentStore.Change("npcs/world/market/baker.json","missing",npc("32345678-1234-1234-1234-123456789abc","baker","world/market",true))));
        assertTrue(repository.reload().isEmpty());
        DefinitionSnapshot hermit=await(service.find(await(service.resolve("hermit")).value().orElseThrow())).value().orElseThrow();
        assertEquals("",hermit.group());
        assertEquals("world/market",await(service.find(await(service.resolve("baker")).value().orElseThrow())).value().orElseThrow().group());
    }
    @Test void listPagesNpcsInIdOrderAndFiltersByGroup()throws Exception{
        List<DocumentStore.Change> changes=new ArrayList<>();
        for(int i=0;i<DefinitionService.PAGE_SIZE+5;i++)changes.add(new DocumentStore.Change("npcs/crowd/extra-"+String.format("%03d",i)+".json","missing",
                npc(String.format("00000000-0000-0000-0000-%012d",i),"extra-"+String.format("%03d",i),"crowd",i%2==0)));
        store.commit(changes);assertTrue(repository.reload().isEmpty());
        DefinitionService.Page first=await(service.list(Optional.empty(),1)).value().orElseThrow();
        assertEquals(DefinitionService.PAGE_SIZE,first.npcs().size());assertEquals(2,first.pages());assertEquals(DefinitionService.PAGE_SIZE+6,first.total());
        assertEquals("extra-000",first.npcs().getFirst().id());
        assertTrue(first.npcs().getFirst().spawned());assertFalse(first.npcs().get(1).spawned());
        DefinitionService.Page second=await(service.list(Optional.empty(),2)).value().orElseThrow();
        assertEquals(6,second.npcs().size());assertEquals("guard",second.npcs().getLast().id());
        DefinitionService.Page town=await(service.list(Optional.of("town"),1)).value().orElseThrow();
        assertEquals(List.of(new DefinitionService.Summary(NPC,"guard","town",false)),town.npcs());
        assertEquals(0,await(service.list(Optional.of("nowhere"),1)).value().orElseThrow().total());
        assertEquals(Outcome.Status.INVALID,await(service.list(Optional.empty(),0)).status());
        assertTrue(await(service.list(Optional.empty(),9)).value().orElseThrow().npcs().isEmpty());
    }
}
