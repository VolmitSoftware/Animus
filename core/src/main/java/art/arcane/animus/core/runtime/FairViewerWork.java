package art.arcane.animus.core.runtime;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;

/**
 * Splits one tick's packet budget across the viewers whose turn it is. Each viewer may spend an even share of what is
 * left, so a share one viewer leaves unused passes to the viewers after it. Viewers that had a turn move to the back;
 * when every viewer fits in one tick the starting viewer still advances by one, so no viewer always goes last.
 */
public final class FairViewerWork {
    @FunctionalInterface public interface Worker<T> { int update(T viewer, int remaining); }
    private FairViewerWork() {}
    public static <T> int run(Deque<T> queue, int maximumUpdates, int budget, Predicate<T> active, Worker<T> worker) {
        if (budget <= 0) return 0;
        int size = queue.size(), limit = Math.min(maximumUpdates, size);
        List<T> turn = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            T viewer = queue.removeFirst();
            if (active.test(viewer)) turn.add(viewer);
        }
        int used = 0, reached = 0;
        for (; reached < turn.size() && used < budget; reached++) {
            T viewer = turn.get(reached);
            int share = Math.ceilDiv(budget - used, turn.size() - reached);
            int consumed = worker.update(viewer, share);
            if (consumed < 0 || consumed > share) throw new IllegalStateException("Viewer exceeded packet work budget");
            used += consumed;
        }
        // Viewers the budget did not reach keep their place at the front for the next tick.
        for (int i = turn.size() - 1; i >= reached; i--) queue.addFirst(turn.get(i));
        for (int i = 0; i < reached; i++) if (active.test(turn.get(i))) queue.addLast(turn.get(i));
        if (limit == size && reached == turn.size() && queue.size() > 1) queue.addLast(queue.removeFirst());
        return used;
    }
}
