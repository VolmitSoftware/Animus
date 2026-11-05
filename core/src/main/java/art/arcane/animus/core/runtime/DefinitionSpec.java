package art.arcane.animus.core.runtime;

import art.arcane.animus.core.mount.MountDeclaration;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAppearance;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * One definition checked against the running server: its body type, world, items and appearance.
 *
 * @param <I> the platform's item stack
 */
public record DefinitionSpec<I>(NpcDefinition definition, String type, String name, WorldPosition position,
                                float yaw, float pitch, String skin, String serialized, NpcAppearance appearance, Map<EquipmentSlot, I> equipment) {
    /** Turns one definition item into a server stack, or adds a field error and returns null. */
    @FunctionalInterface public interface Items<I> { I item(NpcItem item, String path, List<String> errors); }

    public DefinitionSpec { equipment = Map.copyOf(equipment); }

    /** @param worlds the loaded world key a definition's world reference names, or null */
    public static <I> List<String> validate(NpcDefinition definition, Set<String> types, Function<String, String> worlds, Items<I> items) {
        JsonObject json = definition.json(), position = json.getAsJsonObject("position");
        List<String> errors = new ArrayList<>();
        if (!types.contains(json.get("type").getAsString())) errors.add("$.type: packet presentation is unavailable for " + json.get("type").getAsString());
        if (worlds.apply(position.get("world").getAsString()) == null) errors.add("$.position.world: world is not loaded");
        if (Math.abs(position.get("pitch").getAsDouble()) > 90) errors.add("$.position.pitch: must be within -90..90");
        errors.addAll(activation(definition));
        NpcAppearance appearance = NpcAppearance.of(definition);
        if (!appearance.equipment().isEmpty()) equipment(appearance, items, errors);
        return List.copyOf(errors);
    }
    /** The activation rules that hold on every server, so an import preview refuses what the runtime would. */
    public static List<String> activation(NpcDefinition definition) {
        JsonObject json = definition.json();
        List<String> errors = new ArrayList<>();
        if (json.has("skin") && !json.get("type").getAsString().equals(BodyRules.PLAYER)) errors.add("$.skin: textures require a player body");
        if (NpcAppearance.of(definition).pose() == NpcPose.SITTING && MountDeclaration.parse(json).isPresent())
            errors.add("$.pose: a sitting NPC cannot also ride another NPC; remove the pose or the mount");
        return List.copyOf(errors);
    }
    public static <I> DefinitionSpec<I> parse(NpcDefinition definition, Set<String> types, Function<String, String> worlds, Items<I> items) {
        List<String> errors = validate(definition, types, worlds, items);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        JsonObject json = definition.json(), p = json.getAsJsonObject("position");
        String world = worlds.apply(p.get("world").getAsString());
        NpcAppearance appearance = NpcAppearance.of(definition);
        Map<EquipmentSlot, I> equipment = appearance.equipment().isEmpty() ? Map.of() : equipment(appearance, items, new ArrayList<>());
        return new DefinitionSpec<>(definition, json.get("type").getAsString(), definition.name(),
                new WorldPosition(world, new Vec3(p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble())),
                p.get("yaw").getAsFloat(), p.get("pitch").getAsFloat(), json.has("skin") ? json.get("skin").getAsString() : null, definition.serialize(),
                appearance, equipment);
    }
    /** An imported definition stays despawned while Citizens itself runs; see {@link art.arcane.animus.core.migration.CitizensGuard}. */
    public static boolean spawned(NpcDefinition definition) { return definition != null && definition.spawned() && !art.arcane.animus.core.migration.CitizensGuard.holds(definition.imported()); }

    private static <I> Map<EquipmentSlot, I> equipment(NpcAppearance appearance, Items<I> items, List<String> errors) {
        Map<EquipmentSlot, I> stacks = new EnumMap<>(EquipmentSlot.class);
        for (Map.Entry<EquipmentSlot, NpcItem> entry : appearance.equipment().entrySet()) {
            I stack = items.item(entry.getValue(), "$.equipment." + entry.getKey().key(), errors);
            if (stack != null) stacks.put(entry.getKey(), stack);
        }
        return stacks;
    }
}
