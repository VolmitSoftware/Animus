package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.*;
import art.arcane.animus.core.storage.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class ApplicationChecks {
    static int checks;
    interface Checked { void run() throws Exception; }
    static void check(boolean result,String name){if(!result)throw new AssertionError(name);checks++;}
    static void rejects(Checked action,String message)throws Exception{try{action.run();throw new AssertionError("Expected "+message);}catch(IOException error){check(error.getMessage().contains(message),"Wrong error "+error.getMessage());}}
    static final String YAML="""
            npc:
              '1':
                uuid: 00000000-0000-0000-0000-000000000001
                name: Guard
                traits:
                  type: PLAYER
                  location: {world: world, x: 0, y: 64, z: 0}
                  sentinel: {guardedNPC: 2, health: 40}
                  skintrait: {textureRaw: synthetic, signature: synthetic}
              '2':
                uuid: 00000000-0000-0000-0000-000000000002
                name: Partner
                traits:
                  type: PLAYER
                  location: {world: world, x: 2, y: 64, z: 0}
                  skintrait: {textureRaw: synthetic, signature: synthetic}
            """;
    static CitizensImporter.Preview preview(DocumentStore store,Path source,String name)throws Exception{
        Files.writeString(source,YAML.replace("name: Guard","name: "+name));return new CitizensImporter().preview(source,store,Map.of());
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("animus-application-checks-");
        try{
            try(DocumentStore store=new DocumentStore(root.resolve("roundtrip"))){
                var converted=preview(store,root.resolve("source.yml"),"Guard");
                var staged=ImportApplications.stage(store,converted.id(),ImportProjection.Mode.REJECT,null);
                check(store.read("npcs/world/citizens-1.json")==null,"staging installs nothing");
                check(staged.applicationId().equals(ImportApplications.stage(store,converted.id(),ImportProjection.Mode.REJECT,null).applicationId()),"same stage idempotent");
                String installed=ImportApplications.apply(store,converted.id(),staged.applicationId());
                check(ImportApplications.apply(store,converted.id(),staged.applicationId()).equals("already-applied"),"repeat apply idempotent");
                NpcRepository repository=new NpcRepository(store);repository.loadForStartup();
                String original=store.read("npcs/world/citizens-1.json");
                String originalPointer=store.read("state/npc-definitions/last-valid/npcs/world/citizens-1.json");
                check(originalPointer!=null,"initial accepted checkpoint");
                var updated=preview(store,root.resolve("source.yml"),"Updated");
                rejects(()->ImportApplications.stage(store,updated.id(),ImportProjection.Mode.REJECT,null),"collision");
                var replacement=ImportApplications.stage(store,updated.id(),ImportProjection.Mode.REPLACE,null);
                check(replacement.replacements()==2,"explicit replacement count");
                check(store.read("npcs/world/citizens-1.json").equals(original),"replacement stage leaves active data");
                String replaced=ImportApplications.apply(store,updated.id(),replacement.applicationId());
                NpcDefinition accepted=NpcDefinition.parse(store.read("npcs/world/citizens-1.json"));
                check(accepted.revision()==2 && accepted.name().equals("Updated"),"replacement applied with increasing revision");
                check(!store.read("state/npc-definitions/last-valid/npcs/world/citizens-1.json").equals(originalPointer),"replacement updates accepted checkpoint atomically");
                NpcRepository restarted=new NpcRepository(store);restarted.loadForStartup();
                check(restarted.all().size()==2 && restarted.diagnostics().isEmpty(),"replacement restart before rollback");
                store.rollback(replaced);
                check(store.read("npcs/world/citizens-1.json").equals(original),"rollback restores exact prior definition");
                check(store.read("state/npc-definitions/last-valid/npcs/world/citizens-1.json").equals(originalPointer),"rollback restores checkpoint");
                check(store.read("imports/"+updated.id()+"/applications/"+replacement.applicationId()+"/receipt.json")==null,"rollback removes application receipt");
                ImportApplications.apply(store,updated.id(),replacement.applicationId());
                var duplicate=ImportApplications.stage(store,converted.id(),ImportProjection.Mode.DUPLICATE,"town");
                String copied=ImportApplications.apply(store,converted.id(),duplicate.applicationId());
                NpcDefinition first=NpcDefinition.parse(store.read("npcs/copy-town/citizens-1-town.json"));
                NpcDefinition second=NpcDefinition.parse(store.read("npcs/copy-town/citizens-2-town.json"));
                check(!first.uuid().equals(accepted.uuid()),"duplicate receives independent identity");
                check(first.json().getAsJsonObject("migration").getAsJsonArray("references").get(0).getAsJsonObject().get("npcUuid").getAsString().equals(second.uuid().toString()),"duplicate references copied target");
                check(!first.json().get("spawned").getAsBoolean(),"copied definition stays dormant");
                store.rollback(copied);
                check(store.read(first.path())==null && store.read(second.path())==null,"rollback removes copied definitions");
                check(store.read(accepted.path())!=null,"copy rollback preserves originals");
                check(Files.readString(converted.directory().resolve("source.yml")).equals(YAML),"original source archive remains exact");
                check(store.read("state/pending.json")==null,"all transactions completed");
            }
            try(DocumentStore store=new DocumentStore(root.resolve("conflicts"))){
                var converted=preview(store,root.resolve("source.yml"),"Guard");
                new CitizensImporter().apply(store,converted.id());
                var replacement=ImportApplications.stage(store,converted.id(),ImportProjection.Mode.REPLACE,null);
                String path="npcs/world/citizens-1.json", original=store.read(path);
                JsonObject edited=JsonDocuments.object(original);edited.addProperty("name","Concurrent edit");
                store.commit(List.of(new DocumentStore.Change(path,JsonDocuments.hash(original),JsonDocuments.write(edited))));
                rejects(()->ImportApplications.apply(store,converted.id(),replacement.applicationId()),"Conflict");
                check(NpcDefinition.parse(store.read(path)).name().equals("Concurrent edit"),"conflicting edit preserved");
                String plan=Files.readString(replacement.report());Files.writeString(replacement.report(),plan+" ");
                rejects(()->ImportApplications.apply(store,converted.id(),replacement.applicationId()),"preview missing or changed");
                Files.writeString(replacement.report(),plan);
                var copy=ImportApplications.stage(store,converted.id(),ImportProjection.Mode.DUPLICATE,"copy");
                JsonObject manifest=JsonDocuments.object(Files.readString(copy.report())).getAsJsonObject("documents");
                String object=manifest.entrySet().iterator().next().getValue().getAsJsonObject().get("objectHash").getAsString();
                Files.writeString(store.resolve("state/objects/import-applications/"+object+".json"),"{}");
                rejects(()->ImportApplications.apply(store,converted.id(),copy.applicationId()),"stored copy state/objects/");
                check(store.read("npcs/copy-copy/citizens-1-copy.json")==null,"tampered object installs nothing");
            }
            try(DocumentStore store=new DocumentStore(root.resolve("new-collision"))){
                var converted=preview(store,root.resolve("source.yml"),"Guard");
                var staged=ImportApplications.stage(store,converted.id(),ImportProjection.Mode.REJECT,null);
                JsonObject collision=JsonDocuments.object(Files.readString(converted.directory().resolve("npcs/world/citizens-1.json")));
                collision.addProperty("id","other");collision.addProperty("group","custom");
                store.commit(List.of(new DocumentStore.Change("npcs/custom/other.json","missing",JsonDocuments.write(collision))));
                rejects(()->ImportApplications.apply(store,converted.id(),staged.applicationId()),"another path");
                check(store.read("npcs/world/citizens-2.json")==null,"whole batch rejected on new identity collision");
            }
            try(DocumentStore store=new DocumentStore(root.resolve("orphan-pointer"))){
                var converted=preview(store,root.resolve("source.yml"),"Guard");
                new CitizensImporter().apply(store,converted.id());new NpcRepository(store).loadForStartup();
                String sourcePath="npcs/world/citizens-1.json", pointerPath="state/npc-definitions/last-valid/"+sourcePath;
                String original=store.read(sourcePath), pointer=store.read(pointerPath);
                Files.delete(store.resolve(sourcePath));
                NpcDefinition definition=NpcDefinition.parse(original);
                String tx=DefinitionBatch.commit(store,List.of(definition),Map.of(sourcePath,"missing"),List.of());
                check(store.read(pointerPath).equals(pointer),"batch retains explicit unchanged orphan checkpoint");
                new NpcRepository(store).loadForStartup();store.rollback(tx);
                check(store.read(sourcePath)==null,"orphan reinsertion rollback works after normal startup");
                new NpcRepository(store).loadForStartup();
                check(store.read(pointerPath)==null,"next startup acknowledges deletion without resurrecting source");
            }
            try(DocumentStore store=new DocumentStore(root.resolve("preserved-nonobject-skin"))){
                Path source=Files.writeString(root.resolve("source.yml"),YAML.replace("{textureRaw: synthetic, signature: synthetic}","opaque-unrecognized-skin"));
                var converted=new CitizensImporter().preview(source,store,Map.of());
                var staged=ImportApplications.stage(store,converted.id(),ImportProjection.Mode.REJECT,null);
                ImportApplications.apply(store,converted.id(),staged.applicationId());
                String skin=NpcDefinition.parse(store.read("npcs/world/citizens-1.json")).json().get("skin").getAsString();
                check(store.read("skins/"+skin+".json").contains("opaque-unrecognized-skin"),"unknown skin data remains preserved");
            }
            try(DocumentStore store=DocumentStore.readOnly(root.resolve("roundtrip"))){
                String id;try(var paths=Files.list(store.resolve("imports"))){id=paths.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}")).findFirst().orElseThrow().getFileName().toString();}
                String importId=id;
                rejects(()->ImportApplications.stage(store,importId,ImportProjection.Mode.DUPLICATE,"readonly"),"read-only");
            }
            System.out.println("PASS "+checks+" isolated import application checks; active source/artifact unchanged");
        } finally {try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}
