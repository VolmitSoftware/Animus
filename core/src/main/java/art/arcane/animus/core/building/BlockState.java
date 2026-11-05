package art.arcane.animus.core.building;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * A block name with its state properties, as written in a command or a schematic palette:
 * {@code minecraft:oak_stairs[facing=north,half=bottom]}. Properties are kept sorted so that equal states have equal text.
 * A name without a namespace means {@code minecraft}.
 */
public record BlockState(String name, Map<String, String> properties) {
    public static final int MAXIMUM_TEXT = 512, MAXIMUM_PROPERTIES = 32;
    private static final Pattern NAME = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern PROPERTY = Pattern.compile("[a-z0-9_]{1,64}");
    public static final BlockState AIR = new BlockState("minecraft:air", Map.of());

    public BlockState {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(properties, "properties");
        if (!NAME.matcher(name).matches() || name.length() > 256) throw new IllegalArgumentException("Invalid block name: " + name);
        if (properties.size() > MAXIMUM_PROPERTIES) throw new IllegalArgumentException("Too many block state properties: " + name);
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (!PROPERTY.matcher(entry.getKey()).matches() || !PROPERTY.matcher(entry.getValue()).matches())
                throw new IllegalArgumentException("Invalid block state property " + entry.getKey() + "=" + entry.getValue() + " on " + name);
            sorted.put(entry.getKey(), entry.getValue());
        }
        properties = Collections.unmodifiableMap(sorted);
    }

    /** Parses block state text; throws {@link IllegalArgumentException} with the reason when it is malformed. */
    public static BlockState parse(String text) {
        Objects.requireNonNull(text, "text");
        if (text.isEmpty() || text.length() > MAXIMUM_TEXT) throw new IllegalArgumentException("Block state text is empty or too long");
        String trimmed = text.strip();
        int open = trimmed.indexOf('[');
        String name = open < 0 ? trimmed : trimmed.substring(0, open);
        if (!name.contains(":")) name = "minecraft:" + name;
        TreeMap<String, String> properties = new TreeMap<>();
        if (open >= 0) {
            if (!trimmed.endsWith("]")) throw new IllegalArgumentException("Unclosed block state properties: " + text);
            String body = trimmed.substring(open + 1, trimmed.length() - 1);
            if (!body.isBlank()) {
                for (String pair : body.split(",", -1)) {
                    int equals = pair.indexOf('=');
                    if (equals <= 0 || equals == pair.length() - 1) throw new IllegalArgumentException("Malformed block state property '" + pair + "' in " + text);
                    String key = pair.substring(0, equals).strip(), value = pair.substring(equals + 1).strip();
                    if (properties.put(key, value) != null) throw new IllegalArgumentException("Duplicate block state property " + key + " in " + text);
                }
            }
        }
        return new BlockState(name, properties);
    }

    /** The path after the namespace, such as {@code oak_stairs}. */
    public String path() { return name.substring(name.indexOf(':') + 1); }
    public Optional<String> property(String key) { return Optional.ofNullable(properties.get(key)); }
    public BlockState withName(String replacement) { return new BlockState(replacement, properties); }
    public BlockState withProperties(Map<String, String> replacement) { return new BlockState(name, replacement); }
    public BlockState withoutProperties() { return properties.isEmpty() ? this : new BlockState(name, Map.of()); }
    public boolean air() { return name.equals("minecraft:air") || name.equals("minecraft:cave_air") || name.equals("minecraft:void_air"); }
    /** The marker that leaves the world unchanged at its cell. */
    public boolean structureVoid() { return name.equals("minecraft:structure_void"); }

    @Override public String toString() {
        if (properties.isEmpty()) return name;
        StringBuilder text = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (!first) text.append(',');
            text.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return text.append(']').toString();
    }
}
