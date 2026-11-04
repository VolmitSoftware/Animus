package art.arcane.animus.core.behavior.catalog;

import java.util.*;

/** Portable, exact logical identity mapping. IDs are never normalized into a different identity. */
public final class BehaviorPath {
    private BehaviorPath(){}
    public static String forId(String id){
        if(id==null||id.length()>256||!id.matches("[a-z0-9_-]+:[a-z0-9_/-]+"))throw new IllegalArgumentException("Expected namespace:path within 256 characters");
        String[] identity=id.split(":",-1),path=identity[1].split("/",-1);
        segment(identity[0]);if(path.length>15)throw new IllegalArgumentException("Behavior path exceeds 15 segments");
        for(String part:path)segment(part);
        return "behaviors/"+identity[0]+"/"+identity[1]+".json";
    }
    public static String idFor(String relative){
        if(relative==null||!relative.startsWith("behaviors/")||!relative.endsWith(".json"))throw new IllegalArgumentException("Expected behaviors/<namespace>/<path>.json");
        String body=relative.substring(10,relative.length()-5);int separator=body.indexOf('/');
        if(separator<1)throw new IllegalArgumentException("Expected namespace directory and behavior file");
        String id=body.substring(0,separator)+":"+body.substring(separator+1);
        if(!forId(id).equals(relative))throw new IllegalArgumentException("Ambiguous behavior file mapping");
        return id;
    }
    private static void segment(String value){
        if(!value.matches("[a-z0-9_-]{1,64}"))throw new IllegalArgumentException("Path segments require 1..64 lowercase ASCII letters, digits, _ or -; empty segments are forbidden");
        if(Set.of("con","prn","aux","nul").contains(value)||value.matches("(?:com|lpt)[1-9]"))throw new IllegalArgumentException("Reserved portable filesystem name");
    }
}
