package art.arcane.animus.core.shop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Per-player limits, global limits and stock for shop entries. A counter belongs to {@code <shop>#<entry>} and,
 * for player limits, a player. A counter with a window ends at its window and reads as zero afterwards. Every change is
 * an absolute value handed to the sink, so replaying a journal in order rebuilds the same state. Thread safe.
 */
public final class ShopLedger {
    public enum Kind { PLAYER, GLOBAL, STOCK }
    /** What an entry declares. */
    public record Rules(ShopDocument.Limit limit, Optional<ShopDocument.Stock> stock) {
        public static Rules of(ShopDocument.Entry entry) { return new Rules(entry.limit(), entry.stock()); }
        public boolean any() { return limit.any() || stock.isPresent(); }
    }
    /** One counter's new value. {@code windowEnd} is epoch milliseconds, or 0 when the counter never resets. */
    public record Change(Kind kind, String key, Optional<UUID> player, long used, long windowEnd) {
        public Change { Objects.requireNonNull(kind); Objects.requireNonNull(key); Objects.requireNonNull(player); }

        public String write() {
            JsonObject json = new JsonObject();
            json.addProperty("kind", kind.name().toLowerCase(Locale.ROOT));
            json.addProperty("key", key);
            player.ifPresent((UUID id) -> json.addProperty("player", id.toString()));
            json.addProperty("used", used);
            json.addProperty("windowEnd", windowEnd);
            return json.toString();
        }

        public static Change parse(String line) { return parse(JsonParser.parseString(line).getAsJsonObject()); }

        static Change parse(JsonObject json) {
            Kind kind = Kind.valueOf(json.get("kind").getAsString().toUpperCase(Locale.ROOT));
            Optional<UUID> player = json.has("player") ? Optional.of(UUID.fromString(json.get("player").getAsString())) : Optional.empty();
            if ((kind == Kind.PLAYER) != player.isPresent()) throw new IllegalArgumentException("player counters and only player counters name a player");
            long used = json.get("used").getAsLong();
            if (used < 0) throw new IllegalArgumentException("used must not be negative");
            return new Change(kind, json.get("key").getAsString(), player, used, json.get("windowEnd").getAsLong());
        }

        public static String writeAll(List<Change> changes) {
            JsonArray array = new JsonArray();
            for (Change change : changes) array.add(JsonParser.parseString(change.write()));
            return array.toString();
        }

        public static List<Change> parseAll(String json) {
            List<Change> changes = new ArrayList<>();
            for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) changes.add(parse(element.getAsJsonObject()));
            return changes;
        }
    }
    private record Counter(long used, long windowEnd) {
        long current(long now) { return windowEnd != 0 && now >= windowEnd ? 0 : used; }
    }
    private record PlayerKey(String key, UUID player) {}
    /** Units a purchase in progress holds against an entry's limits and stock; {@link #complete} or {@link #release} ends it. */
    public record Reservation(String shop, String entry, Rules rules, UUID player, long units) {}

    private final Consumer<Change> sink;
    private final Map<PlayerKey, Counter> players = new HashMap<>();
    private final Map<String, Counter> globals = new HashMap<>();
    private final Map<String, Counter> stocks = new HashMap<>();
    private final Map<PlayerKey, Long> heldPlayers = new HashMap<>();
    private final Map<String, Long> heldGlobals = new HashMap<>(), heldStocks = new HashMap<>();

    public ShopLedger(Consumer<Change> sink) { this.sink = Objects.requireNonNull(sink); }

    public static String key(String shop, String entry) { return shop + "#" + entry; }

    /** Units the player may take now: the smallest of what each declared limit and the stock allow, less what purchases in progress hold. */
    public synchronized long available(String shop, String entry, Rules rules, UUID player, Instant now) {
        String key = key(shop, entry);
        long millis = now.toEpochMilli();
        long available = Long.MAX_VALUE;
        if (rules.limit().perPlayer() >= 0) {
            PlayerKey counter = new PlayerKey(key, player);
            available = Math.min(available, rules.limit().perPlayer() - read(players.get(counter), millis) - heldPlayers.getOrDefault(counter, 0L));
        }
        if (rules.limit().global() >= 0) available = Math.min(available, rules.limit().global() - read(globals.get(key), millis) - heldGlobals.getOrDefault(key, 0L));
        if (rules.stock().isPresent()) available = Math.min(available, stockLeft(key, rules, millis));
        return Math.max(0, available);
    }

    /** Whether the stock, rather than a limit, is what leaves fewer than {@code units}. */
    public synchronized boolean stockShort(String shop, String entry, Rules rules, long units, Instant now) {
        return rules.stock().isPresent() && stockLeft(key(shop, entry), rules, now.toEpochMilli()) < units;
    }

    private long stockLeft(String key, Rules rules, long millis) {
        return rules.stock().orElseThrow().maximum() - read(stocks.get(key), millis) - heldStocks.getOrDefault(key, 0L);
    }

    /**
     * Holds {@code units} against every declared limit and the stock in one step, so purchases on other threads see them
     * as taken; empty when they are not available.
     */
    public synchronized Optional<Reservation> reserve(String shop, String entry, Rules rules, UUID player, long units, Instant now) {
        if (units <= 0) throw new IllegalArgumentException("units must be positive: " + units);
        if (available(shop, entry, rules, player, now) < units) return Optional.empty();
        Reservation reservation = new Reservation(shop, entry, rules, player, units);
        hold(reservation, units);
        return Optional.of(reservation);
    }

    /** Gives back what a purchase that did not complete held. */
    public synchronized void release(Reservation reservation) { hold(reservation, -reservation.units()); }

    /** Ends the hold and counts the {@code units} the purchase took. */
    public synchronized void complete(Reservation reservation, long units, Instant now) {
        release(reservation);
        if (units > 0) record(reservation.shop(), reservation.entry(), reservation.rules(), reservation.player(), units, now);
    }

    private void hold(Reservation reservation, long delta) {
        String key = key(reservation.shop(), reservation.entry());
        java.util.function.BiFunction<Long, Long, Long> add = (Long held, Long change) -> held + change == 0 ? null : held + change;
        if (reservation.rules().limit().perPlayer() >= 0) heldPlayers.merge(new PlayerKey(key, reservation.player()), delta, add);
        if (reservation.rules().limit().global() >= 0) heldGlobals.merge(key, delta, add);
        if (reservation.rules().stock().isPresent()) heldStocks.merge(key, delta, add);
    }

    public synchronized long usedBy(String shop, String entry, UUID player, Instant now) { return read(players.get(new PlayerKey(key(shop, entry), player)), now.toEpochMilli()); }
    public synchronized long usedGlobally(String shop, String entry, Instant now) { return read(globals.get(key(shop, entry)), now.toEpochMilli()); }
    public synchronized long stockUsed(String shop, String entry, Instant now) { return read(stocks.get(key(shop, entry)), now.toEpochMilli()); }

    /** Counts a completed purchase of {@code units}. */
    public synchronized void record(String shop, String entry, Rules rules, UUID player, long units, Instant now) {
        if (units <= 0) throw new IllegalArgumentException("units must be positive: " + units);
        String key = key(shop, entry);
        long millis = now.toEpochMilli();
        long reset = rules.limit().resetSeconds();
        if (rules.limit().perPlayer() >= 0) {
            PlayerKey counter = new PlayerKey(key, player);
            Counter next = advance(players.get(counter), units, millis, reset);
            players.put(counter, next);
            sink.accept(new Change(Kind.PLAYER, key, Optional.of(player), next.used(), next.windowEnd()));
        }
        if (rules.limit().global() >= 0) {
            Counter next = advance(globals.get(key), units, millis, reset);
            globals.put(key, next);
            sink.accept(new Change(Kind.GLOBAL, key, Optional.empty(), next.used(), next.windowEnd()));
        }
        if (rules.stock().isPresent()) {
            Counter next = advance(stocks.get(key), units, millis, rules.stock().get().restockSeconds());
            stocks.put(key, next);
            sink.accept(new Change(Kind.STOCK, key, Optional.empty(), next.used(), next.windowEnd()));
        }
    }

    /** Clears every counter of a shop, or only one player's counters. Journaled as zero values. */
    public synchronized void reset(String shop, Optional<UUID> player) {
        String prefix = shop + "#";
        for (PlayerKey key : List.copyOf(players.keySet())) {
            if (!key.key().startsWith(prefix) || (player.isPresent() && !player.get().equals(key.player()))) continue;
            players.remove(key);
            sink.accept(new Change(Kind.PLAYER, key.key(), Optional.of(key.player()), 0, 0));
        }
        if (player.isPresent()) return;
        for (String key : List.copyOf(globals.keySet())) if (key.startsWith(prefix)) { globals.remove(key); sink.accept(new Change(Kind.GLOBAL, key, Optional.empty(), 0, 0)); }
        for (String key : List.copyOf(stocks.keySet())) if (key.startsWith(prefix)) { stocks.remove(key); sink.accept(new Change(Kind.STOCK, key, Optional.empty(), 0, 0)); }
    }

    /** Replays a journaled change without journaling it again. */
    public synchronized void apply(Change change) {
        Counter counter = new Counter(change.used(), change.windowEnd());
        boolean clear = change.used() == 0;
        switch (change.kind()) {
            case PLAYER -> {
                PlayerKey key = new PlayerKey(change.key(), change.player().orElseThrow());
                if (clear) players.remove(key); else players.put(key, counter);
            }
            case GLOBAL -> { if (clear) globals.remove(change.key()); else globals.put(change.key(), counter); }
            case STOCK -> { if (clear) stocks.remove(change.key()); else stocks.put(change.key(), counter); }
        }
    }

    /** Adds imported counts to a player's counter, keeping any existing window. */
    public synchronized void importPlayer(String shop, String entry, UUID player, long used) {
        if (used <= 0) return;
        PlayerKey key = new PlayerKey(key(shop, entry), player);
        Counter existing = players.get(key);
        Counter next = new Counter((existing == null ? 0 : existing.used()) + used, existing == null ? 0 : existing.windowEnd());
        players.put(key, next);
        sink.accept(new Change(Kind.PLAYER, key.key(), Optional.of(player), next.used(), next.windowEnd()));
    }

    /** Every live counter, sorted, as absolute changes. Expired counters are dropped. */
    public synchronized List<Change> snapshot() {
        long now = System.currentTimeMillis();
        List<Change> changes = new ArrayList<>();
        players.forEach((PlayerKey key, Counter counter) -> { if (counter.current(now) > 0 || counter.windowEnd() == 0) changes.add(new Change(Kind.PLAYER, key.key(), Optional.of(key.player()), counter.used(), counter.windowEnd())); });
        globals.forEach((String key, Counter counter) -> { if (counter.current(now) > 0 || counter.windowEnd() == 0) changes.add(new Change(Kind.GLOBAL, key, Optional.empty(), counter.used(), counter.windowEnd())); });
        stocks.forEach((String key, Counter counter) -> { if (counter.current(now) > 0 || counter.windowEnd() == 0) changes.add(new Change(Kind.STOCK, key, Optional.empty(), counter.used(), counter.windowEnd())); });
        changes.sort(java.util.Comparator.comparing((Change change) -> change.kind().ordinal() + "|" + change.key() + "|" + change.player().map(UUID::toString).orElse("")));
        return List.copyOf(changes);
    }

    private static long read(Counter counter, long now) { return counter == null ? 0 : counter.current(now); }

    private static Counter advance(Counter counter, long units, long now, long windowSeconds) {
        if (counter == null || counter.current(now) == 0 && counter.windowEnd() != 0) {
            long end = windowSeconds > 0 ? now + windowSeconds * 1000 : 0;
            return new Counter(units, end);
        }
        return new Counter(counter.used() + units, counter.windowEnd());
    }
}
