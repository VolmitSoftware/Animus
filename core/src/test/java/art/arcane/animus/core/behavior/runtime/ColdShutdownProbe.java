package art.arcane.animus.core.behavior.runtime;

import art.arcane.animus.api.*;
import art.arcane.animus.core.behavior.*;
import art.arcane.animus.core.behavior.catalog.BehaviorCatalog;
import art.arcane.animus.core.npc.NpcDefinition;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Loaded in an independent disposable classloader; deliberately has no JUnit/runtime parent. */
public final class ColdShutdownProbe {
    public static Runnable prepare(boolean queued)throws Exception {
        CountDownLatch ownerRelease=new CountDownLatch(1);
        NpcDefinition definition=NpcDefinition.parse("{\"schemaVersion\":2,\"revision\":1,\"uuid\":\"c7ff1352-2c8c-46c5-8f8c-e662e2ccdc6a\",\"id\":\"test\",\"group\":\"test\",\"name\":\"Test\",\"type\":\"minecraft:player\",\"spawned\":false,\"position\":{\"world\":\"world\",\"x\":0,\"y\":61,\"z\":0,\"yaw\":0,\"pitch\":0}}");
        // No compiler, capability key, module installation or prior Outcome/Diagnostic construction.
        BehaviorProgram program=new BehaviorProgram("example:manual","main",Map.of(),Map.of(),Map.of("main",new BehaviorProgram.Wait("$",100)),1,List.of());
        var accepted=new BehaviorCatalog.Accepted(1,"1".repeat(64),100,program,Set.of());
        var catalog=new BehaviorCatalog.Snapshot(1,Map.of(),Map.of("example:manual",new BehaviorCatalog.Resolution("example:manual",true,Optional.of(accepted),List.of())),List.of());
        BehaviorInvoker noModules=new BehaviorInvoker(){
            public boolean available(Capabilities.Key key,Capabilities.Kind kind){throw new AssertionError("Closed runtime must not consult modules");}
            public Call<String> action(Invocation invocation){throw new AssertionError("Closed runtime invoked action");}
            public Call<Boolean> condition(Invocation invocation){throw new AssertionError("Closed runtime invoked condition");}
        };
        var runtime=new BehaviorRuntime(BehaviorRuntime.Limits.defaults(),BehaviorEngine.Limits.defaults(),()->Map.of("test",definition),()->catalog,noModules,null,art.arcane.animus.core.behavior.BehaviorState.UNAVAILABLE,
                task->{Thread thread=new Thread(()->{try{if(!ownerRelease.await(5,TimeUnit.SECONDS))throw new AssertionError("Owner start not released");task.run();}catch(InterruptedException failure){throw new AssertionError(failure);}},"cold-owner");thread.setDaemon(true);return thread;});
        CompletionStage<Outcome<UUID>> request=queued?runtime.run("test","example:manual"):null;
        runtime.close();
        // This runnable is created before closing the archive; its body performs real cold owner cleanup afterward.
        return ()->{
            ownerRelease.countDown();
            try{
                if(!runtime.awaitStopped(Duration.ofSeconds(4)))throw new AssertionError("Cold shutdown retained work: "+runtime.status());
                if(!runtime.status().failure().isEmpty())throw new AssertionError("Cold class resolution failed: "+runtime.status());
                if(request!=null&&request.toCompletableFuture().get(1,TimeUnit.SECONDS).status()!=Outcome.Status.CANCELLED)throw new AssertionError("Cold queued request was not cancelled");
                if(runtime.status().admittedOperations()!=0||!runtime.status().engine().closed())throw new AssertionError("Cold runtime did not clean up");
            }catch(Exception failure){throw new AssertionError(failure);}
        };
    }
}
