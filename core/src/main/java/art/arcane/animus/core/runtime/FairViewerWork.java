package art.arcane.animus.core.runtime;

import java.util.Deque;
import java.util.function.Predicate;

/** Stop at the work budget before rotating untouched viewers, preserving the next starting point. */
public final class FairViewerWork {
    @FunctionalInterface public interface Worker<T> { int update(T viewer, int remaining); }
    private FairViewerWork() {}
    public static <T> int run(Deque<T> queue, int maximumUpdates, int budget, Predicate<T> active, Worker<T> worker) {
        int visited = 0, used = 0, limit = Math.min(maximumUpdates, queue.size());
        while (visited < limit && used < budget && !queue.isEmpty()) {
            T viewer = queue.removeFirst(); visited++;
            if (!active.test(viewer)) continue;
            int consumed = worker.update(viewer, budget - used);
            if (consumed < 0 || consumed > budget - used) throw new IllegalStateException("Viewer exceeded packet work budget");
            used += consumed;
            if (active.test(viewer)) queue.addLast(viewer);
        }
        return used;
    }
}
