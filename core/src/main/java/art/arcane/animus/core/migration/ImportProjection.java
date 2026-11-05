package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure application preview. No source archive or active definition is ever changed here. */
final class ImportProjection {
    enum Mode { REJECT, REPLACE, DUPLICATE }
    record Result(List<NpcDefinition> definitions, Map<UUID, UUID> identities, Set<String> replacedPaths) {
        Result { definitions=List.copyOf(definitions); identities=Map.copyOf(identities); replacedPaths=Set.copyOf(replacedPaths); }
    }

    static Result project(String importId, List<NpcDefinition> imported, Collection<NpcDefinition> active,
                          Mode mode, String label) throws IOException {
        Objects.requireNonNull(mode);
        if(!importId.matches("[a-f0-9]{64}"))throw new IOException("Invalid import identity");
        if(imported.isEmpty() || imported.size()>10_000)throw new IOException("Application requires 1..10000 imported definitions");
        if(mode==Mode.DUPLICATE) {
            if(label==null || !label.matches("[a-z0-9][a-z0-9_-]{0,31}"))throw new IOException("Duplicate label requires 1..32 lowercase letters, digits, _ or -");
        } else if(label!=null)throw new IOException("A copy label is valid only for duplicate mode");

        Map<String,NpcDefinition> currentByPath=new HashMap<>(), currentById=new HashMap<>();
        Map<UUID,NpcDefinition> currentByUuid=new HashMap<>();
        for(NpcDefinition npc:active) {
            if(currentByPath.putIfAbsent(npc.path(),npc)!=null || currentById.putIfAbsent(npc.id(),npc)!=null
                    || currentByUuid.putIfAbsent(npc.uuid(),npc)!=null)throw new IOException("Existing identity collision: "+npc.path());
        }
        Map<UUID,UUID> uuids=new LinkedHashMap<>();
        Map<String,String> ids=new HashMap<>();
        Map<Integer,NpcDefinition> byLegacyId=new HashMap<>();
        for(NpcDefinition npc:imported) {
            JsonObject data=npc.json(), migration=object(data,"migration");
            if(!string(migration,"importId").equals(importId))throw new IOException("NPC import identity mismatch: "+npc.id());
            int legacy=integer(migration,"citizensId");
            if(legacy<0 || !npc.id().equals("citizens-"+legacy) || !npc.path().matches(CitizensImporter.NPC_PATH))throw new IOException("Unexpected source import path: "+npc.path());
            dormant(data);
            UUID uuid=mode==Mode.DUPLICATE?UUID.nameUUIDFromBytes(("animus:import-copy:v1:"+importId+":"+label+":"+npc.uuid()).getBytes(StandardCharsets.UTF_8)):npc.uuid();
            String id=mode==Mode.DUPLICATE?npc.id()+"-"+label:npc.id();
            if(uuids.putIfAbsent(npc.uuid(),uuid)!=null || ids.putIfAbsent(npc.id(),id)!=null || byLegacyId.putIfAbsent(legacy,npc)!=null)
                throw new IOException("Duplicate imported identity: "+npc.id());
        }

        List<NpcDefinition> definitions=new ArrayList<>(); Set<String> replaced=new HashSet<>();
        Set<UUID> projectedUuids=new HashSet<>(); Set<String> projectedIds=new HashSet<>();
        for(NpcDefinition original:imported) {
            JsonObject data=original.json();
            data.addProperty("uuid",uuids.get(original.uuid()).toString()); data.addProperty("id",ids.get(original.id()));
            if(mode==Mode.DUPLICATE) {
                data.addProperty("group","copy-"+label); data.addProperty("revision",1);
                JsonObject copy=new JsonObject();copy.addProperty("label",label);copy.addProperty("sourceUuid",original.uuid().toString());copy.addProperty("sourceId",original.id());
                data.getAsJsonObject("migration").add("copy",copy);
            }
            remapResolvedReferences(data,byLegacyId,uuids,ids);
            NpcDefinition candidate=NpcDefinition.parse(JsonDocuments.write(data));
            NpcDefinition prior=currentByPath.get(candidate.path());
            if(prior!=null) {
                if(mode!=Mode.REPLACE)throw new IOException("NPC collision: "+candidate.path());
                if(!prior.uuid().equals(candidate.uuid()) || !prior.id().equals(candidate.id()))throw new IOException("Replacement must retain the existing UUID and ID: "+candidate.path());
                JsonObject provenance=object(prior.json(),"migration");
                if(integer(provenance,"citizensId")!=integer(data.getAsJsonObject("migration"),"citizensId"))throw new IOException("Replacement source numeric identity changed: "+candidate.path());
                if(prior.revision()==Long.MAX_VALUE)throw new IOException("Revision exhausted: "+candidate.path());
                data.addProperty("revision",prior.revision()+1);candidate=NpcDefinition.parse(JsonDocuments.write(data));
                replaced.add(prior.path());
            }
            NpcDefinition sameId=currentById.get(candidate.id()), sameUuid=currentByUuid.get(candidate.uuid());
            if(sameId!=null && !sameId.path().equals(candidate.path()) || sameUuid!=null && !sameUuid.path().equals(candidate.path()))
                throw new IOException("NPC identity collides at another path: "+candidate.id());
            if(!projectedIds.add(candidate.id()) || !projectedUuids.add(candidate.uuid()))throw new IOException("Projected identity collision: "+candidate.id());
            definitions.add(candidate);
        }
        if((long)active.size()+definitions.size()-replaced.size()>100_000)throw new IOException("NPC definition limit exceeded: 100000");
        return new Result(definitions,uuids,replaced);
    }

    private static void remapResolvedReferences(JsonObject data,Map<Integer,NpcDefinition> source,Map<UUID,UUID> uuids,Map<String,String> ids) throws IOException {
        JsonObject migration=data.getAsJsonObject("migration");
        if(!migration.has("references"))return; // Version-one archives have no normalized reference array.
        if(!migration.get("references").isJsonArray())throw new IOException("Invalid migration references");
        for(JsonElement value:migration.getAsJsonArray("references")) {
            if(!value.isJsonObject())throw new IOException("Invalid migration reference");
            JsonObject reference=value.getAsJsonObject();
            if(!string(reference,"status").equals("resolved"))continue;
            NpcDefinition target=source.get(integer(reference,"citizensId"));
            if(target==null || !string(reference,"npcId").equals(target.id()) || !string(reference,"npcUuid").equals(target.uuid().toString()))
                throw new IOException("Resolved migration reference does not match the source identity map");
            reference.addProperty("npcId",ids.get(target.id()));reference.addProperty("npcUuid",uuids.get(target.uuid()).toString());
            SentinelMapping.remapGuard(data,reference);
        }
    }
    private static void dormant(JsonObject data) throws IOException {
        if(!new JsonPrimitive(false).equals(data.get("spawned")))throw new IOException("Imported definition must remain dormant");
        JsonObject modules=data.has("modules")?object(data,"modules"):new JsonObject();
        if(modules.has("animus:combat")) {
            JsonObject combat=object(modules,"animus:combat");
            if(!new JsonPrimitive(false).equals(combat.get("enabled")) || !new JsonPrimitive(false).equals(combat.get("reviewAccepted")))
                throw new IOException("Imported combat must remain disabled and unreviewed");
        }
    }
    private static JsonObject object(JsonObject parent,String key) throws IOException {
        JsonElement value=parent.get(key);if(value==null || !value.isJsonObject())throw new IOException("Expected object: "+key);return value.getAsJsonObject();
    }
    private static String string(JsonObject parent,String key) throws IOException {
        JsonElement value=parent.get(key);if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())throw new IOException("Expected string: "+key);return value.getAsString();
    }
    private static int integer(JsonObject parent,String key) throws IOException {
        JsonElement value=parent.get(key);
        try {if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())throw new ArithmeticException();return value.getAsBigDecimal().intValueExact();}
        catch(ArithmeticException | NumberFormatException invalid){throw new IOException("Expected exact integer: "+key,invalid);}
    }
}
