package qa.animus.packaging;

import art.arcane.animus.api.*;
import @PRIVATE_NAMESPACE@.core.extension.DefinitionGateway;
import @PRIVATE_NAMESPACE@.core.extension.ModuleHost;
import @PRIVATE_NAMESPACE@.core.extension.loader.ModuleDiscovery;
import @PRIVATE_NAMESPACE@.core.extension.loader.StartupModules;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** QA only. The generated source compiles against one packaged runtime, with no API JAR at execution. */
public final class PackagedApiProbe {
    private static void check(boolean valid,String id) {
        if(!valid)throw new AssertionError(id);
        System.out.println("{\"check\":\""+id+"\",\"passed\":true}");
    }
    public static void main(String[] arguments)throws Exception {
        Path modules=Path.of(arguments[0]),badModules=Path.of(arguments[1]),runtimeJar=Path.of(arguments[2]).toRealPath();
        check(Path.of(TrustedModule.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(runtimeJar),"api-loaded-from-packaged-runtime");
        check(Path.of(ModuleHost.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(runtimeJar),"host-loaded-from-packaged-runtime");
        boolean absent=false;
        try{Class.forName("example.animus.reference.ReferenceModule");}catch(ClassNotFoundException expected){absent=true;}
        check(absent,"external-module-absent-from-jvm-classpath");
        var rejected=ModuleDiscovery.discover(badModules,Platform.PAPER);
        check(rejected.order().isEmpty()&&rejected.problems().stream().anyMatch(p->p.reason().contains("Reserved/unsupported class namespace")),"bundled-public-api-rejected");

        DefinitionGateway definitions=new DefinitionGateway(){
            public DefinitionService scoped(BooleanSupplier available){return this;}
            public CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc){return CompletableFuture.completedFuture(Outcome.rejected(Outcome.Status.NOT_FOUND,"missing","Synthetic probe"));}
            public CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit){return CompletableFuture.completedFuture(Outcome.rejected(Outcome.Status.UNAVAILABLE,"offline","Synthetic probe"));}
            public CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit){return CompletableFuture.completedFuture(Outcome.rejected(Outcome.Status.UNAVAILABLE,"offline","Synthetic probe"));}
        };
        for(Platform platform:List.of(Platform.SPIGOT,Platform.PAPER,Platform.FOLIA)) {
            String suffix="-"+platform.name().toLowerCase(Locale.ROOT);
            ModuleHost host=new ModuleHost(platform,definitions,4,Duration.ofSeconds(2));
            var plan=ModuleDiscovery.discover(modules,platform);
            check(plan.problems().isEmpty()&&plan.order().equals(List.of("reference")),"reference-discovery"+suffix);
            StartupModules loaded=new StartupModules(host,plan);
            try {
                var statuses=loaded.start();
                check(statuses.size()==1&&statuses.getFirst().state().equals("ACTIVE"),"reference-start"+suffix);
                var field=StartupModules.class.getDeclaredField("loaders");field.setAccessible(true);
                ClassLoader moduleLoader=(ClassLoader)((Map<?,?>)field.get(loaded)).get("reference");
                Class<?> reference=Class.forName("example.animus.reference.ReferenceModule",false,moduleLoader);
                check(moduleLoader!=TrustedModule.class.getClassLoader()
                        &&moduleLoader.loadClass(TrustedModule.class.getName())==TrustedModule.class
                        &&Arrays.asList(reference.getInterfaces()).contains(TrustedModule.class),"parent-api-class-identity"+suffix);
                boolean privateRejected=false;
                try{moduleLoader.loadClass(ModuleHost.class.getName());}catch(ClassNotFoundException expected){privateRejected=true;}
                check(privateRejected,"private-core-hidden-from-module"+suffix);
                var outcome=host.action(new Capabilities.Key("reference","hello"),new NpcHandle(UUID.randomUUID()),Optional.empty(),"{}",false,false)
                        .toCompletableFuture().get(3,TimeUnit.SECONDS);
                check(outcome.status()==Outcome.Status.OK&&outcome.value().orElseThrow().equals("{\"message\":\"Hello from a trusted external module\"}"),"reference-action"+suffix);
            } finally {loaded.close();}
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(!loaded.drainCleanup().archivesReleased()&&System.nanoTime()<deadline)Thread.sleep(5);
            check(loaded.cleanupStatus().archivesReleased()&&host.outstandingInvocations()==0,"reference-cleanup"+suffix);
            check(host.action(new Capabilities.Key("reference","hello"),new NpcHandle(UUID.randomUUID()),Optional.empty(),"{}",false,false)
                    .toCompletableFuture().get(1,TimeUnit.SECONDS).status()==Outcome.Status.UNAVAILABLE,"post-close-admission-rejected"+suffix);
        }
    }
}
