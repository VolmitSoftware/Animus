package art.arcane.animus.core.extension.loader;

import art.arcane.animus.api.*;
import art.arcane.animus.core.extension.ModuleHost;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/** Owns one startup batch and its private host. Replacing a binary requires a new engine process. */
public final class StartupModules implements AutoCloseable {
    public record Status(String id,String archive,String sha256,String state,String reason) {}
    public record Cleanup(boolean closing,int retainedLoaders,int hostInvocations,boolean archivesReleased) {}
    public record Lifecycle(boolean started,boolean closing,String module,String phase,long elapsedMillis) {}
    private final ReentrantLock lifecycle=new ReentrantLock();
    private final ModuleHost host;
    private final ModuleDiscovery.Plan plan;
    private final Map<String,ModuleArchiveLoader> loaders=new LinkedHashMap<>();
    private final List<Status> statuses=new ArrayList<>();
    private boolean started,closing,lifecycleFinished;private String callbackModule="",callbackPhase="idle";private long callbackStart;
    public StartupModules(ModuleHost host,ModuleDiscovery.Plan plan){this.host=Objects.requireNonNull(host);this.plan=Objects.requireNonNull(plan);}
    /** Trusted lifecycle callbacks are synchronous and may block. Call only on the engine's startup management lane. */
    public List<Status> start(){
        lifecycle.lock();try {
        synchronized(this){
            if(started||closing)throw new IllegalStateException("Module startup is one-shot; binary replacement requires restart");
            if(!host.modules().isEmpty()||host.invocationStatus().closed())throw new IllegalStateException("Loader requires exclusive ownership of a fresh ModuleHost");started=true;
            for(var issue:plan.problems())statuses.add(new Status(issue.id(),issue.archive(),"","REJECTED",issue.reason()));
        }
        Set<String> active=new HashSet<>();
        for(var candidate:plan.take()){
            String id=candidate.id();
            if(host.invocationStatus().closed()){record(status(candidate,"UNAVAILABLE","host-revoked-before-initialization"));continue;}
            if(candidate.descriptor.manifest().dependencies().stream().anyMatch(d->!active.contains(d.id()))){record(status(candidate,"UNAVAILABLE","dependency-startup-failed"));continue;}
            List<ModuleArchiveLoader> required=candidate.descriptor.manifest().dependencies().stream().map(d->loaders.get(d.id())).toList();
            ModuleArchiveLoader loader=new ModuleArchiveLoader(candidate,required);synchronized(this){loaders.put(id,loader);}record(status(candidate,"STARTING","initializing"));callback(id,"initializing");
            TrustedModule module=null;boolean delegated=false;
            try {
                Class<?> entry=Class.forName(candidate.descriptor.main(),true,loader);
                if(!TrustedModule.class.isAssignableFrom(entry)||!Modifier.isPublic(entry.getModifiers()))throw new IllegalArgumentException("Entrypoint must implement parent-owned TrustedModule");
                module=(TrustedModule)entry.getConstructor().newInstance();
                callback(id,"manifest");if(!candidate.descriptor.manifest().equals(module.manifest()))throw new IllegalArgumentException("Runtime manifest differs from archived declaration");
                TrustedModule actual=module;ModuleManifest verified=candidate.descriptor.manifest();
                // Freeze the verified declaration: an extension cannot change dependency/namespace identity on a second manifest call.
                TrustedModule frozen=new TrustedModule(){
                    public ModuleManifest manifest(){return verified;}
                    public void enable(ModuleContext context)throws Exception{
                        ModuleContext observed=new ModuleContext(){
                            public DefinitionService definitions(){return context.definitions();}
                            public Capabilities.Registrar registrations(){return context.registrations();}
                            public Capabilities.TriggerPublisher triggers(){return context.triggers();}
                            public ModuleScheduler scheduler(){return context.scheduler();}
                            public void onClose(AutoCloseable resource){Objects.requireNonNull(resource);context.onClose(()->{callback(id,"resource-close");try{resource.close();}finally{callback("","idle");}});}
                            public void onInteraction(InteractionNotice.Listener listener){context.onInteraction(listener);}
                            public ImportConverters.Registrar imports(){return context.imports();}
                            public Integrations.Registrar integrations(){return context.integrations();}
                        };
                        callback(id,"enable");try{actual.enable(observed);}finally{callback("","idle");}
                    }
                    public void disable()throws Exception{callback(id,"disable");try{actual.disable();}finally{callback("","idle");}}
                };
                delegated=true;Outcome<ModuleHost.ModuleStatus> result=host.install(frozen);
                if(result.status()==Outcome.Status.OK){active.add(id);record(status(candidate,"ACTIVE","active"));}
                else{if(result.status()!=Outcome.Status.FAILED)try{callback(id,"disable-rejected");module.disable();}catch(Exception|LinkageError|AssertionError ignored){}record(status(candidate,"FAILED",result.diagnostics().stream().map(Diagnostic::code).findFirst().orElse("install-rejected")));}
            }catch(Exception|LinkageError|AssertionError failure){
                Throwable cause=failure instanceof InvocationTargetException reflected?reflected.getCause():failure;
                if(cause instanceof VirtualMachineError fatal)throw fatal;
                if(cause instanceof ThreadDeath fatal)throw fatal;
                if(module!=null&&!delegated)try{callback(id,"disable-rejected");module.disable();}catch(Exception|LinkageError|AssertionError ignored){}
                record(status(candidate,"FAILED","startup-failed: "+cause.getClass().getSimpleName()));
            }finally{callback("","idle");}
        }
        return statuses();
        }finally{lifecycle.unlock();}
    }
    private synchronized void record(Status value){statuses.removeIf(s->s.id().equals(value.id())&&s.archive().equals(value.archive()));statuses.add(value);}
    private synchronized void callback(String id,String phase){callbackModule=id;callbackPhase=phase;callbackStart=System.nanoTime();}
    private static Status status(ModuleDiscovery.Candidate candidate,String state,String reason){return new Status(candidate.id(),candidate.archive,candidate.sha256,state,reason);}
    public synchronized List<Status> statuses(){return List.copyOf(statuses);}
    public synchronized Lifecycle lifecycleStatus(){return new Lifecycle(started,closing,callbackModule,callbackPhase,callbackPhase.equals("idle")?0:(System.nanoTime()-callbackStart)/1_000_000);}
    public synchronized Cleanup cleanupStatus(){return new Cleanup(closing,(int)loaders.values().stream().filter(l->!l.closed()).count(),host.outstandingInvocations(),closing&&lifecycleFinished&&loaders.values().stream().allMatch(ModuleArchiveLoader::closed));}
    /** Release archive storage only after all engine-tracked handlers and public delivery callbacks have actually returned. */
    public synchronized Cleanup drainCleanup(){
        if(closing&&lifecycleFinished&&host.outstandingInvocations()==0){List<ModuleArchiveLoader> reverse=new ArrayList<>(loaders.values());Collections.reverse(reverse);reverse.forEach(ModuleArchiveLoader::close);}
        return cleanupStatus();
    }
    @Override public void close(){lifecycle.lock();try{boolean finish;synchronized(this){finish=!closing;closing=true;}if(finish){plan.releaseUnused();host.close();synchronized(this){lifecycleFinished=true;}}drainCleanup();}finally{lifecycle.unlock();}}
}
