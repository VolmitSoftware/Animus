package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.virtual.*;
import art.arcane.animus.bukkit.packet.NativePacketAdapter;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.function.Function;

record DefinitionSpec(NpcDefinition definition, String type, String name, WorldPosition position,
                      float yaw, float pitch, String skin, String serialized) {
    static List<String> validate(NpcDefinition definition, Set<String> types, Function<String, UUID> worldResolver) {
        JsonObject json = definition.json(), position = json.getAsJsonObject("position");
        List<String> errors = new ArrayList<>();
        if (!types.contains(json.get("type").getAsString())) errors.add("$.type: packet presentation is unavailable for " + json.get("type").getAsString());
        try { NativePacketAdapter.validateProfileName(definition.name()); }
        catch (IllegalArgumentException invalid) { errors.add("$.name: " + invalid.getMessage() + "; longer nameplates are unavailable"); }
        if (worldResolver.apply(position.get("world").getAsString()) == null) errors.add("$.position.world: world is not loaded");
        if (Math.abs(position.get("pitch").getAsDouble()) > 90) errors.add("$.position.pitch: must be within -90..90");
        if (json.has("skin") && !json.get("type").getAsString().equals("minecraft:player")) errors.add("$.skin: textures require a player body");
        return List.copyOf(errors);
    }
    static DefinitionSpec parse(NpcDefinition definition, Set<String> types, Function<String, UUID> worldResolver) {
        List<String> errors = validate(definition, types, worldResolver);
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        JsonObject json = definition.json(), p = json.getAsJsonObject("position");
        UUID world = worldResolver.apply(p.get("world").getAsString());
        return new DefinitionSpec(definition, json.get("type").getAsString(), definition.name(),
                new WorldPosition(world.toString(), new Vec3(p.get("x").getAsDouble(), p.get("y").getAsDouble(), p.get("z").getAsDouble())),
                p.get("yaw").getAsFloat(), p.get("pitch").getAsFloat(), json.has("skin") ? json.get("skin").getAsString() : null, definition.serialize());
    }
    static boolean spawned(NpcDefinition definition) { return definition != null && definition.json().get("spawned").getAsBoolean(); }
}
