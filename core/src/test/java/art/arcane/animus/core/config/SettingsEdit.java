package art.arcane.animus.core.config;

import art.arcane.animus.core.AnimusRuntime;
import art.arcane.volmlib.util.config.TomlCodec;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Edits the running config.toml as an operator would and applies it the way a hotload does. */
public final class SettingsEdit {
    private SettingsEdit() {}

    /** Changes one table of config.toml, writes it back and applies the result live. */
    public static PluginSettings apply(AnimusRuntime runtime, String table, Consumer<JsonObject> edit) throws IOException {
        Path file = runtime.environment().dataDirectory().resolve("config.toml");
        JsonObject document = TomlCodec.toJsonElement(Files.readString(file)).getAsJsonObject();
        if (!document.has(table)) document.add(table, new JsonObject());
        edit.accept(document.getAsJsonObject(table));
        String text = TomlCodec.toToml(document);
        Files.writeString(file, text);
        PluginSettings next = PluginSettings.parse(text, file);
        runtime.applySettings(next);
        return next;
    }
}
