package art.arcane.animus.core.behavior.runtime;

import java.net.*;
import java.util.*;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.npc.NpcDefinition;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

class ColdShutdownTest {
    private void run(boolean queued)throws Exception {
        Set<URL> urls=new LinkedHashSet<>();
        for(Class<?> type:List.of(ManualBehaviorRuntime.class,ColdShutdownProbe.class,NpcDefinition.class,Outcome.class,Gson.class))
            urls.add(type.getProtectionDomain().getCodeSource().getLocation());
        Runnable finish;
        try(URLClassLoader loader=new URLClassLoader(urls.toArray(URL[]::new),ClassLoader.getPlatformClassLoader())){
            Class<?> probe=Class.forName("art.arcane.animus.core.behavior.runtime.ColdShutdownProbe",true,loader);
            finish=(Runnable)probe.getMethod("prepare",boolean.class).invoke(null,queued);
        }
        finish.run(); // owner has not run even once before the isolated classloader was closed
    }
    @Test void closedArchiveBeforeColdEmptyOwnerStartStillStops()throws Exception{run(false);}
    @Test void closedArchiveBeforeColdQueuedOwnerStartCancelsAndDelivers()throws Exception{run(true);}
}
