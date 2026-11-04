package art.arcane.animus.bukkit.packet;

import java.util.concurrent.atomic.AtomicInteger;

/** Shares the server's allocator; multipart client bodies reserve a single consecutive block. */
final class NativeEntityIds {
    private final AtomicInteger counter;
    NativeEntityIds(AtomicInteger counter) { this.counter = counter; }
    int reserve(int count) {
        if (count < 1 || count > 16) throw new IllegalArgumentException("Invalid entity ID reservation");
        while (true) {
            int previous = counter.get();
            if (previous < 0 || previous > Integer.MAX_VALUE - count)
                throw new IllegalStateException("Native entity ID space is exhausted; restart the server");
            if (counter.compareAndSet(previous, previous + count)) return previous + 1;
        }
    }
}
