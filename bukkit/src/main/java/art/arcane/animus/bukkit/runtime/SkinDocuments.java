package art.arcane.animus.bukkit.runtime;

import art.arcane.animus.bukkit.packet.NativePacketAdapter;
import art.arcane.animus.core.storage.*;
import java.io.IOException;

/** Content-addressed, bounded local read. Invoke only through the runtime's admitted I/O work. */
final class SkinDocuments {
    private SkinDocuments() {}
    static NativePacketAdapter.Skin read(DocumentStore documents, String hash) throws IOException {
        if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid skin reference");
        String text = documents.read("skins/" + hash + ".json");
        if (text == null || !JsonDocuments.hash(text).equals(hash)) throw new IOException("missing skin or content hash mismatch");
        var json = JsonDocuments.object(text);
        if (!json.has("textureRaw") || !json.get("textureRaw").isJsonPrimitive() || !json.getAsJsonPrimitive("textureRaw").isString())
            throw new IOException("textureRaw must be a string");
        if (json.has("signature") && !json.get("signature").isJsonNull()
                && (!json.get("signature").isJsonPrimitive() || !json.getAsJsonPrimitive("signature").isString()))
            throw new IOException("signature must be a string or null");
        return new NativePacketAdapter.Skin(json.get("textureRaw").getAsString(),
                json.has("signature") && !json.get("signature").isJsonNull() ? json.get("signature").getAsString() : null);
    }
}
