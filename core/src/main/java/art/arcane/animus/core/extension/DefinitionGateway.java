package art.arcane.animus.core.extension;

import art.arcane.animus.api.DefinitionService;
import java.util.function.BooleanSupplier;

/** Engine-private contract: the gate must be checked inside the shared mutation lane before work begins. */
public interface DefinitionGateway extends DefinitionService {
    DefinitionService scoped(BooleanSupplier available);
}
