package art.arcane.animus.core.npc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A stored inventory. It is data for behaviors and modules to read: nothing renders it, and an item never leaves it
 * except through a definition edit. Slots are numbered from 0 and must be inside {@code size}.
 */
public record NpcInventory(int size, SortedMap<Integer, NpcItem> slots) {
    public static final int MAX_SIZE = 72;
    public static final NpcInventory EMPTY = new NpcInventory(0, new TreeMap<>());

    public NpcInventory { slots = Collections.unmodifiableSortedMap(new TreeMap<>(slots)); }

    public static NpcInventory of(NpcDefinition definition) {
        List<String> errors = new ArrayList<>();
        NpcInventory inventory = read(definition.json(), errors);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        return inventory;
    }

    static List<String> validate(JsonObject definition) {
        List<String> errors = new ArrayList<>();
        read(definition, errors);
        return errors;
    }

    private static NpcInventory read(JsonObject definition, List<String> errors) {
        if (!definition.has("inventory")) return EMPTY;
        JsonElement value = definition.get("inventory");
        if (!value.isJsonObject()) { errors.add("$.inventory: expected object"); return EMPTY; }
        JsonObject inventory = value.getAsJsonObject();
        for (String key : inventory.keySet()) if (!key.equals("size") && !key.equals("slots")) errors.add("$.inventory." + key + ": unknown field");
        int size = 0;
        JsonElement declared = inventory.get("size");
        try {
            if (declared == null || !declared.isJsonPrimitive() || !declared.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            size = declared.getAsBigDecimal().intValueExact();
            if (size < 9 || size > MAX_SIZE || size % 9 != 0) throw new ArithmeticException();
        } catch (ArithmeticException | NumberFormatException invalid) {
            errors.add("$.inventory.size: expected a multiple of 9 within 9.." + MAX_SIZE);
            size = MAX_SIZE;
        }
        TreeMap<Integer, NpcItem> slots = new TreeMap<>();
        JsonElement contents = inventory.get("slots");
        if (contents == null || !contents.isJsonObject()) { errors.add("$.inventory.slots: expected object"); return EMPTY; }
        for (Map.Entry<String, JsonElement> entry : contents.getAsJsonObject().entrySet()) {
            String path = "$.inventory.slots." + entry.getKey();
            if (!entry.getKey().matches("0|[1-9][0-9]?")) { errors.add(path + ": expected slot number"); continue; }
            int slot = Integer.parseInt(entry.getKey());
            if (slot >= size) { errors.add(path + ": slot outside the inventory size " + size); continue; }
            final int index = slot;
            NpcItem.parse(entry.getValue(), path, errors).ifPresent((NpcItem item) -> slots.put(index, item));
        }
        return new NpcInventory(size, slots);
    }
}
