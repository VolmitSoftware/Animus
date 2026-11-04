package art.arcane.animus.core.behavior.catalog;

import java.util.*;

/**
 * Portable, exact logical identity mapping. IDs are never normalized into a different identity. A behavior lives either
 * at {@code behaviors/<namespace>/<path>.json} or inside a pack at {@code packs/<pack>/behaviors/<namespace>/<path>.json}.
 */
public final class BehaviorPath {
    private static final String PACKS="packs/",BEHAVIORS="behaviors/";
    private BehaviorPath(){}
    public static String forId(String id){
        if(id==null||id.length()>256||!id.matches("[a-z0-9_-]+:[a-z0-9_/-]+"))throw new IllegalArgumentException("Expected namespace:path within 256 characters");
        String[] identity=id.split(":",-1),path=identity[1].split("/",-1);
        segment(identity[0]);if(path.length>15)throw new IllegalArgumentException("Behavior path exceeds 15 segments");
        for(String part:path)segment(part);
        return BEHAVIORS+identity[0]+"/"+identity[1]+".json";
    }
    /** The source path of an ID inside a pack. */
    public static String forId(String id,String pack){packId(pack);return PACKS+pack+"/"+forId(id);}
    public static String idFor(String relative){
        if(relative==null)throw new IllegalArgumentException("Expected behaviors/<namespace>/<path>.json");
        String local=relative;
        if(relative.startsWith(PACKS)){
            int end=relative.indexOf('/',PACKS.length());
            if(end<0)throw new IllegalArgumentException("Expected packs/<pack>/behaviors/<namespace>/<path>.json");
            packId(relative.substring(PACKS.length(),end));local=relative.substring(end+1);
        }
        if(!local.startsWith(BEHAVIORS)||!local.endsWith(".json"))throw new IllegalArgumentException("Expected behaviors/<namespace>/<path>.json");
        String body=local.substring(BEHAVIORS.length(),local.length()-5);int separator=body.indexOf('/');
        if(separator<1)throw new IllegalArgumentException("Expected namespace directory and behavior file");
        String id=body.substring(0,separator)+":"+body.substring(separator+1);
        if(!forId(id).equals(local))throw new IllegalArgumentException("Ambiguous behavior file mapping");
        return id;
    }
    /** Whether {@code relative} is a valid location for {@code id}, loose or in any pack. */
    public static boolean matches(String id,String relative){
        try{return idFor(relative).equals(id);}catch(IllegalArgumentException invalid){return false;}
    }
    /** The pack directory a source path belongs to; empty for loose behaviors. */
    public static Optional<String> pack(String relative){
        if(relative==null||!relative.startsWith(PACKS))return Optional.empty();
        int end=relative.indexOf('/',PACKS.length());
        return end<0?Optional.empty():Optional.of(relative.substring(PACKS.length(),end));
    }
    /** Pack directory names follow the identifier rule used for namespaces. */
    public static String packId(String pack){
        if(pack==null)throw new IllegalArgumentException("Pack directory names require 1..64 lowercase ASCII letters, digits, _ or -");
        segment(pack);return pack;
    }
    private static void segment(String value){
        if(!value.matches("[a-z0-9_-]{1,64}"))throw new IllegalArgumentException("Path segments require 1..64 lowercase ASCII letters, digits, _ or -; empty segments are forbidden");
        if(Set.of("con","prn","aux","nul").contains(value)||value.matches("(?:com|lpt)[1-9]"))throw new IllegalArgumentException("Reserved portable filesystem name");
    }
}
