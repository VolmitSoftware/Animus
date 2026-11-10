package art.arcane.animus.qa;

import art.arcane.animus.api.*;
import art.arcane.animus.core.extension.*;
import art.arcane.animus.core.extension.loader.*;
import art.arcane.animus.core.npc.*;
import art.arcane.animus.core.storage.*;
import com.google.gson.JsonObject;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Actual external fixture JARs against the active private host; no Minecraft server. */
public final class FixtureSmoke {
    private static final UUID NPC=UUID.fromString("1672fb38-c795-4bbe-8eda-8cb98e218c92");
    public static void main(String[] args)throws Exception {
        Path modules=Path.of(args[0]),data=Path.of(args[1]);Files.createDirectories(data.resolve("npcs/qa"));
        Path file=data.resolve("npcs/qa/module-qa-local.json");
        Files.writeString(file,"""
                {"schemaVersion":2,"revision":1,"uuid":"1672fb38-c795-4bbe-8eda-8cb98e218c92","id":"module-qa-local","type":"minecraft:player","group":"qa","name":"Module Before","spawned":false,"position":{"world":"world","x":0,"y":61,"z":3,"yaw":0,"pitch":0},"modules":{"missing:module":{"retained":true}}}
                """);
        try(DocumentStore store=new DocumentStore(data);MutationLane lane=new MutationLane(16)){
            NpcRepository repository=new NpcRepository(store);repository.loadForStartup();
            try(RepositoryDefinitions definitions=new RepositoryDefinitions(repository,lane)){
                ModuleHost host=new ModuleHost(Platform.PAPER,definitions,8,Duration.ofSeconds(1));
                StartupModules runtime=new StartupModules(host,ModuleDiscovery.discover(modules,Platform.PAPER));
                try{
                    List<StartupModules.Status> statuses=runtime.start();
                    require(statuses.size()==15,"status count");require(statuses.stream().filter(s->s.state().equals("ACTIVE")).count()==4,"active count");
                    require(statuses.stream().filter(s->s.state().equals("FAILED")).count()==2,"failed count");
                    require(host.capabilities().size()==12,"capability count");
                    require(action(host,"qalink","linked").get("linked").getAsBoolean(),"dependency linkage");
                    require(Boolean.FALSE.equals(host.condition(new Capabilities.Key("qaapi","false"),new NpcHandle(NPC),Optional.empty(),"{}",false).toCompletableFuture().get(2,TimeUnit.SECONDS).value().orElseThrow()),"false condition");
                    require(host.action(new Capabilities.Key("qaapi","privileged"),new NpcHandle(NPC),Optional.empty(),"{}",false,false).toCompletableFuture().get(2,TimeUnit.SECONDS).status()==Outcome.Status.UNAVAILABLE,"privileged gate");
                    require(action(host,"qaapi","cache").get("revision").getAsLong()==1,"initial token");
                    lane.submit(()->{try{var draft=repository.draft("module-qa-local");var changed=draft.document();changed.addProperty("name","Module Concurrent");repository.apply(new NpcRepository.Draft(draft.path(),draft.expectedHash(),draft.expectedRevision(),changed));return Outcome.ok("edited");}catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}}).toCompletableFuture().get(2,TimeUnit.SECONDS);
                    byte[] before=Files.readAllBytes(file);require(action(host,"qaapi","apply_cached").get("status").getAsString().equals("CONFLICT"),"stale token");require(Arrays.equals(before,Files.readAllBytes(file)),"conflict writes");
                    JsonObject edited=action(host,"qaapi","edit");require(edited.get("status").getAsString().equals("OK")&&edited.get("revision").getAsLong()==3,"API edit revision");
                    JsonObject document=JsonDocuments.object(Files.readString(file));require(document.getAsJsonObject("modules").getAsJsonObject("missing:module").get("retained").getAsBoolean(),"unknown module preserved");
                    before=Files.readAllBytes(file);
                    require(action(host,"qaapi","invalid").get("status").getAsString().equals("INVALID"),"invalid draft");
                    require(action(host,"qaapi","identity").get("status").getAsString().equals("INVALID"),"identity change");
                    require(action(host,"qaapi","activate").get("status").getAsString().equals("UNAVAILABLE"),"activation gate");
                    require(Arrays.equals(before,Files.readAllBytes(file)),"rejected writes");
                    var held=host.action(new Capabilities.Key("qawait","hold"),new NpcHandle(NPC),Optional.empty(),"{}",false,false).toCompletableFuture().get(3,TimeUnit.SECONDS);
                    require(held.status()==Outcome.Status.TIMED_OUT,"timeout outcome");require(host.outstandingInvocations()==1,"unfinished handler retained");
                }finally{runtime.close();}
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(!runtime.drainCleanup().archivesReleased()&&System.nanoTime()<until)Thread.sleep(5);
                require(runtime.cleanupStatus().archivesReleased(),"archive cleanup");require(host.outstandingInvocations()==0,"host cleanup");
            }
        }
        System.out.println("PASS external fixtures:15 startup outcomes,12 capabilities,dependency linking,typed false condition,privileged gate,API edits/conflicts/validation,unknown configuration,timeout retention,shutdown cleanup");
    }
    private static JsonObject action(ModuleHost host,String module,String name)throws Exception {
        var result=host.action(new Capabilities.Key(module,name),new NpcHandle(NPC),Optional.empty(),"{}",false,false).toCompletableFuture().get(2,TimeUnit.SECONDS);
        require(result.status()==Outcome.Status.OK,"action "+module+":"+name+" "+result.status());return JsonDocuments.object(result.value().orElseThrow());
    }
    private static void require(boolean condition,String detail){if(!condition)throw new AssertionError(detail);}
}
