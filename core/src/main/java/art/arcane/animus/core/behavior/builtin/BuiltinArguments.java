package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.BehaviorValues;
import art.arcane.animus.core.extension.CapabilityRefusal;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.npc.NpcAttributes;
import art.arcane.animus.core.recording.Playback;
import art.arcane.animus.core.virtual.Vec3;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** The argument schemas and checks the built-in capabilities share: schema builders, validators and argument readers. */
final class BuiltinArguments {
    static final String IDENTIFIER = "[a-z0-9][a-z0-9_-]*";
    static final String RECORDING_ID = "[a-z0-9][a-z0-9_-]{0,63}";
    /** The farthest a coordinate may lie from the origin: the world border's limit. */
    static final double MAXIMUM_COORDINATE = 30_000_000;
    static final String LOCATION_FORM = "location must be x,y,z or x,y,z,world, such as 10,64,-5,world";
    private static final Pattern COORDINATE = Pattern.compile("\\s*-?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][-+]?[0-9]+)?\\s*");

    private BuiltinArguments() {}

    static String schema(String properties, String... constraints) {
        StringBuilder result = new StringBuilder("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{").append(properties).append('}');
        for (String constraint : constraints) result.append(',').append(constraint);
        return result.append('}').toString();
    }

    static String string(String name, int maximum) { return "\"" + name + "\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":" + maximum + "}"; }

    static String number(String name, double minimum, double maximum) { return "\"" + name + "\":{\"type\":\"number\",\"minimum\":" + minimum + ",\"maximum\":" + maximum + "}"; }

    static String integer(String name, long minimum, long maximum) { return "\"" + name + "\":{\"type\":\"integer\",\"minimum\":" + minimum + ",\"maximum\":" + maximum + "}"; }

    static String choice(String name, String... values) { return "\"" + name + "\":{\"enum\":[\"" + String.join("\",\"", values) + "\"]}"; }

    static String bool(String name) { return "\"" + name + "\":{\"type\":\"boolean\"}"; }

    static String required(String... names) { return "\"required\":[\"" + String.join("\",\"", names) + "\"]"; }

    static String texts(String name, int minimum, int maximum, int length) {
        return "\"" + name + "\":{\"type\":\"array\",\"minItems\":" + minimum + ",\"maxItems\":" + maximum + ",\"items\":{\"type\":\"string\",\"maxLength\":" + length + "}}";
    }

    /** A position and the world it names, as a {@code location} argument gives them; an empty world is the action's own. */
    record Place(Vec3 position, Optional<String> world) {}

    /** Location text written {@code x,y,z} or {@code x,y,z,world}, as the location facts, ray-trace and read-block give it. */
    static Optional<Place> place(String text) {
        String[] parts = text.split(",", -1);
        if (parts.length < 3 || parts.length > 4) return Optional.empty();
        double[] axes = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            if (!COORDINATE.matcher(parts[axis]).matches()) return Optional.empty();
            axes[axis] = Double.parseDouble(parts[axis].strip());
            if (!Double.isFinite(axes[axis]) || Math.abs(axes[axis]) > MAXIMUM_COORDINATE) return Optional.empty();
        }
        if (parts.length == 3) return Optional.of(new Place(new Vec3(axes[0], axes[1], axes[2]), Optional.empty()));
        String world = parts[3].strip();
        if (world.isEmpty() || world.codePoints().anyMatch(Character::isISOControl)) return Optional.empty();
        return Optional.of(new Place(new Vec3(axes[0], axes[1], axes[2]), Optional.of(world)));
    }

    /** The position {@code location} or x, y and z give, empty when neither is given. Arguments are checked before a run reads them. */
    static Optional<Place> position(JsonObject arguments) {
        if (arguments.has("location"))
            return Optional.of(place(arguments.get("location").getAsString()).orElseThrow(() -> new CapabilityRefusal(Outcome.Status.INVALID, "location-syntax", LOCATION_FORM)));
        if (!arguments.has("x")) return Optional.empty();
        return Optional.of(new Place(new Vec3(arguments.get("x").getAsDouble(), arguments.get("y").getAsDouble(), arguments.get("z").getAsDouble()), Optional.empty()));
    }

    /** A {@code location} parses, stands instead of x, y and z, and names its world only when no {@code world} argument does. */
    static void checkLocation(JsonObject arguments, List<Diagnostic> problems) {
        if (!arguments.has("location")) return;
        Optional<Place> place = place(arguments.get("location").getAsString());
        if (place.isEmpty()) problems.add(problem("$.location", "location-syntax", LOCATION_FORM));
        if (arguments.has("x") || arguments.has("y") || arguments.has("z"))
            problems.add(problem("$.location", "location-and-coordinates", "give location or x, y and z, not both"));
        if (arguments.has("world") && place.flatMap(Place::world).isPresent())
            problems.add(problem("$.world", "location-names-world", "the location already names its world"));
    }

    static void checkTeleport(JsonObject arguments, List<Diagnostic> problems) {
        checkPlayer(arguments, problems);
        checkLocation(arguments, problems);
        if (arguments.has("anchor")) {
            matches(arguments, "anchor", art.arcane.animus.core.npc.NpcAnchors.NAME, problems);
            if (!text(arguments, "target").orElse("player").equals("npc"))
                problems.add(problem("$.anchor", "anchor-needs-npc", "an anchor is a place of the NPC's; set target to npc"));
        }
        String target = text(arguments, "target").orElse("player");
        if (arguments.has("to") && arguments.get("to").getAsString().equals(target))
            problems.add(problem("$.to", "teleport-to-self", "to names the target itself"));
        if (arguments.has("to") && (arguments.has("world") || arguments.has("yaw") || arguments.has("pitch")))
            problems.add(problem("$.to", "teleport-destination", "world, yaw and pitch belong to an x, y, z or location destination"));
        if (arguments.has("world")) matches(arguments, "world", "[A-Za-z0-9_./:-]{1,64}", problems);
    }

    /** A flag value is any JSON value within the behavior value bounds: lists, maps and text up to 4096 bytes. */
    static void checkFlagValue(JsonObject arguments, List<Diagnostic> problems) {
        matches(arguments, "name", IDENTIFIER, problems);
        if (!arguments.has("value") || arguments.get("value").isJsonNull()) return;
        try { BehaviorValues.read(arguments.get("value")); }
        catch (IllegalArgumentException | ArithmeticException invalid) { problems.add(problem("$.value", "flag-value-unsupported", "Flag value is outside the value bounds: " + invalid.getMessage())); }
    }

    static void checkSetFlag(JsonObject arguments, List<Diagnostic> problems) {
        checkFlagValue(arguments, problems);
        if (arguments.has("clear") && (arguments.has("value") || arguments.has("expireSeconds")))
            problems.add(problem("$.clear", "flag-clear", "clear unsets the flag and cannot be combined with value or expireSeconds"));
    }

    static void checkChoices(JsonObject arguments, List<Diagnostic> problems) {
        plainText(arguments, "id", problems);
        plainText(arguments, "prompt", problems);
        Set<String> seen = new HashSet<>();
        for (JsonElement option : arguments.getAsJsonArray("options")) {
            JsonObject entry = option.getAsJsonObject();
            String id = entry.get("id").getAsString();
            if (!id.matches(IDENTIFIER)) problems.add(problem("$.options", "choice-option-id", "Option ids use lowercase letters, digits, _ or -"));
            if (!seen.add(id)) problems.add(problem("$.options", "choice-option-duplicate", "Option ids must be unique"));
            plainText(entry, "text", problems);
            if (entry.has("color") && RichText.colourCode(entry.get("color").getAsString()).isEmpty())
                problems.add(problem("$.options", "choice-option-color", "color is a colour name such as green, a code such as a, or #RRGGBB"));
            if (entry.has("style") && !DialogueFormat.STYLES.contains(entry.get("style").getAsString()))
                problems.add(problem("$.options", "choice-option-style", "style is bold, italic, underlined or plain"));
            if (entry.has("hover")) {
                String hover = entry.get("hover").getAsString();
                if (hover.isBlank() || hover.codePoints().anyMatch((int glyph) -> glyph != '\n' && Character.isISOControl(glyph)))
                    problems.add(problem("$.options", "argument-control-characters", "hover must not be blank or contain control characters other than new lines"));
            }
        }
    }

    static void checkCommand(JsonObject arguments, List<Diagnostic> problems) {
        plainText(arguments, "command", problems);
        checkPlayer(arguments, problems);
        if (arguments.get("command").getAsString().startsWith("/"))
            problems.add(problem("$.command", "command-leading-slash", "Write the command without a leading slash"));
        boolean granted = arguments.has("permissions");
        if (granted) for (JsonElement node : arguments.getAsJsonArray("permissions"))
            if (!node.getAsString().matches(art.arcane.animus.core.behavior.trigger.TriggerCompiler.PERMISSION))
                problems.add(problem("$.permissions", "argument-syntax", "permissions are permission nodes such as essentials.warp"));
        boolean player = text(arguments, "as").orElse("console").equals("player");
        if (flag(arguments, "asOperator", false) && !player)
            problems.add(problem("$.asOperator", "operator-needs-player", "asOperator makes the player an operator for the command; set as to player"));
        if ((granted || arguments.has("permissionTicks")) && !player)
            problems.add(problem("$.permissions", "permissions-need-player", "permissions and permissionTicks are granted to the player; set as to player"));
        else if (arguments.has("permissionTicks") && !granted)
            problems.add(problem("$.permissionTicks", "permission-ticks-without-permissions", "permissionTicks applies only with permissions"));
    }

    /** The permission nodes an {@code animus:command} grants its player, empty without any. */
    static List<String> permissions(JsonObject arguments) {
        if (!arguments.has("permissions")) return List.of();
        List<String> nodes = new java.util.ArrayList<>();
        for (JsonElement node : arguments.getAsJsonArray("permissions")) nodes.add(node.getAsString());
        return List.copyOf(nodes);
    }

    static void checkNavigate(JsonObject arguments, List<Diagnostic> problems) {
        checkLocation(arguments, problems);
        if (arguments.has("timeoutTicks") && !flag(arguments, "wait", false))
            problems.add(problem("$.timeoutTicks", "navigation-timeout-without-wait", "timeoutTicks applies only with wait set to true"));
        if (arguments.has("anchor")) matches(arguments, "anchor", art.arcane.animus.core.npc.NpcAnchors.NAME, problems);
        if (!arguments.has("route")) return;
        matches(arguments, "route", IDENTIFIER, problems);
        if (arguments.has("mode") || arguments.has("speed"))
            problems.add(problem("$.route", "route-owns-movement", "A reusable route keeps its own mode and speed"));
    }

    static void checkPlayRecording(JsonObject arguments, List<Diagnostic> problems) {
        if (arguments.has("timeoutTicks") && !flag(arguments, "wait", false))
            problems.add(problem("$.timeoutTicks", "navigation-timeout-without-wait", "timeoutTicks applies only with wait set to true"));
        if (!arguments.get("recording").getAsString().matches(RECORDING_ID))
            problems.add(problem("$.recording", "recording-id", "recording is a recording id: lowercase letters, digits, _ or -, starting with a letter or digit"));
        if (flag(arguments, "wait", false) && !text(arguments, "playback").orElse(Playback.ONCE.key()).equals(Playback.ONCE.key()))
            problems.add(problem("$.wait", "play-recording-wait-loop", "Only a once playback finishes; wait needs playback set to once"));
    }

    static void checkAppearance(JsonObject arguments, List<Diagnostic> problems) {
        if (arguments.has("name")) plainText(arguments, "name", problems);
        if (arguments.has("skin")) matches(arguments, "skin", "[a-f0-9]{64}", problems);
        if (arguments.has("reset") && (!arguments.get("reset").getAsBoolean() || arguments.has("name") || arguments.has("skin")))
            problems.add(problem("$.reset", "appearance-reset", "reset must be true and cannot be combined with name or skin"));
    }

    static void checkAttribute(JsonObject arguments, List<Diagnostic> problems) {
        String attribute = arguments.get("attribute").getAsString();
        if (NpcAttributes.id(attribute).isEmpty()) {
            problems.add(problem("$.attribute", "attribute-unknown", NpcAttributes.problem(attribute, 0)));
            return;
        }
        if (arguments.has("reset") && (!arguments.get("reset").getAsBoolean() || arguments.has("value")))
            problems.add(problem("$.reset", "attribute-reset", "reset must be true and cannot be combined with value"));
        if (arguments.has("value") && NpcAttributes.problem(attribute, arguments.get("value").getAsDouble()) != null)
            problems.add(problem("$.value", "attribute-range", NpcAttributes.problem(attribute, arguments.get("value").getAsDouble())));
    }

    static void checkBuild(JsonObject arguments, List<Diagnostic> problems) {
        boolean start = arguments.get("operation").getAsString().equals("start");
        checkLocation(arguments, problems);
        for (String name : List.of("schematic", "x", "y", "z", "location", "rotation", "mirror", "anchor", "pattern", "materials", "excavate", "ignoreAir", "ignoreLiquids"))
            if (!start && arguments.has(name)) problems.add(problem("$." + name, "build-option", name + " is used only by the start operation"));
        if (arguments.has("x") != arguments.has("y") || arguments.has("y") != arguments.has("z"))
            problems.add(problem("$.x", "build-origin", "give all of x, y and z, or none to build where the NPC stands"));
        if (arguments.has("rotation") && arguments.get("rotation").getAsInt() % 90 != 0)
            problems.add(problem("$.rotation", "build-rotation", "rotation must be 0, 90, 180 or 270"));
        if (arguments.has("schematic")) matches(arguments, "schematic", "[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\\.(schem|nbt|schematic)", problems);
    }

    static void checkCombat(JsonObject arguments, List<Diagnostic> problems) {
        String operation = arguments.get("operation").getAsString();
        boolean targeting = operation.equals("target");
        if (!operation.equals("guard") && targeting != arguments.has("target")) {
            problems.add(problem("$.target", "combat-target", "target is required by the target operation, optional for guard, and refused otherwise"));
            return;
        }
        if (!arguments.has("target")) return;
        String target = arguments.get("target").getAsString();
        if (target.equals("player")) return;
        try { if (!UUID.fromString(target).toString().equals(target)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException invalid) { problems.add(problem("$.target", "combat-target", "target must be an entity UUID or player")); }
    }

    static void checkPlayer(JsonObject arguments, List<Diagnostic> problems) {
        if (!arguments.has("player")) return;
        String player = arguments.get("player").getAsString();
        try { if (!UUID.fromString(player).toString().equals(player)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException invalid) { problems.add(problem("$.player", "player-uuid", "player must be a lowercase player UUID")); }
    }

    static Optional<String> text(JsonObject arguments, String name) {
        return arguments.has(name) ? Optional.of(arguments.get(name).getAsString()) : Optional.empty();
    }

    static OptionalDouble decimal(JsonObject arguments, String name) {
        return arguments.has(name) ? OptionalDouble.of(arguments.get(name).getAsDouble()) : OptionalDouble.empty();
    }

    static boolean flag(JsonObject arguments, String name, boolean fallback) {
        return arguments.has(name) ? arguments.get(name).getAsBoolean() : fallback;
    }

    static void matches(JsonObject arguments, String name, String pattern, List<Diagnostic> problems) {
        if (!arguments.get(name).getAsString().matches(pattern))
            problems.add(problem("$." + name, "argument-syntax", name + " does not have the expected form"));
    }

    static void plainText(JsonObject arguments, String name, List<Diagnostic> problems) {
        String value = arguments.get(name).getAsString();
        if (value.isBlank() || value.codePoints().anyMatch(Character::isISOControl))
            problems.add(problem("$." + name, "argument-control-characters", name + " must not be blank or contain control characters"));
    }

    /** Title, subtitle and action bar text: a space alone is a deliberate blank line, so only empty text and control characters are refused. */
    static void displayText(JsonObject arguments, String name, List<Diagnostic> problems) {
        String value = arguments.get(name).getAsString();
        if (value.isEmpty() || value.codePoints().anyMatch(Character::isISOControl))
            problems.add(problem("$." + name, "argument-control-characters", name + " must not be empty or contain control characters"));
    }

    static Diagnostic problem(String field, String code, String message) {
        return new Diagnostic(Diagnostic.Severity.ERROR, code, field, message);
    }
}
