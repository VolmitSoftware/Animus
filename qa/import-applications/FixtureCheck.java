package art.arcane.animus.core.migration;

import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Local QA fixture validation against an explicitly selected isolated draft. */
public final class FixtureCheck {
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]),fixtures=Path.of(args[1]),receipt=output.resolve("receipt.json");
        String phase=args[2];
        try(DocumentStore store=new DocumentStore(output.resolve("data"))) {
            if(phase.equals("prepare")) {
                var importer=new CitizensImporter();
                var baseline=importer.preview(fixtures.resolve("baseline.json"),store,Map.of("qa_legacy","world"));
                var updated=importer.preview(fixtures.resolve("updated.json"),store,Map.of("qa_legacy","world"));
                if(baseline.records()!=3||baseline.valid()!=3||updated.records()!=4||updated.valid()!=4)throw new AssertionError("Fixture conversion invalid");
                var base=ImportApplications.stage(store,baseline.id(),ImportProjection.Mode.REJECT,null);
                String baseTx=ImportApplications.apply(store,baseline.id(),base.applicationId());
                var copy=ImportApplications.stage(store,baseline.id(),ImportProjection.Mode.DUPLICATE,"qa");
                String copyTx=ImportApplications.apply(store,baseline.id(),copy.applicationId());
                var replacement=ImportApplications.stage(store,updated.id(),ImportProjection.Mode.REPLACE,null);
                String replacementTx=ImportApplications.apply(store,updated.id(),replacement.applicationId());
                new NpcRepository(store).loadForStartup();
                JsonObject result=new JsonObject();
                result.addProperty("baselineImport",baseline.id());result.addProperty("baseApplication",base.applicationId());result.addProperty("baseTransaction",baseTx);
                result.addProperty("copyApplication",copy.applicationId());result.addProperty("copyTransaction",copyTx);
                result.addProperty("updatedImport",updated.id());result.addProperty("replacementApplication",replacement.applicationId());result.addProperty("replacementTransaction",replacementTx);
                Files.writeString(receipt,JsonDocuments.write(result));
            } else {
                JsonObject result=JsonDocuments.object(Files.readString(receipt));
                new NpcRepository(store).loadForStartup();
                if(phase.equals("rollback-replacement"))store.rollback(result.get("replacementTransaction").getAsString());
                else if(phase.equals("rollback-rest")) {
                    store.rollback(result.get("copyTransaction").getAsString());
                    store.rollback(result.get("baseTransaction").getAsString());
                    new NpcRepository(store).loadForStartup();
                } else throw new IllegalArgumentException("Unknown fixture check phase");
            }
        }
        System.out.println("PASS public fixture phase "+phase);
    }
}
