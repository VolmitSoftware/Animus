package art.arcane.animus.core.building;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Items handed to a build and not yet used, by item id. Saved with the build. */
public final class MaterialLedger {
    public static final int MAXIMUM_ITEMS = 4096;
    public static final long MAXIMUM_COUNT = 1L << 40;
    private static final Pattern ITEM = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private final TreeMap<String, Long> counts = new TreeMap<>();

    public long available(String item) { return counts.getOrDefault(item, 0L); }
    public Map<String, Long> counts() { return Collections.unmodifiableMap(counts); }
    public boolean isEmpty() { return counts.isEmpty(); }

    /** Adds items; refuses a new item past the item limit. */
    public void add(String item, long amount) {
        if (!ITEM.matcher(item).matches()) throw new IllegalArgumentException("Invalid item id: " + item);
        if (amount <= 0) throw new IllegalArgumentException("Donated amount must be positive");
        if (!counts.containsKey(item) && counts.size() >= MAXIMUM_ITEMS) throw new IllegalStateException("The build already holds " + MAXIMUM_ITEMS + " kinds of item");
        counts.merge(item, amount, (Long a, Long b) -> Math.min(MAXIMUM_COUNT, a + b));
    }

    /** Takes items only when all of them are there. */
    public boolean take(String item, long amount) {
        if (amount <= 0) return true;
        long have = available(item);
        if (have < amount) return false;
        if (have == amount) counts.remove(item); else counts.put(item, have - amount);
        return true;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        counts.forEach(json::addProperty);
        return json;
    }

    public static MaterialLedger fromJson(JsonObject json, String path) {
        MaterialLedger ledger = new MaterialLedger();
        if (json.size() > MAXIMUM_ITEMS) throw new IllegalArgumentException(path + ": more than " + MAXIMUM_ITEMS + " items");
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(path + "." + entry.getKey() + ": expected a count");
            long count;
            try { count = value.getAsBigDecimal().longValueExact(); }
            catch (ArithmeticException invalid) { throw new IllegalArgumentException(path + "." + entry.getKey() + ": expected a whole count"); }
            if (count <= 0 || count > MAXIMUM_COUNT) throw new IllegalArgumentException(path + "." + entry.getKey() + ": count out of range");
            ledger.add(entry.getKey(), count);
        }
        return ledger;
    }
}
