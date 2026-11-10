package art.arcane.animus.core.integration;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a new NPC can take its starting look from, by source name, such as {@code mythic} for a MythicMobs mob type.
 * Platform integrations register a source while the plugin behind it is enabled; {@code /animus create} reads it.
 */
public final class NpcTemplates {
    /** MythicMobs mob types. */
    public static final String MYTHIC = "mythic";

    /**
     * Definition fields a template lays over a new NPC, and what the reply tells the player about it, such as a body type
     * that had to be replaced.
     */
    public record Template(JsonObject fields, List<String> notes) {
        public Template { fields = fields.deepCopy(); notes = List.copyOf(notes); }
        @Override public JsonObject fields() { return fields.deepCopy(); }
    }

    /** One source. Called on the command sender's owner thread. */
    public interface Source {
        /** The template called {@code name}. @throws IllegalArgumentException naming why there is none */
        Template template(String name);
        /** Every name, for completion. */
        List<String> names();
    }

    private final Map<String, Source> sources = new ConcurrentHashMap<>();

    /** Registers {@code source} under {@code name} until the returned handle closes. */
    public AutoCloseable register(String name, Source source) {
        Objects.requireNonNull(name); Objects.requireNonNull(source);
        if (sources.putIfAbsent(name, source) != null) throw new IllegalStateException("A template source is already registered as " + name);
        return () -> sources.remove(name, source);
    }

    /** The template {@code name} of {@code source}. @throws IllegalArgumentException when the source is missing or refuses */
    public Template template(String source, String name) {
        Source found = sources.get(source);
        if (found == null) throw new IllegalArgumentException(missing(source));
        return found.template(name);
    }

    /** The names {@code source} offers; empty when it is not registered. */
    public List<String> names(String source) {
        Source found = sources.get(source);
        return found == null ? List.of() : found.names();
    }

    private static String missing(String source) {
        return source.equals(MYTHIC) ? "MythicMobs is not running on this server" : "No " + source + " templates on this server";
    }
}
