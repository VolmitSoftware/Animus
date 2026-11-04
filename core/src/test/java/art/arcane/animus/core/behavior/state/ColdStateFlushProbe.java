package art.arcane.animus.core.behavior.state;

import art.arcane.animus.core.behavior.BehaviorProgram.Lifetime;
import art.arcane.animus.core.behavior.BehaviorProgram.Scope;
import art.arcane.animus.core.behavior.BehaviorProgram.ScopedVariable;
import art.arcane.animus.core.behavior.BehaviorProgram.Type;
import art.arcane.animus.core.behavior.BehaviorProgram.Value;
import art.arcane.animus.core.behavior.BehaviorState;
import art.arcane.animus.core.storage.DocumentStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Loaded in a disposable classloader that closes before the returned final flush runs, as a plugin archive can. */
public final class ColdStateFlushProbe {
    private ColdStateFlushProbe() {}

    public static Runnable prepare(Path root) throws IOException {
        DocumentStore documents = new DocumentStore(root);
        BehaviorStateStore state = BehaviorStateStore.open(documents, BehaviorStateStore.Limits.defaults());
        state.write(new BehaviorState.Subject("example:quest", UUID.fromString("00000000-0000-0000-0000-00000000000a"), Optional.empty()),
                Map.of("v", new Value(Type.INTEGER, 7L)), Map.of("v", new ScopedVariable(Scope.NPC, Lifetime.PERSISTENT)));
        return new Finish(state, documents);
    }

    private record Finish(BehaviorStateStore state, DocumentStore documents) implements Runnable {
        @Override public void run() {
            try {
                state.close();
                documents.close();
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        }
    }
}
