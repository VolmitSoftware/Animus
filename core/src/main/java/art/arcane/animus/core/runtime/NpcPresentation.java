package art.arcane.animus.core.runtime;

import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcAttributes;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.presentation.BodyTransport;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * What a persisted NPC's body shows: its definition equipment, pose, armor stand look, nameplate and text display.
 * Every runtime builds bodies, counts native IDs and accepts appearance changes only here, so platforms cannot drift.
 */
public final class NpcPresentation {
    private NpcPresentation() {}

    /** Call where the viewer is owned. {@code attributes} are the definition's with any runtime change on top. */
    public static <I> BodyTransport.Body body(BodyTransport<?, ?> packets, DefinitionSpec<I> spec, String name, BodySkin skin, NpcAttributes attributes) {
        return packets.createBody(spec.definition().uuid(), spec.type(), name, spec.appearance().withAttributes(attributes), skin);
    }
    /** Native IDs the body reserves: its type's own plus the text display and seat. */
    public static <I> int nativeIds(BodyTransport<?, ?> packets, DefinitionSpec<I> spec, String name, int typeIds) {
        UUID uuid = spec.definition().uuid();
        return typeIds + packets.presentationIds(uuid, spec.type(), name, spec.appearance());
    }
    /** The definition's own items, copied so runtime changes never alter the parsed definition. */
    public static <I> Map<EquipmentSlot, I> equipment(DefinitionSpec<I> spec, UnaryOperator<I> copy) {
        Map<EquipmentSlot, I> items = new EnumMap<>(EquipmentSlot.class);
        spec.equipment().forEach((EquipmentSlot slot, I item) -> items.put(slot, copy.apply(item)));
        return items;
    }
    /** A runtime name or skin change. Any body can carry a long name; only player bodies carry skins. */
    public static void validateChange(DefinitionSpec<?> spec, Optional<String> name, Optional<String> skin) {
        if (skin.isPresent() && !spec.type().equals(BodyRules.PLAYER)) throw new IllegalArgumentException("Skins display only on player bodies");
        if (name.isPresent() && (name.get().isBlank() || !NpcAppearance.displayText(name.get())))
            throw new IllegalArgumentException("Names must contain 1.." + NpcAppearance.MAX_TEXT + " characters without control characters");
    }
    public static String describe(DefinitionSpec<?> spec, int equipment, boolean presentationAvailable) {
        NpcAppearance look = spec.appearance();
        return "; pose=" + look.pose().key() + "; equipment=" + equipment + "; text-lines=" + look.hologram().size()
                + spec.definition().owner().map((UUID owner) -> "; owner=" + owner).orElse("")
                + (presentationAvailable ? "" : "; presentation=unavailable on this server build");
    }
}
