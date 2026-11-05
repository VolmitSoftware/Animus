package art.arcane.animus.core.building;

import art.arcane.animus.core.navigation.BlockPos;
import art.arcane.animus.core.npc.NpcDefinition;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code animus:builder} section of an NPC definition: a saved build request that {@code /animus build start <npc>}
 * runs. An imported section also needs literal {@code "reviewAccepted": true}. Nothing here starts on its own.
 */
public record BuilderModule(String schematic, Optional<BlockPos> origin, BuildOptions options, boolean reviewAccepted) {
    public static final String MODULE = "animus:builder";
    private static final String ROOT = "$.modules." + MODULE;
    private static final Set<String> FIELDS = Set.of("schematic", "origin", "options", "reviewAccepted", "imported");

    /** Empty when the definition has no builder section; invalid sections throw with the field path. */
    public static Optional<BuilderModule> of(NpcDefinition definition) {
        JsonObject document = definition.json();
        JsonElement modules = document.get("modules");
        if (modules == null || !modules.isJsonObject() || !modules.getAsJsonObject().has(MODULE)) return Optional.empty();
        JsonElement value = modules.getAsJsonObject().get(MODULE);
        if (!value.isJsonObject()) throw new IllegalArgumentException(ROOT + ": expected object");
        JsonObject module = value.getAsJsonObject();
        for (String key : module.keySet()) if (!FIELDS.contains(key)) throw new IllegalArgumentException(ROOT + "." + key + ": unknown field");
        JsonElement schematic = module.get("schematic");
        if (schematic == null || !schematic.isJsonPrimitive() || !schematic.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(ROOT + ".schematic: expected a schematic file name");
        Optional<BlockPos> origin = Optional.empty();
        if (module.has("origin")) {
            if (!module.get("origin").isJsonObject()) throw new IllegalArgumentException(ROOT + ".origin: expected {x, y, z}");
            origin = Optional.of(BuildOptions.point(module.getAsJsonObject("origin"), ROOT + ".origin"));
        }
        BuildOptions options = BuildOptions.DEFAULT;
        if (module.has("options")) {
            if (!module.get("options").isJsonObject()) throw new IllegalArgumentException(ROOT + ".options: expected object");
            options = BuildOptions.fromJson(module.getAsJsonObject("options"), ROOT + ".options");
        }
        JsonElement accepted = module.get("reviewAccepted");
        boolean review = accepted != null && accepted.isJsonPrimitive() && accepted.getAsJsonPrimitive().isBoolean() && accepted.getAsBoolean();
        return Optional.of(new BuilderModule(schematic.getAsString(), origin, options, review || !document.has("migration")));
    }
}
