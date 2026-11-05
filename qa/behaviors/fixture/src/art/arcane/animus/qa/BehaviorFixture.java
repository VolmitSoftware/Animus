package art.arcane.animus.qa;

import org.bukkit.command.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.invoke.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.*;

/** QA only: no entity/world access; exact handles avoid scanning a closed plugin archive. */
public final class BehaviorFixture extends JavaPlugin implements CommandExecutor {
    private Plugin target;
    private Object service,core,extensions,lane;
    private AtomicBoolean serviceClosed;
    private Object coordinator;
    private AtomicReference<?> catalogTask;
    private MethodHandle fileQuiescent,moduleStopped,cleanupFailure;
    private MethodHandle runtimeStatus,extensionStatus,fileStatus,run, outcomeStatus;
    private Object capturedCoordinator;
    private boolean cold,disableQueued,disabled;
    private static final Duration ZERO=Duration.ZERO;

    @Override public void onEnable() {
        try {
            target=Objects.requireNonNull(getServer().getPluginManager().getPlugin("Animus"));
            Path config=getDataFolder().toPath().resolve("config.toml");String text=Files.readString(config);
            if(text.length()>1024 || !(text.equals("mode = \"cold\"\n")||text.equals("mode = \"manual\"\n")))throw new IllegalArgumentException("Exact synthetic TOML mode required");
            cold=text.contains("\"cold\"");capturePrimitives();
            Objects.requireNonNull(getCommand("behaviorqa")).setExecutor(this);
            if(cold){disableQueued=true;global(()->disable(null,null));}
        } catch(Throwable failure){throw new IllegalStateException("Behavior QA initialization failed",failure);}
    }
    private static Object field(Object receiver,String name,Class<?> type)throws Throwable {
        return MethodHandles.privateLookupIn(receiver.getClass(),MethodHandles.lookup()).findGetter(receiver.getClass(),name,type).bindTo(receiver).invoke();
    }
    private Class<?> type(String name)throws ClassNotFoundException {return Class.forName(name,false,target.getClass().getClassLoader());}
    private static MethodHandle method(Object receiver,String name,Class<?> result,Class<?>... arguments)throws IllegalAccessException,NoSuchMethodException {
        return MethodHandles.publicLookup().findVirtual(receiver.getClass(),name,MethodType.methodType(result,arguments)).bindTo(receiver);
    }
    private void capturePrimitives()throws Throwable {
        String base=target.getClass().getName().substring(0,target.getClass().getName().lastIndexOf(".bukkit."));
        // These instance fields already exist. No method scan, status invocation,
        // interpreter construction, execution, cancellation, or engine-node preload.
        service=field(target,"behaviors",type(base+".core.command.BehaviorCommands"));
        core=field(target,"runtime",type(base+".core.AnimusRuntime"));
        extensions=field(target,"extensions",type(base+".core.extension.ExtensionRuntime"));
        serviceClosed=(AtomicBoolean)field(service,"closed",AtomicBoolean.class);
        coordinator=field(service,"coordinator",type(base+".core.behavior.runtime.BehaviorRuntime"));
        catalogTask=(AtomicReference<?>)field(service,"fileTask",AtomicReference.class);
        lane=field(core,"fileWork",type(base+".core.extension.MutationLane"));
        // ZERO never waits. Observe the lane directly so the runtime's storage-close
        // monitor cannot make a platform owner wait for filesystem cleanup.
        fileQuiescent=method(lane,"awaitQuiescence",boolean.class,Duration.class);
        moduleStopped=method(extensions,"awaitStopped",boolean.class,Duration.class);
        cleanupFailure=MethodHandles.privateLookupIn(core.getClass(),MethodHandles.lookup()).findGetter(core.getClass(),"cleanupFailure",Throwable.class).bindTo(core);
    }
    private void captureWarm()throws Throwable {
        if(cold)throw new IllegalStateException("Cold probe forbids warm diagnostic preparation");
        capturedCoordinator=Objects.requireNonNull(coordinator,"Behavior coordinator not ready");
        runtimeStatus=method(capturedCoordinator,"status",type(capturedCoordinator.getClass().getName()+"$Status"));
        extensionStatus=method(extensions,"status",type(extensions.getClass().getName()+"$Status"));
        fileStatus=method(lane,"status",type(lane.getClass().getName()+"$Status"));
        run=method(capturedCoordinator,"run",CompletionStage.class,String.class,String.class);
        Class<?> outcome=type("art.arcane.animus.api.Outcome"),status=type("art.arcane.animus.api.Outcome$Status");
        outcomeStatus=MethodHandles.publicLookup().findVirtual(outcome,"status",MethodType.methodType(status));
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof ConsoleCommandSender)){sender.sendMessage("Behavior QA requires the isolated server console");return true;}
        try {
            if(args.length==1&&args[0].equals("observe")){if(disabled)throw new IllegalStateException("Already disabled");captureWarm();sender.sendMessage("Behavior QA observed "+status());return true;}
            if(args.length==1&&args[0].equals("status")){sender.sendMessage("Behavior QA status "+status());return true;}
            if(args.length==3&&args[0].equals("disable")){
                if(cold||disableQueued)throw new IllegalArgumentException("Disable unavailable or already queued");
                if(!args[1].matches("behavior-qa-[a-z0-9-]+")||!args[2].startsWith("qabehavior:"))throw new IllegalArgumentException("Synthetic identity required");
                captureWarm();disableQueued=true;String npc=args[1],behavior=args[2];global(()->disable(npc,behavior));sender.sendMessage("Behavior QA disable queued");return true;
            }
            throw new IllegalArgumentException("behaviorqa status|observe|disable <npc-id> <behavior-id>");
        }catch(Throwable failure){getLogger().log(java.util.logging.Level.SEVERE,"Behavior QA command failed",failure);sender.sendMessage("Behavior QA ERROR "+failure.getClass().getSimpleName());return true;}
    }
    private void disable(String npc,String behavior) {
        try {
            boolean existed=coordinator!=null;
            String before=cold?"null":quote(runtimeStatus.invoke().toString());
            long start=System.nanoTime();getServer().getPluginManager().disablePlugin(target);long nanos=System.nanoTime()-start;
            disabled=!target.isEnabled();if(!disabled||!serviceClosed.get())throw new AssertionError("Official disable did not close service admission");
            getLogger().info("Behavior QA disabled {\"cold\":"+cold+",\"enabled\":false,\"serviceClosed\":true,\"durationNanos\":"+nanos+",\"coordinatorBefore\":"+existed+",\"before\":"+before+"}");
            if(!cold){
                CompletionStage<?> stage=(CompletionStage<?>)run.invoke(npc,behavior);
                stage.whenComplete((result,error)->{
                    try {if(error!=null||!outcomeStatus.invoke(result).toString().equals("UNAVAILABLE"))throw new AssertionError("Post-disable run did not reject");getLogger().info("Behavior QA post-disable run=UNAVAILABLE");}
                    catch(Throwable failure){getLogger().log(java.util.logging.Level.SEVERE,"Behavior QA post-disable check failed",failure);}
                });
            }
        }catch(Throwable failure){getLogger().log(java.util.logging.Level.SEVERE,"Behavior QA actual disable failed",failure);}
    }
    private String status()throws Throwable {
        Object current=coordinator;boolean stopped=current==null||(boolean)method(current,"awaitStopped",boolean.class,Duration.class).invoke(ZERO);
        boolean files=(boolean)fileQuiescent.invoke(ZERO),modules=(boolean)moduleStopped.invoke(ZERO);
        Throwable failure=(Throwable)cleanupFailure.invoke();
        long threads=Arrays.stream(ManagementFactory.getThreadMXBean().getThreadInfo(ManagementFactory.getThreadMXBean().getAllThreadIds())).filter(Objects::nonNull).filter(info->info.getThreadName().equals("animus-behavior-owner")||info.getThreadName().equals("animus-behavior-result")).count();
        String runtime=runtimeStatus==null?"null":quote(runtimeStatus.invoke().toString());
        String extension=extensionStatus==null?"null":quote(extensionStatus.invoke().toString());
        String file=fileStatus==null?"null":quote(fileStatus.invoke().toString());
        return "{\"cold\":"+cold+",\"disabled\":"+disabled+",\"serviceClosed\":"+serviceClosed.get()+",\"catalogWorkPending\":"+(catalogTask.get()!=null)+",\"coordinatorPresent\":"+(current!=null)+",\"coordinatorStopped\":"+stopped+",\"moduleStopped\":"+modules+",\"fileQuiescent\":"+files+",\"fileFailure\":"+(failure==null?"null":quote(failure.getClass().getName()))+",\"behaviorThreads\":"+threads+",\"runtime\":"+runtime+",\"modules\":"+extension+",\"files\":"+file+"}";
    }
    private void global(Runnable task)throws Throwable {
        try {
            Object scheduler=getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(getServer());
            Class<?> contract=Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler",false,getServer().getClass().getClassLoader());
            contract.getMethod("execute",Plugin.class,Runnable.class).invoke(scheduler,this,task);
        }catch(NoSuchMethodException absent){getServer().getScheduler().runTask(this,task);}
    }
    private static String quote(String text){StringBuilder result=new StringBuilder("\"");for(char c:text.toCharArray())switch(c){case '"'->result.append("\\\"");case '\\'->result.append("\\\\");case '\n'->result.append("\\n");case '\r'->result.append("\\r");case '\t'->result.append("\\t");default->{if(c<32)result.append(String.format("\\u%04x",(int)c));else result.append(c);}}return result.append('"').toString();}
}
