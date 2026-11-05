package art.arcane.animus.core.behavior.state;

import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.BehaviorProgram.Lifetime;
import art.arcane.animus.core.behavior.BehaviorProgram.Scope;
import art.arcane.animus.core.behavior.BehaviorProgram.ScopedVariable;
import art.arcane.animus.core.behavior.BehaviorProgram.Type;
import art.arcane.animus.core.behavior.BehaviorProgram.Value;
import art.arcane.animus.core.behavior.BehaviorState;
import art.arcane.animus.core.behavior.catalog.BehaviorPath;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocumentTree;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;

/**
 * Scoped behavior variables. All values live in memory and the engine owner reads and writes them without file work.
 * Persistent values reach {@code state/behavior-state/} through coalesced journaled flushes on the shared file lane,
 * so a set never writes through and a crash loses at most the writes since the last flush.
 * Generated data only: nothing here reads or changes behavior, NPC or module definitions.
 */
public final class BehaviorStateStore implements BehaviorState {
    public record Limits(int maximumKeys,int maximumTransientKeys,int maximumValueBytes,long maximumBytes,int flushMillis){
        public Limits{
            if(maximumKeys<1||maximumKeys>MAXIMUM_KEYS)throw new IllegalArgumentException("maximumKeys must be within 1.."+MAXIMUM_KEYS);
            if(maximumTransientKeys<1||maximumTransientKeys>MAXIMUM_KEYS)throw new IllegalArgumentException("maximumTransientKeys must be within 1.."+MAXIMUM_KEYS);
            if(maximumValueBytes<1||maximumValueBytes>MAXIMUM_VALUE_BYTES)throw new IllegalArgumentException("maximumValueBytes must be within 1.."+MAXIMUM_VALUE_BYTES);
            if(maximumBytes<1||maximumBytes>MAXIMUM_BYTES)throw new IllegalArgumentException("maximumBytes must be within 1.."+MAXIMUM_BYTES);
            if(flushMillis<MINIMUM_FLUSH_MILLIS||flushMillis>MAXIMUM_FLUSH_MILLIS)throw new IllegalArgumentException("flushMillis must be within "+MINIMUM_FLUSH_MILLIS+".."+MAXIMUM_FLUSH_MILLIS);
        }
        public static Limits defaults(){return new Limits(65536,65536,4096,16L*1024*1024,5000);}
    }
    public record Status(int persistentKeys,int transientKeys,long persistentBytes,long transientBytes,int documents,int dirtyDocuments,
                         int invalidDocuments,long flushes,long flushFailures,String lastFailure,boolean closed){}
    public record Problem(String path,String message){}
    public record PurgeResult(int documents,int keys){}
    /** Selects what an explicit purge removes. Nothing is purged implicitly: a deleted behavior or NPC keeps its state. */
    public record Purge(Optional<String> behavior,Optional<UUID> npc,Optional<UUID> player){
        public static Purge behavior(String id){return new Purge(Optional.of(id),Optional.empty(),Optional.empty());}
        public static Purge npc(UUID npc){return new Purge(Optional.empty(),Optional.of(npc),Optional.empty());}
        public static Purge player(UUID player){return new Purge(Optional.empty(),Optional.empty(),Optional.of(player));}
        public static Purge relationship(UUID npc,UUID player){return new Purge(Optional.empty(),Optional.of(npc),Optional.of(player));}
        public static Purge all(){return new Purge(Optional.empty(),Optional.empty(),Optional.empty());}
        public boolean everything(){return behavior.isEmpty()&&npc.isEmpty()&&player.isEmpty();}
        private boolean matches(Key key){
            if(behavior.isPresent())return behavior.get().equals(key.behavior());
            if(npc.isPresent()&&player.isPresent())return key.scope()==Scope.RELATIONSHIP&&npc.get().equals(key.npc())&&player.get().equals(key.player());
            if(npc.isPresent())return npc.get().equals(key.npc());
            if(player.isPresent())return player.get().equals(key.player());
            return true;
        }
    }
    private static final int MAXIMUM_KEYS=1_048_576,MAXIMUM_VALUE_BYTES=4096,MINIMUM_FLUSH_MILLIS=100,MAXIMUM_FLUSH_MILLIS=300_000;
    private static final long MAXIMUM_BYTES=256L*1024*1024;
    private static final String ROOT="state/behavior-state";
    /** Retained keys from older revisions count too. With the commit budget this keeps every state journal inline. */
    private static final int MAXIMUM_KEYS_PER_DOCUMENT=128;
    private static final int BATCH_CHARS=3*1024*1024,TREE_DEPTH=24,MAXIMUM_PROBLEM_CHARS=512;
    private static final String NAME_PATTERN="[a-z0-9][a-z0-9_-]{0,63}";

    private record Key(Lifetime lifetime,Scope scope,String behavior,UUID npc,UUID player){}
    private static final class Document {
        final Key key;final String path;final TreeMap<String,Value> values=new TreeMap<>();
        long version,bytes;String problem;
        Document(Key key,String path){this.key=key;this.path=path;}
    }
    private record Pending(Document document,long version,Map<String,Value> values){}

    private final DocumentStore store;
    private Limits limits; // this monitor
    private final LongSupplier clock;
    private volatile long flushNanos;
    private final Runnable flushTask=this::flushFromLane;
    private final Map<Key,Document> documents=new HashMap<>();
    private final Set<Document> dirty=new LinkedHashSet<>();
    private final List<Problem> strays=new ArrayList<>();
    private int persistentKeys,transientKeys;
    private long persistentBytes,transientBytes,flushes,flushFailures;
    private String lastFailure="";
    private boolean closed;
    private volatile Executor lane;
    private volatile boolean dirtySignal,flushQueued;
    private volatile long notBefore;

    private BehaviorStateStore(DocumentStore store,Limits limits,LongSupplier clock){
        this.store=Objects.requireNonNull(store);this.limits=Objects.requireNonNull(limits);this.clock=Objects.requireNonNull(clock);
        flushNanos=limits.flushMillis()*1_000_000L;
    }

    /** Reads the whole state tree. An invalid document is preserved and blocks its own subject; a structural tree failure throws. */
    public static BehaviorStateStore open(DocumentStore store,Limits limits)throws IOException{return open(store,limits,System::nanoTime);}
    // Package-private deterministic clock seam; production uses System.nanoTime.
    static BehaviorStateStore open(DocumentStore store,Limits limits,LongSupplier clock)throws IOException{
        BehaviorStateStore state=new BehaviorStateStore(store,limits,clock);
        state.load();
        state.prepareClosingClasses();
        return state;
    }

    /**
     * A hotload of {@code [behaviors.state]}, applied on the file lane. Lowered caps keep every stored value: writes that add
     * keys or bytes are refused until purges bring the store back under them. A shorter flush interval pulls the next flush in.
     */
    public void limits(Limits next){
        Objects.requireNonNull(next);
        synchronized(this){limits=next;}
        long nanos=next.flushMillis()*1_000_000L;flushNanos=nanos;
        if(dirtySignal&&notBefore-(clock.getAsLong()+nanos)>0)notBefore=clock.getAsLong()+nanos;
    }

    /** The executor must be the shared file lane. Until it is attached, values only reach disk through an explicit flush or close. */
    public void attach(Executor lane){this.lane=Objects.requireNonNull(lane);}

    @Override public synchronized Value read(Subject subject,String variable,ScopedVariable declaration,Value initial){
        Document document=documents.get(key(subject,declaration));
        if(document==null)return initial;
        if(document.problem!=null)throw invalid(document);
        Value stored=document.values.get(variable);
        if(stored==null)return initial;
        if(stored.type()!=initial.type())throw new Rejected(Outcome.Status.CONFLICT,"behavior-state-type-mismatch","$.variables."+variable+".type",
                "Stored value is "+name(stored.type())+" and the declaration is "+name(initial.type())+"; purge this state or restore the declaration");
        return stored;
    }

    @Override public synchronized void write(Subject subject,Map<String,Value> writes,Map<String,ScopedVariable> declarations){
        if(closed)throw new Rejected(Outcome.Status.UNAVAILABLE,"behavior-state-closed","$.variables","Behavior state is closing");
        int newPersistent=0,newTransient=0;long persistentDelta=0,transientDelta=0;Map<Key,Integer> growth=null;
        for(Map.Entry<String,Value> write:writes.entrySet()){
            String name=write.getKey();ScopedVariable declaration=declarations.get(name);Key key=key(subject,declaration);
            Document document=documents.get(key);
            if(document!=null&&document.problem!=null)throw invalid(document);
            if(document==null&&declaration.lifetime()==Lifetime.PERSISTENT)path(key);
            int valueBytes=valueBytes(write.getValue());
            if(write.getValue().type()==Type.STRING&&valueBytes>limits.maximumValueBytes())
                throw new Rejected(Outcome.Status.FAILED,"behavior-state-value-size","$.variables."+name,"Value of "+valueBytes+" bytes exceeds maximumValueBytes="+limits.maximumValueBytes());
            Value prior=document==null?null:document.values.get(name);
            long delta=prior==null?valueBytes+(long)name.length():valueBytes-(long)valueBytes(prior);
            if(prior==null){
                if(growth==null)growth=new HashMap<>();
                int added=growth.merge(key,1,Integer::sum);
                if((document==null?0:document.values.size())+added>MAXIMUM_KEYS_PER_DOCUMENT)
                    throw new Rejected(Outcome.Status.FAILED,"behavior-state-key-budget","$.variables."+name,"One subject of one behavior holds at most "+MAXIMUM_KEYS_PER_DOCUMENT+" keys; purge retained keys");
                if(declaration.lifetime()==Lifetime.PERSISTENT)newPersistent++;else newTransient++;
            }
            if(declaration.lifetime()==Lifetime.PERSISTENT)persistentDelta+=delta;else transientDelta+=delta;
            checkKeys(name,newPersistent,persistentKeys,limits.maximumKeys(),"maximumKeys");
            checkKeys(name,newTransient,transientKeys,limits.maximumTransientKeys(),"maximumTransientKeys");
            checkBytes(name,persistentDelta,persistentBytes);
            checkBytes(name,transientDelta,transientBytes);
        }
        for(Map.Entry<String,Value> write:writes.entrySet())apply(key(subject,declarations.get(write.getKey())),write.getKey(),write.getValue());
    }
    private void checkKeys(String name,int added,int current,int maximum,String setting){
        if(added>0&&current+added>maximum)throw new Rejected(Outcome.Status.FAILED,"behavior-state-key-budget","$.variables."+name,"State holds "+current+" keys; "+setting+"="+maximum);
    }
    private void checkBytes(String name,long delta,long current){
        if(delta>0&&current+delta>limits.maximumBytes())throw new Rejected(Outcome.Status.FAILED,"behavior-state-byte-budget","$.variables."+name,"State holds "+current+" bytes; maximumBytes="+limits.maximumBytes());
    }
    private void apply(Key key,String name,Value value){
        Document document=documents.get(key);
        if(document==null){document=new Document(key,key.lifetime()==Lifetime.PERSISTENT?path(key):null);documents.put(key,document);}
        Value prior=document.values.put(name,value);
        if(value.equals(prior))return;
        long delta=prior==null?valueBytes(value)+(long)name.length():valueBytes(value)-(long)valueBytes(prior);
        document.bytes+=delta;document.version++;
        if(key.lifetime()==Lifetime.TRANSIENT){transientBytes+=delta;if(prior==null)transientKeys++;return;}
        persistentBytes+=delta;if(prior==null)persistentKeys++;
        dirty.add(document);
        if(!dirtySignal){notBefore=clock.getAsLong()+flushNanos;dirtySignal=true;}
    }

    /** Owner turn hook. Costs one volatile read while nothing is dirty and queues at most one flush. */
    @Override public void maintain(){
        if(!dirtySignal||flushQueued)return;
        Executor executor=lane;
        if(executor==null||clock.getAsLong()-notBefore<0)return;
        flushQueued=true;
        try{executor.execute(flushTask);}
        catch(RejectedExecutionException full){flushQueued=false;notBefore=clock.getAsLong()+flushNanos;}
    }
    /** Lane observer: a cancelled or failed flush task must not block later ones. */
    public void flushAbandoned(){flushQueued=false;}
    private void flushFromLane(){
        boolean more=false,failed=false;
        try{more=flushBatch();}
        catch(IOException | RuntimeException failure){failed=true;}
        notBefore=clock.getAsLong()+(failed||!more?flushNanos:0);
        flushQueued=false;
    }

    /** File-lane only. Commits every dirty document in bounded journaled batches; on failure the rest stays dirty and retries later. */
    public void flushNow()throws IOException{
        while(flushBatch()){
            // Each batch is one bounded commit.
        }
    }
    private boolean flushBatch()throws IOException{
        List<Pending> batch=new ArrayList<>();
        synchronized(this){
            Iterator<Document> documentsToFlush=dirty.iterator();
            long chars=0;
            while(documentsToFlush.hasNext()){
                Document document=documentsToFlush.next();
                if(document.problem!=null){documentsToFlush.remove();continue;}
                // Escaped JSON can be six times the logical size; the budget stays conservative.
                long estimate=document.bytes*6+512;
                if(!batch.isEmpty()&&chars+estimate>BATCH_CHARS)break;
                chars+=estimate;batch.add(new Pending(document,document.version,new TreeMap<>(document.values)));
            }
        }
        List<DocumentStore.Generated> changes=new ArrayList<>(batch.size());
        try{
            for(int index=0;index<batch.size();index++){
                Pending pending=batch.get(index);
                String existing;
                try{existing=store.read(pending.document().path);}
                catch(CharacterCodingException undecodable){quarantine(pending.document(),"Stored bytes are not valid UTF-8");continue;}
                if(pending.values().isEmpty()&&existing==null)continue;
                changes.add(new DocumentStore.Generated(pending.document().path,pending.values().isEmpty()?null:serialize(pending.document().key,pending.values())));
            }
            if(!changes.isEmpty())store.commitGenerated(changes);
        }catch(IOException | RuntimeException failure){
            synchronized(this){flushFailures++;lastFailure=bounded(failure.getClass().getSimpleName()+": "+failure.getMessage());}
            throw failure;
        }
        List<String> emptied=new ArrayList<>();
        int cleaned=0;
        boolean more;
        synchronized(this){
            if(!changes.isEmpty())flushes++;
            for(int index=0;index<batch.size();index++){
                Pending pending=batch.get(index);Document document=pending.document();
                if(document.version!=pending.version())continue;
                dirty.remove(document);cleaned++;
                if(document.values.isEmpty()&&document.problem==null){documents.remove(document.key,document);emptied.add(document.path);}
            }
            if(dirty.isEmpty())dirtySignal=false;
            // Documents that changed again during this commit wait for the next interval, so a busy owner cannot pin the lane.
            more=cleaned>0&&!dirty.isEmpty();
        }
        for(int index=0;index<emptied.size();index++)pruneParents(emptied.get(index));
        return more;
    }
    private synchronized void quarantine(Document document,String message){
        document.problem=bounded(message);dirty.remove(document);
        if(document.key.lifetime()==Lifetime.PERSISTENT){persistentKeys-=document.values.size();persistentBytes-=document.bytes;}
        document.values.clear();document.bytes=0;
    }

    /** File-lane only. Removes matching keys from memory at once, then durably. Running executions read initial values afterward. */
    public PurgeResult purge(Purge selector)throws IOException{
        Objects.requireNonNull(selector);
        int removedDocuments=0,removedKeys=0;List<Document> invalid=new ArrayList<>();List<Problem> unattributed=List.of();
        synchronized(this){
            Iterator<Document> iterator=documents.values().iterator();
            while(iterator.hasNext()){
                Document document=iterator.next();
                if(!selector.matches(document.key))continue;
                if(document.problem!=null){invalid.add(document);iterator.remove();removedDocuments++;continue;}
                if(document.values.isEmpty())continue;
                removedDocuments++;removedKeys+=document.values.size();
                if(document.key.lifetime()==Lifetime.PERSISTENT){
                    persistentKeys-=document.values.size();persistentBytes-=document.bytes;
                    document.values.clear();document.bytes=0;document.version++;dirty.add(document);dirtySignal=true;
                }else{
                    transientKeys-=document.values.size();transientBytes-=document.bytes;iterator.remove();
                }
            }
            if(selector.everything()){unattributed=List.copyOf(strays);strays.clear();removedDocuments+=unattributed.size();}
        }
        for(Document document:invalid){store.deleteGenerated(document.path);pruneParents(document.path);}
        for(Problem stray:unattributed){store.deleteGenerated(stray.path());pruneParents(stray.path());}
        flushNow();
        return new PurgeResult(removedDocuments,removedKeys);
    }
    /** Empty directories would otherwise accumulate one per purged subject and count against the tree budget. */
    private void pruneParents(String path){
        try{
            Path root=store.resolve(ROOT);
            for(Path parent=store.resolve(path).getParent();parent!=null&&parent.startsWith(root)&&!parent.equals(root);parent=parent.getParent())Files.delete(parent);
        }catch(DirectoryNotEmptyException | NoSuchFileException stillUsed){
            // Another subject uses this directory, or it is already gone.
        }catch(IOException leftBehind){
            // An empty directory is harmless; the next purge of this subject tries again.
        }
    }

    /** Stops new writes, then flushes what is left. Runs on the file lane before the document store closes. */
    public void close()throws IOException{
        synchronized(this){closed=true;}
        flushNow();
    }

    public synchronized Status status(){
        int persistentDocuments=0,invalid=strays.size();
        for(Document document:documents.values()){
            if(document.problem!=null)invalid++;
            else if(document.key.lifetime()==Lifetime.PERSISTENT&&!document.values.isEmpty())persistentDocuments++;
        }
        return new Status(persistentKeys,transientKeys,persistentBytes,transientBytes,persistentDocuments,dirty.size(),invalid,flushes,flushFailures,lastFailure,closed);
    }
    /** Every invalid stored file with its exact reason, in path order. */
    public synchronized List<Problem> problems(){
        List<Problem> result=new ArrayList<>(strays);
        for(Document document:documents.values())if(document.problem!=null)result.add(new Problem(document.path,document.problem));
        result.sort(Comparator.comparing(Problem::path));
        return List.copyOf(result);
    }

    private void load()throws IOException{
        Path root=store.resolve(ROOT);
        for(Path file:JsonDocumentTree.files(root,TREE_DEPTH)){
            String relative=root.relativize(file).toString().replace('\\','/'),path=ROOT+"/"+relative;
            Key key=parsePath(relative);
            if(key==null){strays.add(new Problem(path,"This path does not name a behavior state subject"));continue;}
            Document document=new Document(key,path);documents.put(key,document);
            try{
                parse(store.read(path),key,document);
                persistentKeys+=document.values.size();persistentBytes+=document.bytes;
            }catch(IOException | RuntimeException invalid){
                document.problem=bounded(invalid.getMessage()==null?invalid.getClass().getSimpleName():invalid.getMessage());
                document.values.clear();document.bytes=0;
            }
        }
    }
    private static void parse(String content,Key key,Document document)throws IOException{
        JsonObject root=JsonDocuments.object(content);
        JsonElement version=root.get("schemaVersion");
        if(version==null||!version.isJsonPrimitive()||!version.getAsJsonPrimitive().isNumber()||version.getAsBigDecimal().compareTo(BigDecimal.ONE)!=0)throw new IOException("$.schemaVersion: expected 1");
        if(!key.behavior().equals(text(root,"behavior")))throw new IOException("$.behavior: does not match this file's path");
        if(!name(key.scope()).equals(text(root,"scope")))throw new IOException("$.scope: does not match this file's path");
        identity(root,"npc",key.npc());identity(root,"player",key.player());
        JsonElement values=root.get("values");
        if(values==null||!values.isJsonObject())throw new IOException("$.values: expected object");
        for(Map.Entry<String,JsonElement> entry:values.getAsJsonObject().entrySet()){
            String name=entry.getKey(),field="$.values."+name;
            if(!name.matches(NAME_PATTERN))throw new IOException(field+": invalid variable name");
            if(!entry.getValue().isJsonObject())throw new IOException(field+": expected object");
            Value value=value(entry.getValue().getAsJsonObject(),field);
            document.values.put(name,value);document.bytes+=valueBytes(value)+name.length();
        }
    }
    private static void identity(JsonObject root,String field,UUID expected)throws IOException{
        if(expected==null){if(root.has(field))throw new IOException("$."+field+": does not belong to this scope");return;}
        if(!expected.toString().equals(text(root,field)))throw new IOException("$."+field+": does not match this file's path");
    }
    private static String text(JsonObject root,String field)throws IOException{
        JsonElement value=root.get(field);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IOException("$."+field+": expected string");
        return value.getAsString();
    }
    private static Value value(JsonObject object,String field)throws IOException{
        String declared=text(object,"type");JsonElement raw=object.get("value");
        if(raw==null||!raw.isJsonPrimitive())throw new IOException(field+".value: expected scalar");
        JsonPrimitive primitive=raw.getAsJsonPrimitive();
        try{
            return switch(declared){
                case "boolean"->{if(!primitive.isBoolean())throw new IOException(field+".value: expected boolean");yield new Value(Type.BOOLEAN,primitive.getAsBoolean());}
                case "integer"->{if(!primitive.isNumber())throw new IOException(field+".value: expected integer");yield new Value(Type.INTEGER,primitive.getAsBigDecimal().longValueExact());}
                case "decimal"->{if(!primitive.isNumber())throw new IOException(field+".value: expected decimal");yield new Value(Type.DECIMAL,primitive.getAsDouble());}
                case "string"->{if(!primitive.isString())throw new IOException(field+".value: expected string");yield new Value(Type.STRING,primitive.getAsString());}
                default->throw new IOException(field+".type: expected boolean, integer, decimal or string");
            };
        }catch(ArithmeticException | IllegalArgumentException invalid){throw new IOException(field+".value: outside the type's bounds",invalid);}
    }
    private static String serialize(Key key,Map<String,Value> values){
        JsonObject root=new JsonObject();
        root.addProperty("schemaVersion",1);root.addProperty("behavior",key.behavior());root.addProperty("scope",name(key.scope()));
        if(key.npc()!=null)root.addProperty("npc",key.npc().toString());
        if(key.player()!=null)root.addProperty("player",key.player().toString());
        JsonObject stored=new JsonObject();
        for(Map.Entry<String,Value> entry:values.entrySet()){
            Value value=entry.getValue();JsonObject item=new JsonObject();
            item.addProperty("type",name(value.type()));
            if(value.value() instanceof Boolean flag)item.addProperty("value",flag);
            else if(value.value() instanceof Number number)item.addProperty("value",number);
            else item.addProperty("value",(String)value.value());
            stored.add(entry.getKey(),item);
        }
        root.add("values",stored);
        return JsonDocuments.write(root);
    }

    private static Key key(Subject subject,ScopedVariable declaration){
        Scope scope=declaration.scope();
        if(scope.requiresPlayer()&&subject.player().isEmpty())throw new Rejected(Outcome.Status.INVALID,"behavior-state-player-required","$.variables","This variable belongs to a player, and this execution has none");
        UUID npc=scope==Scope.NPC||scope==Scope.RELATIONSHIP?subject.npc():null;
        UUID player=scope.requiresPlayer()?subject.player().get():null;
        return new Key(declaration.lifetime(),scope,subject.behavior(),npc,player);
    }
    private static String path(Key key){
        String behavior;
        try{behavior=BehaviorPath.forId(key.behavior()).substring("behaviors/".length());}
        catch(IllegalArgumentException invalid){throw new Rejected(Outcome.Status.INVALID,"behavior-state-identity","$.id",invalid.getMessage());}
        StringBuilder path=new StringBuilder(ROOT).append('/').append(name(key.scope())).append('/');
        if(key.npc()!=null)path.append(key.npc()).append('/');
        if(key.player()!=null)path.append(key.player()).append('/');
        return path.append(behavior).toString();
    }
    /** Null for a path that names no subject. The document's own fields must then agree with what this returns. */
    private static Key parsePath(String relative){
        String[] parts=relative.split("/",-1);
        Scope scope;
        try{scope=Scope.valueOf(parts[0].toUpperCase(Locale.ROOT));}catch(IllegalArgumentException unknown){return null;}
        if(!name(scope).equals(parts[0]))return null;
        int identities=(scope==Scope.NPC||scope==Scope.PLAYER)?1:scope==Scope.RELATIONSHIP?2:0;
        if(parts.length<identities+3)return null;
        try{
            UUID first=identities>0?canonical(parts[1]):null,second=identities>1?canonical(parts[2]):null;
            String behavior=BehaviorPath.idFor("behaviors/"+String.join("/",List.of(parts).subList(identities+1,parts.length)));
            UUID npc=scope==Scope.PLAYER?null:first,player=scope==Scope.PLAYER?first:second;
            return new Key(Lifetime.PERSISTENT,scope,behavior,npc,player);
        }catch(IllegalArgumentException invalid){return null;}
    }
    private static UUID canonical(String text){
        UUID value=UUID.fromString(text);
        if(!value.toString().equals(text))throw new IllegalArgumentException("Non-canonical identity");
        return value;
    }
    private static Rejected invalid(Document document){
        return new Rejected(Outcome.Status.CONFLICT,"behavior-state-document-invalid",document.path,"Stored state is invalid and left unchanged; purge it to continue: "+document.problem);
    }
    private static int valueBytes(Value value){return value.value() instanceof String text?text.getBytes(StandardCharsets.UTF_8).length:Long.BYTES;}
    private static String name(Enum<?> value){return value.name().toLowerCase(Locale.ROOT);}
    private static String bounded(String message){
        String text=message==null?"Unavailable":message.length()>MAXIMUM_PROBLEM_CHARS?message.substring(0,MAXIMUM_PROBLEM_CHARS):message;
        StringBuilder safe=new StringBuilder(text.length());
        for(int offset=0;offset<text.length();){
            int point=text.codePointAt(offset);offset+=Character.charCount(point);
            safe.appendCodePoint(point>=0xd800&&point<=0xdfff?'?':point);
        }
        return safe.toString();
    }
    /**
     * The final flush runs on the file lane after the platform may have closed the plugin archive.
     * Everything it needs must already be resolved, so walk that code here while the archive is open.
     */
    private void prepareClosingClasses(){
        Document sample=new Document(new Key(Lifetime.PERSISTENT,Scope.NPC,"animus:closing",new UUID(0,0),null),"unused");
        Map<String,Value> values=new TreeMap<>();
        values.put("flag",new Value(Type.BOOLEAN,true));values.put("text",new Value(Type.STRING,"closing"));values.put("count",new Value(Type.INTEGER,0L));
        JsonObject journal=new JsonObject();journal.add("entries",new JsonArray());journal.addProperty("receipt",false);
        JsonDocuments.write(journal);
        JsonDocuments.hash(serialize(sample.key,values));
        new Pending(sample,0,values);
        new DocumentStore.Generated("unused",null);
        new DocumentStore.Change("unused","missing",null);
    }
}
