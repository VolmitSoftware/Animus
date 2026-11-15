package art.arcane.animus.core.behavior;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Reads behavior document nodes as written, before they compile: the capability a node calls and which of its arguments
 * are literal. For checks that decide what a document may do before it loads.
 */
public final class BehaviorNodes {
    private BehaviorNodes() {}

    /** Whether any object in {@code root}, at any depth, passes {@code test}. */
    public static boolean any(JsonElement root, Predicate<JsonObject> test) {
        ArrayDeque<JsonElement> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            JsonElement element = pending.removeFirst();
            if (element.isJsonArray()) element.getAsJsonArray().forEach(pending::add);
            else if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                if (test.test(object)) return true;
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) pending.add(entry.getValue());
            }
        }
        return false;
    }

    /** The string at {@code key}, empty when it is missing or not a string. */
    public static Optional<String> text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? Optional.of(value.getAsString()) : Optional.empty();
    }

    /** The capability a node calls, empty for a node that calls none. */
    public static Optional<String> capability(JsonObject node) { return text(node, "capability"); }

    /** Whether a binding, expression or template fills the argument; slot keys are JSON Pointers into the arguments. */
    public static boolean bound(JsonObject node, String argument) {
        for (String slots : List.of("bindings", "expressions", "templates")) {
            JsonElement value = node.get(slots);
            if (value == null) continue;
            if (!value.isJsonObject()) return true;
            for (String pointer : value.getAsJsonObject().keySet()) if (pointer.equals("/" + argument) || pointer.startsWith("/" + argument + "/")) return true;
        }
        return false;
    }

    /** The literal argument, empty when it is not written; a bound argument may still be filled while it runs. */
    public static Optional<JsonElement> literal(JsonObject node, String argument) {
        return node.get("arguments") instanceof JsonObject arguments ? Optional.ofNullable(arguments.get(argument)) : Optional.empty();
    }

    /** Whether the argument is written or filled while it runs. */
    public static boolean given(JsonObject node, String argument) { return literal(node, argument).isPresent() || bound(node, argument); }

    /**
     * Whether a boolean argument is surely false: written as {@code false}, or left out when {@code absent} says that means
     * false, and filled by no binding, expression or template.
     */
    public static boolean off(JsonObject node, String argument, boolean absent) {
        if (bound(node, argument)) return false;
        Optional<JsonElement> literal = literal(node, argument);
        if (literal.isEmpty()) return absent;
        return literal.get().isJsonPrimitive() && literal.get().getAsJsonPrimitive().isBoolean() && !literal.get().getAsBoolean();
    }

    /** A node that can pay a quest's rewards: advancing past the last stage completes it, and a forced start repeats it. */
    public static boolean paysQuest(JsonObject node) {
        return switch (capability(node).orElse("")) {
            case "animus:advance-quest" -> true;
            case "animus:complete-quest" -> !off(node, "rewards", false);
            case "animus:start-quest" -> !off(node, "force", true);
            default -> false;
        };
    }
}
