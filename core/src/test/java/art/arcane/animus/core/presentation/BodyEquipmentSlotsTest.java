package art.arcane.animus.core.presentation;

import art.arcane.animus.core.npc.EquipmentSlot;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Each presented body shows only the slots the client draws on it; nothing else reaches a viewer. */
class BodyEquipmentSlotsTest {
    @Test void everyPresentedBodyNamesItsSlots() {
        assertEquals(BodyRules.TYPES, BodyRules.limitedEquipment().keySet(), "a new body type needs its slots");
        Set<EquipmentSlot> worn = EnumSet.of(EquipmentSlot.MAIN_HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
        assertEquals(worn, BodyRules.equipmentSlots("minecraft:player"));
        assertEquals(worn, BodyRules.equipmentSlots("minecraft:armor_stand"));
        assertEquals(EnumSet.of(EquipmentSlot.BODY, EquipmentSlot.SADDLE), BodyRules.equipmentSlots("minecraft:horse"));
        assertEquals(EnumSet.of(EquipmentSlot.BODY), BodyRules.equipmentSlots("minecraft:llama"));
        for (String bare : new String[] {"minecraft:turtle", "minecraft:frog", "minecraft:armadillo", "minecraft:ender_dragon"})
            assertTrue(BodyRules.equipmentSlots(bare).isEmpty(), bare);
        assertEquals(EnumSet.allOf(EquipmentSlot.class), BodyRules.equipmentSlots("minecraft:zombie"), "a type no transport presents keeps every slot");
    }

    @Test void onlyTheShownSlotsAreSent() {
        Map<EquipmentSlot, String> items = Map.of(EquipmentSlot.HEAD, "helmet", EquipmentSlot.SADDLE, "saddle", EquipmentSlot.BODY, "armor");
        assertEquals(Map.of(EquipmentSlot.SADDLE, "saddle", EquipmentSlot.BODY, "armor"), BodyRules.shownEquipment("minecraft:horse", items));
        assertEquals(Map.of(EquipmentSlot.HEAD, "helmet"), BodyRules.shownEquipment("minecraft:player", items));
        assertTrue(BodyRules.shownEquipment("minecraft:turtle", items).isEmpty());
        assertSame(items, BodyRules.shownEquipment("minecraft:zombie", items));
    }

    @Test void theRefusalNamesTheBodyAndTheSlot() {
        assertEquals("minecraft:horse bodies cannot show head; they show body and saddle", BodyRules.slotRefusal("minecraft:horse", EquipmentSlot.HEAD));
        assertEquals("minecraft:player bodies cannot show saddle; they show main_hand, off_hand, head, chest, legs and feet",
                BodyRules.slotRefusal("minecraft:player", EquipmentSlot.SADDLE));
        assertEquals("minecraft:frog bodies show no equipment", BodyRules.slotRefusal("minecraft:frog", EquipmentSlot.BODY));
    }
}
