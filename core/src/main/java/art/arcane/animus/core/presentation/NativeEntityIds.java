package art.arcane.animus.core.presentation;

import java.util.concurrent.atomic.AtomicInteger;

/** Shares the server's allocator; multipart client bodies reserve a single consecutive block. */
public final class NativeEntityIds {
    private final AtomicInteger counter;
    public NativeEntityIds(AtomicInteger counter) { this.counter = counter; }
    public int reserve(int count) {
        if (count < 1 || count > 16) throw new IllegalArgumentException("Invalid entity ID reservation");
        while (true) {
            int previous = counter.get();
            if (previous < 0 || previous > Integer.MAX_VALUE - count)
                throw new IllegalStateException("Native entity ID space is exhausted; restart the server");
            if (counter.compareAndSet(previous, previous + count)) return previous + 1;
        }
    }
}
