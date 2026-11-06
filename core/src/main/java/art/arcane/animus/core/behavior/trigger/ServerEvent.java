package art.arcane.animus.core.behavior.trigger;

import art.arcane.animus.api.Capabilities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An immutable server-wide event for server behaviors. {@code context} holds plain Boolean, String and List of String values
 * that a run sees as {@code context.<key>}. Publishing grants no world authority: a run only carries the player UUID.
 */
public record ServerEvent(TriggerKind kind, Optional<UUID> player, Map<String, Object> context, Optional<Capabilities.Key> module) {
    /** Text carried by one event; longer chat lines and command arguments are cut at this many characters. */
    public static final int MAXIMUM_TEXT = 4096, MAXIMUM_ARGUMENTS = 256;

    public ServerEvent {
        Objects.requireNonNull(kind); Objects.requireNonNull(player); Objects.requireNonNull(module);
        if (!kind.server()) throw new IllegalArgumentException(kind.jsonName() + " is not a server event");
        context = Collections.unmodifiableMap(new LinkedHashMap<>(context));
    }

    public static ServerEvent join(UUID player, boolean first) { return of(TriggerKind.PLAYER_JOIN, player, Map.of("first", first)); }
    public static ServerEvent quit(UUID player) { return of(TriggerKind.PLAYER_QUIT, player, Map.of()); }
    public static ServerEvent chat(UUID player, String message) { return of(TriggerKind.PLAYER_CHAT, player, Map.of("message", bounded(message))); }
    public static ServerEvent death(UUID player, Optional<String> message) {
        return of(TriggerKind.PLAYER_DEATH, player, message.isPresent() ? Map.of("message", bounded(message.get())) : Map.of());
    }
    public static ServerEvent respawn(UUID player) { return of(TriggerKind.PLAYER_RESPAWN, player, Map.of()); }
    public static ServerEvent worldChange(UUID player, String from, String to) { return of(TriggerKind.PLAYER_WORLD_CHANGE, player, Map.of("from", from, "to", to)); }
    public static ServerEvent serverStart() { return new ServerEvent(TriggerKind.SERVER_START, Optional.empty(), Map.of(), Optional.empty()); }
    public static ServerEvent timer() { return new ServerEvent(TriggerKind.TIMER, Optional.empty(), Map.of(), Optional.empty()); }
    public static ServerEvent module(Optional<UUID> player, Capabilities.Key key) { return new ServerEvent(TriggerKind.MODULE, player, Map.of(), Optional.of(key)); }

    /**
     * A run of the Animus-managed command {@code name}, typed as {@code alias}. An empty player is the console.
     * {@code args} splits {@code raw_args} on whitespace.
     */
    public static ServerEvent command(Optional<UUID> player, String name, String alias, String rawArguments) {
        String raw = bounded(rawArguments.strip());
        List<String> args = new ArrayList<>();
        if (!raw.isEmpty()) for (String part : raw.split("\\s+")) if (args.size() < MAXIMUM_ARGUMENTS) args.add(part);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("command", name);
        context.put("alias", alias);
        context.put("args", List.copyOf(args));
        context.put("raw_args", raw);
        return new ServerEvent(TriggerKind.COMMAND, player, context, Optional.empty());
    }

    private static ServerEvent of(TriggerKind kind, UUID player, Map<String, Object> context) {
        return new ServerEvent(kind, Optional.of(Objects.requireNonNull(player)), context, Optional.empty());
    }
    private static String bounded(String text) {
        Objects.requireNonNull(text);
        if (text.length() <= MAXIMUM_TEXT) return text;
        int end = MAXIMUM_TEXT;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    /** Whether this event fires a trigger with this spec. */
    public boolean matches(TriggerBinding.Spec spec) {
        if (spec.kind() != kind) return false;
        return switch (spec) {
            case TriggerBinding.PlayerJoin join -> join.first().isEmpty() || join.first().get().equals(context.get("first"));
            case TriggerBinding.PlayerChat chat -> chat.pattern().isEmpty() || glob(chat.pattern().get(), (String) context.get("message"));
            case TriggerBinding.Command command -> command.name().equals(context.get("command"));
            case TriggerBinding.WorldChange change -> change.from().map(world -> world.equals(context.get("from"))).orElse(true)
                    && change.to().map(world -> world.equals(context.get("to"))).orElse(true);
            case TriggerBinding.ModuleEvent event -> module.map(event.key()::equals).orElse(false);
            case TriggerBinding.Lifecycle ignored -> true;
            default -> false;
        };
    }

    /** Case-insensitive match of the whole text, where {@code *} matches any run of characters. */
    static boolean glob(String pattern, String text) {
        String p = pattern.toLowerCase(java.util.Locale.ROOT), t = text.toLowerCase(java.util.Locale.ROOT);
        int pi = 0, ti = 0, star = -1, mark = 0;
        while (ti < t.length()) {
            if (pi < p.length() && p.charAt(pi) != '*' && p.charAt(pi) == t.charAt(ti)) { pi++; ti++; }
            else if (pi < p.length() && p.charAt(pi) == '*') { star = pi++; mark = ti; }
            else if (star >= 0) { pi = star + 1; ti = ++mark; }
            else return false;
        }
        while (pi < p.length() && p.charAt(pi) == '*') pi++;
        return pi == p.length();
    }
}
