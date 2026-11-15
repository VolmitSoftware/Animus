package art.arcane.animus.core.migration;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A player NPC's Citizens skin trait: the saved texture becomes a skin file that names the account it came from
 * ({@code player}), {@code updateSkins} becomes {@code animus:skin refresh}, and a player NPC with no saved texture that
 * Citizens would have dressed in the skin of the account named like it ({@code fetchDefaultSkin}, on by default) gets
 * {@code animus:skin fromName}. A skin name of {@code %player%} (the viewer's own skin) becomes {@code animus:mirror}; a
 * PlaceholderAPI skin name stays the skin's source, filled for no viewer and refreshed as Citizens refills it.
 */
final class CitizensSkins {
    /** The Mojang account names Citizens looks up: 3 to 16 letters, digits or underscores. */
    static final Pattern ACCOUNT = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern COLOURS = Pattern.compile("(?i)[&§][0-9a-fk-orx]");
    private static final Set<String> VIEWER = Set.of("%player%", "%player_name%", "<player>", "<p>");

    /**
     * @param file the skin file's text, when there is a texture to keep
     * @param options the {@code animus:skin} module, empty when it sets nothing
     * @param mirror the skin name asked for each viewer's own skin
     */
    record Result(Optional<String> file, JsonObject options, boolean mirror, Map<String, CitizensImporter.Note> notes) {
        Result { options = options.deepCopy(); notes = Map.copyOf(notes); }
        @Override public JsonObject options() { return options.deepCopy(); }
    }

    private CitizensSkins() {}

    /** {@code trait} is the saved {@code skintrait}, or null when the NPC has none; {@code name} is its converted display name. */
    static Result map(JsonElement trait, String name) {
        JsonObject skin = trait != null && trait.isJsonObject() ? trait.getAsJsonObject() : new JsonObject();
        Map<String, CitizensImporter.Note> notes = new HashMap<>();
        JsonObject options = new JsonObject();
        String plain = COLOURS.matcher(name).replaceAll("").strip();
        String skinName = text(skin.get("skinName")).strip();
        String texture = text(skin.get("textureRaw"));
        if (texture.equals("cache")) texture = "";
        boolean update = Boolean.TRUE.equals(bool(skin.get("updateSkins")));
        boolean fetchDefault = !Boolean.FALSE.equals(bool(skin.get("fetchDefaultSkin")));
        boolean viewer = VIEWER.contains(skinName.toLowerCase(Locale.ROOT));
        if (viewer) notes.put("/traits/skintrait/skinName", new CitizensImporter.Note(CitizensImporter.Status.CONVERTED, "each viewer sees their own skin; became animus:mirror"));
        if (skinName.equalsIgnoreCase("<npc>")) skinName = plain;
        boolean placeholder = !viewer && skinName.matches(".*%[^%\\s]+%.*");
        if (!viewer && !placeholder && skinName.matches(".*<[a-z_]+>.*"))
            notes.put("/traits/skintrait/skinName", new CitizensImporter.Note(CitizensImporter.Status.REQUIRES_REVIEW, "the Citizens placeholder " + skinName + " has no Animus equivalent; the skin it last filled is kept"));
        Optional<String> source = placeholder ? Optional.of(skinName) : Optional.of(skinName).filter((String account) -> ACCOUNT.matcher(account).matches());
        if (texture.isEmpty()) {
            boolean ownName = skinName.isEmpty() || skinName.equalsIgnoreCase(plain);
            if (!viewer && ownName && (fetchDefault || update) && ACCOUNT.matcher(plain).matches()) {
                options.addProperty("fromName", true);
                if (update) options.addProperty("refresh", true);
            } else if (!viewer && !ownName && source.isPresent())
                notes.put("/traits/skintrait/skinName", new CitizensImporter.Note(CitizensImporter.Status.REQUIRES_REVIEW,
                        "the skin " + skinName + " was saved without its texture; set it with /animus npc skin <npc> " + skinName));
            return new Result(Optional.empty(), options, viewer, notes);
        }
        JsonObject file = new JsonObject();
        file.addProperty("textureRaw", texture);
        String signature = text(skin.get("signature"));
        if (!signature.isEmpty()) file.addProperty("signature", signature);
        if (source.isPresent() && !placeholder) file.addProperty("label", source.get());
        source.ifPresent((String account) -> file.addProperty("player", account));
        if (source.isPresent() && (update || placeholder)) options.addProperty("refresh", true);
        if (placeholder) notes.put("/traits/skintrait/skinName", new CitizensImporter.Note(CitizensImporter.Status.CONVERTED,
                "filled by PlaceholderAPI for no viewer and looked up again every [skins] refreshMinutes"));
        return new Result(Optional.of(JsonDocuments.write(file)), options, viewer, notes);
    }

    private static String text(JsonElement value) { return value != null && value.isJsonPrimitive() ? value.getAsString() : ""; }

    private static Boolean bool(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return null;
        if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        String text = value.getAsString();
        return text.equals("true") ? Boolean.TRUE : text.equals("false") ? Boolean.FALSE : null;
    }
}
