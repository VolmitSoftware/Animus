package art.arcane.animus.core.runtime;

import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcAttributes;
import art.arcane.animus.core.presentation.BodyLook;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.presentation.BodySkin;
import art.arcane.animus.core.presentation.BodyTransport;
import art.arcane.animus.core.text.Placeholders;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
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

    /** The text a body shows one viewer: its name and hologram lines with that viewer's placeholders filled. */
    public record Label(UUID uuid, String type, String name, NpcAppearance appearance) {
        /** The coloured text of each text row, as the body's text displays show it. */
        public List<String> rows() {
            List<String> rows = new ArrayList<>();
            for (BodyLook.TextRow row : BodyLook.of(uuid, type, name, appearance).rows()) rows.add(row.text());
            return List.copyOf(rows);
        }
    }

    /** Call on the viewer's owner. {@code attributes} are the definition's with any runtime change on top. */
    public static <I> Label label(DefinitionSpec<I> spec, String name, NpcAttributes attributes, Placeholders placeholders, UUID viewer) {
        return label(spec, name, null, attributes, placeholders, viewer);
    }

    /** As {@link #label(DefinitionSpec, String, NpcAttributes, Placeholders, UUID)}, with a speech bubble line on top when {@code bubble} is set. */
    public static <I> Label label(DefinitionSpec<I> spec, String name, String bubble, NpcAttributes attributes, Placeholders placeholders, UUID viewer) {
        NpcAppearance appearance = spec.appearance().withAttributes(attributes);
        List<String> lines = placeholders.fill(viewer, appearance.hologram());
        if (lines != appearance.hologram()) appearance = appearance.withHologram(lines);
        if (bubble != null) appearance = appearance.withBubble(bubble);
        return new Label(spec.definition().uuid(), spec.type(), placeholders.fill(viewer, shownName(spec, name)), appearance);
    }

    /** Whether the name or a hologram line holds a placeholder, so the body's text may change while it is shown. */
    public static boolean placeholdered(DefinitionSpec<?> spec, String name) {
        return Placeholders.marked(name) || spec.appearance().hologram().stream().anyMatch(Placeholders::marked);
    }

    /** Call where the viewer is owned: a body carrying this viewer's label. */
    public static BodyTransport.Body body(BodyTransport<?, ?> packets, Label label, BodySkin skin) {
        return packets.createBody(label.uuid(), label.type(), label.name(), label.appearance(), skin);
    }
    /** Native IDs the body reserves: its type's own plus the text display and seat. */
    public static <I> int nativeIds(BodyTransport<?, ?> packets, DefinitionSpec<I> spec, String name, int typeIds) {
        UUID uuid = spec.definition().uuid();
        return typeIds + packets.presentationIds(uuid, spec.type(), shownName(spec, name), spec.appearance());
    }
    /**
     * A player body's name with a placeholder always shows on a text row, never as the profile name: a filled value
     * could otherwise switch between the two and change how many IDs the body reserves.
     */
    static String shownName(DefinitionSpec<?> spec, String name) {
        return spec.type().equals(BodyRules.PLAYER) && Placeholders.marked(name) ? "&r" + name : name;
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
