package art.arcane.animus.bukkit.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Bounded work for one NPC's owner. A paused mailbox refuses new work until its NPC wakes; a closed one forever. */
final class OwnerMailbox {
    /** Runs exactly once on the owner, or is refused exactly once. */
    interface Work {
        void run();
        void refuse(String reason);
    }
    private final int capacity;
    private final ArrayDeque<Work> queue = new ArrayDeque<>();
    private boolean paused, closed;

    OwnerMailbox(int capacity) {
        if (capacity < 1 || capacity > 1024) throw new IllegalArgumentException("Mailbox capacity must be 1..1024");
        this.capacity = capacity;
    }

    synchronized boolean offer(Work work) {
        if (closed || paused || queue.size() >= capacity) return false;
        queue.addLast(work);
        return true;
    }
    /** Owner only. Runs up to {@code maximum} items outside the lock and returns how many ran. */
    int drain(int maximum) {
        int ran = 0;
        while (ran < maximum) {
            Work work;
            synchronized (this) { work = queue.pollFirst(); }
            if (work == null) break;
            ran++;
            try { work.run(); }
            catch (RuntimeException failure) { work.refuse(PersistedNpcService.concise(failure)); }
        }
        return ran;
    }
    void pause(String reason) { refuse(drop(false), reason); }
    synchronized void resume() { if (!closed) paused = false; }
    void close(String reason) { refuse(drop(true), reason); }
    synchronized int size() { return queue.size(); }

    private synchronized List<Work> drop(boolean close) {
        paused = true;
        closed |= close;
        List<Work> dropped = new ArrayList<>(queue);
        queue.clear();
        return dropped;
    }
    private static void refuse(List<Work> dropped, String reason) {
        for (Work work : dropped) {
            try { work.refuse(reason); }
            catch (RuntimeException ignored) { /* One failing callback cannot strand the rest. */ }
        }
    }
}
