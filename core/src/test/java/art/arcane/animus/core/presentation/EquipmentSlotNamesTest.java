package art.arcane.animus.core.presentation;

import art.arcane.animus.core.npc.EquipmentSlot;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EquipmentSlotNamesTest {
    @Test void everySlotMapsToItsNativeConstant() {
        Map<EquipmentSlot, String> expected = Map.of(EquipmentSlot.MAIN_HAND, "MAINHAND", EquipmentSlot.OFF_HAND, "OFFHAND", EquipmentSlot.HEAD, "HEAD",
                EquipmentSlot.CHEST, "CHEST", EquipmentSlot.LEGS, "LEGS", EquipmentSlot.FEET, "FEET", EquipmentSlot.BODY, "BODY", EquipmentSlot.SADDLE, "SADDLE");
        assertEquals(EquipmentSlot.values().length, expected.size());
        expected.forEach((slot, name) -> assertEquals(name, BodyRules.nativeSlot(slot)));
    }
}
