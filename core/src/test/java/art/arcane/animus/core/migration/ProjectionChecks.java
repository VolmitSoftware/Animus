package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class ProjectionChecks {
    private static int checks;
    interface Checked { void run() throws Exception; }
    static void check(boolean value,String name) { if(!value)throw new AssertionError(name); checks++; }
    static void rejects(Checked action,String message) throws Exception {
        try {action.run();throw new AssertionError("Expected rejection: "+message);}
        catch(IOException error){check(error.getMessage().contains(message),"Wrong rejection: "+error.getMessage());}
    }
    static NpcDefinition with(NpcDefinition npc,String field,JsonElement value) throws Exception {
        JsonObject data=npc.json();data.add(field,value);return NpcDefinition.parse(JsonDocuments.write(data));
    }
    public static void main(String[] args) throws Exception {
        Path temp=Files.createTempDirectory("animus-projection-checks-");
        try {
            String source="""
                    npc:
                      '1':
                        uuid: 00000000-0000-0000-0000-000000000001
                        name: Guard
                        traits:
                          type: PLAYER
                          location: {world: world, x: 0, y: 64, z: 0}
                          sentinel: {guardedNPC: 2, health: 40}
                          mounttrait: {mountedon: '00000000-0000-0000-0000-000000000002'}
                          unknown: {npc-reference: '00000000-0000-0000-0000-000000000002'}
                      '2':
                        uuid: 00000000-0000-0000-0000-000000000002
                        name: Mount
                        traits:
                          type: HORSE
                          location: {world: world, x: 3, y: 64, z: 0}
                    """;
            Path input=Files.writeString(temp.resolve("saves.yml"),source);
            var preview=new CitizensImporter().preview(input,temp.resolve("data"),Map.of());
            List<NpcDefinition> original=new ArrayList<>();
            for(int id=1;id<=2;id++)original.add(NpcDefinition.parse(Files.readString(preview.directory().resolve("npcs/world/citizens-"+id+".json"))));
            String firstBefore=original.getFirst().serialize();
            String id=preview.id();
            var fresh=ImportProjection.project(id,original,List.of(),ImportProjection.Mode.REJECT,null);
            check(fresh.definitions().size()==2 && fresh.replacedPaths().isEmpty(),"fresh batch");
            check(fresh.definitions().getFirst().uuid().equals(original.getFirst().uuid()),"normal identity retained");
            rejects(()->ImportProjection.project(id,original,original,ImportProjection.Mode.REJECT,null),"collision");
            NpcDefinition edited=with(original.getFirst(),"revision",new JsonPrimitive(7));
            var replaced=ImportProjection.project(id,original,List.of(edited),ImportProjection.Mode.REPLACE,null);
            check(replaced.definitions().getFirst().revision()==8 && replaced.definitions().get(1).revision()==1,"replacement revision / new additions");
            check(replaced.replacedPaths().equals(Set.of(edited.path())),"exact replacement list");
            check(replaced.definitions().getFirst().json().get("spawned").getAsBoolean(),"replacement spawns as converted");
            check(replaced.definitions().getFirst().json().getAsJsonObject("modules").getAsJsonObject("animus:combat").get("enabled").getAsBoolean(),"replacement combat on as converted");
            NpcDefinition mismatched=with(edited,"uuid",new JsonPrimitive(UUID.randomUUID().toString()));
            rejects(()->ImportProjection.project(id,original,List.of(mismatched),ImportProjection.Mode.REPLACE,null),"retain the existing UUID");
            NpcDefinition elsewhere=with(edited,"group",new JsonPrimitive("elsewhere"));
            var relocated=ImportProjection.project(id,original,List.of(elsewhere),ImportProjection.Mode.REPLACE,null);
            check(relocated.replacedPaths().equals(Set.of("npcs/elsewhere/citizens-1.json")) && relocated.definitions().getFirst().path().equals("npcs/elsewhere/citizens-1.json"),"a moved NPC is replaced where it lives");
            rejects(()->ImportProjection.project(id,original,List.of(elsewhere),ImportProjection.Mode.REJECT,null),"collision");
            NpcDefinition impostor=with(with(elsewhere,"uuid",new JsonPrimitive(UUID.randomUUID().toString())),"id",new JsonPrimitive("someone-else"));
            NpcDefinition sameUuid=with(impostor,"uuid",new JsonPrimitive(original.getFirst().uuid().toString()));
            rejects(()->ImportProjection.project(id,original,List.of(sameUuid),ImportProjection.Mode.REPLACE,null),"another path");
            NpcDefinition exhausted=with(edited,"revision",new JsonPrimitive(Long.MAX_VALUE));
            rejects(()->ImportProjection.project(id,original,List.of(exhausted),ImportProjection.Mode.REPLACE,null),"Revision exhausted");
            NpcDefinition noProvenance=with(edited,"migration",JsonNull.INSTANCE);
            rejects(()->ImportProjection.project(id,original,List.of(noProvenance),ImportProjection.Mode.REPLACE,null),"Expected object");

            var copy=ImportProjection.project(id,original,original,ImportProjection.Mode.DUPLICATE,"town");
            var repeat=ImportProjection.project(id,original,original,ImportProjection.Mode.DUPLICATE,"town");
            var another=ImportProjection.project(id,original,original,ImportProjection.Mode.DUPLICATE,"other");
            check(copy.identities().equals(repeat.identities()),"deterministic copy identities");
            check(!copy.identities().equals(another.identities()),"distinct copy labels");
            check(copy.definitions().getFirst().path().equals("npcs/copy-town/citizens-1-town.json"),"readable copied path");
            check(!copy.definitions().getFirst().uuid().equals(original.getFirst().uuid()),"new copy UUID");
            for(JsonElement element:copy.definitions().getFirst().json().getAsJsonObject("migration").getAsJsonArray("references")) {
                JsonObject reference=element.getAsJsonObject();
                check(reference.get("npcId").getAsString().equals("citizens-2-town"),"forward copied id");
                check(reference.get("npcUuid").getAsString().equals(copy.definitions().get(1).uuid().toString()),"forward copied uuid");
            }
            check(copy.definitions().getFirst().json().getAsJsonObject("modules").get("citizens:review").equals(original.getFirst().json().getAsJsonObject("modules").get("citizens:review")),"preserved review data untouched");
            check(original.getFirst().serialize().equals(firstBefore),"projection leaves source object unchanged");
            check(Files.readString(preview.directory().resolve("source.yml")).equals(source),"original archive unchanged");
            check(!Files.exists(temp.resolve("data/npcs")),"projection installs nothing");
            rejects(()->ImportProjection.project(id,original,copy.definitions(),ImportProjection.Mode.DUPLICATE,"town"),"collision");
            for(String label:new String[]{"Uppercase","../path","a".repeat(33),""})
                rejects(()->ImportProjection.project(id,original,List.of(),ImportProjection.Mode.DUPLICATE,label),"Duplicate label");
            rejects(()->ImportProjection.project(id,original,List.of(),ImportProjection.Mode.REPLACE,"town"),"only for duplicate");
            JsonObject corrupted=original.getFirst().json();
            corrupted.getAsJsonObject("migration").getAsJsonArray("references").get(0).getAsJsonObject().addProperty("npcUuid",UUID.randomUUID().toString());
            NpcDefinition bad=NpcDefinition.parse(JsonDocuments.write(corrupted));
            rejects(()->ImportProjection.project(id,List.of(bad,original.get(1)),List.of(),ImportProjection.Mode.DUPLICATE,"town"),"does not match");
            System.out.println("PASS "+checks+" isolated import projection checks; no active source/artifact changed");
        } finally {
            try(var paths=Files.walk(temp)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        }
    }
}
