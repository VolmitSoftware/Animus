package art.arcane.animus.core.migration;

import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.trigger.TriggerCompiler;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * The size limits of {@link BehaviorCompiler} checked on a generated document, so a converter can keep a container
 * that would not compile instead of writing it. Counts nodes and depth the way the compiler does: an omitted
 * {@code else} counts as an empty sequence. The browser converter has a line-for-line copy.
 */
final class BehaviorLimits {
    static final int ROUTINES = BehaviorCompiler.MAX_ROUTINES, TRIGGERS = TriggerCompiler.MAX_TRIGGERS;

    private BehaviorLimits() {}

    static Optional<String> check(JsonObject document, String text) {
        if (text.getBytes(StandardCharsets.UTF_8).length > BehaviorCompiler.MAX_BYTES) return Optional.of("exceeds 256 KiB");
        JsonObject routines = document.getAsJsonObject("routines");
        if (routines.size() > ROUTINES) return Optional.of("has more than 32 routines");
        if (document.has("triggers") && document.getAsJsonArray("triggers").size() > TRIGGERS) return Optional.of("has more than 32 triggers");
        int[] nodes = {0};
        for (Map.Entry<String, JsonElement> routine : routines.entrySet()) {
            Optional<String> problem = node(routine.getValue().getAsJsonObject(), 0, nodes);
            if (problem.isPresent()) return problem;
        }
        return Optional.empty();
    }

    private static Optional<String> node(JsonObject node, int depth, int[] nodes) {
        if (depth > BehaviorCompiler.MAX_DEPTH) return Optional.of("nests deeper than 32");
        if (++nodes[0] > BehaviorCompiler.MAX_NODES) return Optional.of("has more than 2048 steps");
        String type = node.get("type").getAsString();
        if (type.equals("sequence")) {
            if (node.getAsJsonArray("steps").size() > BehaviorCompiler.MAX_STEPS) return Optional.of("has a sequence longer than 128 steps");
            for (JsonElement step : node.getAsJsonArray("steps")) {
                Optional<String> problem = node(step.getAsJsonObject(), depth + 1, nodes);
                if (problem.isPresent()) return problem;
            }
        } else if (type.equals("condition")) {
            Optional<String> then = node(node.getAsJsonObject("then"), depth + 1, nodes);
            if (then.isPresent()) return then;
            if (node.has("else")) return node(node.getAsJsonObject("else"), depth + 1, nodes);
            if (++nodes[0] > BehaviorCompiler.MAX_NODES) return Optional.of("has more than 2048 steps");
        } else if (type.equals("repeat")) return node(node.getAsJsonObject("body"), depth + 1, nodes);
        return Optional.empty();
    }
}
