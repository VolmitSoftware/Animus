package art.arcane.animus.core.command;

import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.virtual.Vec3;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /animus navigation status} in words. The runtimes keep their detailed status line for logs and debug output;
 * this keeps the state, goal, position, arrivals, route, error and recording, and drops the planner's counters.
 */
final class NavigationReport {
    private static final Pattern HEAD = Pattern.compile("(Navigation [^:]+): (.*)", Pattern.DOTALL);
    private static final Pattern KEY = Pattern.compile("([a-z][a-z-]*)=(.*)", Pattern.DOTALL);
    private static final Pattern VEC = Pattern.compile("Vec3\\[x=([^,]+), y=([^,]+), z=([^\\]]+)]");
    private static final Pattern WORD = Pattern.compile("(\\S+)=(\\S+)");

    private NavigationReport() {}

    static String readable(String raw) {
        Matcher head = HEAD.matcher(raw);
        if (!head.matches()) return raw;
        String[] parts = head.group(2).split("; ");
        StringBuilder state = new StringBuilder(parts[0]);
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 1; index < parts.length; index++) {
            Matcher key = KEY.matcher(parts[index]);
            if (key.matches()) values.putIfAbsent(key.group(1), key.group(2));
            else state.append(", ").append(parts[index]);
        }
        StringBuilder out = new StringBuilder(head.group(1)).append(": ").append(state);
        point(values.get("goal")).ifPresent((Vec3 goal) -> out.append(" to ").append(goal.brief()));
        point(values.get("position")).ifPresent((Vec3 at) -> out.append(", now at ").append(at.brief()));
        String arrivals = values.getOrDefault("arrivals", "0");
        if (!arrivals.equals("0")) out.append(", ").append(arrivals).append(arrivals.equals("1") ? " arrival" : " arrivals");
        if (values.containsKey("route")) out.append("; route ").append(ChatLine.shortId(values.get("route")));
        if (values.containsKey("route-source")) out.append("; route file ").append(values.get("route-source"));
        if (values.containsKey("route-error")) out.append("; error ").append(values.get("route-error"));
        recording(values).ifPresent((String recording) -> out.append("; ").append(recording));
        return out.toString();
    }

    private static Optional<String> recording(Map<String, String> values) {
        String run = values.containsKey("recording") ? "recording=" + values.get("recording") : values.get("step");
        if (run != null) {
            Map<String, String> words = new LinkedHashMap<>();
            Matcher word = WORD.matcher(values.containsKey("recording") ? run : "step=" + run);
            while (word.find()) words.put(word.group(1), word.group(2));
            String name = words.containsKey("recording") ? words.get("recording") : words.getOrDefault("step", "").replaceFirst("^recording:", "");
            return Optional.of("recording " + name + (words.containsKey("cursor") ? " " + words.get("cursor") : ""));
        }
        return Optional.ofNullable(values.get("recording-source")).map((String source) -> "recording " + source);
    }

    private static Optional<Vec3> point(String text) {
        if (text == null) return Optional.empty();
        Matcher vec = VEC.matcher(text);
        if (!vec.find()) return Optional.empty();
        try { return Optional.of(new Vec3(Double.parseDouble(vec.group(1)), Double.parseDouble(vec.group(2)), Double.parseDouble(vec.group(3)))); }
        catch (NumberFormatException unreadable) { return Optional.empty(); }
    }
}
