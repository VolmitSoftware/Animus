package art.arcane.animus.core.behavior.catalog;

import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocumentTree;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Generated last-valid behavior programs, stored like definition checkpoints: a pointer under
 * {@code state/behavior-catalog/last-valid/} names a hashed copy of the accepted source under
 * {@code state/objects/behavior-catalog/}. Never substitutes for or overwrites a hand-edited source file.
 * All calls belong on the shared file lane.
 */
public final class BehaviorCheckpoints {
    private static final String PREFIX="state/behavior-catalog/last-valid/",NAMESPACE="behavior-catalog";
    private static final int TREE_DEPTH=20;
    /** Used by validation, which must not write anything. */
    private static final BehaviorCheckpoints DISABLED=new BehaviorCheckpoints();

    record Saved(long revision,String sourceHash,String source){}
    record Loaded(Map<String,Saved> saved,Map<String,String> problems){}

    private final DocumentStore store;
    /** Accepted source hash per pointer path; a path with an unusable pointer maps to the empty string. */
    private final Map<String,String> current=new HashMap<>();

    public BehaviorCheckpoints(DocumentStore store){this.store=Objects.requireNonNull(store);}
    private BehaviorCheckpoints(){store=null;}
    static BehaviorCheckpoints disabled(){return DISABLED;}

    /** Reads pointers whose source file still exists. A pointer without a source stays unused and is dropped by the next reload. */
    Loaded load()throws IOException{
        if(store==null)return new Loaded(Map.of(),Map.of());
        Map<String,Saved> saved=new TreeMap<>();Map<String,String> problems=new TreeMap<>();
        Path directory=store.resolve(PREFIX);
        for(Path file:JsonDocumentTree.files(directory,TREE_DEPTH)){
            String path=directory.relativize(file).toString().replace('\\','/');
            current.put(path,"");
            if(!Files.exists(store.resolve(path),LinkOption.NOFOLLOW_LINKS))continue;
            try{
                Saved value=read(path);
                saved.put(path,value);current.put(path,value.sourceHash());
            }catch(IOException | RuntimeException invalid){
                problems.put(path,invalid.getMessage()==null?invalid.getClass().getSimpleName():invalid.getMessage());
            }
        }
        return new Loaded(Map.copyOf(saved),Map.copyOf(problems));
    }
    private Saved read(String path)throws IOException{
        BehaviorPath.idFor(path);
        JsonObject pointer=JsonDocuments.object(requireText(store.read(PREFIX+path)));
        JsonElement version=pointer.get("schemaVersion");
        if(version==null||!version.isJsonPrimitive()||!version.getAsJsonPrimitive().isNumber()||version.getAsBigDecimal().compareTo(BigDecimal.ONE)!=0)throw new IOException("Unsupported checkpoint schema");
        if(!path.equals(string(pointer,"path")))throw new IOException("Checkpoint does not match its source path");
        String sourceHash=string(pointer,"sourceHash");
        if(!sourceHash.matches("[a-f0-9]{64}"))throw new IOException("Invalid checkpoint hash");
        JsonElement revision=pointer.get("revision");
        if(revision==null||!revision.isJsonPrimitive()||!revision.getAsJsonPrimitive().isNumber())throw new IOException("Invalid checkpoint revision");
        return new Saved(revision.getAsBigDecimal().longValueExact(),sourceHash,store.immutableJson(NAMESPACE,sourceHash));
    }
    private static String requireText(String content)throws IOException{if(content==null)throw new IOException("Checkpoint disappeared");return content;}
    private static String string(JsonObject object,String key)throws IOException{
        JsonElement value=object.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IOException("Invalid checkpoint field: "+key);
        return value.getAsString();
    }

    /**
     * Brings pointers to the accepted programs in {@code accepted}, in one journal. Sources present without an accepted
     * program keep nothing new; pointers whose source is gone are removed so a later file at that path is not a recovery.
     */
    void update(Map<String,Pending> accepted,Set<String> present)throws IOException{
        if(store==null)return;
        List<DocumentStore.Generated> changes=new ArrayList<>();Map<String,String> next=new HashMap<>(current);
        for(Map.Entry<String,Pending> item:accepted.entrySet()){
            Pending pending=item.getValue();
            if(pending.sourceHash().equals(current.get(item.getKey())))continue;
            if(pending.source()==null)continue; // Retained from a checkpoint or an earlier scan, so its object already exists.
            String objectHash=store.putImmutableJson(NAMESPACE,pending.source());
            if(!objectHash.equals(pending.sourceHash()))throw new IOException("Accepted source hash mismatch: "+item.getKey());
            JsonObject pointer=new JsonObject();
            pointer.addProperty("schemaVersion",1);pointer.addProperty("path",item.getKey());
            pointer.addProperty("revision",pending.revision());pointer.addProperty("sourceHash",pending.sourceHash());
            clearUnreadable(PREFIX+item.getKey());
            changes.add(new DocumentStore.Generated(PREFIX+item.getKey(),JsonDocuments.write(pointer)));
            next.put(item.getKey(),pending.sourceHash());
        }
        for(String path:current.keySet()){
            if(present.contains(path))continue;
            clearUnreadable(PREFIX+path);
            changes.add(new DocumentStore.Generated(PREFIX+path,null));
            next.remove(path);
        }
        if(changes.isEmpty())return;
        store.commitGenerated(changes);
        current.clear();current.putAll(next);
    }
    /** A journal cannot carry undecodable bytes, and a generated pointer holds nothing worth preserving. */
    private void clearUnreadable(String path)throws IOException{
        try{store.read(path);}catch(CharacterCodingException undecodable){store.deleteGenerated(path);}
    }
    record Pending(long revision,String sourceHash,String source){}
}
