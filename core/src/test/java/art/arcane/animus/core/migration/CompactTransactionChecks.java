package art.arcane.animus.core.migration;

import art.arcane.animus.core.storage.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class CompactTransactionChecks {
    static int checks;
    interface Checked {void run()throws Exception;}
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;}
    static void rejects(Checked operation,String message)throws Exception{try{operation.run();throw new AssertionError("Expected "+message);}catch(IOException error){check(error.getMessage().contains(message),"Wrong error: "+error.getMessage());}}
    static String large="{\"value\":\""+"a".repeat(9*1024*1024)+"\"}\n";
    static String another="{\"value\":\""+"b".repeat(1024)+"\"}\n";
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("animus-reference-journal-checks-");
        try{
            Path normal=root.resolve("normal");String transaction,inverse;
            try(DocumentStore store=new DocumentStore(normal)){
                transaction=store.commit(List.of(new DocumentStore.Change("packs/data.json","missing",large)));
                JsonObject journal=JsonDocuments.object(store.read("backups/"+transaction+".json"));
                check(journal.get("schemaVersion").getAsInt()==2,"large transaction uses reference schema");
                check(Files.size(store.resolve("backups/"+transaction+".json"))<1024,"metadata excludes inline payload");
                check(store.read("packs/data.json").equals(large),"large content exact");
            }
            try(DocumentStore store=new DocumentStore(normal)){
                inverse=store.rollback(transaction);check(store.read("packs/data.json")==null,"rollback deletes large insertion");
                store.rollback(inverse);check(store.read("packs/data.json").equals(large),"undo rollback restores exact payload");
            }
            try(DocumentStore store=new DocumentStore(root.resolve("malformed-before"))){
                DocumentStore.atomic(store.resolve("packs/data.json"),"{malformed original\n");
                String tx=store.commit(List.of(new DocumentStore.Change("packs/data.json",JsonDocuments.hash("{malformed original\n"),large)));
                store.rollback(tx);check(store.read("packs/data.json").equals("{malformed original\n"),"invalid original JSON survives rollback");
            }
            Path recovery=root.resolve("recovery");String pending;String secondHash;
            try(DocumentStore store=new DocumentStore(recovery)){
                String tx=store.commit(List.of(new DocumentStore.Change("packs/one.json","missing",large),new DocumentStore.Change("packs/two.json","missing",another)));
                pending=store.read("backups/"+tx+".json");
                secondHash=JsonDocuments.object(pending).getAsJsonArray("entries").get(1).getAsJsonObject().get("afterObject").getAsString();
                Files.delete(store.resolve("packs/one.json"));Files.delete(store.resolve("packs/two.json"));
                DocumentStore.atomic(store.resolve("state/pending.json"),pending);
                Files.writeString(store.resolve("state/objects/transaction-data/"+secondHash+".json"),"tampered");
            }
            rejects(()->{try(DocumentStore ignored=new DocumentStore(recovery)){}},"object missing or changed");
            check(!Files.exists(recovery.resolve("packs/one.json")) && !Files.exists(recovery.resolve("packs/two.json")),"corrupt later object prevents all writes");
            check(Files.exists(recovery.resolve("state/pending.json")),"failed recovery retains journal");
            DocumentStore.atomic(recovery.resolve("state/objects/transaction-data/"+secondHash+".json"),another);
            try(DocumentStore store=new DocumentStore(recovery)){
                check(store.read("packs/one.json").equals(large) && store.read("packs/two.json").equals(another),"repaired objects allow complete rollforward");
                check(store.read("state/pending.json")==null,"recovery clears journal after backup");
                Files.delete(store.resolve("packs/two.json"));DocumentStore.atomic(store.resolve("state/pending.json"),pending);
            }
            try(DocumentStore store=new DocumentStore(recovery)){check(store.read("packs/one.json").equals(large) && store.read("packs/two.json").equals(another),"partially applied transaction completes without rewriting accepted state");}
            Path invalid=root.resolve("invalid");Files.createDirectories(invalid.resolve("state"));
            JsonObject malformed=JsonDocuments.object(pending);
            for(JsonElement version:List.of(new JsonPrimitive(2.1),new JsonPrimitive("2"),JsonParser.parseString("[2]"))){
                malformed.add("schemaVersion",version);DocumentStore.atomic(invalid.resolve("state/pending.json"),JsonDocuments.write(malformed));
                rejects(()->{try(DocumentStore ignored=new DocumentStore(invalid)){}},"Unsupported transaction journal schema");
            }
            malformed=JsonDocuments.object(pending);malformed.getAsJsonArray("entries").get(0).getAsJsonObject().addProperty("afterObject","../escape");
            DocumentStore.atomic(invalid.resolve("state/pending.json"),JsonDocuments.write(malformed));
            rejects(()->{try(DocumentStore ignored=new DocumentStore(invalid)){}},"Invalid transaction object reference");
            Path budget=root.resolve("budget");String hash=JsonDocuments.hash(large);
            DocumentStore.atomic(budget.resolve("state/objects/transaction-data/"+hash+".json"),large);
            JsonObject many=new JsonObject();many.addProperty("schemaVersion",2);many.addProperty("id",UUID.randomUUID().toString());JsonArray entries=new JsonArray();
            for(int i=0;i<30;i++){JsonObject e=new JsonObject();e.addProperty("path","packs/data-"+i+".json");e.add("beforeObject",JsonNull.INSTANCE);e.addProperty("afterObject",hash);entries.add(e);}
            many.add("entries",entries);DocumentStore.atomic(budget.resolve("state/pending.json"),JsonDocuments.write(many));
            rejects(()->{try(DocumentStore ignored=new DocumentStore(budget)){}},"payload exceeds 256 MiB");
            check(!Files.exists(budget.resolve("packs")),"oversized logical recovery changes no document");
            Path rollbackBudget=root.resolve("rollback-budget");
            try(DocumentStore store=new DocumentStore(rollbackBudget)) {
                DocumentStore.atomic(store.resolve("state/objects/transaction-data/"+hash+".json"),large);
                JsonObject attack=new JsonObject();String id=UUID.randomUUID().toString();attack.addProperty("schemaVersion",2);attack.addProperty("id",id);
                JsonArray repeated=new JsonArray();
                for(int i=0;i<1000;i++){JsonObject e=new JsonObject();e.addProperty("path","packs/restore-"+i+".json");e.addProperty("beforeObject",hash);e.add("afterObject",JsonNull.INSTANCE);repeated.add(e);}
                attack.add("entries",repeated);DocumentStore.atomic(store.resolve("backups/"+id+".json"),JsonDocuments.write(attack));
                rejects(()->store.rollback(id),"payload exceeds 256 MiB");
                check(!Files.exists(store.resolve("packs")) && store.read("state/pending.json")==null,"oversized rollback rejects before retaining unbounded payloads or writes");
                JsonObject missing=new JsonObject();missing.addProperty("schemaVersion",2);missing.addProperty("id",id);missing.add("entries",new JsonArray());
                DocumentStore.atomic(store.resolve("backups/"+id+".json"),JsonDocuments.write(missing));
                rejects(()->store.rollback(id),"Invalid transaction journal");
            }
            System.out.println("PASS "+checks+" isolated compact-transaction checks");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}
