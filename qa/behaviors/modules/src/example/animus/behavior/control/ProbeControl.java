package example.animus.behavior.control;

import art.arcane.animus.api.Capabilities;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Trusted QA fixture, not a production behavior capability. No threads or world access. */
public final class ProbeControl {
    private static final int MAX_EVENTS=256, MAX_PENDING=64;
    private static final ArrayDeque<String> events=new ArrayDeque<>();
    private static final LinkedHashMap<UUID,Pending> pending=new LinkedHashMap<>();
    private static long sequence,dropped;
    private record Pending(CompletableFuture<String> result,Capabilities.Cancellation cancellation) {}
    private ProbeControl() {}

    public static synchronized void record(UUID npc,String kind,String arguments) {
        String entry="{\"sequence\":"+(++sequence)+",\"npc\":\""+npc+"\",\"kind\":\""+kind+"\",\"arguments\":"+arguments+"}";
        if(events.size()==MAX_EVENTS){events.removeFirst();dropped++;}
        events.addLast(entry);
        System.out.println("Animus behavior QA event "+entry);
    }
    public static synchronized CompletableFuture<String> hold(UUID npc,Capabilities.Cancellation cancellation) {
        if(pending.size()>=MAX_PENDING || pending.containsKey(npc))throw new IllegalStateException("QA hold capacity or duplicate NPC");
        CompletableFuture<String> result=new CompletableFuture<>();pending.put(npc,new Pending(result,cancellation));
        record(npc,"hold-enter","{}");return result;
    }
    /** Complete outside the monitor. Only host accounting establishes actual handler quiescence. */
    public static String releaseAll() {
        Map<UUID,Pending> released;
        synchronized(ProbeControl.class){released=new LinkedHashMap<>(pending);pending.clear();}
        int cancelled=0;
        for(var entry:released.entrySet()) {
            boolean value=entry.getValue().cancellation().cancelled();if(value)cancelled++;
            record(entry.getKey(),"hold-release","{\"cancelled\":"+value+"}");
            entry.getValue().result().complete("{\"released\":true}");
        }
        return "{\"released\":"+released.size()+",\"cancelled\":"+cancelled+"}";
    }
    public static synchronized String inspect() {
        StringJoiner held=new StringJoiner(",","[","]");
        for(var entry:pending.entrySet())held.add("{\"npc\":\""+entry.getKey()+"\",\"cancelled\":"+entry.getValue().cancellation().cancelled()+"}");
        return "{\"sequence\":"+sequence+",\"dropped\":"+dropped+",\"events\":["+String.join(",",events)+"],\"pending\":"+held+"}";
    }
    public static synchronized String reset() {
        if(!pending.isEmpty())throw new IllegalStateException("Cannot discard unfinished QA holds");
        events.clear();sequence=0;dropped=0;return "{\"reset\":true}";
    }
}
