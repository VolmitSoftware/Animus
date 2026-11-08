package art.arcane.animus.core.runtime;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Deletes the definition of a combat NPC that died with {@code afterDeath} {@code delete} in {@code animus:health}. It is an ordinary repository
 * deletion: one committed transaction with a receipt, so {@code /animus rollback <transaction>} restores it.
 */
public final class CombatDeletions {
    public record Deleted(String id, String transaction) {}

    private CombatDeletions() {}

    /** Empty when no definition has this UUID any more. Runs on the file lane. */
    public static Optional<Deleted> delete(NpcRepository repository, UUID npc) throws IOException {
        for (NpcDefinition definition : repository.snapshot().values()) {
            if (definition.uuid().equals(npc)) return Optional.of(new Deleted(definition.id(), repository.delete(definition.id())));
        }
        return Optional.empty();
    }
}
