package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.extension.CapabilityRefusal;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Process-local placeholder: values are bounded and lost on restart until the durable state lane backs this contract. */
public final class InMemoryQuestStateStore implements QuestStateStore {
    private final int maximumKeys;
    private final Map<Key, Long> values = new HashMap<>();

    public InMemoryQuestStateStore(int maximumKeys) {
        if (maximumKeys < 1) throw new IllegalArgumentException("State capacity must be positive");
        this.maximumKeys = maximumKeys;
    }

    @Override public synchronized CompletionStage<OptionalLong> read(Key key) {
        Long value = values.get(key);
        return CompletableFuture.completedFuture(value == null ? OptionalLong.empty() : OptionalLong.of(value));
    }

    @Override public synchronized CompletionStage<Long> set(Key key, long value) { return store(key, value); }

    @Override public synchronized CompletionStage<Long> advance(Key key, long delta, long maximum) {
        long current = values.getOrDefault(key, 0L);
        long next;
        try { next = Math.addExact(current, delta); }
        catch (ArithmeticException overflow) { next = delta > 0 ? Long.MAX_VALUE : Long.MIN_VALUE; }
        return store(key, Math.min(next, Math.max(current, maximum)));
    }

    private CompletionStage<Long> store(Key key, long value) {
        if (!values.containsKey(key) && values.size() >= maximumKeys)
            return CompletableFuture.failedFuture(new CapabilityRefusal(Outcome.Status.BUSY, "state-capacity", "In-memory behavior state is full"));
        values.put(key, value);
        return CompletableFuture.completedFuture(value);
    }
}
