package art.arcane.animus.core.config;

import art.arcane.animus.core.platform.MinecraftIds;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code [editor.notify]} in {@code config.toml}: what online players hear and see when the web editor changes the server.
 * Holders of {@code animus.editor.notify} (operators by default) and the player who paired the token get each notice.
 * {@code [editor.notify.sounds.<event>]} sets the sound per event; an empty {@code sound} is silent.
 *
 * @param chat one chat line per notice, with the transaction and rollback command on hover and click
 * @param actionBar the same line on the action bar
 * @param console one terse log line per notice
 */
public record EditorNotifySettings(boolean enabled, boolean chat, boolean actionBar, boolean console, Map<Kind, Sound> sounds) {
    /** Editor events with their own sound. {@code refused} is also played when a committed change reported reload problems. */
    public enum Kind {
        APPLY, ROLLBACK, IMPORT, SPAWN, DESPAWN, PAIR, REVOKE, REFUSED;

        public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    /** A namespaced sound id, or empty for silence. Volume 0..2, pitch 0.5..2. */
    public record Sound(String id, double volume, double pitch) {
        public Sound {
            Objects.requireNonNull(id);
            if (!id.isEmpty() && (id.length() > 128 || !id.matches(MinecraftIds.PATTERN))) throw new IllegalArgumentException("sound must be a sound id such as minecraft:block.note_block.pling, or empty for silence");
            if (!(volume >= 0 && volume <= 2)) throw new IllegalArgumentException("volume must be within 0..2");
            if (!(pitch >= 0.5 && pitch <= 2)) throw new IllegalArgumentException("pitch must be within 0.5..2");
        }

        public boolean silent() { return id.isEmpty() || volume == 0; }
    }

    private static final Set<String> FIELDS = Set.of("enabled", "chat", "actionBar", "console", "sounds");
    public static final EditorNotifySettings DEFAULT = new EditorNotifySettings(true, true, false, true, Map.of(
            Kind.APPLY, new Sound("minecraft:block.note_block.pling", 0.8, 1.6),
            Kind.ROLLBACK, new Sound("minecraft:block.note_block.pling", 0.8, 1.0),
            Kind.IMPORT, new Sound("minecraft:block.note_block.chime", 0.8, 1.4),
            Kind.SPAWN, new Sound("minecraft:block.note_block.pling", 0.6, 2.0),
            Kind.DESPAWN, new Sound("minecraft:block.note_block.pling", 0.6, 1.2),
            Kind.PAIR, new Sound("minecraft:entity.experience_orb.pickup", 0.7, 1.2),
            Kind.REVOKE, new Sound("minecraft:block.note_block.bass", 0.8, 0.8),
            Kind.REFUSED, new Sound("minecraft:block.note_block.bass", 0.9, 0.5)));

    public EditorNotifySettings {
        EnumMap<Kind, Sound> complete = new EnumMap<>(Kind.class);
        complete.putAll(sounds);
        for (Kind kind : Kind.values()) if (!complete.containsKey(kind)) throw new IllegalArgumentException("sounds." + kind.wireName() + " is missing");
        sounds = Map.copyOf(complete);
    }

    public Sound sound(Kind kind) { return sounds.get(kind); }

    /** Reads {@code [editor.notify]}; missing keys and sound fields keep their defaults. */
    public static EditorNotifySettings parse(JsonObject table) {
        for (String key : table.keySet()) if (!FIELDS.contains(key)) throw new IllegalArgumentException(key + " is not an editor notify setting");
        EditorNotifySettings d = DEFAULT;
        Map<Kind, Sound> sounds = new EnumMap<>(d.sounds);
        if (table.has("sounds")) {
            if (!table.get("sounds").isJsonObject()) throw new IllegalArgumentException("sounds must be a table");
            JsonObject given = table.getAsJsonObject("sounds");
            for (String name : given.keySet()) {
                Kind kind = kind(name);
                if (!given.get(name).isJsonObject()) throw new IllegalArgumentException("sounds." + name + " must be a table");
                sounds.put(kind, sound(given.getAsJsonObject(name), "sounds." + name + ".", d.sound(kind)));
            }
        }
        return new EditorNotifySettings(bool(table, "enabled", d.enabled), bool(table, "chat", d.chat), bool(table, "actionBar", d.actionBar),
                bool(table, "console", d.console), sounds);
    }

    /** The table written into a new config.toml. */
    public JsonObject toJson() {
        JsonObject table = new JsonObject();
        table.addProperty("enabled", enabled);
        table.addProperty("chat", chat);
        table.addProperty("actionBar", actionBar);
        table.addProperty("console", console);
        JsonObject all = new JsonObject();
        for (Kind kind : Kind.values()) {
            Sound sound = sounds.get(kind);
            JsonObject entry = new JsonObject();
            entry.addProperty("sound", sound.id());
            entry.addProperty("volume", sound.volume());
            entry.addProperty("pitch", sound.pitch());
            all.add(kind.wireName(), entry);
        }
        table.add("sounds", all);
        return table;
    }

    private static Kind kind(String name) {
        for (Kind kind : Kind.values()) if (kind.wireName().equals(name)) return kind;
        throw new IllegalArgumentException("sounds: " + name + " is not an editor event; events are apply, rollback, import, spawn, despawn, pair, revoke and refused");
    }

    private static Sound sound(JsonObject entry, String path, Sound fallback) {
        for (String key : entry.keySet()) if (!Set.of("sound", "volume", "pitch").contains(key)) throw new IllegalArgumentException(path + key + " is not a sound setting");
        String id = fallback.id();
        if (entry.has("sound")) {
            JsonElement value = entry.get("sound");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(path + "sound must be a string");
            id = value.getAsString();
        }
        double volume = number(entry, "volume", path, fallback.volume()), pitch = number(entry, "pitch", path, fallback.pitch());
        try { return new Sound(id, volume, pitch); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(path + invalid.getMessage(), invalid); }
    }

    private static double number(JsonObject entry, String key, String path, double fallback) {
        if (!entry.has(key)) return fallback;
        JsonElement value = entry.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(path + key + " must be a number");
        return value.getAsDouble();
    }

    private static boolean bool(JsonObject table, String key, boolean fallback) {
        if (!table.has(key)) return fallback;
        JsonElement value = table.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(key + " must be a boolean");
        return value.getAsBoolean();
    }
}
