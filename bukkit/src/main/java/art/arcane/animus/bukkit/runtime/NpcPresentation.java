package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.bukkit.packet.NativePacketAdapter;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAppearance;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/**
 * What a persisted NPC's body shows: its definition equipment, pose, armor stand look, nameplate and text display.
 * Both runtimes build bodies, count native IDs and accept appearance changes only here, so Paper and Folia cannot
 * drift. The packet adapter renders the text display, its riding, the hidden-name team and the sitting seat.
 */
final class NpcPresentation {
    private NpcPresentation() {}

    /** Call where the viewer is owned: on the main thread, or on the viewer's region on Folia. */
    static NativePacketAdapter.Body body(NativePacketAdapter packets, DefinitionSpec spec, String name, NativePacketAdapter.Skin skin) throws ReflectiveOperationException {
        return packets.createBody(spec.definition().uuid(), spec.type(), name, spec.appearance(), skin);
    }
    /** Native IDs the body reserves: its type's own plus the text display and seat. */
    static int nativeIds(NativePacketAdapter packets, DefinitionSpec spec, String name, int typeIds) {
        UUID uuid = spec.definition().uuid();
        return typeIds + packets.presentationIds(uuid, spec.type(), name, spec.appearance());
    }
    /** The definition's own items, copied so runtime changes never alter the parsed definition. */
    static Map<EquipmentSlot, ItemStack> equipment(DefinitionSpec spec) {
        Map<EquipmentSlot, ItemStack> items = new EnumMap<>(EquipmentSlot.class);
        spec.equipment().forEach((slot, item) -> items.put(slot, item.clone()));
        return items;
    }
    /** A runtime name or skin change. Any body can carry a long name; only player bodies carry skins. */
    static void validateChange(DefinitionSpec spec, Optional<String> name, Optional<String> skin) {
        if (skin.isPresent() && !spec.type().equals("minecraft:player")) throw new IllegalArgumentException("Skins display only on player bodies");
        if (name.isPresent() && (name.get().isBlank() || !NpcAppearance.displayText(name.get())))
            throw new IllegalArgumentException("Names must contain 1.." + NpcAppearance.MAX_TEXT + " characters without control characters");
    }
    static String describe(DefinitionSpec spec, int equipment, boolean presentationAvailable) {
        NpcAppearance look = spec.appearance();
        return "; pose=" + look.pose().key() + "; equipment=" + equipment + "; text-lines=" + look.hologram().size()
                + spec.definition().owner().map((UUID owner) -> "; owner=" + owner).orElse("")
                + (presentationAvailable ? "" : "; presentation=unavailable on this server build");
    }
}
