package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.text.Words;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The base of the schema-driven editors: the {@link Scope} they read and write, the path inside it a screen shows, and
 * what every field row shares: humanised names, a short description, value text, step sizes and the one confirm a
 * change to more than {@link #BULK} NPCs asks for.
 */
abstract class ValueScreen<P, S> extends ToolScreen<P, S> {
    /** Lore lines a schema description is cut into, and their width in characters. */
    static final int LINES = 2, CHARS = 40;
    /** Ticks in a second: fields counted in ticks read and take seconds. */
    static final double TICKS = 20;
    /** Root NPC fields the menus call by another word than their key. */
    private static final Map<String, String> ROOT_NAMES = Map.of("type", "Body", "groups", "Trait groups", "group", "Folder", "uuid", "UUID");
    private static final Pattern STATED = Pattern.compile("(?:^|\\s)(-?\\d+(?:\\.\\d+)?|[a-z_]+) by default");

    /**
     * Writes a change on the file lane. {@code before} is the scope's whole object as the screen read it and
     * {@code after} the same object with one change made; the result is the chat line, ending in {@code "; tx " + id}
     * when something was written.
     */
    @FunctionalInterface interface Save { String save(JsonObject before, JsonObject after) throws IOException; }

    /**
     * What an editor works on: {@code values} reads the whole object on the player's owner, {@code save} writes it, and
     * {@code affected} says how many NPCs a change at a path touches (above {@link #BULK} asks once more).
     */
    record Scope(Supplier<JsonObject> values, Save save, ToIntFunction<List<String>> affected) {}

    /** How a field is edited, from its schema. */
    enum Kind { TOGGLE, CHOICE, NUMBER, TEXT, LIST, FIELDS, MAP, ITEM, UNKNOWN }

    final Scope scope;
    final List<String> path;

    ValueScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, Scope scope, List<String> path) {
        super(toolkit, viewer, commands);
        this.scope = scope;
        this.path = List.copyOf(path);
    }

    @Override int rows() { return 6; }

    /** Every value editor writes NPC files or trait group values, which {@code animus.npc.edit} covers. */
    @Override String node() { return PermissionNodes.NPC_EDIT; }

    /** The element this screen shows, or null when it is not set. */
    JsonElement current() { return at(scope.values().get(), path); }

    /** How many NPCs a change at {@code target} touches. */
    int affected(List<String> target) { return scope.affected().applyAsInt(target); }

    /**
     * A field row: its lines, the click hints and, in Advanced, the raw key as the last line in dark grey so the file and
     * the web editor can be matched up.
     */
    void field(int slot, NpcItem icon, String name, List<String> lines, List<String> hints, String key, Handler handler) {
        List<String> lore = lore(lines, hints);
        if (!key.isEmpty() && advanced()) lore.add("&8" + key);
        button(slot, icon, title(name), lore, handler);
    }

    void field(int slot, String icon, String name, List<String> lines, List<String> hints, String key, Handler handler) {
        field(slot, new NpcItem(icon, 1, Optional.empty(), Optional.empty()), name, lines, hints, key, handler);
    }

    /** Runs a change now, or asks once more on {@code slot} when it reaches more than {@link #BULK} NPCs. */
    void change(int slot, List<String> target, String what, Runnable run) { ask(slot, what, affected(target), false, run); }

    /** Applies one edit to a copy of the scope's object and saves it on the file lane; nothing happens when it changes nothing. */
    void apply(UnaryOperator<JsonObject> edit) { apply(edit, () -> { }); }

    /** As {@link #apply(UnaryOperator)}; {@code saved} runs on the file lane once the save went through. */
    void apply(UnaryOperator<JsonObject> edit, Runnable saved) {
        JsonObject before = scope.values().get();
        JsonObject after = edit.apply(before.deepCopy());
        if (after.equals(before)) return;
        Save save = scope.save();
        toolkit.write(viewer, (ToolkitFiles files) -> {
            String line = save.save(before, after);
            saved.run();
            return line;
        });
    }

    /** Sets or, when empty, removes the value at {@code target}. */
    void set(List<String> target, Optional<JsonElement> value) { apply((JsonObject root) -> with(root, target, value)); }

    /** The field's own schema after following a {@code $ref} (except items) and taking the first of {@code oneOf}/{@code anyOf}. */
    static JsonObject shape(JsonObject node) {
        JsonObject shaped = node;
        if (node.has("$ref") && !item(node)) shaped = PropertyScreen.definition(node.get("$ref").getAsString()).orElse(node);
        for (String choice : List.of("oneOf", "anyOf"))
            if (shaped.has(choice) && shaped.get(choice).isJsonArray() && !shaped.getAsJsonArray(choice).isEmpty()) {
                JsonObject first = shaped.getAsJsonArray(choice).get(0).getAsJsonObject().deepCopy();
                if (!first.has("description") && shaped.has("description")) first.add("description", shaped.get("description"));
                shaped = first;
            }
        if (shaped != node && !shaped.has("description") && node.has("description")) {
            shaped = shaped.deepCopy();
            shaped.add("description", node.get("description"));
        }
        return shaped;
    }

    /** What one item holds; the item in the player's main hand sets them all at once. */
    static final List<String> ITEM_FIELDS = List.of("id", "count", "components", "imported");

    static boolean item(JsonObject node) { return node.has("$ref") && node.get("$ref").getAsString().endsWith("/item"); }

    static Kind kind(String key, JsonObject node) {
        if (item(node)) return Kind.ITEM;
        JsonObject shaped = shape(node);
        if (shaped.has("enum")) return Kind.CHOICE;
        String type = shaped.has("type") && shaped.get("type").isJsonPrimitive() ? shaped.get("type").getAsString() : "";
        return switch (type) {
            case "boolean" -> Kind.TOGGLE;
            case "integer", "number" -> Kind.NUMBER;
            case "string" -> Kind.TEXT;
            case "array" -> {
                if (!shaped.has("items") || !shaped.get("items").isJsonObject()) yield Kind.UNKNOWN;
                Kind items = kind(key, shaped.getAsJsonObject("items"));
                yield items == Kind.ITEM ? Kind.ITEM : Set.of(Kind.TEXT, Kind.CHOICE, Kind.NUMBER, Kind.FIELDS).contains(items) ? Kind.LIST : Kind.UNKNOWN;
            }
            case "object" -> {
                if (shaped.has("properties") && !shaped.getAsJsonObject("properties").isEmpty()) yield Kind.FIELDS;
                if (!shaped.has("additionalProperties") || !shaped.get("additionalProperties").isJsonObject()) yield Kind.UNKNOWN;
                Kind values = kind(key, shaped.getAsJsonObject("additionalProperties"));
                yield values == Kind.ITEM ? Kind.ITEM : values == Kind.NUMBER || values == Kind.FIELDS ? Kind.MAP : Kind.UNKNOWN;
            }
            // Untyped switches such as combat's enabled read only a literal true, so a toggle is the honest editor.
            default -> key.equals("enabled") ? Kind.TOGGLE : Kind.UNKNOWN;
        };
    }

    /**
     * Adds every leaf path under the object schema that these editors reach: {@code a.b} for fields, {@code a[]} for
     * list entries and {@code a.*} for map values. Items, unknown shapes and the later branches of a choice are left out.
     */
    static void covered(JsonObject object, String prefix, Set<String> out) {
        if (!object.has("properties")) return;
        for (Map.Entry<String, JsonElement> property : object.getAsJsonObject("properties").entrySet()) {
            JsonObject node = property.getValue().getAsJsonObject(), shaped = shape(node);
            String at = prefix + property.getKey();
            switch (kind(property.getKey(), node)) {
                case TOGGLE, CHOICE, NUMBER, TEXT -> out.add(at);
                case FIELDS -> covered(shaped, at + ".", out);
                case LIST -> entry(shape(shaped.getAsJsonObject("items")), at + "[]", out);
                case MAP -> entry(shape(shaped.getAsJsonObject("additionalProperties")), at + ".*", out);
                case ITEM -> { if (item(node)) for (String part : ITEM_FIELDS) out.add(at + "." + part); }
                case UNKNOWN -> { }
            }
        }
    }

    private static void entry(JsonObject shaped, String at, Set<String> out) {
        if (shaped.has("properties")) covered(shaped, at + ".", out);
        else out.add(at);
    }

    /** The element at a path of object keys and list indices, or null. */
    static JsonElement at(JsonElement root, List<String> path) {
        JsonElement here = root;
        for (String key : path) {
            if (here == null) return null;
            if (here.isJsonObject()) here = here.getAsJsonObject().get(key);
            else if (here.isJsonArray() && key.matches("\\d+") && Integer.parseInt(key) < here.getAsJsonArray().size()) here = here.getAsJsonArray().get(Integer.parseInt(key));
            else return null;
        }
        return here;
    }

    /**
     * {@code root} with the value at {@code path} set, creating missing objects on the way, or removed when empty. A
     * removal also drops the objects it leaves empty, so a cleared {@code rig.id} removes {@code rig}.
     */
    static JsonObject with(JsonObject root, List<String> path, Optional<JsonElement> value) {
        if (path.isEmpty()) return value.filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject).orElse(new JsonObject());
        List<JsonElement> chain = new ArrayList<>(List.of(root));
        JsonElement here = root;
        for (String key : path.subList(0, path.size() - 1)) {
            JsonElement next = at(here, List.of(key));
            if (next == null || next.isJsonNull()) {
                if (value.isEmpty()) return root;
                if (!here.isJsonObject()) return root;
                next = new JsonObject();
                here.getAsJsonObject().add(key, next);
            }
            here = next;
            chain.add(here);
        }
        String last = path.getLast();
        if (value.isPresent()) put(here, last, value.get());
        else {
            take(here, last);
            for (int depth = chain.size() - 1; depth > 0; depth--) {
                JsonElement node = chain.get(depth);
                if (!node.isJsonObject() || !node.getAsJsonObject().isEmpty()) break;
                take(chain.get(depth - 1), path.get(depth - 1));
            }
        }
        return root;
    }

    private static void put(JsonElement parent, String key, JsonElement value) {
        if (parent.isJsonObject()) parent.getAsJsonObject().add(key, value);
        else if (parent.isJsonArray() && key.matches("\\d+") && Integer.parseInt(key) < parent.getAsJsonArray().size()) parent.getAsJsonArray().set(Integer.parseInt(key), value);
    }

    private static void take(JsonElement parent, String key) {
        if (parent.isJsonObject()) parent.getAsJsonObject().remove(key);
        else if (parent.isJsonArray() && key.matches("\\d+") && Integer.parseInt(key) < parent.getAsJsonArray().size()) parent.getAsJsonArray().remove(Integer.parseInt(key));
    }

    static List<String> append(List<String> path, String key) {
        List<String> next = new ArrayList<>(path);
        next.add(key);
        return next;
    }

    /** A value a new field or entry starts from: the schema default, else the first option, false, or zero within bounds. */
    static Optional<JsonElement> initial(JsonObject node) {
        JsonObject shaped = shape(node);
        if (shaped.has("default")) return Optional.of(shaped.get("default").deepCopy());
        if (shaped.has("enum") && !shaped.getAsJsonArray("enum").isEmpty()) return Optional.of(shaped.getAsJsonArray("enum").get(0).deepCopy());
        String type = shaped.has("type") && shaped.get("type").isJsonPrimitive() ? shaped.get("type").getAsString() : "";
        return switch (type) {
            case "boolean" -> Optional.of(new JsonPrimitive(false));
            case "integer" -> Optional.of(new JsonPrimitive((long) Math.clamp(0, minimum(shaped), maximum(shaped))));
            case "number" -> Optional.of(new JsonPrimitive(Math.clamp(0, minimum(shaped), maximum(shaped))));
            case "object" -> Optional.of(seeded(shaped));
            case "array" -> Optional.of(new JsonArray());
            default -> Optional.empty();
        };
    }

    /** An object holding a starting value for every required field that has one. */
    static JsonObject seeded(JsonObject object) {
        JsonObject seeded = new JsonObject();
        JsonObject properties = object.has("properties") ? object.getAsJsonObject("properties") : new JsonObject();
        if (object.has("required"))
            for (JsonElement required : object.getAsJsonArray("required")) {
                String key = required.getAsString();
                if (properties.has(key)) initial(properties.getAsJsonObject(key)).ifPresent((JsonElement value) -> seeded.add(key, value));
            }
        return seeded;
    }

    /** The first required text field of an object schema, which a new entry asks for before it is added. */
    static Optional<String> firstText(JsonObject object) {
        if (!object.has("required") || !object.has("properties")) return Optional.empty();
        for (JsonElement required : object.getAsJsonArray("required")) {
            JsonElement property = object.getAsJsonObject("properties").get(required.getAsString());
            if (property != null && kind(required.getAsString(), property.getAsJsonObject()) == Kind.TEXT) return Optional.of(required.getAsString());
        }
        return Optional.empty();
    }

    static double minimum(JsonObject node) { return node.has("minimum") ? node.get("minimum").getAsDouble() : -1_000_000_000d; }
    static double maximum(JsonObject node) { return node.has("maximum") ? node.get("maximum").getAsDouble() : 1_000_000_000d; }
    static boolean integer(JsonObject node) { return node.has("type") && node.get("type").getAsString().equals("integer"); }

    /** One click's change: {@code multipleOf}, else 1 for whole numbers, else a share of the range; ten seconds' worth of a tick field. */
    static double stepOf(JsonObject node) {
        if (node.has("multipleOf")) return node.get("multipleOf").getAsDouble();
        if (integer(node)) return 1;
        double range = maximum(node) - minimum(node);
        return range <= 1 ? 0.05 : range <= 50 ? 0.1 : 1;
    }

    /** The value the schema states applies when the field is not set: its {@code default}, else "N by default" in its description. */
    static Optional<String> stated(JsonObject node) {
        if (node.has("default") && node.get("default").isJsonPrimitive()) return Optional.of(node.get("default").getAsString());
        if (!node.has("description")) return Optional.empty();
        Matcher found = STATED.matcher(node.get("description").getAsString());
        return found.find() ? Optional.of(found.group(1)) : Optional.empty();
    }

    /** The number shown and stepped from: the value, else what the schema says applies, else zero within bounds. */
    static double numberOf(JsonElement value, JsonObject node) {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) return value.getAsDouble();
        Optional<String> stated = stated(node);
        if (stated.isPresent() && stated.get().matches("-?\\d+(\\.\\d+)?")) return Double.parseDouble(stated.get());
        return Math.clamp(0, minimum(node), maximum(node));
    }

    static JsonPrimitive number(double value, JsonObject node) { return integer(node) ? new JsonPrimitive(Math.round(value)) : new JsonPrimitive(value); }

    /**
     * Whether a whole number counts ticks: its key ends in {@code Ticks} or is {@code ticks}, or its description starts
     * with the word. These fields read and take seconds.
     */
    static boolean ticks(String key, JsonObject node) {
        if (!integer(node)) return false;
        String description = node.has("description") ? node.get("description").getAsString() : "";
        return key.endsWith("Ticks") || key.equals("ticks") || description.startsWith("Ticks ");
    }

    /** {@code 2.5 seconds} for 50 ticks. */
    static String seconds(double ticks) {
        double seconds = ticks / TICKS;
        return plain(Math.round(seconds * 100) / 100d) + (seconds == 1 ? " second" : " seconds");
    }

    /** A typed number within the bounds, or empty after saying why nothing changed. Tick fields take seconds. */
    Optional<JsonPrimitive> typed(String text, JsonObject node, boolean ticks) {
        try {
            double value = Double.parseDouble(text.trim()) * (ticks ? TICKS : 1);
            if (Double.isFinite(value) && value >= minimum(node) && value <= maximum(node)) return Optional.of(number(value, node));
        } catch (NumberFormatException notNumber) { /* refused below */ }
        refuse(text.trim() + " is not a number from " + (ticks ? plain(minimum(node) / TICKS) + " to " + seconds(maximum(node)) : plain(minimum(node)) + " to " + plain(maximum(node))));
        return Optional.empty();
    }

    /** Whether a toggle that is not set reads as on: the schema default, or a description that says so. */
    static boolean onByDefault(JsonObject node) {
        if (node.has("default") && node.get("default").isJsonPrimitive()) return node.get("default").getAsBoolean();
        return node.has("description") && node.get("description").getAsString().contains("True by default");
    }

    /** A value for lore: numbers without a trailing .0, text as it is, lists and objects by size. */
    static String text(JsonElement value) {
        if (value == null || value.isJsonNull()) return "not set";
        if (value.isJsonPrimitive()) return value.getAsJsonPrimitive().isNumber() ? plain(value.getAsDouble()) : clip(value.getAsString(), CHARS);
        if (value.isJsonArray()) return value.getAsJsonArray().size() + (value.getAsJsonArray().size() == 1 ? " entry" : " entries");
        return value.getAsJsonObject().size() + (value.getAsJsonObject().size() == 1 ? " field" : " fields");
    }

    /** An option as people read it: {@code light_purple} reads {@code Light purple}, numbers stay numbers. */
    static String option(JsonElement value) {
        if (value == null || value.isJsonNull()) return "not set";
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? Words.readable(value.getAsString()) : text(value);
    }

    static String plain(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) return Long.toString((long) value);
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    static String clip(String text, int length) { return text.length() <= length ? text : text.substring(0, length - 3) + "..."; }

    /**
     * A field's name as people read it. Root NPC fields ({@code root}) use the words players see in the menus, so {@code type} reads
     * Body; a tick field loses the word, as its value reads in seconds.
     */
    static String name(String key, boolean root) {
        if (root && ROOT_NAMES.containsKey(key)) return ROOT_NAMES.get(key);
        if (key.equals("ticks")) return "Time";
        return Words.readable(key.endsWith("Ticks") ? key.substring(0, key.length() - "Ticks".length()) : key);
    }

    /** A prompt label from a field name, within what an anvil title shows. */
    static String asking(String name) { return clip(name, 20); }

    /**
     * The schema description cut into at most {@link #LINES} short grey lines; the last ends in ... when text was left
     * out, and the Help item points at the wiki for the rest. A tick field's description reads in seconds.
     */
    static List<String> describe(JsonObject node, boolean ticks) { return describe(node, ticks, LINES); }

    /**
     * As {@link #describe(JsonObject, boolean)} in at most {@code most} lines; one line keeps only the first sentence, so a
     * Simple row stays within four lines.
     */
    static List<String> describe(JsonObject node, boolean ticks, int most) {
        List<String> lines = new ArrayList<>();
        if (!node.has("description")) return lines;
        String text = node.get("description").getAsString();
        if (ticks) text = secondsIn(text);
        int sentence = text.indexOf(". ");
        if (most == 1 && sentence > 0) text = text.substring(0, sentence + 1);
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (!line.isEmpty() && line.length() + 1 + word.length() > CHARS) { lines.add(line.toString()); line.setLength(0); }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        if (lines.size() > most) {
            lines = new ArrayList<>(lines.subList(0, most));
            String last = lines.getLast();
            lines.set(most - 1, last.substring(0, Math.min(last.length(), CHARS - 3)) + "...");
        }
        lines.replaceAll((String shown) -> "&7" + shown);
        return lines;
    }

    static List<String> describe(JsonObject node) { return describe(node, false); }

    /** A tick field's description in seconds: "100 by default" reads "5 seconds by default", "Ticks between" reads "Time between". */
    static String secondsIn(String description) {
        Matcher stated = Pattern.compile("(\\d+) by default").matcher(description);
        StringBuilder out = new StringBuilder();
        while (stated.find()) stated.appendReplacement(out, seconds(Double.parseDouble(stated.group(1))) + " by default");
        stated.appendTail(out);
        return out.toString().replace("many ticks", "many seconds").replace("Ticks", "Time").replace("ticks", "time");
    }

    /** The screen title for a path: the label, then the field's name, cut to what a window shows. */
    static String label(String label, String name) { return clip(label + " / " + name, 32); }
}
