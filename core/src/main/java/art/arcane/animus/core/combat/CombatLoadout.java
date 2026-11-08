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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What a combat NPC's definition says about it outside the combat module: its owner, the items it wears and holds, and
 * how many of each item its stored inventory has. Read once per definition publication.
 *
 * @param equipment item id by slot key such as {@code main_hand} or {@code head}
 * @param inventory total count by item id
 * @param worn      every worn and held item as it drops with {@code dropEquipment}
 */
public record CombatLoadout(Optional<UUID> owner, Map<String, String> equipment, Map<String, Integer> inventory, List<CombatWorld.Drop> worn) {
    public static final String EMPTY = "minecraft:air";
    public static final CombatLoadout NONE = new CombatLoadout(Optional.empty(), Map.of(), Map.of(), List.of());

    public CombatLoadout {
        equipment = Map.copyOf(equipment);
        inventory = Map.copyOf(inventory);
        worn = List.copyOf(worn);
    }
    public CombatLoadout(Optional<UUID> owner, Map<String, String> equipment, Map<String, Integer> inventory) { this(owner, equipment, inventory, List.of()); }

    public static CombatLoadout of(NpcDefinition definition) {
        Map<String, String> equipment = new HashMap<>();
        List<CombatWorld.Drop> worn = new ArrayList<>();
        for (Map.Entry<EquipmentSlot, NpcItem> entry : NpcAppearance.of(definition).equipment().entrySet()) {
            equipment.put(entry.getKey().key(), entry.getValue().id());
            worn.add(new CombatWorld.Drop(entry.getValue().id(), entry.getValue().count(), entry.getValue().components()));
        }
        Map<String, Integer> inventory = new HashMap<>();
        for (NpcItem item : NpcInventory.of(definition).slots().values()) inventory.merge(item.id(), item.count(), Integer::sum);
        return new CombatLoadout(definition.owner(), equipment, inventory, worn);
    }

    /** The item in this slot, or {@link #EMPTY}. */
    public String item(String slot) { return equipment.getOrDefault(slot, EMPTY); }

    /** How many of these items the stored inventory holds together. */
    public int count(Set<String> items) {
        int total = 0;
        for (String item : items) total += inventory.getOrDefault(item, 0);
        return total;
    }
}
