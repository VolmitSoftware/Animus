package art.arcane.animus.core.npc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The effective definition of an NPC that lists trait groups. Groups apply in list order, then the NPC's own fields:
 * objects merge key by key, recursively; {@code behaviors} and {@code tags} append without duplicates; other arrays and
 * scalars replace. A group's {@code skins} pool sets one {@code skin} picked by a stable hash of the NPC UUID, which the
 * NPC's own {@code skin} still overrides. {@code animus-web/lib/logic/npc/group_merge.dart} implements the same rules.
 */
public final class GroupMerge {
    /** The provenance source of the NPC's own fields. */
    public static final String NPC = "npc";
    private static final Set<String> APPENDED = Set.of("behaviors", "tags");

    /**
     * @param provenance top-level fields and {@code modules.<id>} paths mapped to the last source that set them: a group id
     *                   or {@code npc}. Identity and state fields are left out; they only ever come from the NPC.
     */
    public record Result(JsonObject effective, Map<String, String> provenance) {
        public Result {
            effective = effective.deepCopy();
            provenance = Collections.unmodifiableMap(new TreeMap<>(provenance));
        }
        @Override public JsonObject effective() { return effective.deepCopy(); }
    }

    private GroupMerge() {}

    public static Result merge(JsonObject npc, List<GroupDocument> groups) {
        String uuid = npc.has("uuid") && npc.get("uuid").isJsonPrimitive() ? npc.get("uuid").getAsString() : "";
        JsonObject merged = new JsonObject();
        Map<String, String> provenance = new TreeMap<>();
        for (GroupDocument group : groups) apply(merged, group.valuesView(), group.id(), uuid, provenance);
        apply(merged, npc, NPC, uuid, provenance);
        JsonObject ordered = new JsonObject();
        for (String key : npc.keySet()) ordered.add(key, merged.get(key));
        for (Map.Entry<String, JsonElement> entry : merged.entrySet()) if (!ordered.has(entry.getKey())) ordered.add(entry.getKey(), entry.getValue());
        return new Result(ordered, provenance);
    }

    /** The skin a pool gives an NPC: the first four bytes of SHA-256 over the UUID text, unsigned, modulo the pool size. */
    public static String pick(String uuid, List<String> skins) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(uuid.getBytes(StandardCharsets.UTF_8));
            long value = ((digest[0] & 0xFFL) << 24) | ((digest[1] & 0xFFL) << 16) | ((digest[2] & 0xFFL) << 8) | (digest[3] & 0xFFL);
            return skins.get((int) (value % skins.size()));
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 is unavailable", unavailable); }
    }

    private static void apply(JsonObject target, JsonObject source, String label, String uuid, Map<String, String> provenance) {
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (key.equals("skins") && !label.equals(NPC)) {
                List<String> pool = new ArrayList<>();
                for (JsonElement skin : value.getAsJsonArray()) pool.add(skin.getAsString());
                target.addProperty("skin", pick(uuid, pool));
                provenance.put("skin", label);
                continue;
            }
            JsonElement current = target.get(key);
            if (APPENDED.contains(key) && value.isJsonArray() && current != null && current.isJsonArray()) {
                JsonArray combined = current.getAsJsonArray();
                for (JsonElement item : value.getAsJsonArray()) if (!combined.contains(item)) combined.add(item.deepCopy());
            } else if (value.isJsonObject() && current != null && current.isJsonObject()) deep(current.getAsJsonObject(), value.getAsJsonObject());
            else target.add(key, value.deepCopy());
            if (GroupDocument.REFUSED.contains(key)) continue;
            if (key.equals("modules") && value.isJsonObject()) for (String module : value.getAsJsonObject().keySet()) provenance.put("modules." + module, label);
            else provenance.put(key, label);
        }
    }

    private static void deep(JsonObject target, JsonObject source) {
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            JsonElement current = target.get(entry.getKey());
            if (entry.getValue().isJsonObject() && current != null && current.isJsonObject()) deep(current.getAsJsonObject(), entry.getValue().getAsJsonObject());
            else target.add(entry.getKey(), entry.getValue().deepCopy());
        }
    }
}
