package art.arcane.animus.core.migration;

import static art.arcane.animus.core.migration.CitizensBehaviors.action;
import static art.arcane.animus.core.migration.CitizensBehaviors.arguments;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Citizens' own click command lines, read as Citizens 2.0.44's {@code Util.runCommand} rewrites them before they run: a
 * line that starts with {@code say} is NPC speech to the clicker, and {@code npc}, {@code waypoints} and {@code wp} lines
 * act on the clicked NPC. Each becomes the Animus action that does the same, or goes to review when there is none.
 */
final class CitizensCommandLines {
    sealed interface Line permits Plain, Mapped, Review {}
    /** Not one of Citizens' own lines; it runs as a command. */
    record Plain() implements Line {}
    /** The Animus node that does the same; {@code note} says what differs. */
    record Mapped(JsonObject node, Optional<String> note) implements Line {}
    record Review(String reason) implements Line {}

    /** Citizens' default {@code npc.chat.options.range}: who hears speech with no target and no range. */
    static final double CHAT_RANGE = 5;
    private static final String CLICKER = "{player_name}";
    private static final Pattern VALUE_FLAG = Pattern.compile("--[a-zA-Z0-9_-]+"), FLAG = Pattern.compile("-[a-zA-Z]+");
    private static final Pattern DURATION = Pattern.compile("(?i)(?:(\\d{1,6})h)?(?:(\\d{1,6})m)?(?:(\\d{1,6}(?:\\.\\d{1,3})?)s)?");

    private CitizensCommandLines() {}

    /**
     * {@code text} has no leading slash and Citizens' placeholders already written as Animus ones. {@code clicker} when the
     * clicking player runs the line, so commands about the sender mean that player.
     */
    static Line read(String text, boolean clicker, String key) {
        if (text.startsWith("say")) return speech(text.substring(3).trim());
        if (!text.startsWith("npc ") && !text.startsWith("waypoints ") && !text.startsWith("wp ")) return new Plain();
        Words words = Words.of(text);
        if (!words.args.getFirst().equals("npc")) return new Review("waypoint commands edit Citizens' waypoints, which Animus routes replace");
        String id = words.values.getOrDefault("id", key);
        if (!id.equals(key)) return new Review("acts on NPC " + id + ", not this one");
        String sub = words.args.size() > 1 ? words.args.get(1).toLowerCase(Locale.ROOT) : "";
        List<String> rest = words.args.subList(Math.min(2, words.args.size()), words.args.size());
        return switch (sub) {
            case "speak" -> speak(String.join(" ", rest), words);
            case "tp", "teleport" -> clicker ? mapped(action("animus:teleport", arguments("to", "npc"))) : console("teleport");
            case "tphere", "tph", "move" -> !clicker ? console("bring the NPC to")
                    : rest.isEmpty() ? mapped(action("animus:teleport", npcTo("player"))) : new Review("npc " + sub + " cursor moves the NPC to where the clicker looks, which Animus cannot read");
            case "moveto" -> moveto(rest, words);
            case "pathto" -> pathto(rest, words);
            case "rotate" -> rotate(words, clicker);
            case "pose" -> words.values.containsKey("assume") && !CitizensTraits.poseName(words.values.get("assume")).isEmpty()
                    ? mapped(action("animus:pose", arguments("pose", CitizensTraits.poseName(words.values.get("assume")))))
                    : new Review("npc pose edits the NPC's pose list; only --assume has an Animus action");
            case "panimate" -> rest.isEmpty() ? new Review("npc panimate names no animation") : animate(rest.getFirst());
            case "rename" -> rename(String.join(" ", rest), key);
            case "despawn" -> rest.isEmpty() || rest.getFirst().equals(key) ? mapped(command("animus despawn citizens-" + key))
                    : new Review("despawns NPC " + rest.getFirst() + ", not this one");
            default -> new Review(sub.isEmpty() ? "npc names no subcommand" : "npc " + sub + " has no Animus action");
        };
    }

    private static Line speech(String text) {
        if (text.isEmpty()) return new Review("says no text");
        return mapped(action("animus:say", arguments("text", text)));
    }

    /** Speech to the target, to everyone within the range, or to bystanders in Citizens' chat range; a bubble alone with --bubble. */
    private static Line speak(String text, Words words) {
        if (text.isBlank()) return new Review("npc speak says no text");
        JsonObject spoken = arguments("text", text);
        if (words.values.containsKey("bubble")) {
            OptionalLong ticks = ticks(words.values.get("bubble"));
            if (ticks.isEmpty() || ticks.getAsLong() < 1) return new Review("the bubble duration " + words.values.get("bubble") + " cannot be read");
            spoken.addProperty("bubbleTicks", Math.min(ticks.getAsLong(), 1200));
            spoken.addProperty("chat", false);
            return new Mapped(action("animus:say", spoken), ticks.getAsLong() > 1200 ? Optional.of("bubbles longer than 1,200 ticks became 1,200") : Optional.empty());
        }
        Optional<String> target = Optional.ofNullable(words.values.get("target"));
        if (target.isPresent() && !target.get().equals(CLICKER)) return new Review("speaks to " + target.get() + ", one fixed player or NPC");
        if (words.values.containsKey("range")) {
            OptionalDouble range = number(words.values.get("range"));
            if (range.isEmpty() || range.getAsDouble() <= 0) return new Review("the range " + words.values.get("range") + " is not a positive number");
            spoken.addProperty("audience", "nearby");
            spoken.addProperty("radius", Math.max(1, Math.min(64, range.getAsDouble())));
            Optional<String> note = target.isPresent() ? Optional.of("the clicker hears it only within the range")
                    : range.getAsDouble() > 64 ? Optional.of("ranges above 64 blocks became 64") : Optional.empty();
            return new Mapped(action("animus:say", spoken), note);
        }
        if (target.isEmpty()) {
            spoken.addProperty("audience", "nearby");
            spoken.addProperty("radius", CHAT_RANGE);
        }
        return mapped(action("animus:say", spoken));
    }

    private static Line moveto(List<String> rest, Words words) {
        List<String> parts;
        if (!rest.isEmpty()) parts = List.of(String.join(":", rest).split(":", -1));
        else if (words.values.keySet().containsAll(List.of("x", "y", "z"))) parts = List.of(words.values.get("x"), words.values.get("y"), words.values.get("z"));
        else return new Review("npc moveto without all of x, y and z keeps the NPC's other coordinates, which Animus cannot read");
        if (parts.size() == 4 || words.values.containsKey("world")) return new Review("npc moveto names a world; set the Animus world by hand");
        if (parts.size() != 3) return new Review("npc moveto needs x, y and z");
        JsonObject arguments = npcTo(null);
        if (!coordinates(parts, arguments)) return new Review("npc moveto has a coordinate that is not a number");
        for (String angle : List.of("yaw", "pitch")) {
            if (!words.values.containsKey(angle)) continue;
            OptionalDouble value = number(words.values.get(angle));
            double limit = angle.equals("yaw") ? 360 : 90;
            if (value.isEmpty() || Math.abs(value.getAsDouble()) > limit) return new Review("npc moveto has a " + angle + " outside " + (int) -limit + " to " + (int) limit);
            arguments.addProperty(angle, value.getAsDouble());
        }
        return mapped(action("animus:teleport", arguments));
    }

    private static Line pathto(List<String> rest, Words words) {
        if (rest.size() != 3) return new Review("npc pathto " + String.join(" ", rest) + " walks to the clicker or where they look; animus:navigate needs a point");
        JsonObject arguments = new JsonObject();
        if (!coordinates(rest, arguments)) return new Review("npc pathto has a coordinate that is not a number");
        boolean tuned = words.flags.contains('s') || words.values.containsKey("margin");
        return new Mapped(action("animus:navigate", arguments), tuned ? Optional.of("straight-line walking and --margin are not kept") : Optional.empty());
    }

    private static Line rotate(Words words, boolean clicker) {
        String entity = words.values.get("toentity");
        if (entity != null && (entity.equals(CLICKER) || entity.equals("me"))) {
            if (entity.equals("me") && !clicker) return console("turn the NPC toward");
            return mapped(action("animus:look", arguments("target", "player")));
        }
        String towards = words.values.get("towards");
        if (entity == null && towards != null) {
            JsonObject arguments = new JsonObject();
            if (coordinates(List.of(towards.split("[,:]", -1)), arguments)) return mapped(action("animus:look", arguments));
        }
        return new Review("npc rotate turns to a set yaw, pitch or entity; Animus looks only at a point or the clicker");
    }

    private static Line animate(String animation) {
        String animus = CitizensBehaviors.ANIMATIONS.get(animation.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_'));
        return animus == null ? new Review("the player animation " + animation + " has no Animus animation") : mapped(action("animus:animate", arguments("animation", animus)));
    }

    private static Line rename(String name, String key) {
        if (name.isBlank()) return new Review("npc rename gives no name");
        if (name.contains("\"")) return new Review("the new name holds a quote, which the rename command cannot pass");
        return mapped(command("animus npc rename citizens-" + key + " \"" + name + "\""));
    }

    private static Line console(String what) { return new Review("runs from the console, where Citizens has no player to " + what); }
    private static Mapped mapped(JsonObject node) { return new Mapped(node, Optional.empty()); }

    private static JsonObject command(String command) {
        JsonObject arguments = arguments("command", command);
        arguments.addProperty("as", "console");
        return action("animus:command", arguments);
    }

    /** Teleport arguments that move the NPC, to {@code to} when given. */
    private static JsonObject npcTo(String to) {
        JsonObject arguments = arguments("target", "npc");
        if (to != null) arguments.addProperty("to", to);
        return arguments;
    }

    /** Adds x, y and z when all three are numbers. */
    private static boolean coordinates(List<String> parts, JsonObject arguments) {
        List<Double> values = new ArrayList<>();
        for (String part : parts) {
            OptionalDouble value = number(part);
            if (value.isEmpty() || Math.abs(value.getAsDouble()) > 30_000_000) return false;
            values.add(value.getAsDouble());
        }
        if (values.size() != 3) return false;
        arguments.addProperty("x", values.get(0));
        arguments.addProperty("y", values.get(1));
        arguments.addProperty("z", values.get(2));
        return true;
    }

    private static OptionalDouble number(String text) {
        if (!text.matches("[-+]?(\\d{1,9}(\\.\\d{1,9})?|\\.\\d{1,9})")) return OptionalDouble.empty();
        return OptionalDouble.of(Double.parseDouble(text));
    }

    /** Citizens' duration words: ticks as a bare number or with t, else hours, minutes and seconds such as 1m30s. */
    static OptionalLong ticks(String text) {
        if (text.matches("\\d{1,9}")) return OptionalLong.of(Long.parseLong(text));
        if (text.matches("\\d{1,9}t")) return OptionalLong.of(Long.parseLong(text.substring(0, text.length() - 1)));
        Matcher duration = DURATION.matcher(text.replace("min", "m").replace("hr", "h"));
        if (text.isEmpty() || !duration.matches()) return OptionalLong.empty();
        double seconds = (duration.group(1) == null ? 0 : Long.parseLong(duration.group(1)) * 3600.0) + (duration.group(2) == null ? 0 : Long.parseLong(duration.group(2)) * 60.0)
                + (duration.group(3) == null ? 0 : Double.parseDouble(duration.group(3)));
        return OptionalLong.of((long) Math.floor(seconds * 20));
    }

    /** A line split the way Citizens' CommandContext splits it: quoted words joined, then --name value and -abc flags taken out. */
    private record Words(List<String> args, Map<String, String> values, Set<Character> flags) {
        static Words of(String text) {
            List<String> raw = new ArrayList<>();
            for (String word : text.split(" ")) if (!word.isBlank()) raw.add(word.trim());
            List<String> words = new ArrayList<>();
            List<Boolean> quoted = new ArrayList<>();
            for (int index = 0; index < raw.size(); index++) {
                String word = raw.get(index);
                char quote = word.charAt(0);
                if (index > 0 && (quote == '"' || quote == '\'' || quote == '`')) {
                    StringBuilder joined = new StringBuilder(word.substring(1));
                    int end = index;
                    boolean closed = joined.length() > 0 && joined.charAt(joined.length() - 1) == quote;
                    while (!closed && end + 1 < raw.size()) {
                        end++;
                        joined.append(' ').append(raw.get(end));
                        closed = raw.get(end).charAt(raw.get(end).length() - 1) == quote;
                    }
                    if (closed) {
                        words.add(joined.substring(0, joined.length() - 1));
                        quoted.add(true);
                        index = end;
                        continue;
                    }
                }
                words.add(word);
                quoted.add(false);
            }
            List<String> args = new ArrayList<>();
            Map<String, String> values = new HashMap<>();
            Set<Character> flags = new HashSet<>();
            for (int index = 0; index < words.size(); index++) {
                String word = words.get(index);
                if (index > 0 && !quoted.get(index) && index + 1 < words.size() && VALUE_FLAG.matcher(word).matches()) {
                    values.put(word.substring(2).toLowerCase(Locale.ROOT), words.get(++index));
                } else if (index > 0 && !quoted.get(index) && FLAG.matcher(word).matches()) {
                    for (char flag : word.substring(1).toCharArray()) flags.add(flag);
                } else args.add(word);
            }
            return new Words(List.copyOf(args), Map.copyOf(values), Set.copyOf(flags));
        }
    }
}
