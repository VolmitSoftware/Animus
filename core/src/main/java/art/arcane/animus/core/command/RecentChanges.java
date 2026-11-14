package art.arcane.animus.core.command;

import art.arcane.animus.core.text.ChatLine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The changes each player reported through their own commands and Animus menu clicks, newest first, for
 * {@code /animus undo}. The menu keeps the one the commands use; see {@link ReplyActor}. Kept in memory and bounded: the
 * {@link #PLAYERS} players who changed something most recently, {@link #KEPT} changes each, none older than {@link #AGE}.
 * A player's list survives a rejoin. A restart forgets them all, and {@code /animus rollback} still undoes any change by its id.
 */
public final class RecentChanges {
    /** How many changes a player can step back through. */
    static final int KEPT = 20;
    /** How many players' lists are kept; the player whose last change is oldest is forgotten first. */
    static final int PLAYERS = 200;
    /** How long a change stays on its player's list. */
    static final Duration AGE = Duration.ofHours(24);

    /**
     * One change: its transaction id, what the reply said it did, such as {@code Deleted guard}, and the permission node
     * the command that made it needed. The node is empty for a change made in the Animus menu, whose undo needs the
     * node for what the change wrote; see {@link UndoPermission}.
     */
    public record Change(String transaction, String what, String node) {}

    private record Kept(Change change, Instant at) {}

    private final Clock clock;
    private final Map<UUID, Deque<Kept>> changes = new LinkedHashMap<>();

    public RecentChanges() { this(Clock.systemUTC()); }

    RecentChanges(Clock clock) { this.clock = clock; }

    synchronized void record(UUID player, String transaction, String what, String node) {
        Instant now = clock.instant();
        Deque<Kept> own = changes.remove(player);
        if (own == null) own = new ArrayDeque<>();
        own.removeIf((Kept kept) -> kept.change().transaction().equals(transaction));
        own.addFirst(new Kept(new Change(transaction, what, node), now));
        while (own.size() > KEPT) own.removeLast();
        changes.put(player, own);
        // Insertion order is the order of each player's newest change, so the first lists are the stalest.
        changes.values().removeIf((Deque<Kept> list) -> fresh(list, now).isEmpty());
        while (changes.size() > PLAYERS) changes.remove(changes.keySet().iterator().next());
    }

    public synchronized Optional<Change> latest(UUID player) { return fresh(player).stream().findFirst(); }

    /** The player's own change whose id starts with {@code prefix}, as an {@code [Undo]} chip names it. */
    synchronized Optional<Change> find(UUID player, String prefix) {
        if (prefix.isBlank()) return Optional.empty();
        return fresh(player).stream().filter((Change change) -> change.transaction().startsWith(prefix)).findFirst();
    }

    /** The player's changes by the first characters an {@code [Undo]} carries, newest first, for completion. */
    synchronized List<String> ids(UUID player) { return fresh(player).stream().map((Change change) -> ChatLine.shortId(change.transaction())).toList(); }

    synchronized void forget(UUID player, String transaction) {
        Deque<Kept> own = changes.get(player);
        if (own == null) return;
        own.removeIf((Kept kept) -> kept.change().transaction().equals(transaction));
        if (own.isEmpty()) changes.remove(player);
    }

    private List<Change> fresh(UUID player) {
        Deque<Kept> own = changes.get(player);
        return own == null ? List.of() : fresh(own, clock.instant());
    }

    private static List<Change> fresh(Deque<Kept> own, Instant now) {
        Instant oldest = now.minus(AGE);
        return own.stream().filter((Kept kept) -> kept.at().isAfter(oldest)).map(Kept::change).toList();
    }
}
