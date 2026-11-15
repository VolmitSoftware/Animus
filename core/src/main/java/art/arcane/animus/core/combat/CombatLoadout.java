package art.arcane.animus.core.combat;

import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcInventory;
import art.arcane.animus.core.npc.NpcItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What a combat NPC's definition says about it outside the combat module: its owner, body type, the items it wears and
 * holds with their enchantments, and what its stored inventory carries. Read once per definition publication.
 *
 * @param equipment    item id by slot key such as {@code main_hand} or {@code head}
 * @param inventory    total count by item id
 * @param worn         every worn and held item as it drops with {@code dropEquipment}
 * @param body         the definition's body type, which enchantments such as Smite read
 * @param enchantments enchantment levels by slot key, only for slots whose item has any
 * @param carried      the held main-hand item first, when there is one, then the stored inventory in slot order; what
 *                     weapon switching chooses from
 */
public record CombatLoadout(Optional<UUID> owner, Map<String, String> equipment, Map<String, Integer> inventory, List<CombatWorld.Drop> worn, String body,
                            Map<String, Map<String, Integer>> enchantments, List<NpcItem> carried) {
    public static final String EMPTY = "minecraft:air";
    public static final String PLAYER = "minecraft:player";
    public static final CombatLoadout NONE = new CombatLoadout(Optional.empty(), Map.of(), Map.of(), List.of());

    public CombatLoadout {
        Objects.requireNonNull(body, "body");
        equipment = Map.copyOf(equipment);
        inventory = Map.copyOf(inventory);
        worn = List.copyOf(worn);
        Map<String, Map<String, Integer>> levels = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : enchantments.entrySet()) if (!entry.getValue().isEmpty()) levels.put(entry.getKey(), Map.copyOf(entry.getValue()));
        enchantments = Map.copyOf(levels);
        carried = List.copyOf(carried);
    }
    public CombatLoadout(Optional<UUID> owner, Map<String, String> equipment, Map<String, Integer> inventory) { this(owner, equipment, inventory, List.of()); }
    /** Plain items with no components: the held main-hand item is the only one carried. */
    public CombatLoadout(Optional<UUID> owner, Map<String, String> equipment, Map<String, Integer> inventory, List<CombatWorld.Drop> worn) {
        this(owner, equipment, inventory, worn, PLAYER, Map.of(),
                equipment.containsKey("main_hand") ? List.of(new NpcItem(equipment.get("main_hand"), 1, Optional.empty(), Optional.empty())) : List.of());
    }

    public static CombatLoadout of(NpcDefinition definition) {
        Map<String, String> equipment = new HashMap<>();
        Map<String, Map<String, Integer>> enchantments = new HashMap<>();
        List<CombatWorld.Drop> worn = new ArrayList<>();
        List<NpcItem> carried = new ArrayList<>();
        for (Map.Entry<EquipmentSlot, NpcItem> entry : NpcAppearance.of(definition).equipment().entrySet()) {
            NpcItem item = entry.getValue();
            equipment.put(entry.getKey().key(), item.id());
            enchantments.put(entry.getKey().key(), EnchantmentLevels.of(item.components()));
            worn.add(new CombatWorld.Drop(item.id(), item.count(), item.components()));
            if (entry.getKey() == EquipmentSlot.MAIN_HAND) carried.addFirst(item);
        }
        Map<String, Integer> inventory = new HashMap<>();
        for (NpcItem item : NpcInventory.of(definition).slots().values()) {
            inventory.merge(item.id(), item.count(), Integer::sum);
            carried.add(item);
        }
        com.google.gson.JsonElement type = definition.json().get("type");
        String body = type != null && type.isJsonPrimitive() ? type.getAsString() : PLAYER;
        return new CombatLoadout(definition.owner(), equipment, inventory, worn, body, enchantments, carried);
    }

    /** The item in this slot, or {@link #EMPTY}. */
    public String item(String slot) { return equipment.getOrDefault(slot, EMPTY); }
    /** The item in this slot as a weapon, with its enchantments. */
    public VanillaMelee.Weapon weapon(String slot) { return new VanillaMelee.Weapon(item(slot), enchantments.getOrDefault(slot, Map.of())); }

    /** How many of these items the stored inventory holds together. */
    public int count(Set<String> items) {
        int total = 0;
        for (String item : items) total += inventory.getOrDefault(item, 0);
        return total;
    }
}
