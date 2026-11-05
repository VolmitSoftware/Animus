package art.arcane.animus.core.presentation;

import java.util.Base64;

/** Signed player textures, kept opaque and bounded. */
public record BodySkin(String textureValue, String signature) {
    public BodySkin {
        if (textureValue == null || textureValue.isBlank() || textureValue.length() > 32768)
            throw new IllegalArgumentException("Skin texture must contain 1..32768 characters");
        Base64.getDecoder().decode(textureValue);
        if (signature != null) {
            if (signature.isBlank() || signature.length() > 8192) throw new IllegalArgumentException("Invalid skin signature size");
            Base64.getDecoder().decode(signature);
        }
    }
}
