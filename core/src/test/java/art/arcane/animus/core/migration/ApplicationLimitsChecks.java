package art.arcane.animus.core.migration;

import art.arcane.animus.core.storage.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class ApplicationLimitsChecks {
    static int checks;
    interface Checked{void run()throws Exception;}
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    static void rejects(Checked action,String message)throws Exception{try{action.run();throw new AssertionError("Expected "+message);}catch(IOException error){check(error.getMessage().contains(message),"Wrong error: "+error.getMessage());}}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("animus-application-limits-");String id="1".repeat(64);
        try{
            try(DocumentStore store=new DocumentStore(root.resolve("candidate-bytes"))){
                JsonObject documents=new JsonObject();
                for(int i=0;i<4;i++){
                    String content="{\"id\":"+i+",\"payload\":\""+"a".repeat(9*1024*1024)+"\"}\n";
                    JsonObject saved=new JsonObject();saved.addProperty("schemaVersion",1);saved.addProperty("document",content);
                    String object=store.putImmutableJson("import-applications",JsonDocuments.write(saved));
                    JsonObject entry=new JsonObject();entry.addProperty("expectedHash","missing");entry.addProperty("objectHash",object);
                    documents.add("skins/"+JsonDocuments.hash(content)+".json",entry);
                }
                JsonObject plan=new JsonObject();plan.addProperty("schemaVersion",1);plan.addProperty("importId",id);plan.add("documents",documents);
                String content=JsonDocuments.write(plan),application=JsonDocuments.hash(content);
                store.commit(List.of(new DocumentStore.Change("imports/"+id+"/applications/"+application+"/plan.json","missing",content)));
                rejects(()->ImportApplications.apply(store,id,application),"exceeds 64 MiB");
                check(!Files.exists(store.resolve("skins")) && !Files.exists(store.resolve("npcs")),"oversized expanded candidates install nothing");
                JsonObject tooMany=new JsonObject();
                for(int i=0;i<5000;i++){JsonObject descriptor=new JsonObject();descriptor.addProperty("expectedHash","missing");descriptor.addProperty("objectHash","0".repeat(64));tooMany.add("npcs/group/npc-"+i+".json",descriptor);}
                plan.add("documents",tooMany);content=JsonDocuments.write(plan);String manyId=JsonDocuments.hash(content);
                store.commit(List.of(new DocumentStore.Change("imports/"+id+"/applications/"+manyId+"/plan.json","missing",content)));
                rejects(()->ImportApplications.apply(store,id,manyId),"10000 transaction entries");
            }
            try(DocumentStore store=new DocumentStore(root.resolve("stage-entry-cap"))){
                Path directory=store.resolve("imports/"+id);Files.createDirectories(directory.resolve("npcs/citizens"));
                JsonObject manifest=new JsonObject(),ids=new JsonObject();
                for(int i=0;i<5000;i++){
                    JsonObject npc=new JsonObject();npc.addProperty("schemaVersion",1);npc.addProperty("revision",1);
                    String uuid=UUID.nameUUIDFromBytes(("limit-"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                    npc.addProperty("uuid",uuid);npc.addProperty("id","citizens-"+i);npc.addProperty("group","citizens");npc.addProperty("name","Synthetic");npc.addProperty("type","minecraft:player");npc.addProperty("spawned",false);
                    JsonObject position=new JsonObject();position.addProperty("world","world");for(String key:List.of("x","y","z","yaw","pitch"))position.addProperty(key,0);npc.add("position",position);
                    JsonObject migration=new JsonObject();migration.addProperty("importId",id);migration.addProperty("citizensId",i);npc.add("migration",migration);
                    String content=JsonDocuments.write(npc),path="npcs/citizens/citizens-"+i+".json";
                    Files.writeString(directory.resolve(path),content);manifest.addProperty(path,JsonDocuments.hash(content));ids.addProperty(Integer.toString(i),uuid);
                }
                JsonObject report=new JsonObject();report.addProperty("schemaVersion",1);report.addProperty("converterVersion",2);report.addProperty("importId",id);
                report.addProperty("records",5000);report.addProperty("valid",5000);report.addProperty("skins",0);report.add("idMap",ids);report.add("documents",manifest);
                Files.writeString(directory.resolve("report.json"),JsonDocuments.write(report));
                rejects(()->ImportApplications.stage(store,id,ImportProjection.Mode.REJECT,null),"10000 transaction entries");
                check(!Files.exists(store.resolve("state/objects/import-applications")),"oversized entry count rejected before candidate publication");
                check(!Files.exists(directory.resolve("applications")),"no ready plan published for oversized transaction");
            }
            try(DocumentStore store=new DocumentStore(root.resolve("stage-apply-budget"))){
                Path directory=store.resolve("imports/"+id);Files.createDirectories(directory.resolve("npcs/citizens"));
                JsonObject manifest=new JsonObject(),ids=new JsonObject();
                for(int i=1;i<=2;i++) {
                    JsonObject npc=JsonDocuments.object("{\"schemaVersion\":1,\"revision\":1,\"uuid\":\"00000000-0000-0000-0000-00000000000"+i+"\",\"id\":\"citizens-"+i+"\",\"group\":\"citizens\",\"name\":\"Synthetic\",\"type\":\"minecraft:player\",\"spawned\":false,\"position\":{\"world\":\"world\",\"x\":0,\"y\":64,\"z\":0,\"yaw\":0,\"pitch\":0},\"migration\":{\"importId\":\""+id+"\",\"citizensId\":"+i+"}}");
                    npc.addProperty("preservedExtra","a".repeat(10*1024*1024));
                    String content=JsonDocuments.write(npc),path="npcs/citizens/citizens-"+i+".json";
                    Files.writeString(directory.resolve(path),content);manifest.addProperty(path,JsonDocuments.hash(content));ids.addProperty(Integer.toString(i),npc.get("uuid").getAsString());
                }
                JsonObject report=new JsonObject();report.addProperty("schemaVersion",1);report.addProperty("converterVersion",2);report.addProperty("importId",id);
                report.addProperty("records",2);report.addProperty("valid",2);report.addProperty("skins",0);report.add("idMap",ids);report.add("documents",manifest);
                Files.writeString(directory.resolve("report.json"),JsonDocuments.write(report));
                rejects(()->ImportApplications.stage(store,id,ImportProjection.Mode.REJECT,null),"exceeds 64 MiB");
                check(!Files.exists(directory.resolve("applications")),"stage reserves unchanged apply memory before publishing a ready plan");
            }
            System.out.println("PASS "+checks+" isolated application input/entry budget checks");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}
