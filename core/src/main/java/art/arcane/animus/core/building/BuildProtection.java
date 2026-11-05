package art.arcane.animus.core.building;

import art.arcane.animus.core.navigation.BlockPos;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Decides whether a build may change one block. An adapter that throws or answers null denies the change; a platform
 * that cannot reach its protection plugin must deny. {@link #NONE} is for a server with no protection plugin.
 */
@FunctionalInterface
public interface BuildProtection {
    enum Decision { ALLOW, DENY }
    /** {@code PLACE} puts a block where air or another block was; {@code BREAK} leaves air; {@code USE} opens or closes a door, fence gate or trapdoor. */
    enum Action { PLACE, BREAK, USE }

    record Request(UUID npc, Optional<UUID> startedBy, String world, BlockPos at, Action action) {
        public Request {
            Objects.requireNonNull(npc); Objects.requireNonNull(startedBy); Objects.requireNonNull(world);
            Objects.requireNonNull(at); Objects.requireNonNull(action);
        }
    }

    BuildProtection NONE = request -> Decision.ALLOW;

    Decision decide(Request request);

    /** Fail-closed front of an adapter with denial and failure counts. */
    final class Gate {
        private final BuildProtection adapter;
        private final Consumer<String> diagnostics;
        private long denials, failures;

        public Gate(BuildProtection adapter, Consumer<String> diagnostics) {
            this.adapter = Objects.requireNonNull(adapter);
            this.diagnostics = Objects.requireNonNull(diagnostics);
        }

        public boolean allows(Request request) {
            Decision decision;
            try { decision = adapter.decide(request); }
            catch (RuntimeException | LinkageError failure) {
                if (failures++ < 8) diagnostics.accept("Build protection failed closed: " + failure);
                denials++;
                return false;
            }
            if (decision == Decision.ALLOW) return true;
            if (decision == null && failures++ < 8) diagnostics.accept("Build protection returned no decision; failed closed");
            denials++;
            return false;
        }
        public long denials() { return denials; }
        public long failures() { return failures; }
    }
}
