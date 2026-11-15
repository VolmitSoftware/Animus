package art.arcane.animus.core.owner;

import art.arcane.animus.core.editor.EditorHost;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * {@link OwnerPermissions} from a platform's two checks: an online player's nodes, asked live, and an offline player's,
 * which may load them. Every answer is remembered, so a running behavior on an offline owner's NPC asks without
 * waiting; an owner never asked yet is looked up on {@code lookups} and refused until the answer is in.
 */
public final class SeenOwnerPermissions implements OwnerPermissions {
    private final Function<UUID, Optional<Predicate<String>>> online;
    private final BiFunction<UUID, String, EditorHost.Admin> offline;
    private final Executor lookups;
    private final Map<String, Answer> seen = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    /** {@code online}: the nodes of a player who is online now, safe to ask from any thread; {@code offline} may block. */
    public SeenOwnerPermissions(Function<UUID, Optional<Predicate<String>>> online, BiFunction<UUID, String, EditorHost.Admin> offline, Executor lookups) {
        this.online = Objects.requireNonNull(online); this.offline = Objects.requireNonNull(offline); this.lookups = Objects.requireNonNull(lookups);
    }

    @Override public Answer has(UUID player, String node) {
        Optional<Answer> live = live(player, node);
        if (live.isPresent()) return live.get();
        Answer answer = switch (offline.apply(player, node)) {
            case ADMIN -> Answer.YES;
            case NOT_ADMIN -> Answer.NO;
            case UNKNOWN -> Answer.UNKNOWN;
        };
        seen.put(key(player, node), answer);
        return answer;
    }

    @Override public Answer cached(UUID player, String node) {
        Optional<Answer> live = live(player, node);
        if (live.isPresent()) return live.get();
        String key = key(player, node);
        Answer known = seen.get(key);
        if (known != null) return known;
        if (pending.add(key)) {
            try { lookups.execute(() -> { try { has(player, node); } finally { pending.remove(key); } }); }
            catch (RejectedExecutionException full) { pending.remove(key); }
        }
        return Answer.UNKNOWN;
    }

    private Optional<Answer> live(UUID player, String node) {
        Optional<Answer> live = online.apply(player).map((Predicate<String> has) -> has.test(node) ? Answer.YES : Answer.NO);
        live.ifPresent((Answer answer) -> seen.put(key(player, node), answer));
        return live;
    }

    private static String key(UUID player, String node) { return player + " " + node; }
}
