package art.arcane.animus.core.config;

import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.storage.*;
import art.arcane.volmlib.util.config.TomlCodec;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;

/** Plugin-wide settings are TOML. NPC definitions, behaviors, state and generated data remain JSON. */
public record PluginSettings(String statusMessage, boolean packetProofEnabled) {
    public static PluginSettings load(DocumentStore store) throws IOException {
        Path toml = store.resolve("config.toml");
        if (Files.exists(toml)) return readToml(toml);
        Path legacy = store.resolve("config.json");
        JsonObject document;
        if (Files.exists(legacy)) {
            String original = JsonDocuments.read(legacy);
            try { document = JsonDocuments.object(original); }
            catch (IOException e) { throw new IOException("Cannot load " + legacy + ": " + e.getMessage(), e); }
            validate(document, legacy);
            // Leave the old file intact and archive it before creating the authoritative TOML file.
            DocumentStore.atomic(store.resolve("backups/config-" + JsonDocuments.hash(original) + ".json"), original);
        } else {
            document = new JsonObject();
            document.addProperty("statusMessage", "Hello from " + BuildIdentity.NAME);
            document.addProperty("packetProofEnabled", false);
        }
        String encoded;
        try { encoded = TomlCodec.toToml(document); }
        catch (RuntimeException e) { throw new IOException("Cannot migrate config.json to TOML; original retained", e); }
        if (!document.equals(TomlCodec.toJsonElement(encoded)))
            throw new IOException("config.json cannot be represented losslessly in TOML; original retained");
        DocumentStore.atomic(toml, "# Animus plugin settings. NPC and behavior documents use JSON.\n" + encoded);
        return readToml(toml);
    }

    private static PluginSettings readToml(Path path) throws IOException {
        try {
            String text = JsonDocuments.read(path);
            if (text.length() > 256 * 1024) throw new IOException("Plugin settings exceed 256 KiB");
            return validate(TomlCodec.toJsonElement(text), path);
        } catch (IOException | RuntimeException e) { throw new IOException("Cannot load " + path + ": " + e.getMessage(), e); }
    }

    private static PluginSettings validate(JsonElement data, Path path) throws IOException {
        if (data == null || !data.isJsonObject()) throw new IOException(path + ": expected settings table");
        JsonElement message = data.getAsJsonObject().get("statusMessage");
        if (message == null || !message.isJsonPrimitive() || !message.getAsJsonPrimitive().isString() || message.getAsString().isBlank())
            throw new IOException(path + ": statusMessage must be a nonblank string");
        JsonElement proof = data.getAsJsonObject().get("packetProofEnabled");
        if (proof != null && (!proof.isJsonPrimitive() || !proof.getAsJsonPrimitive().isBoolean()))
            throw new IOException(path + ": packetProofEnabled must be a boolean");
        return new PluginSettings(message.getAsString(), proof != null && proof.getAsBoolean());
    }
}
