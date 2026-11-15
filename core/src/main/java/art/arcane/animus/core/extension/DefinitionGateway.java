package art.arcane.animus.core.extension;

import art.arcane.animus.api.DefinitionService;
import art.arcane.animus.core.npc.NpcDefinition;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Engine-private contract: the gate must be checked inside the shared mutation lane before work begins. */
public interface DefinitionGateway extends DefinitionService {
    DefinitionService scoped(BooleanSupplier available);
    /** Connect once the NPC runtime runs: why a replacement that goes live could not, checked where the runtime checks it. */
    void activation(Function<NpcDefinition, CompletionStage<List<String>>> check);
}
