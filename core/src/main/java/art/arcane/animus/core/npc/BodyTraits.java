package art.arcane.animus.core.npc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Body state a definition may declare beyond its look: an outline glow, a team colour for the glow and the name, a
 * baby body, a raised held item, a tab-list entry for player bodies, whether the body falls when nothing holds it, and
 * its vanilla attributes and the settings only its body type has. {@code gravity} false makes the body hover whatever its
 * {@code gravity} attribute says.
 */
public record BodyTraits(boolean glowing, Optional<String> teamColor, boolean baby, boolean usingItem, boolean listed, boolean gravity, NpcAttributes attributes,
                         BodyVariant variant) {
    public static final BodyTraits DEFAULT = new BodyTraits(false, Optional.empty(), false, false, false, true, NpcAttributes.NONE, BodyVariant.NONE);
    /** The sixteen named chat colours a team may use, by their lowercase names. */
    public static final List<String> TEAM_COLORS = List.of("black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white");

    public BodyTraits { Objects.requireNonNull(teamColor); Objects.requireNonNull(attributes); Objects.requireNonNull(variant); }

    /** The legacy formatting code of the team colour, such as {@code 4} for dark red. */
    public Optional<Character> colorCode() { return teamColor.map((String color) -> "0123456789abcdef".charAt(TEAM_COLORS.indexOf(color))); }

    /** These traits with other attributes, as a runtime override shows them. */
    public BodyTraits withAttributes(NpcAttributes next) { return new BodyTraits(glowing, teamColor, baby, usingItem, listed, gravity, next, variant); }

    static BodyTraits read(JsonObject definition, List<String> errors) {
        boolean glowing = flag(definition, "glowing", false, errors), baby = flag(definition, "baby", false, errors);
        boolean usingItem = flag(definition, "usingItem", false, errors), listed = flag(definition, "listed", false, errors);
        boolean gravity = flag(definition, "gravity", true, errors);
        Optional<String> color = Optional.empty();
        if (definition.has("teamColor")) {
            JsonElement value = definition.get("teamColor");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !TEAM_COLORS.contains(value.getAsString()))
                errors.add("$.teamColor: expected a team colour such as red, dark_aqua or gold");
            else color = Optional.of(value.getAsString());
        }
        String type = definition.has("type") && definition.get("type").isJsonPrimitive() ? definition.get("type").getAsString() : "";
        return new BodyTraits(glowing, color, baby, usingItem, listed, gravity, NpcAttributes.read(definition, errors), BodyVariant.read(definition, type, errors));
    }

    private static boolean flag(JsonObject definition, String key, boolean fallback, List<String> errors) {
        JsonElement value = definition.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) { errors.add("$." + key + ": expected boolean"); return fallback; }
        return value.getAsBoolean();
    }
}
