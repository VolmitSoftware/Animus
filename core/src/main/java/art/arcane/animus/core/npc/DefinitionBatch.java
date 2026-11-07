package art.arcane.animus.core.npc;

import art.arcane.animus.core.storage.*;
import java.io.IOException;
import java.util.*;

/** Internal validated batch writer, including last-valid pointers in the same recoverable transaction. */
public final class DefinitionBatch {
    private DefinitionBatch() {}
    public static String commit(DocumentStore store,List<NpcDefinition> definitions,Map<String,String> expected,
                                List<DocumentStore.Change> otherChanges) throws IOException {
        Set<String> paths=new HashSet<>();Set<UUID> identities=new HashSet<>();Set<String> ids=new HashSet<>();
        for(NpcDefinition definition:definitions) {
            if(!paths.add(definition.path()) || !identities.add(definition.uuid()) || !ids.add(definition.id()))throw new IOException("Duplicate batch definition identity");
            if(!store.hash(definition.path()).equals(expected.get(definition.path())))throw DocumentStore.conflict(definition.path());
        }
        DefinitionCheckpoints checkpoints=new DefinitionCheckpoints(store);
        List<DocumentStore.Change> changes=new ArrayList<>(otherChanges);
        for(NpcDefinition definition:definitions) {
            String content=definition.serialize();
            changes.add(new DocumentStore.Change(definition.path(),expected.get(definition.path()),content));
            changes.add(checkpoints.prepare(definition,JsonDocuments.hash(content)));
        }
        return store.commit(changes);
    }
}
