package art.arcane.animus.bukkit.packet;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NativeEntityIdsTest {
    @Test void multipartReservationsNeverOverlapNativeAllocationsUnderContention() throws Exception {
        AtomicInteger nativeCounter = new AtomicInteger();
        NativeEntityIds ids = new NativeEntityIds(nativeCounter);
        Set<Integer> used = ConcurrentHashMap.newKeySet();
        try (ExecutorService workers = Executors.newFixedThreadPool(8)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                final boolean vanilla = worker % 2 == 0;
                tasks.add(workers.submit(() -> {
                    for (int i = 0; i < 1000; i++) {
                        if (vanilla) assertTrue(used.add(nativeCounter.incrementAndGet()));
                        else {
                            int first = ids.reserve(9);
                            for (int child = 0; child < 9; child++) assertTrue(used.add(first + child));
                        }
                    }
                }));
            }
            for (Future<?> task : tasks) task.get();
        }
        assertEquals(40_000, used.size());
        assertEquals(40_000, nativeCounter.get());
        assertFalse(used.contains(0));
    }
    @Test void exhaustionIsExplicitAndDoesNotWrapOrConsumeIds() {
        AtomicInteger nativeCounter = new AtomicInteger(Integer.MAX_VALUE - 8);
        NativeEntityIds ids = new NativeEntityIds(nativeCounter);
        assertThrows(IllegalStateException.class, () -> ids.reserve(9));
        assertEquals(Integer.MAX_VALUE - 8, nativeCounter.get());
        assertThrows(IllegalArgumentException.class, () -> ids.reserve(0));
        assertThrows(IllegalArgumentException.class, () -> ids.reserve(17));
        assertEquals(Integer.MAX_VALUE - 7, ids.reserve(1));
    }
}
