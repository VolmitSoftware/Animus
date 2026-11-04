package art.arcane.animus.api;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Engine-owned bounded asynchronous I/O. Completion threads grant no platform ownership. */
public interface DefinitionService {
    CompletionStage<Outcome<DefinitionSnapshot>> find(NpcHandle npc);
    CompletionStage<Outcome<List<Diagnostic>>> validate(DefinitionEdit edit);
    CompletionStage<Outcome<DefinitionSnapshot>> apply(DefinitionEdit edit);
}
