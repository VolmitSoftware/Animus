package art.arcane.animus.bukkit.folia;

import java.util.IdentityHashMap;
import java.util.Objects;

/** Bounded pre-admission authority, independent of player/world APIs and scheduler execution. */
final class PendingRequests<T> implements AutoCloseable {
    static final class Ticket<T> {
        private final T owner;
        private final long generation;
        private final Object epoch;
        private Ticket(T owner, long generation, Object epoch) { this.owner=owner; this.generation=generation; this.epoch=epoch; }
        long generation() { return generation; }
    }
    private final int maximum;
    private final IdentityHashMap<T,Ticket<T>> pending=new IdentityHashMap<>();
    private boolean closed;
    private long generation;
    PendingRequests(int maximum) {
        if(maximum<1 || maximum>64)throw new IllegalArgumentException("Request capacity must be 1..64");
        this.maximum=maximum;
    }
    synchronized Ticket<T> begin(T owner) { return begin(owner,null); }
    synchronized Ticket<T> begin(T owner,Object epoch) {
        Objects.requireNonNull(owner);
        if(closed || pending.size()>=maximum || pending.containsKey(owner) || generation==Long.MAX_VALUE)return null;
        Ticket<T> ticket=new Ticket<>(owner,++generation,epoch);pending.put(owner,ticket);return ticket;
    }
    synchronized boolean current(Ticket<T> ticket) {
        return !closed && ticket!=null && pending.get(ticket.owner)==ticket;
    }
    synchronized boolean current(Ticket<T> ticket,Object epoch) {
        return current(ticket) && Objects.equals(ticket.epoch,epoch);
    }
    synchronized void finish(Ticket<T> ticket) {
        if(ticket!=null && pending.get(ticket.owner)==ticket)pending.remove(ticket.owner);
    }
    /** Exact owner identity: an old connection's lifecycle event cannot revoke its replacement. */
    synchronized void invalidate(T owner) { pending.remove(owner); }
    synchronized int size() { return pending.size(); }
    @Override public synchronized void close() { closed=true;pending.clear(); }
}
