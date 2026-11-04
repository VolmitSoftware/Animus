package art.arcane.animus.qa;

import org.bukkit.command.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletionStage;

/** Pure module lifecycle inspection. No entity/world/chunk access on any scheduler. */
public final class ModuleFixture extends JavaPlugin implements CommandExecutor {
    private Plugin target;
    private Object runtime;
    private boolean disableQueued;
    @Override public void onEnable(){target=getServer().getPluginManager().getPlugin("Animus");if(target==null)throw new IllegalStateException("Animus required");Objects.requireNonNull(getCommand("moduleqa")).setExecutor(this);}
    private Object runtime()throws ReflectiveOperationException {
        if(runtime==null){Field field=target.getClass().getDeclaredField("extensions");field.setAccessible(true);runtime=Objects.requireNonNull(field.get(target),"Extension runtime missing");}return runtime;
    }
    private static Object call(Object object,String method)throws ReflectiveOperationException{return object.getClass().getMethod(method).invoke(object);}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!(sender instanceof ConsoleCommandSender)){sender.sendMessage("Module QA requires the isolated server console");return true;}
        try{
            if(args.length==1&&args[0].equals("status")){sender.sendMessage(status());return true;}
            if(args.length==2&&args[0].equals("disable")){
                if(disableQueued)throw new IllegalArgumentException("Disable already queued");Object extensions=runtime();UUID npc=UUID.fromString(args[1]);
                ClassLoader loader=target.getClass().getClassLoader();Class<?> keyType=Class.forName("art.arcane.animus.api.Capabilities$Key",true,loader),npcType=Class.forName("art.arcane.animus.api.NpcHandle",true,loader);
                Object key=keyType.getConstructor(String.class,String.class).newInstance("qaapi","inspect"),handle=npcType.getConstructor(UUID.class).newInstance(npc);
                Method action=extensions.getClass().getMethod("action",keyType,npcType,Optional.class,String.class);
                disableQueued=true;global(()->{
                    try{
                        long before=System.nanoTime();getServer().getPluginManager().disablePlugin(target);long millis=(System.nanoTime()-before)/1_000_000;
                        boolean closed=(boolean)call(call(call(extensions,"status"),"invocations"),"closed");
                        if(target.isEnabled()||!closed)throw new AssertionError("Plugin disable did not revoke admission");
                        getLogger().info("Module QA disabled enabled=false admission-closed=true duration-ms="+millis);
                        CompletionStage<?> result=(CompletionStage<?>)action.invoke(extensions,key,handle,Optional.empty(),"{}");
                        result.whenComplete((outcome,error)->{
                            try{if(error!=null||!call(outcome,"status").toString().equals("UNAVAILABLE"))throw new AssertionError("Post-disable dispatch did not reject");getLogger().info("Module QA post-disable action=UNAVAILABLE");}
                            catch(ReflectiveOperationException|RuntimeException|AssertionError failure){getLogger().severe("Module QA ERROR post-disable "+failure.getClass().getSimpleName());}
                        });
                    }catch(ReflectiveOperationException|RuntimeException|AssertionError failure){getLogger().severe("Module QA ERROR disable "+failure.getClass().getSimpleName());}
                });sender.sendMessage("Module QA disable queued");return true;
            }
            throw new IllegalArgumentException("moduleqa status|disable <owned-synthetic-npc-uuid>");
        }catch(ReflectiveOperationException|RuntimeException failure){sender.sendMessage("Module QA ERROR "+failure.getClass().getSimpleName()+": "+failure.getMessage());return true;}
    }
    private String status()throws ReflectiveOperationException {
        Object snapshot=call(runtime(),"status"),invocations=call(snapshot,"invocations"),cleanup=call(snapshot,"cleanup");
        return "Module QA status state="+call(snapshot,"state")+" admission-closed="+call(invocations,"closed")+" admitted="+call(invocations,"admitted")
                +" retained="+(cleanup==null?-1:call(cleanup,"retainedLoaders"))+" released="+(cleanup!=null&&Boolean.TRUE.equals(call(cleanup,"archivesReleased")))+" failure="+call(snapshot,"failure");
    }
    private void global(Runnable task)throws ReflectiveOperationException {
        try{
            Method getter=getServer().getClass().getMethod("getGlobalRegionScheduler");Object scheduler=getter.invoke(getServer());
            Class<?> contract=Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler",false,getServer().getClass().getClassLoader());
            contract.getMethod("execute",Plugin.class,Runnable.class).invoke(scheduler,this,task);
        }catch(NoSuchMethodException absent){getServer().getScheduler().runTask(this,task);}
    }
}
