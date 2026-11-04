package art.arcane.animus.core.npc;

import java.util.Locale;
import java.util.Optional;

/** Visible equipment slots. Definition JSON and behavior arguments use the lowercase key. */
public enum EquipmentSlot {
    MAIN_HAND, OFF_HAND, HEAD, CHEST, LEGS, FEET, BODY, SADDLE;

    public String key() { return name().toLowerCase(Locale.ROOT); }

    public static Optional<EquipmentSlot> fromKey(String key) {
        for (EquipmentSlot slot : values()) if (slot.key().equals(key)) return Optional.of(slot);
        return Optional.empty();
    }
}
