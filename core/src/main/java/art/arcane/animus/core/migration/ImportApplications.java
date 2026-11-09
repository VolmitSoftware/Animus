package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.DefinitionBatch;
import art.arcane.animus.core.storage.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Explicit content-addressed application previews with optimistic apply and ordinary rollback.
 * Call from the shared serial mutation lane; applying and reloading the live repository are one lane job.
 */
public final class ImportApplications {
    public record Preview(String importId,String applicationId,Path report,int records,int replacements,String mode) {}
    private record Existing(NpcDefinition definition,String hash) {}
    /** {@code extras} are the skins, generated behaviors and factored groups: written when missing, refused when present with other content. */
    private record Bundle(String reportHash,List<NpcDefinition> definitions,Map<String,String> extras) {}
    private static final String OBJECTS="import-applications";
    private static final class LoadBudget {
        private long bytes;
        void charge(String text) throws IOException { charge(text==null?0:text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length); }
        void charge(long additional) throws IOException {
            bytes+=additional;
            if(bytes>64L*1024*1024)throw new IOException("Application input/projection exceeds 64 MiB; use a smaller reviewed batch");
        }
    }

    public static Preview stage(DocumentStore store,String importId,String mode,String label) throws IOException {
        ImportProjection.Mode selected;
        try { selected=ImportProjection.Mode.valueOf(Objects.requireNonNull(mode).toUpperCase(Locale.ROOT)); }
        catch(IllegalArgumentException | NullPointerException invalid) { throw new IOException("Expected import mode reject, replace or duplicate",invalid); }
        return stage(store,importId,selected,label);
    }

    static Preview stage(DocumentStore store,String importId,ImportProjection.Mode mode,String label) throws IOException {
        LoadBudget budget=new LoadBudget();
        Bundle bundle=bundle(store,importId,budget);
        Map<String,Existing> existing=active(store,budget);
        var projection=ImportProjection.project(importId,bundle.definitions(),existing.values().stream().map(Existing::definition).toList(),mode,label);
        if(2L*projection.definitions().size()+1>10_000)throw new IOException("Application exceeds 10000 transaction entries including checkpoints and receipt");
        JsonObject documents=new JsonObject();
        long transactionEntries=2L*projection.definitions().size()+1, candidateReadBytes=0;
        // Immutable objects are harmless before publication; failed staging never changes active definitions.
        for(NpcDefinition npc:projection.definitions()) {
            Existing prior=existing.get(npc.path());
            String content=npc.serialize();budget.charge(content);
            SavedCandidate saved=saveCandidate(store,content);candidateReadBytes+=saved.readBytes();
            add(documents,npc.path(),prior==null?"missing":prior.hash(),saved.hash());
        }
        for(var skin:bundle.extras().entrySet()) {
            String before=store.read(skin.getKey());budget.charge(before);
            if(before==null && ++transactionEntries>10_000)throw new IOException("Application exceeds 10000 transaction entries including skins/checkpoints/receipt");
            if(before!=null && !before.equals(skin.getValue()))throw new IOException((skin.getKey().startsWith("skins/")?"Skin":skin.getKey().startsWith("groups/")?"Group":"Behavior")+" collision: "+skin.getKey()+" exists with other content");
            SavedCandidate saved=saveCandidate(store,skin.getValue());candidateReadBytes+=saved.readBytes();
            add(documents,skin.getKey(),JsonDocuments.hash(before),saved.hash());
        }
        JsonObject plan=new JsonObject();plan.addProperty("schemaVersion",1);plan.addProperty("importId",importId);
        plan.addProperty("sourceReportSha256",bundle.reportHash());plan.addProperty("mode",mode.name());
        if(label!=null)plan.addProperty("copyLabel",label);
        plan.addProperty("records",projection.definitions().size());plan.addProperty("replacements",projection.replacedPaths().size());
        plan.addProperty("notice","Explicit application installs dormant NPCs and disables imported combat. Replacement overwrites reviewed target definitions; rollback refuses subsequent edits. Unknown preserved references still require integration review.");
        plan.add("documents",documents);
        JsonObject mapping=new JsonObject();projection.identities().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->mapping.addProperty(e.getKey().toString(),e.getValue().toString()));plan.add("uuidMap",mapping);
        String content=JsonDocuments.write(plan), id=JsonDocuments.hash(content), path=planPath(importId,id);
        // Reserve the candidate-object and plan reads that apply performs in addition to this projection.
        // Existing-skin preflight bytes make this conservative, never less strict than unchanged apply.
        budget.charge(candidateReadBytes);budget.charge(content);
        String prior=store.read(path);
        if(prior!=null && !prior.equals(content))throw new IOException("Application preview hash mismatch");
        if(prior==null)store.commit(List.of(new DocumentStore.Change(path,"missing",content)));
        return new Preview(importId,id,store.resolve(path),projection.definitions().size(),projection.replacedPaths().size(),mode.name().toLowerCase(Locale.ROOT));
    }

    /** Installs the application; {@code already-applied} when its receipt says it is installed. */
    public static String apply(DocumentStore store,String importId,String applicationId) throws IOException {
        Installed installed=install(store,importId,applicationId,UUID.randomUUID().toString());
        return installed.repeated()?"already-applied":installed.transaction();
    }

    /**
     * Installs the application under {@code transaction}. When its receipt says it is installed, returns the transaction
     * that installed it, so a retried apply records the real id.
     */
    public static String apply(DocumentStore store,String importId,String applicationId,String transaction) throws IOException {
        Installed installed=install(store,importId,applicationId,transaction);
        if(installed.transaction()==null)throw new IOException("Application "+applicationId.substring(0,12)+" was installed by an older Animus that did not record its transaction; stage a new preview");
        return installed.transaction();
    }

    private record Installed(String transaction,boolean repeated) {}

    private static Installed install(DocumentStore store,String importId,String applicationId,String transaction) throws IOException {
        LoadBudget budget=new LoadBudget();
        JsonObject plan=plan(store,importId,applicationId,budget);
        String receiptPath=receiptPath(importId,applicationId), receipt=store.read(receiptPath);
        Map<String,String> candidates=candidates(store,plan,budget);
        if(receipt!=null) {
            JsonObject data=JsonDocuments.object(receipt);
            if(!string(data,"importId").equals(importId) || !string(data,"applicationId").equals(applicationId)
                    || !string(data,"state").equals("installed-dormant"))throw new IOException("Invalid application receipt");
            for(var candidate:candidates.entrySet())if(!Objects.equals(store.read(candidate.getKey()),candidate.getValue()))
                throw new IOException("Already-applied application has changed or missing output: "+candidate.getKey());
            JsonElement recorded=data.get("transaction");
            return new Installed(recorded!=null && recorded.isJsonPrimitive()?recorded.getAsString():null,true);
        }
        Bundle source=bundle(store,importId,budget);
        if(!source.reportHash().equals(string(plan,"sourceReportSha256")))throw new IOException("Source import report changed; stage a new application preview");
        Map<String,Existing> current=active(store,budget);
        ImportProjection.Mode mode;
        try {mode=ImportProjection.Mode.valueOf(string(plan,"mode"));}
        catch(IllegalArgumentException invalid){throw new IOException("Invalid application mode",invalid);}
        String label=plan.has("copyLabel")?string(plan,"copyLabel"):null;
        // Recompute against current identities. A new UUID/ID collision outside the edited paths must also reject apply.
        var projected=ImportProjection.project(importId,source.definitions(),current.values().stream().map(Existing::definition).toList(),mode,label);
        Map<String,String> expected=new TreeMap<>(source.extras());
        for(NpcDefinition definition:projected.definitions()){String content=definition.serialize();budget.charge(content);expected.put(definition.path(),content);}
        if(!expected.equals(candidates))throw new IOException("Application no longer matches current definitions; stage a new preview");
        if(integer(plan,"records")!=projected.definitions().size() || integer(plan,"replacements")!=projected.replacedPaths().size())
            throw new IOException("Application counts changed");
        JsonObject manifest=object(plan,"documents");List<DocumentStore.Change> changes=new ArrayList<>();
        Map<String,String> expectedHashes=new HashMap<>();
        for(var candidate:candidates.entrySet()) {
            String beforeHash=string(object(manifest,candidate.getKey()),"expectedHash");
            if(!beforeHash.equals(store.hash(candidate.getKey())))throw new IOException("Conflict: "+candidate.getKey()+"; stage a new application preview");
            expectedHashes.put(candidate.getKey(),beforeHash);
            // Existing content-addressed skin files are validated above, as in the default importer.
            // Avoid duplicating unchanged payloads in the bounded rollback journal.
            if(!candidate.getKey().startsWith("npcs/") && beforeHash.equals("missing"))
                changes.add(new DocumentStore.Change(candidate.getKey(),beforeHash,candidate.getValue()));
        }
        JsonObject installed=new JsonObject();installed.addProperty("schemaVersion",1);installed.addProperty("importId",importId);
        installed.addProperty("applicationId",applicationId);installed.addProperty("state","installed-dormant");
        installed.addProperty("records",projected.definitions().size());installed.addProperty("mode",mode.name());
        installed.addProperty("transaction",transaction);
        changes.add(new DocumentStore.Change(receiptPath,"missing",JsonDocuments.write(installed)));
        return new Installed(DefinitionBatch.commit(store,transaction,projected.definitions(),expectedHashes,changes),false);
    }

    private static JsonObject plan(DocumentStore store,String importId,String applicationId,LoadBudget budget) throws IOException {
        String raw=store.read(planPath(importId,applicationId));budget.charge(raw);
        if(raw==null || !JsonDocuments.hash(raw).equals(applicationId))throw new IOException("Application preview missing or changed");
        JsonObject plan=JsonDocuments.object(raw);
        if(integer(plan,"schemaVersion")!=1 || !string(plan,"importId").equals(importId))throw new IOException("Invalid application preview identity/schema");
        return plan;
    }
    private static Map<String,String> candidates(DocumentStore store,JsonObject plan,LoadBudget budget) throws IOException {
        JsonObject documents=object(plan,"documents");
        if(documents.isEmpty() || documents.size()>9_999)throw new IOException("Application requires 1..9999 documents before checkpoints/receipt");
        long transactionEntries=1;
        for(var entry:documents.entrySet())transactionEntries+=entry.getKey().startsWith("npcs/")?2:string(object(documents,entry.getKey()),"expectedHash").equals("missing")?1:0;
        if(transactionEntries>10_000)throw new IOException("Application exceeds 10000 transaction entries including checkpoints and receipt");
        Map<String,String> result=new TreeMap<>();
        for(var entry:documents.entrySet()) {
            String path=entry.getKey();
            if(!NpcPaths.file(path) && !path.matches("skins/[a-f0-9]{64}\\.json") && !path.matches(CitizensImporter.BEHAVIOR_PATH) && !path.matches(GroupFactoring.GROUP_PATH))throw new IOException("Invalid application target: "+path);
            JsonObject descriptor=object(documents,path);String hash=string(descriptor,"objectHash"), before=string(descriptor,"expectedHash");
            if(!before.equals("missing") && !before.matches("[a-f0-9]{64}"))throw new IOException("Invalid expected hash: "+path);
            String encoded=store.immutableJson(OBJECTS,hash);budget.charge(encoded);
            JsonObject saved=JsonDocuments.object(encoded);
            if(integer(saved,"schemaVersion")!=1)throw new IOException("Unsupported candidate object schema");
            String value=string(saved,"document");budget.charge(value);
            if(path.startsWith("npcs/")) {
                NpcDefinition npc=NpcDefinition.parse(value);if(!npc.path().equals(path))throw new IOException("Application NPC path mismatch");
            } else if(path.startsWith("skins/") && !path.equals("skins/"+JsonDocuments.hash(value)+".json"))throw new IOException("Application skin hash mismatch");
            result.put(path,value);
        }
        return result;
    }
    private static Map<String,Existing> active(DocumentStore store,LoadBudget budget) throws IOException {
        Map<String,Existing> definitions=new LinkedHashMap<>();Set<String> ids=new HashSet<>();Set<UUID> uuids=new HashSet<>();
        Path directory=store.resolve("npcs");
        for(Path path:JsonDocumentTree.files(directory,NpcPaths.TREE_DEPTH)) {
            String raw=JsonDocuments.read(path);budget.charge(raw);
            NpcDefinition npc=NpcDefinition.read("npcs/"+directory.relativize(path).toString().replace('\\','/'),raw);
            if(!ids.add(npc.id()) || !uuids.add(npc.uuid()))throw new IOException("Existing NPC identity collision: "+path);
            definitions.put(npc.path(),new Existing(npc,JsonDocuments.hash(raw)));
        }
        return definitions;
    }
    private static Bundle bundle(DocumentStore store,String importId,LoadBudget budget) throws IOException {
        requireId(importId);String prefix="imports/"+importId+"/";
        var preview=new CitizensImporter().readPreview(store.resolve("imports/"+importId),importId);
        String report=store.read(prefix+"report.json");budget.charge(report);JsonObject summary=preview.summary();
        if(integer(summary,"schemaVersion")!=1 || preview.records()==0 || preview.records()!=preview.valid())throw new IOException("Import has validation errors");
        JsonObject documents=object(summary,"documents"), ids=object(summary,"idMap");
        if(documents.size()!=preview.records()+preview.skins()+preview.behaviors()+preview.groups() || documents.size()>9_999)throw new IOException("Invalid or oversized import manifest");
        List<NpcDefinition> definitions=new ArrayList<>();Map<String,String> skins=new TreeMap<>(), behaviors=new TreeMap<>(), groups=new TreeMap<>();Set<String> required=new HashSet<>();
        for(var entry:documents.entrySet()) {
            String path=entry.getKey();
            if(!path.matches(CitizensImporter.BUNDLE_PATH))throw new IOException("Invalid bundle path: "+path);
            String value=store.read(prefix+path);budget.charge(value);
            if(value==null || !JsonDocuments.hash(value).equals(string(documents,path)))throw new IOException("Preview changed: "+path);
            if(path.startsWith("npcs/")) {
                NpcDefinition npc=NpcDefinition.parse(value);JsonObject data=npc.json();
                if(!npc.path().equals(path))throw new IOException("Bundle NPC path mismatch");
                int oldId=ModuleImports.sourceNumber(object(data,"migration"));
                if(!npc.uuid().toString().equals(string(ids,Integer.toString(oldId))))throw new IOException("Import identity map mismatch");
                if(data.has("skin"))required.add("skins/"+string(data,"skin")+".json");definitions.add(npc);
            } else if(path.startsWith("behaviors/")) behaviors.put(path,value);
            else if(path.startsWith("groups/")) {
                if(art.arcane.animus.core.npc.GroupDocument.parse(path.substring(7,path.length()-5),value).group().isEmpty())throw new IOException("Invalid factored group: "+path);
                groups.put(path,value);
            }
            else {
                if(!path.equals("skins/"+JsonDocuments.hash(value)+".json"))throw new IOException("Skin hash mismatch");
                skins.put(path,value);
            }
        }
        if(definitions.size()!=preview.records() || skins.size()!=preview.skins() || behaviors.size()!=preview.behaviors() || groups.size()!=preview.groups() || !skins.keySet().containsAll(required))throw new IOException("Incomplete import bundle");
        Map<String,String> extras=new TreeMap<>(skins);extras.putAll(behaviors);extras.putAll(groups);
        return new Bundle(JsonDocuments.hash(report),List.copyOf(definitions),Map.copyOf(extras));
    }
    private record SavedCandidate(String hash,long readBytes) {}
    private static SavedCandidate saveCandidate(DocumentStore store,String content) throws IOException {
        JsonObject saved=new JsonObject();saved.addProperty("schemaVersion",1);saved.addProperty("document",content);
        String encoded=JsonDocuments.write(saved);
        return new SavedCandidate(store.putImmutableJson(OBJECTS,encoded),
                (long)encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }
    private static void add(JsonObject manifest,String path,String before,String hash){JsonObject entry=new JsonObject();entry.addProperty("expectedHash",before);entry.addProperty("objectHash",hash);manifest.add(path,entry);}
    private static String planPath(String importId,String applicationId) throws IOException {requireId(importId);requireId(applicationId);return "imports/"+importId+"/applications/"+applicationId+"/plan.json";}
    private static String receiptPath(String importId,String applicationId) throws IOException {return planPath(importId,applicationId).replace("/plan.json","/receipt.json");}
    private static void requireId(String id) throws IOException {if(id==null || !id.matches("[a-f0-9]{64}"))throw new IOException("Invalid import/application ID");}
    private static JsonObject object(JsonObject parent,String key) throws IOException {JsonElement value=parent.get(key);if(value==null || !value.isJsonObject())throw new IOException("Expected object: "+key);return value.getAsJsonObject();}
    private static String string(JsonObject parent,String key) throws IOException {JsonElement value=parent.get(key);if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())throw new IOException("Expected string: "+key);return value.getAsString();}
    private static int integer(JsonObject parent,String key) throws IOException {
        JsonElement value=parent.get(key);try {if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())throw new ArithmeticException();return value.getAsBigDecimal().intValueExact();}
        catch(ArithmeticException | NumberFormatException error){throw new IOException("Expected exact integer: "+key,error);}
    }
}
