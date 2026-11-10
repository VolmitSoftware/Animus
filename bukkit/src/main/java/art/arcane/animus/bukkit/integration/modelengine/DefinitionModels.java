package art.arcane.animus.bukkit.integration.modelengine;

import art.arcane.animus.core.integration.ModelEngineModule;
import art.arcane.animus.core.npc.NpcDefinition;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The model each NPC shows: a runtime override from another integration first, then its definition's
 * {@code animus:modelengine} module. The definition index is rebuilt only when the definition snapshot changes.
 */
final class DefinitionModels implements Function<UUID, Optional<ModeledTransport.Shape>> {
    private record Index(Map<String, NpcDefinition> source, Map<UUID, ModelEngineModule> models, Map<UUID, String> names) {}

    private final Supplier<Map<String, NpcDefinition>> definitions;
    private final Map<UUID, ModelEngineModule> overrides = new ConcurrentHashMap<>();
    private volatile Index index = new Index(null, Map.of(), Map.of());

    DefinitionModels(Supplier<Map<String, NpcDefinition>> definitions) { this.definitions = Objects.requireNonNull(definitions); }

    @Override public Optional<ModeledTransport.Shape> apply(UUID npc) {
        Index current = current();
        ModelEngineModule model = overrides.getOrDefault(npc, current.models().get(npc));
        return model == null ? Optional.empty() : Optional.of(new ModeledTransport.Shape(model, current.names().getOrDefault(npc, "")));
    }

    void override(UUID npc, ModelEngineModule model) { overrides.put(Objects.requireNonNull(npc), Objects.requireNonNull(model)); }
    void clear(UUID npc) { overrides.remove(npc); }

    private Index current() {
        Map<String, NpcDefinition> source = definitions.get();
        Index known = index;
        if (known.source() == source) return known;
        Map<UUID, ModelEngineModule> models = new HashMap<>();
        Map<UUID, String> names = new HashMap<>();
        for (NpcDefinition definition : source.values()) {
            names.put(definition.uuid(), definition.name());
            // A malformed module is refused at spawn with its field named, so it never reaches a viewer here.
            try { ModelEngineModule.parse(definition.json()).ifPresent((ModelEngineModule model) -> models.put(definition.uuid(), model)); }
            catch (IllegalArgumentException malformed) { continue; }
        }
        Index next = new Index(source, Map.copyOf(models), Map.copyOf(names));
        index = next;
        return next;
    }
}
