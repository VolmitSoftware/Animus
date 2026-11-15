package art.arcane.animus.core.skin;

import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * What a skin file under {@code skins/} says beyond the texture the runtime reads: its label, the Minecraft account it
 * came from ({@code player}), the PNG it was made from while it waits to be signed ({@code image}), and the texture
 * address and arm model inside {@code textureRaw}.
 */
public record SkinFile(String texture, Optional<String> signature, Optional<String> label, Optional<String> player, Optional<byte[]> image,
                       Optional<String> url, boolean slim) {
    /** Bounds the kept PNG at MineSkin's own limit; a 64x64 skin is a few kilobytes. */
    public static final int MAXIMUM_IMAGE = 20_000;

    public boolean signed() { return signature.isPresent(); }

    /** Whether MineSkin can sign it: unsigned, with its PNG kept or a texture address to fetch. */
    public boolean signable() { return !signed() && (image.isPresent() || url.isPresent()); }

    /** The file's name to show: its label, else the account it came from. */
    public Optional<String> name() { return label.isPresent() ? label : player; }

    public static SkinFile parse(String text) throws IOException {
        JsonObject json = JsonDocuments.object(text);
        String texture = string(json, "textureRaw").orElseThrow(() -> new IOException("textureRaw must be a string"));
        Optional<byte[]> image = Optional.empty();
        Optional<String> encoded = string(json, "image");
        if (encoded.isPresent()) {
            try { image = Optional.of(Base64.getDecoder().decode(encoded.get())); }
            catch (IllegalArgumentException invalid) { throw new IOException("image must be a base64 PNG"); }
            if (image.get().length > MAXIMUM_IMAGE) throw new IOException("image is over " + MAXIMUM_IMAGE + " bytes");
        }
        Texture decoded = texture(texture);
        return new SkinFile(texture, string(json, "signature"), string(json, "label").filter((String label) -> !label.isBlank()),
                string(json, "player").filter((String player) -> !player.isBlank()), image, decoded.url(), decoded.slim());
    }

    /** The skin address and arm model inside a {@code textures} property value. */
    public record Texture(Optional<String> url, boolean slim) {}

    /** What a base64 {@code textures} value names; an opaque value names nothing. */
    public static Texture texture(String value) {
        try {
            JsonObject textures = JsonParser.parseString(new String(Base64.getDecoder().decode(value.strip()), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject all = textures.has("textures") && textures.get("textures").isJsonObject() ? textures.getAsJsonObject("textures") : null;
            JsonObject skin = all != null && all.has("SKIN") && all.get("SKIN").isJsonObject() ? all.getAsJsonObject("SKIN") : null;
            if (skin == null) return new Texture(Optional.empty(), false);
            Optional<String> url = string(skin, "url").filter((String address) -> address.startsWith("https://") || address.startsWith("http://"));
            JsonElement metadata = skin.get("metadata");
            return new Texture(url, metadata != null && metadata.isJsonObject() && string(metadata.getAsJsonObject(), "model").filter("slim"::equals).isPresent());
        } catch (RuntimeException unreadable) { return new Texture(Optional.empty(), false); }
    }

    private static Optional<String> string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? Optional.of(value.getAsString()) : Optional.empty();
    }
}
