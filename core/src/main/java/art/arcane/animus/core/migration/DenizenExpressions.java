package art.arcane.animus.core.migration;

import art.arcane.animus.core.migration.DenizenArgs.Part;
import art.arcane.animus.core.migration.DenizenArgs.Tag;
import art.arcane.animus.core.migration.DenizenConverter.Scope;
import art.arcane.animus.core.migration.DenizenScripts.Container;
import art.arcane.animus.core.migration.DenizenScripts.Item;
import art.arcane.animus.core.migration.DenizenScripts.Mapping;
import art.arcane.animus.core.migration.DenizenScripts.Node;
import art.arcane.animus.core.migration.DenizenScripts.Scalar;
import art.arcane.animus.core.migration.DenizenScripts.Sequence;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Denizen tags, text and {@code if} conditions as behavior expressions, templates and condition nodes. A tag becomes an
 * expression over the run's variables ({@code d_<name>} for definitions), facts ({@code player.*}, {@code npc.*},
 * {@code server.*}), the event's {@code context.*} and the behavior functions; text becomes a template with
 * {@code {{ expression }}} holes, and colour and escape tags become formatting codes. A procedure tag or a map constant
 * cannot be written inline, so it is hoisted into steps that run before the command. The browser converter has a
 * line-for-line copy.
 */
final class DenizenExpressions {
    static final int MAXIMUM_EXPRESSION = 4096;

    /**
     * An expression and what it gives: {@code b} boolean, {@code n} number, {@code s} text, {@code l} list, {@code a} anything,
     * {@code e} one NPC's UUID and {@code N} a list of NPC UUIDs, whose attributes read NPC facts and flags, and {@code L}
     * a location as the facts write it, {@code x,y,z,world}, {@code t} a time in epoch milliseconds, {@code d} a duration
     * in seconds, {@code i} an item, a map with item, amount and optionally name, lore and enchantments, as
     * {@code animus:give-item} takes it as stack, and {@code I} a list of items.
     * {@code flag} is the call prefix of a plain flag read, which takes its default last. A read that can be unset, such
     * as a flag, a definition or a list item, gives its {@code ||} fallback as {@code before + fallback + after}.
     */
    record Expr(String text, char kind, String flag, String before, String after, String guard) {
        static Expr of(String text, char kind) { return new Expr(text, kind, null, null, null, null); }

        static Expr flagRead(String prefix) { return new Expr(prefix + "'')", 'a', prefix, prefix, ")", null); }

        static Expr orElse(String text, char kind, String before, String after) { return new Expr(text, kind, null, before, after, null); }

        /** A value that is set exactly when {@code guard} holds, which attributes read from it keep. */
        static Expr guarded(String text, char kind, String guard) { return new Expr(text, kind, null, null, null, guard); }

        Expr withGuard(String held) { return guard != null || held == null ? this : new Expr(text, kind, flag, before, after, held); }
    }

    /** A value or the reason there is none. */
    record Result<T>(T value, String reason) {
        static <T> Result<T> ok(T value) { return new Result<>(value, null); }
        static <T> Result<T> fail(String reason) { return new Result<>(null, reason); }
        boolean failed() { return reason != null; }
    }

    /** Steps a command needs before it runs: hoisted procedure calls and map constants. */
    static final class Pending {
        final List<JsonObject> steps = new ArrayList<>();
    }

    /** Text as written, with no holes, or as a template with holes. */
    record Text(String literal, String template, boolean dynamic) {
        String value() { return dynamic ? template : literal; }
    }

    sealed interface Cond permits Atom, Capability, And, Or, Not {}
    record Atom(String expression) implements Cond {}
    /** A capability condition; {@code expressions} fill its null arguments as the condition runs. */
    record Capability(String capability, JsonObject arguments, JsonObject expressions) implements Cond {
        Capability(String capability, JsonObject arguments) { this(capability, arguments, new JsonObject()); }
    }
    record And(Cond left, Cond right) implements Cond {}
    record Or(Cond left, Cond right) implements Cond {}
    record Not(Cond inner) implements Cond {}

    private record Step(Expr expr, int next) {}

    private final DenizenConverter converter;

    DenizenExpressions(DenizenConverter converter) { this.converter = converter; }

    // ---- Literals ----

    static String quote(String text) { return "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"; }

    /** A literal Denizen word: true and false as booleans, numbers as numbers, anything else as text. */
    static Expr literal(String text) {
        if (text.equals("true") || text.equals("false")) return Expr.of(text, 'b');
        if (text.matches("-?(?:0|[1-9][0-9]{0,14})(?:\\.[0-9]{1,15})?")) return Expr.of(text, 'n');
        return Expr.of(quote(text), 's');
    }

    static String numeric(Expr expr) {
        if (expr.kind() == 'n' || expr.kind() == 't' || expr.kind() == 'd') return expr.text();
        if (expr.flag() != null) return "num(" + expr.flag() + "0))";
        return "num(" + expr.text() + ")";
    }

    static String textual(Expr expr) { return expr.kind() == 's' || expr.kind() == 'L' ? expr.text() : "text(" + expr.text() + ")"; }

    static String truth(Expr expr) { return expr.kind() == 'b' ? expr.text() : "truthy(" + expr.text() + ")"; }

    // ---- Values ----

    /** An argument value: a single tag is that tag's expression, text without tags a literal, anything else joined text. */
    Result<Expr> value(String raw, Scope scope, Pending pending) {
        List<DenizenArgs.Segment> segments = DenizenArgs.segments(raw);
        if (segments.size() == 1 && segments.getFirst().tag()) {
            Tag tag = DenizenArgs.tag(segments.getFirst().text());
            Optional<String> code = literalTag(tag, false);
            if (code.isPresent()) return Result.ok(Expr.of(quote(code.get()), 's'));
            return tag(tag, scope, pending, 0);
        }
        boolean tags = false;
        for (DenizenArgs.Segment segment : segments) if (segment.tag()) tags = true;
        if (!tags) return Result.ok(literal(raw));
        List<String> pieces = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (DenizenArgs.Segment segment : segments) {
            if (!segment.tag()) { text.append(segment.text()); continue; }
            Tag tag = DenizenArgs.tag(segment.text());
            Optional<String> code = literalTag(tag, false);
            if (code.isPresent()) { text.append(code.get()); continue; }
            Result<Expr> part = tag(tag, scope, pending, 0);
            if (part.failed()) return part;
            if (!text.isEmpty()) { pieces.add(quote(text.toString())); text.setLength(0); }
            pieces.add(textual(part.value()));
        }
        if (!text.isEmpty()) pieces.add(quote(text.toString()));
        if (!pieces.getFirst().startsWith("'")) pieces.addFirst("''");
        return Result.ok(Expr.of("(" + String.join(" + ", pieces) + ")", 's'));
    }

    /** Splits Denizen list text on {@code |} outside tags. */
    static List<String> pipes(String text) {
        List<String> items = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (DenizenArgs.Segment segment : DenizenArgs.segments(text)) {
            if (segment.tag()) { current.append('<').append(segment.text()).append('>'); continue; }
            for (char at : segment.text().toCharArray()) {
                if (at == '|') { items.add(current.toString()); current.setLength(0); }
                else current.append(at);
            }
        }
        items.add(current.toString());
        return items;
    }

    // ---- Text ----

    /**
     * Text with its tags mapped. Colour and escape tags become formatting codes ({@code &}, or section signs inside
     * commands), {@code <script.data_key[...]>} its literal text, and any other tag a {@code {{ expression }}} hole. A
     * literal {@code {{} in a template is written as a hole that gives it.
     */
    Result<Text> text(String raw, Scope scope, Pending pending, boolean command, int depth) {
        StringBuilder literal = new StringBuilder(), template = new StringBuilder();
        boolean dynamic = false;
        for (DenizenArgs.Segment segment : DenizenArgs.segments(raw)) {
            if (!segment.tag()) {
                literal.append(segment.text());
                template.append(segment.text().replace("{{", "{{ '{{' }}"));
                continue;
            }
            Tag tag = DenizenArgs.tag(segment.text());
            Optional<String> code = literalTag(tag, command);
            if (code.isPresent()) {
                literal.append(code.get());
                template.append(code.get().replace("{{", "{{ '{{' }}"));
                continue;
            }
            Optional<Node> data = depth < 4 ? dataNode(tag, scope) : Optional.empty();
            if (data.isPresent() && data.get() instanceof Scalar scalar) {
                Result<Text> inner = text(scalar.text(), scope, pending, command, depth + 1);
                if (inner.failed()) return inner;
                literal.append(inner.value().literal() == null ? "" : inner.value().literal());
                template.append(inner.value().dynamic() ? inner.value().template() : inner.value().literal().replace("{{", "{{ '{{' }}"));
                dynamic |= inner.value().dynamic();
                continue;
            }
            Result<Expr> expr = tag(tag, scope, pending, depth);
            if (expr.failed()) return Result.fail(expr.reason());
            template.append("{{ ").append(expr.value().text()).append(" }}");
            dynamic = true;
        }
        return Result.ok(new Text(dynamic ? null : literal.toString(), template.toString(), dynamic));
    }

    private static final Map<String, String> ESCAPES = Map.ofEntries(
            Map.entry("&nl", "\n"), Map.entry("n", "\n"), Map.entry("&sp", " "), Map.entry("&co", ":"), Map.entry("&sc", ";"), Map.entry("&pipe", "|"),
            Map.entry("&lt", "<"), Map.entry("&gt", ">"), Map.entry("&amp", "&"), Map.entry("&lb", "["), Map.entry("&rb", "]"), Map.entry("&dq", "\""),
            Map.entry("&quo", "\""), Map.entry("&sq", "'"), Map.entry("&pc", "%"), Map.entry("&ns", "#"), Map.entry("&hash", "#"), Map.entry("&at", "@"),
            Map.entry("&dot", "."), Map.entry("&cm", ","), Map.entry("&exc", "!"), Map.entry("&ss", "§"), Map.entry("&bs", "\\"), Map.entry("&fs", "/"),
            Map.entry("&lc", "{"), Map.entry("&rc", "}"), Map.entry("&eq", "="), Map.entry("&nbsp", " "), Map.entry("&hrt", "❤"));
    private static final Map<String, String> COLORS = Map.ofEntries(
            Map.entry("black", "0"), Map.entry("dark_blue", "1"), Map.entry("dark_green", "2"), Map.entry("dark_aqua", "3"), Map.entry("dark_red", "4"),
            Map.entry("dark_purple", "5"), Map.entry("gold", "6"), Map.entry("gray", "7"), Map.entry("dark_gray", "8"), Map.entry("blue", "9"),
            Map.entry("green", "a"), Map.entry("aqua", "b"), Map.entry("red", "c"), Map.entry("light_purple", "d"), Map.entry("yellow", "e"),
            Map.entry("white", "f"), Map.entry("magic", "k"), Map.entry("bold", "l"), Map.entry("strikethrough", "m"), Map.entry("underline", "n"),
            Map.entry("italic", "o"), Map.entry("reset", "r"));

    /** A colour or escape tag as its text: {@code <&a>}, {@code <red>}, {@code <&color[#hex]>}, {@code <&co>}, {@code <&chr[2764]>}. */
    static Optional<String> literalTag(Tag tag, boolean command) {
        List<Part> parts = tag.parts();
        String base = tag.base().toLowerCase(Locale.ROOT);
        String code = command ? "§" : "&";
        if (parts.size() == 2 && base.equals("element") && tag.fallback().isEmpty() && parts.get(1).name().equalsIgnoreCase("color_gradient")) return gradient(parts, command);
        if (parts.size() != 1 || tag.fallback().isPresent()) return Optional.empty();
        if (parts.getFirst().parameter().isEmpty()) {
            if (base.matches("&[0-9a-fk-or]")) return Optional.of(code + base.charAt(1));
            if (ESCAPES.containsKey(base)) return Optional.of(ESCAPES.get(base));
            if (COLORS.containsKey(base)) return Optional.of(code + COLORS.get(base));
            return Optional.empty();
        }
        String parameter = parts.getFirst().parameter().get();
        if (base.equals("&color")) {
            if (parameter.matches("#[0-9a-fA-F]{6}")) {
                if (!command) return Optional.of("&" + parameter);
                StringBuilder hex = new StringBuilder("§x");
                for (char digit : parameter.substring(1).toLowerCase(Locale.ROOT).toCharArray()) hex.append('§').append(digit);
                return Optional.of(hex.toString());
            }
            if (COLORS.containsKey(parameter.toLowerCase(Locale.ROOT))) return Optional.of(code + COLORS.get(parameter.toLowerCase(Locale.ROOT)));
            return Optional.empty();
        }
        if (base.equals("&chr") && parameter.matches("[0-9a-fA-F]{1,6}")) {
            int point = Integer.parseInt(parameter, 16);
            if (point > 0x10FFFF || point >= 0xD800 && point <= 0xDFFF || point < 0x20) return Optional.empty();
            return Optional.of(new String(Character.toChars(point)));
        }
        return Optional.empty();
    }

    private static final Map<String, Integer> NAMED_COLORS = Map.ofEntries(
            Map.entry("white", 0xFFFFFF), Map.entry("silver", 0xC0C0C0), Map.entry("gray", 0x808080), Map.entry("black", 0x000000), Map.entry("red", 0xFF0000),
            Map.entry("maroon", 0x800000), Map.entry("yellow", 0xFFFF00), Map.entry("olive", 0x808000), Map.entry("lime", 0x00FF00), Map.entry("green", 0x008000),
            Map.entry("aqua", 0x00FFFF), Map.entry("teal", 0x008080), Map.entry("blue", 0x0000FF), Map.entry("navy", 0x000080), Map.entry("fuchsia", 0xFF00FF),
            Map.entry("purple", 0x800080), Map.entry("orange", 0xFFA500));

    /** A Denizen colour written as {@code #rrggbb}, {@code r,g,b} or a colour name, as an RGB number; empty for anything else. */
    static Optional<Integer> color(String raw) {
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("co@")) text = text.substring(3);
        if (text.matches("#[0-9a-f]{6}")) return Optional.of(Integer.parseInt(text.substring(1), 16));
        if (text.matches("[0-9]{1,3},[0-9]{1,3},[0-9]{1,3}")) {
            int rgb = 0;
            for (String channel : text.split(",")) {
                int value = Integer.parseInt(channel);
                if (value > 255) return Optional.empty();
                rgb = rgb << 8 | value;
            }
            return Optional.of(rgb);
        }
        return Optional.ofNullable(NAMED_COLORS.get(text));
    }

    /**
     * {@code <element[text].color_gradient[from=..;to=..]>} on written text: one colour per character, worked out as
     * Denizen's RGB gradient does, in single precision, so each character gets the colour Denizen gives it. The HSB
     * style, text with tags or formatting codes, and characters outside the basic plane are left to the tag mapping.
     */
    private static Optional<String> gradient(List<Part> parts, boolean command) {
        if (parts.getFirst().parameter().isEmpty() || parts.get(1).parameter().isEmpty()) return Optional.empty();
        String text = parts.getFirst().parameter().get();
        if (text.isEmpty() || text.contains("<") || text.contains("&") || text.contains("§") || text.chars().anyMatch((int at) -> Character.isSurrogate((char) at))) return Optional.empty();
        Optional<Integer> from = Optional.empty(), to = Optional.empty();
        for (String entry : parts.get(1).parameter().get().split(";", -1)) {
            int equals = entry.indexOf('=');
            if (equals <= 0) return Optional.empty();
            String key = entry.substring(0, equals).trim().toLowerCase(Locale.ROOT), value = entry.substring(equals + 1);
            switch (key) {
                case "from" -> from = color(value);
                case "to" -> to = color(value);
                case "style" -> { if (!value.trim().equalsIgnoreCase("rgb")) return Optional.empty(); }
                default -> { return Optional.empty(); }
            }
        }
        if (from.isEmpty() || to.isEmpty()) return Optional.empty();
        int length = text.length();
        float r = fromSRGB(from.get() >> 16 & 255), g = fromSRGB(from.get() >> 8 & 255), b = fromSRGB(from.get() & 255);
        float toR = fromSRGB(to.get() >> 16 & 255), toG = fromSRGB(to.get() >> 8 & 255), toB = fromSRGB(to.get() & 255);
        float x = (float) Math.pow(r + g + b, 0.43);
        float xMove = ((float) Math.pow(toR + toG + toB, 0.43) - x) / length;
        float rMove = (toR - r) / length, gMove = (toG - g) / length, bMove = (toB - b) / length;
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < length; index++) {
            float red = r, green = g, blue = b, sum = red + green + blue;
            if (sum > 0) {
                float multiplier = (float) Math.pow(x, 1f / 0.43f) / sum;
                red *= multiplier;
                green *= multiplier;
                blue *= multiplier;
            }
            String hex = Integer.toHexString((int) toSRGB(red) << 16 | (int) toSRGB(green) << 8 | (int) toSRGB(blue));
            hex = "0".repeat(Math.max(0, 6 - hex.length())) + hex;
            if (command) {
                out.append("§x");
                for (char digit : hex.toCharArray()) out.append('§').append(digit);
            } else out.append("&#").append(hex);
            out.append(text.charAt(index));
            x += xMove;
            r += rMove;
            g += gMove;
            b += bMove;
        }
        return Optional.of(out.toString());
    }

    private static float fromSRGB(float channel) {
        float value = channel / 255f;
        if (value <= 0.04045) return value / 12.92f;
        return (float) Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static float toSRGB(float value) {
        return 255.9999f * (value <= 0.0031308f ? 12.92f * value : 1.055f * (float) Math.pow(value, 1f / 2.4f) - 0.055f);
    }

    // ---- Tags ----

    /** A tag as an expression, with its {@code ||} fallback applied to a flag read. */
    Result<Expr> tag(Tag tag, Scope scope, Pending pending, int depth) {
        if (depth > 4) return Result.fail("data values nest too deep");
        Optional<Result<Expr>> data = dataFallback(tag, scope, pending, depth);
        if (data.isPresent()) return data.get();
        Optional<Result<Expr>> noted = areaFallback(tag, scope, pending);
        if (noted.isPresent()) return noted.get();
        Result<Step> base = base(tag, scope, pending, depth);
        if (base.failed()) return Result.fail(base.reason());
        Expr expr = base.value().expr();
        List<Part> parts = tag.parts();
        int index = base.value().next();
        while (index < parts.size()) {
            Part part = parts.get(index);
            if (part.name().equalsIgnoreCase("if_null") && part.parameter().isPresent() && index == parts.size() - 1) {
                Result<Expr> fallback = fallback(expr, part.parameter().get(), scope, pending);
                if (fallback.failed()) return fallback;
                expr = fallback.value();
                index++;
                continue;
            }
            Result<Step> next = attribute(expr, parts, index, scope, pending);
            if (next.failed()) return Result.fail(next.reason());
            expr = next.value().expr().withGuard(expr.guard());
            index = next.value().next();
        }
        if (tag.fallback().isPresent()) return fallback(expr, tag.fallback().get(), scope, pending);
        return Result.ok(expr);
    }

    /**
     * A data key read with a fallback ({@code ||} or a last {@code if_null}): a constant, so the fallback stands in
     * exactly when the key does not exist. Empty for any other tag.
     */
    private Optional<Result<Expr>> dataFallback(Tag tag, Scope scope, Pending pending, int depth) {
        List<Part> parts = tag.parts();
        Optional<String> fallback = tag.fallback();
        if (fallback.isEmpty() && parts.size() == 3 && parts.get(2).name().equalsIgnoreCase("if_null") && parts.get(2).parameter().isPresent()) fallback = parts.get(2).parameter();
        if (fallback.isEmpty() || !tag.base().equalsIgnoreCase("script") || parts.size() < 2 || !List.of("data_key", "yaml_key").contains(parts.get(1).name().toLowerCase(Locale.ROOT))) return Optional.empty();
        Tag bare = new Tag(parts.subList(0, 2), Optional.empty());
        if (tag.fallback().isPresent() && parts.size() != 2 || parts.get(1).parameter().filter((String key) -> key.contains("<")).isPresent()) return Optional.empty();
        if (dataNode(bare, scope).isEmpty()) return Optional.of(value(fallback.get(), scope, pending));
        return Optional.of(tag(bare, scope, pending, depth));
    }

    /**
     * A noted area read with a fallback ({@code ||} or a last {@code if_null}): the area when it is noted, which is never
     * unset, or else the fallback. Empty for any other tag.
     */
    private Optional<Result<Expr>> areaFallback(Tag tag, Scope scope, Pending pending) {
        List<Part> parts = tag.parts();
        String base = tag.base().toLowerCase(Locale.ROOT);
        if (!base.equals("cuboid") && !base.equals("ellipsoid") || parts.getFirst().parameter().filter((String name) -> !name.contains("<")).isEmpty()) return Optional.empty();
        Optional<String> fallback = tag.fallback();
        if (fallback.isEmpty() && parts.size() == 2 && parts.get(1).name().equalsIgnoreCase("if_null") && parts.get(1).parameter().isPresent()) fallback = parts.get(1).parameter();
        if (fallback.isEmpty() || parts.size() > 2 || parts.size() == 2 && tag.fallback().isPresent()) return Optional.empty();
        Optional<String> area = area(base, parts.getFirst(), scope, pending);
        if (area.isPresent()) return Optional.of(Result.ok(Expr.of(area.get(), 'a')));
        return Optional.of(value(fallback.get(), scope, pending));
    }

    private Result<Expr> fallback(Expr expr, String raw, Scope scope, Pending pending) {
        // A flag test and a constant never fail, so their fallback is never used.
        if (expr.before() == null && (expr.guard() == null || expr.guard().equals("true")) && expr.kind() == 'b' && (expr.text().equals("true") || expr.text().startsWith("has_flag(") || "true".equals(expr.guard()))) return Result.ok(Expr.of(expr.text(), 'b'));
        if (expr.before() == null && expr.guard() == null) return Result.fail("the || fallback works only on a flag, definition or list item read; other tags have no unset value");
        Result<Expr> value = value(raw, scope, pending);
        if (value.failed()) return value;
        if (expr.before() == null) return Result.ok(Expr.of("(" + expr.guard() + " ? " + expr.text() + " : " + value.value().text() + ")", 'a'));
        return Result.ok(Expr.of(expr.before() + value.value().text() + expr.after(), 'a'));
    }

    private Result<Step> base(Tag tag, Scope scope, Pending pending, int depth) {
        List<Part> parts = tag.parts();
        Part first = parts.getFirst();
        String base = first.name().toLowerCase(Locale.ROOT);
        String label = "tag " + tag.label();
        if (base.isEmpty() && first.parameter().isPresent()) return definition(first.parameter().get(), scope);
        return switch (base) {
            case "player" -> first.parameter().isPresent() ? Result.fail("tags of another player have no Animus expression") : player(parts, scope, pending);
            case "npc" -> {
                if (first.parameter().isPresent()) yield Result.fail("tags of another NPC have no Animus expression");
                if (scope.server) yield Result.fail(label + " needs an NPC; server behaviors have none");
                yield npc(parts, scope, pending);
            }
            case "server" -> server(parts, scope, pending);
            case "util" -> util(parts, scope, pending);
            case "context" -> context(parts, scope, pending);
            case "element" -> {
                if (first.parameter().isEmpty()) yield Result.fail("element without a value");
                Result<Expr> value = value(first.parameter().get(), scope, pending);
                yield value.failed() ? Result.fail(value.reason()) : Result.ok(new Step(value.value(), 1));
            }
            case "list" -> {
                if (first.parameter().isEmpty()) yield Result.ok(new Step(Expr.of("[]", 'l'), 1));
                List<String> items = new ArrayList<>();
                for (String raw : pipes(first.parameter().get())) {
                    if (raw.isEmpty()) continue;
                    Result<Expr> item = value(raw, scope, pending);
                    if (item.failed()) yield Result.fail(item.reason());
                    items.add(item.value().text());
                }
                yield Result.ok(new Step(Expr.of("[" + String.join(", ", items) + "]", 'l'), 1));
            }
            case "map" -> {
                if (first.parameter().isEmpty()) yield Result.fail("an empty map has no expression form");
                JsonObject map = new JsonObject();
                for (String entry : first.parameter().get().split(";", -1)) {
                    int equals = entry.indexOf('=');
                    if (equals <= 0 || entry.contains("<")) yield Result.fail("map values with tags have no expression form");
                    map.add(entry.substring(0, equals), DenizenFlags.scalar(entry.substring(equals + 1)));
                }
                yield hoist(map, scope, pending);
            }
            case "script" -> script(parts, scope, pending, depth);
            case "duration" -> {
                if (first.parameter().isEmpty()) yield Result.fail("duration without a value");
                java.util.OptionalLong ticks = DenizenArgs.ticks(first.parameter().get());
                if (ticks.isPresent()) yield Result.ok(new Step(Expr.of(literal(trimmed(ticks.getAsLong() / 20.0)).text(), 'd'), 1));
                Result<Expr> value = value(first.parameter().get(), scope, pending);
                yield value.failed() ? Result.fail(value.reason()) : Result.ok(new Step(Expr.of("duration_seconds(" + value.value().text() + ")", 'd'), 1));
            }
            case "item" -> item(first, scope, pending);
            case "cuboid", "ellipsoid" -> {
                Optional<String> area = area(base, first, scope, pending);
                yield area.isEmpty() ? Result.fail("no noted " + base + " named " + first.parameter().orElse("")) : Result.ok(new Step(Expr.of(area.get(), 'a'), 1));
            }
            case "proc" -> procedure(parts, scope, pending);
            case "entry" -> entry(parts, scope);
            default -> Result.fail(label + " has no Animus expression");
        };
    }

    /**
     * {@code <[name]>}: a loop's own value, or the execution variable {@code d_<name>}; dots inside walk map keys. A loop
     * value written {@code @variable} is that variable, such as the player of a fan-out.
     */
    private Result<Step> definition(String raw, Scope scope) {
        if (raw.contains("<")) return Result.fail("a computed definition name has no Animus expression");
        String[] path = raw.split("\\.", -1);
        String name = path[0].toLowerCase(Locale.ROOT);
        String override = scope.definitions.get(name);
        StringBuilder text = new StringBuilder();
        if (override != null && override.startsWith("@npc:") && path.length == 1) return Result.ok(new Step(Expr.of(override.substring(5), 'e'), 1));
        if (override != null && override.startsWith("@item:") && path.length == 1) return Result.ok(new Step(Expr.of(override.substring(6), 'i'), 1));
        if (override != null && override.startsWith("@") && path.length == 1) return Result.ok(new Step(Expr.of(override.substring(1), 'a'), 1));
        if (override != null) {
            if (path.length > 1) return Result.fail("definition " + name + " is a loop counter, not a map");
            return Result.ok(new Step(Expr.of(override, 'n'), 1));
        }
        String variable = DenizenConverter.definitionVariable(name);
        converter.declare(scope, variable, "any");
        text.append(variable);
        for (int index = 1; index < path.length; index++) {
            if (!path[index].matches("[A-Za-z0-9_]{1,64}")) return Result.fail("definition key " + path[index] + " cannot be read with a dotted name");
            text.append('.').append(path[index]);
        }
        // An unset definition reads as empty text, which is what its fallback stands in for; one map key falls back as get does.
        char kind = scope.doc.kinds.getOrDefault(name, 'a');
        if (path.length == 1) return Result.ok(new Step(Expr.orElse(variable, kind == DenizenCommands.UNCONVERTED ? 'a' : kind, "(same(text(" + variable + "), '') ? ", " : " + variable + ")"), 1));
        if (path.length == 2) return Result.ok(new Step(Expr.orElse(text.toString(), 'a', "get(" + variable + ", " + quote(path[1]) + ", ", ")"), 1));
        return Result.ok(new Step(Expr.of(text.toString(), 'a'), 1));
    }

    private Result<Step> flagRead(String scope, Part part, int next, Scope from, Pending pending) {
        if (part.parameter().isEmpty()) return Result.fail("flag tag without a name");
        Result<String> name = flagNameExpression(part.parameter().get(), from, pending);
        if (name.failed()) return Result.fail(name.reason());
        String prefix = "flag(" + quote(scope) + ", " + name.value() + ", ";
        if (part.name().equalsIgnoreCase("has_flag")) return Result.ok(new Step(Expr.of("has_flag(" + quote(scope) + ", " + name.value() + ")", 'b'), next));
        return Result.ok(new Step(Expr.flagRead(prefix), next));
    }

    /** When a flag expires, as a time in epoch milliseconds that is set only while the flag has an expiry. */
    private Result<Step> expiryRead(String prefix, Part part, int next, Scope from, Pending pending) {
        if (part.parameter().isEmpty()) return Result.fail("flag_expiration without a name");
        Result<String> name = flagNameExpression(part.parameter().get(), from, pending);
        if (name.failed()) return Result.fail(name.reason());
        String read = prefix + name.value() + ")";
        return Result.ok(new Step(Expr.guarded(read, 't', read + " > 0"), next));
    }

    /** The global flag that holds the flag {@code raw} of every block: {@code locations-<name>}. */
    Result<String> locationFlagName(String raw, Scope scope, Pending pending) {
        Result<String> name = flagNameExpression(raw, scope, pending);
        if (name.failed()) return name;
        if (name.value().startsWith("'")) return Result.ok(quote("locations-" + name.value().substring(1, name.value().length() - 1)));
        return Result.ok("('locations-' + " + name.value() + ")");
    }

    /** The blocks a location flag is set on, as a map: the global flag {@code name}, or an empty map hoisted before the command when it is unset. */
    String locationBlocks(String name, Scope scope, Pending pending) {
        String empty = constant(new JsonObject(), scope, pending);
        return "(has_flag('global', " + name + ") ? flag('global', " + name + ", 0) : " + empty + ")";
    }

    /** The block a location written x,y,z,world is in, written {@code x,y,z,world} with whole coordinates, as Denizen's simple form. */
    static String blockKey(String location) {
        List<String> coordinates = new ArrayList<>();
        for (int axis = 0; axis < 3; axis++) coordinates.add("text(floor(num(get(split(" + location + ", ','), " + axis + ", 0))))");
        return "(" + String.join(" + ',' + ", coordinates) + " + ',' + get(split(" + location + ", ','), 3, ''))";
    }

    /**
     * {@code <item[material]>} or {@code <item[material[quantity=3]]>}: an item map hoisted as a constant, or built as the
     * command runs when the material is computed. Item scripts and other properties have no value form.
     */
    private Result<Step> item(Part first, Scope scope, Pending pending) {
        if (first.parameter().isEmpty()) return Result.fail("item without a material");
        String raw = first.parameter().get();
        if (raw.contains("<")) {
            Result<Expr> named = value(raw, scope, pending);
            if (named.failed()) return Result.fail(named.reason());
            JsonObject base = new JsonObject();
            base.addProperty("amount", 1);
            String constant = constant(base, scope, pending);
            return Result.ok(new Step(Expr.of("put(" + constant + ", 'item', " + material(textual(named.value())) + ")", 'i'), 1));
        }
        java.util.regex.Matcher written = java.util.regex.Pattern.compile("(?:minecraft:)?([a-z0-9_]{1,64})(?:\\[(.*)])?").matcher(raw.toLowerCase(Locale.ROOT));
        if (!written.matches()) return Result.fail("item " + raw + " is not a material");
        if (converter.containers.containsKey(written.group(1))) return Result.fail("item scripts have no value form in Animus");
        JsonObject item = new JsonObject();
        item.addProperty("item", "minecraft:" + written.group(1));
        item.addProperty("amount", 1);
        if (written.group(2) != null) for (String property : written.group(2).split(";", -1)) {
            int equals = property.indexOf('=');
            String key = equals < 0 ? property : property.substring(0, equals);
            if (!key.equals("quantity") || !property.substring(equals + 1).matches("[0-9]{1,4}")) return Result.fail("item property " + key + " has no Animus equivalent");
            long amount = Long.parseLong(property.substring(equals + 1));
            if (amount < 1 || amount > 6400) return Result.fail("quantity must be from 1 to 6400");
            item.addProperty("amount", amount);
        }
        return Result.ok(new Step(Expr.of(constant(item, scope, pending), 'i'), 1));
    }

    /** A material read from text, as an item key: {@code minecraft:} added when it names no namespace. */
    static String material(String text) {
        String lowered = "lower(replace(" + text + ", 'i@', ''))";
        return "(contains(" + lowered + ", ':') ? " + lowered + " : 'minecraft:' + " + lowered + ")";
    }

    /**
     * {@code <cuboid[name]>} or {@code <ellipsoid[name]>}: the noted area as a constant map of its world, shape and six
     * bounds, which {@code within} tests. Empty when no area of that shape has the name.
     */
    private Optional<String> area(String shape, Part first, Scope scope, Pending pending) {
        if (first.parameter().isEmpty()) return Optional.empty();
        DenizenEvents.Note note = converter.notes.get(first.parameter().get().toLowerCase(Locale.ROOT));
        if (note == null || note.cuboid() != shape.equals("cuboid")) return Optional.empty();
        JsonObject map = new JsonObject();
        map.addProperty("world", note.world());
        map.addProperty("shape", shape);
        JsonArray bounds = new JsonArray();
        for (double value : note.bounds()) bounds.add(value == Math.rint(value) && Math.abs(value) < 1e15 ? (Number) (long) value : (Number) value);
        map.add("bounds", bounds);
        return Optional.of(constant(map, scope, pending));
    }

    /**
     * {@code <player.cursor_on[range]>}: the block the player looks at, found before the command with
     * {@code animus:ray-trace}, as a location that is unset when the player looks at no block.
     */
    private Result<Step> cursor(Part part, Scope scope, Pending pending) {
        JsonObject arguments = new JsonObject();
        if (part.parameter().isPresent()) {
            if (!part.parameter().get().matches("[0-9]{1,3}(?:\\.[0-9]{1,4})?") || Double.parseDouble(part.parameter().get()) < 1 || Double.parseDouble(part.parameter().get()) > 128)
                return Result.fail("cursor_on takes a written range of 1 to 128 blocks");
            arguments.addProperty("range", Double.parseDouble(part.parameter().get()));
        }
        String result = converter.temporary(scope);
        JsonObject trace = new JsonObject();
        trace.addProperty("type", "action");
        trace.addProperty("capability", "animus:ray-trace");
        trace.add("arguments", arguments);
        trace.addProperty("result", result);
        pending.steps.add(trace);
        String location = "get(" + result + ", 'location', '')";
        return Result.ok(new Step(Expr.guarded(location, 'L', "(len(" + location + ") > 0)"), 2));
    }

    /** A constant map set into a temporary before the command; the temporary's name. */
    String constant(JsonObject map, Scope scope, Pending pending) { return hoist(map, scope, pending).value().expr().text(); }

    /** {@code inventory.list_contents}: the items of the player's, or the held player's, inventory, read before the command. */
    private Result<Step> inventory(Optional<String> player, int next, Scope scope, Pending pending) {
        String result = converter.temporary(scope);
        JsonObject read = new JsonObject();
        read.addProperty("type", "action");
        read.addProperty("capability", "animus:read-inventory");
        JsonObject arguments = new JsonObject();
        if (player.isPresent()) {
            arguments.add("player", com.google.gson.JsonNull.INSTANCE);
            JsonObject expressions = new JsonObject();
            expressions.addProperty("/player", "get(players(" + player.get() + "), 0, '')");
            read.add("arguments", arguments);
            read.add("expressions", expressions);
        } else read.add("arguments", arguments);
        read.addProperty("result", result);
        pending.steps.add(read);
        return Result.ok(new Step(Expr.of("get(" + result + ", 'items', [])", 'I'), next));
    }

    /**
     * A value as one item map for {@code stack}: an item as it is, any other value read as the command runs, a map kept
     * and anything else taken as a material name. {@code empty} is a hoisted empty map.
     */
    String stack(Expr value, Scope scope, Pending pending) {
        if (value.kind() == 'i') return value.text();
        String empty = constant(new JsonObject(), scope, pending);
        return "(same(type_of(" + value.text() + "), 'map') ? " + value.text() + " : put(" + empty + ", 'item', " + material("text(" + value.text() + ")") + "))";
    }

    /** A number as Denizen writes it: without a trailing {@code .0}. */
    static String trimmed(double number) { return number == Math.rint(number) ? Long.toString((long) number) : Double.toString(number); }

    /**
     * A flag name as an expression: a literal Animus flag name, or written text joined with tags such as
     * {@code npc_done_<npc.id>}, whose written parts are mapped as literal names are.
     */
    Result<String> flagNameExpression(String raw, Scope scope, Pending pending) {
        if (!raw.contains("<")) {
            Optional<String> name = DenizenConverter.flagName(raw);
            return name.isEmpty() ? Result.fail("flag name " + raw + " cannot be an Animus flag name") : Result.ok(quote(name.get()));
        }
        StringBuilder mapped = new StringBuilder();
        for (DenizenArgs.Segment segment : DenizenArgs.segments(raw)) {
            if (segment.tag()) { mapped.append('<').append(segment.text()).append('>'); continue; }
            String lowered = segment.text().toLowerCase(Locale.ROOT);
            if (!lowered.matches("[a-z0-9_.]*")) return Result.fail("flag name " + raw + " cannot be an Animus flag name");
            mapped.append(lowered.replace('.', '-'));
        }
        Result<Expr> name = value(mapped.toString(), scope, pending);
        // Flag names ignore case and their dots name sub-flags, which Animus writes with dashes, as the import does.
        return name.failed() ? Result.fail(name.reason()) : Result.ok("replace(lower(" + textual(name.value()) + "), '.', '-')");
    }

    private static Optional<String> attributeName(List<Part> parts, int index) {
        return index < parts.size() && parts.get(index).parameter().isEmpty() ? Optional.of(parts.get(index).name().toLowerCase(Locale.ROOT)) : Optional.empty();
    }

    private Result<Step> player(List<Part> parts, Scope scope, Pending pending) {
        if (parts.size() == 1) return Result.ok(new Step(Expr.of("player.uuid", 's'), 1));
        Part part = parts.get(1);
        String attribute = part.name().toLowerCase(Locale.ROOT);
        if (attribute.equals("flag") || attribute.equals("has_flag")) return flagRead("player", part, 2, scope, pending);
        if (attribute.equals("has_permission")) return Result.fail("has_permission works only as an if condition");
        if (attribute.equals("flag_expiration")) return expiryRead("flag_expiry('player', ", part, 2, scope, pending);
        // A run has a player when a player started it; a command run from the console has none.
        if (attribute.equals("exists") && part.parameter().isEmpty()) return fact("has_player()", 'b', 2);
        if (attribute.equals("cursor_on")) return cursor(part, scope, pending);
        if (part.parameter().isPresent()) return Result.fail("tag player." + attribute + " has no Animus expression");
        Optional<String> next = attributeName(parts, 2);
        return switch (attribute) {
            case "name" -> fact("player.name", 's', 2);
            case "uuid" -> fact("player.uuid", 's', 2);
            case "health" -> fact("player.health", 'n', 2);
            case "health_max", "max_health" -> fact("player.max_health", 'n', 2);
            case "food_level" -> fact("player.food", 'n', 2);
            case "xp_level", "level" -> fact("player.level", 'n', 2);
            case "gamemode" -> fact("player.gamemode", 's', 2);
            case "is_op" -> fact("player.op", 'b', 2);
            case "is_sneaking" -> fact(scope.context.containsKey("sneaking") ? "context.sneaking" : "player.sneaking", 'b', 2);
            case "target" -> {
                // On a drop or swap-hands trigger, the player's target is the NPC the key was pressed at.
                if (!scope.context.containsKey(DenizenEvents.TARGET)) yield Result.fail("tag player.target has no Animus expression");
                if (next.filter("is_npc"::equals).isPresent()) yield fact("true", 'b', 3);
                Result<Step> npc = npc(parts.subList(1, parts.size()), scope, pending);
                yield npc.failed() ? npc : Result.ok(new Step(npc.value().expr(), npc.value().next() + 1));
            }
            case "is_flying" -> fact("player.flying", 'b', 2);
            case "is_online" -> fact("player.online", 'b', 2);
            case "world" -> fact("player.world", 's', next.isPresent() && next.get().equals("name") ? 3 : 2);
            case "location" -> location("player", parts, next);
            case "inventory" -> next.filter("list_contents"::equals).isPresent() ? inventory(Optional.empty(), 3, scope, pending) : Result.fail("tag player.inventory has no Animus expression");
            case "item_in_hand" -> {
                if (next.isPresent() && next.get().equals("quantity")) yield fact("player.item_in_hand_amount", 'n', 3);
                if (next.isPresent() && next.get().equals("material")) yield fact("player.item_in_hand", 's', attributeName(parts, 3).filter("name"::equals).isPresent() ? 4 : 3);
                yield fact("player.item_in_hand", 's', 2);
            }
            default -> Result.fail("tag player." + attribute + " has no Animus expression");
        };
    }

    private static Result<Step> fact(String name, char kind, int next) { return Result.ok(new Step(Expr.of(name, kind), next)); }

    private static Result<Step> location(String root, List<Part> parts, Optional<String> next) {
        if (next.isPresent() && List.of("x", "y", "z").contains(next.get())) return fact(root + "." + next.get(), 'n', 3);
        if (next.isPresent() && next.get().equals("world")) return fact(root + ".world", 's', attributeName(parts, 3).filter("name"::equals).isPresent() ? 4 : 3);
        return fact(root + ".location", 'L', 2);
    }

    private Result<Step> npc(List<Part> parts, Scope scope, Pending pending) {
        if (parts.size() == 1) return Result.ok(new Step(Expr.of("npc.uuid", 's'), 1));
        Part part = parts.get(1);
        String attribute = part.name().toLowerCase(Locale.ROOT);
        if (attribute.equals("flag") || attribute.equals("has_flag")) return flagRead("npc", part, 2, scope, pending);
        if (attribute.equals("flag_expiration")) return expiryRead("flag_expiry('npc', ", part, 2, scope, pending);
        if (part.parameter().isPresent()) return Result.fail("tag npc." + attribute + " has no Animus expression");
        Optional<String> next = attributeName(parts, 2);
        return switch (attribute) {
            case "name" -> fact("npc.name", 's', 2);
            case "id" -> fact("npc.citizens_id", 'a', 2);
            case "uuid" -> fact("npc.uuid", 's', 2);
            case "is_spawned" -> fact("npc.spawned", 'b', 2);
            case "health" -> fact("npc.health", 'n', 2);
            case "world" -> fact("npc.world", 's', next.isPresent() && next.get().equals("name") ? 3 : 2);
            case "location" -> location("npc", parts, next);
            default -> Result.fail("tag npc." + attribute + " has no Animus expression");
        };
    }

    private Result<Step> server(List<Part> parts, Scope scope, Pending pending) {
        if (parts.size() == 1) return Result.fail("tag server has no Animus expression");
        Part part = parts.get(1);
        String attribute = part.name().toLowerCase(Locale.ROOT);
        if (attribute.equals("flag") || attribute.equals("has_flag")) return flagRead("global", part, 2, scope, pending);
        if (attribute.equals("flag_expiration")) return expiryRead("flag_expiry('global', ", part, 2, scope, pending);
        if (attribute.equals("match_player") && part.parameter().isPresent()) {
            Result<Expr> name = value(part.parameter().get(), scope, pending);
            if (name.failed()) return Result.fail(name.reason());
            String found = "player_named(" + textual(name.value()) + ")";
            // No player of that name is no value, which a fallback stands in for.
            return Result.ok(new Step(Expr.guarded(found, 'a', "(len(" + found + ") > 0)"), 2));
        }
        Optional<String> next = attributeName(parts, 2);
        return switch (attribute) {
            case "online_players" -> next.isPresent() && (next.get().equals("size") || next.get().equals("count"))
                    ? fact("server.online_players", 'n', 3) : fact("server.players", 'l', 2);
            case "npcs" -> fact("npcs()", 'N', 2);
            case "spawned_npcs" -> filtered(Expr.of("npcs()", 'N'), (String npc) -> "get(npc_info(" + npc + "), 'spawned', false)", 2, scope, pending);
            case "npcs_flagged", "online_players_flagged" -> {
                if (part.parameter().isEmpty()) yield Result.fail("tag server." + attribute + " needs a flag name");
                Result<String> flag = flagNameExpression(part.parameter().get(), scope, pending);
                if (flag.failed()) yield Result.fail(flag.reason());
                boolean npcs = attribute.equals("npcs_flagged");
                yield filtered(npcs ? Expr.of("npcs()", 'N') : Expr.of("server.players", 'l'), (String item) -> (npcs ? "npc_has_flag(" : "player_has_flag(") + item + ", " + flag.value() + ")", 2, scope, pending);
            }
            case "npcs_assigned" -> {
                if (part.parameter().isEmpty() || part.parameter().get().contains("<")) yield Result.fail("tag server.npcs_assigned needs a literal assignment name");
                Optional<List<String>> behaviors = converter.assignmentBehaviors(part.parameter().get());
                if (behaviors.isEmpty()) yield Result.fail("no assignment script named " + part.parameter().get());
                yield filtered(Expr.of("npcs()", 'N'), (String npc) -> {
                    List<String> tests = new ArrayList<>();
                    for (String behavior : behaviors.get()) tests.add("contains(get(npc_info(" + npc + "), 'behaviors', []), " + quote(behavior) + ")");
                    return tests.size() == 1 ? tests.getFirst() : "(" + String.join(" || ", tests) + ")";
                }, 2, scope, pending);
            }
            case "max_players" -> fact("server.max_players", 'n', 2);
            case "current_time_millis" -> fact("server.time_millis", 'n', 2);
            case "current_tick" -> fact("server.tick", 'n', 2);
            default -> Result.fail("tag server." + attribute + " has no Animus expression");
        };
    }

    private Result<Step> util(List<Part> parts, Scope scope, Pending pending) {
        Optional<String> first = attributeName(parts, 1);
        if (parts.size() < 2) return Result.fail("tag util has no Animus expression");
        String attribute = parts.get(1).name().toLowerCase(Locale.ROOT);
        if (attribute.equals("random") && parts.size() >= 3) {
            Part kind = parts.get(2);
            String which = kind.name().toLowerCase(Locale.ROOT);
            if ((which.equals("int") || which.equals("decimal")) && kind.parameter().isPresent()) {
                if (parts.size() < 4 || !parts.get(3).name().equalsIgnoreCase("to") || parts.get(3).parameter().isEmpty()) return Result.fail("util.random." + which + " needs [min].to[max]");
                Result<Expr> low = value(kind.parameter().get(), scope, pending);
                if (low.failed()) return Result.fail(low.reason());
                Result<Expr> high = value(parts.get(3).parameter().get(), scope, pending);
                if (high.failed()) return Result.fail(high.reason());
                if (which.equals("int")) return Result.ok(new Step(Expr.of("random(" + numeric(low.value()) + ", " + numeric(high.value()) + ")", 'n'), 4));
                return Result.ok(new Step(Expr.of("(" + numeric(low.value()) + " + random_decimal() * (" + numeric(high.value()) + " - " + numeric(low.value()) + "))", 'n'), 4));
            }
            if (which.equals("decimal") && kind.parameter().isEmpty()) return fact("random_decimal()", 'n', 3);
            if (which.equals("boolean")) return fact("chance(50)", 'b', 3);
            return Result.fail("tag util.random." + which + " has no Animus expression");
        }
        if (attribute.equals("random_chance") && parts.get(1).parameter().isPresent()) {
            Result<Expr> percent = value(parts.get(1).parameter().get(), scope, pending);
            if (percent.failed()) return Result.fail(percent.reason());
            return Result.ok(new Step(Expr.of("chance(" + numeric(percent.value()) + ")", 'b'), 2));
        }
        if (first.isPresent()) {
            switch (first.get()) {
                case "random_decimal" -> { return fact("random_decimal()", 'n', 2); }
                case "time_now" -> { return fact("now()", 't', 2); }
                case "random_boolean" -> { return fact("chance(50)", 'b', 2); }
                case "current_time_millis" -> { return fact("server.time_millis", 'n', 2); }
                case "pi" -> { return fact("3.141592653589793", 'n', 2); }
                default -> { }
            }
        }
        return Result.fail("tag util." + attribute + " has no Animus expression");
    }

    /** The context key an inventory opening or closing event lists, meaning it passes the inventory's contents and location. */
    static final String INVENTORY_CONTENTS = "@contents";
    private static final Map<String, String> CONTEXT_KINDS = Map.of("args", "l", "first", "b", "damage", "n", "force", "n");

    private Result<Step> context(List<Part> parts, Scope scope, Pending pending) {
        if (parts.size() < 2 || parts.get(1).parameter().isPresent()) return Result.fail("tag context has no Animus expression");
        String key = parts.get(1).name().toLowerCase(Locale.ROOT);
        String mapped = scope.context.get(key);
        if (mapped == null) return Result.fail("context." + key + " is not passed by the Animus trigger for this event");
        // The clicked or hit entity of an NPC event is always the NPC, and Denizen writes the hand as mainhand or offhand.
        if (mapped.equals("npc")) {
            if (attributeName(parts, 2).filter("is_npc"::equals).isPresent()) return fact("true", 'b', 3);
            Result<Step> npc = npc(parts.subList(1, parts.size()), scope, pending);
            return npc.failed() ? npc : Result.ok(new Step(npc.value().expr(), npc.value().next() + 1));
        }
        if (key.equals("hand") && mapped.equals("hand")) return fact("replace(context.hand, '_', '')", 's', 2);
        Optional<String> attribute = attributeName(parts, 2);
        // The shooter of a projectile that hit an NPC: its UUID, type key and name, empty for a dispenser's shot.
        if (mapped.equals(DenizenEvents.SHOOTER)) {
            if (attribute.isEmpty()) return fact("context.entity", 's', 2);
            return switch (attribute.get()) {
                case "uuid" -> fact("context.entity", 's', 3);
                case "is_npc" -> fact("false", 'b', 3);
                case "is_player" -> fact("same(context.entity_type, 'minecraft:player')", 'b', 3);
                case "entity_type" -> fact("replace(context.entity_type, 'minecraft:', '')", 's', 3);
                case "name" -> fact("(same(context.entity_name, '') ? replace(context.entity_type, 'minecraft:', '') : context.entity_name)", 's', 3);
                default -> Result.fail("tag attribute " + attribute.get() + " of context." + key + " has no Animus expression");
            };
        }
        // A world event passes keys and coordinates rather than Denizen objects; the common attributes read those.
        switch (mapped) {
            case "inventory_type" -> {
                if (attribute.filter("inventory_type"::equals).isPresent()) return fact("upper(context.inventory_type)", 's', 3);
                // Opening and closing pass what the inventory holds and the block that holds it.
                if (scope.context.containsKey(INVENTORY_CONTENTS) && attribute.filter("list_contents"::equals).isPresent()) return fact("context.inventory_items", 'I', 3);
                if (scope.context.containsKey(INVENTORY_CONTENTS) && attribute.filter("location"::equals).isPresent()) return fact("context.inventory_location", 'L', 3);
                return Result.fail("context.inventory is an inventory; Animus passes its type, and on opening and closing its contents and location");
            }
            case "location" -> {
                if (attribute.isEmpty() || attribute.get().equals("distance")) return Result.ok(new Step(Expr.orElse("context.location", 'L', "(same(text(context.location), '') ? ", " : context.location)"), 2));
                return switch (attribute.get()) {
                    case "x", "y", "z", "block_x", "block_y", "block_z" -> fact("context." + attribute.get().substring(attribute.get().length() - 1), 'n', 3);
                    case "world" -> fact("context.world", 's', attributeName(parts, 3).filter("name"::equals).isPresent() ? 4 : 3);
                    // Every event that passes a location is a block or projectile hit event, which passes the block too.
                    case "material" -> fact("replace(context.block, 'minecraft:', '')", 's', attributeName(parts, 3).filter("name"::equals).isPresent() ? 4 : 3);
                    default -> Result.fail("tag attribute " + attribute.get() + " of context.location has no Animus expression");
                };
            }
            case "item", "block", "damager", "bow" -> {
                if (attribute.filter((String name) -> name.equals("material") || name.equals("name") || name.equals("entity_type")).isPresent())
                    return fact("replace(context." + mapped + ", 'minecraft:', '')", 's', attribute.get().equals("material") && attributeName(parts, 3).filter("name"::equals).isPresent() ? 4 : 3);
                return fact("context." + mapped, 's', 2);
            }
            case "entity", "projectile", "shooter" -> {
                if (attribute.isEmpty()) return fact("context." + mapped, 's', 2);
                return switch (attribute.get()) {
                    case "is_npc" -> fact("false", 'b', 3);
                    case "is_player" -> fact("same(context." + mapped + ", 'minecraft:player')", 'b', 3);
                    case "uuid" -> fact("context." + mapped + "_uuid", 's', 3);
                    case "entity_type", "name" -> fact("replace(context." + mapped + ", 'minecraft:', '')", 's', 3);
                    default -> Result.fail("tag attribute " + attribute.get() + " of context." + key + " has no Animus expression");
                };
            }
            case "slot" -> { return fact("(context.slot + 1)", 'n', 2); }
            case "click" -> { return fact(key.equals("click_type") ? "(upper(context.click) + '_CLICK_BLOCK')" : "upper(context.click)", 's', 2); }
            default -> { }
        }
        int next = 2;
        if ((key.equals("origin_world") || key.equals("destination_world")) && attribute.filter("name"::equals).isPresent()) next = 3;
        String kind = CONTEXT_KINDS.getOrDefault(mapped, "s");
        // An event value that is empty text stands for one the event did not have, which a fallback stands in for.
        return Result.ok(new Step(Expr.orElse("context." + mapped, kind.charAt(0), "(same(text(context." + mapped + "), '') ? ", " : context." + mapped + ")"), next));
    }

    // ---- Data scripts ----

    /** The data node a {@code <script[name].data_key[path]>} tag reads, if it is one. */
    Optional<Node> dataNode(Tag tag, Scope scope) {
        List<Part> parts = tag.parts();
        if (!tag.base().equalsIgnoreCase("script") || parts.size() != 2 || tag.fallback().isPresent()) return Optional.empty();
        Container target = scope.container;
        if (parts.getFirst().parameter().isPresent()) {
            target = converter.containers.get(parts.getFirst().parameter().get().toLowerCase(Locale.ROOT));
            if (target == null) return Optional.empty();
        }
        if (!List.of("data_key", "yaml_key").contains(parts.get(1).name().toLowerCase(Locale.ROOT)) || parts.get(1).parameter().isEmpty()) return Optional.empty();
        Node node = target.body();
        for (String key : parts.get(1).parameter().get().split("\\.", -1)) {
            if (!(node instanceof Mapping mapping)) return Optional.empty();
            Optional<Node> child = mapping.get(key.toLowerCase(Locale.ROOT));
            if (child.isEmpty()) return Optional.empty();
            node = child.get();
        }
        return Optional.of(node);
    }

    private Result<Step> script(List<Part> parts, Scope scope, Pending pending, int depth) {
        Tag tag = new Tag(parts.subList(0, Math.min(parts.size(), 2)), Optional.empty());
        if (parts.size() >= 2 && parts.get(1).name().equalsIgnoreCase("name") && parts.get(1).parameter().isEmpty()) {
            Container target = scope.container;
            if (parts.getFirst().parameter().isPresent()) target = converter.containers.get(parts.getFirst().parameter().get().toLowerCase(Locale.ROOT));
            if (target == null) return Result.fail("no script named " + parts.getFirst().parameter().orElse(""));
            return Result.ok(new Step(Expr.of(quote(target.name()), 's'), 2));
        }
        Optional<Node> node = dataNode(tag, scope);
        if (node.isEmpty()) {
            Optional<Result<Step>> computed = computedDataKey(parts, scope, pending);
            if (computed.isPresent()) return computed.get();
            return Result.fail("tag script." + (parts.size() > 1 ? parts.get(1).name().toLowerCase(Locale.ROOT) : "") + " reads no data key that exists");
        }
        if (node.get() instanceof Scalar scalar) {
            if (!scalar.text().contains("<")) return Result.ok(new Step(literal(scalar.text()), 2));
            Result<Expr> value = value(scalar.text(), scope, pending);
            return value.failed() ? Result.fail(value.reason()) : Result.ok(new Step(value.value(), 2));
        }
        if (node.get() instanceof Sequence sequence) {
            List<String> items = new ArrayList<>();
            for (Item item : sequence.items()) {
                if (item.text().contains("<")) {
                    Result<Expr> value = value(item.text(), scope, pending);
                    if (value.failed()) return Result.fail(value.reason());
                    items.add(value.value().text());
                } else items.add(literal(item.text()).text());
            }
            return Result.ok(new Step(Expr.of("[" + String.join(", ", items) + "]", 'l'), 2));
        }
        Optional<JsonElement> json = data(node.get(), 0);
        if (json.isEmpty() || !json.get().isJsonObject()) return Result.fail("data maps with tags or deep nesting have no constant form");
        Result<Step> hoisted = hoist(json.get().getAsJsonObject(), scope, pending);
        return hoisted.failed() ? hoisted : Result.ok(new Step(hoisted.value().expr(), 2));
    }

    /**
     * {@code <script[name].data_key[path.<[key]>]>}: the map the written path names, hoisted once as a constant with its
     * keys lowercased, read at the computed last key ignoring case. Empty when the key is not computed or the map has tags.
     */
    private Optional<Result<Step>> computedDataKey(List<Part> parts, Scope scope, Pending pending) {
        if (parts.size() < 2 || !List.of("data_key", "yaml_key").contains(parts.get(1).name().toLowerCase(Locale.ROOT)) || parts.get(1).parameter().isEmpty()) return Optional.empty();
        String path = parts.get(1).parameter().get();
        if (!path.contains("<")) return Optional.empty();
        Container target = scope.container;
        if (parts.getFirst().parameter().isPresent()) {
            target = converter.containers.get(parts.getFirst().parameter().get().toLowerCase(Locale.ROOT));
            if (target == null) return Optional.empty();
        }
        int dot = -1, depth = 0;
        for (int index = 0; index < path.length(); index++) {
            char at = path.charAt(index);
            if (at == '<') depth++;
            else if (at == '>' && depth > 0) depth--;
            else if (at == '.' && depth == 0) dot = index;
        }
        String written = dot < 0 ? "" : path.substring(0, dot), key = path.substring(dot + 1);
        if (written.contains("<")) return Optional.empty();
        Node node = target.body();
        if (!written.isEmpty()) for (String part : written.split("\\.", -1)) {
            if (!(node instanceof Mapping mapping)) return Optional.empty();
            Optional<Node> child = mapping.get(part.toLowerCase(Locale.ROOT));
            if (child.isEmpty()) return Optional.empty();
            node = child.get();
        }
        if (!(node instanceof Mapping mapping)) return Optional.empty();
        JsonObject map = new JsonObject();
        for (DenizenScripts.Entry entry : mapping.entries()) {
            if (written.isEmpty() && entry.key().equalsIgnoreCase("type")) continue;
            Optional<JsonElement> value = data(entry.value(), 1);
            if (value.isEmpty()) return Optional.empty();
            map.add(entry.key().toLowerCase(Locale.ROOT), value.get());
        }
        if (map.isEmpty()) return Optional.empty();
        Result<Expr> computed = value(key, scope, pending);
        if (computed.failed()) return Optional.of(Result.fail(computed.reason()));
        Result<Step> hoisted = hoist(map, scope, pending);
        if (hoisted.failed()) return Optional.of(hoisted);
        String read = "lower(" + textual(computed.value()) + ")", table = hoisted.value().expr().text();
        return Optional.of(Result.ok(new Step(Expr.orElse("get(" + table + ", " + read + ")", 'a', "get(" + table + ", " + read + ", ", ")"), 2)));
    }

    /** A data node as constant JSON: scalars by the typed rules, lists as arrays, keys as objects. Empty when a value holds a tag. */
    static Optional<JsonElement> data(Node node, int depth) {
        if (depth > DenizenFlags.MAXIMUM_NESTING) return Optional.empty();
        if (node instanceof Scalar scalar) return scalar.text().contains("<") ? Optional.empty() : Optional.of(DenizenFlags.scalar(scalar.text()));
        if (node instanceof Sequence sequence) {
            JsonArray array = new JsonArray();
            for (Item item : sequence.items()) {
                if (item.text().contains("<") || item.children().isPresent()) return Optional.empty();
                array.add(DenizenFlags.scalar(item.text()));
            }
            return Optional.of(array);
        }
        JsonObject object = new JsonObject();
        for (DenizenScripts.Entry entry : ((Mapping) node).entries()) {
            Optional<JsonElement> value = data(entry.value(), depth + 1);
            if (value.isEmpty()) return Optional.empty();
            object.add(entry.key(), value.get());
        }
        return Optional.of(object);
    }

    /**
     * The items of a list a test holds for, gathered by a loop before the command into a temporary: a list of NPCs
     * stays one. {@code test} writes the condition on the variable holding each item.
     */
    private Result<Step> filtered(Expr list, java.util.function.Function<String, String> test, int next, Scope scope, Pending pending) {
        String out = converter.temporary(scope), item = converter.temporary(scope);
        pending.steps.add(set(out, new JsonArray()));
        JsonObject keep = new JsonObject();
        keep.addProperty("type", "condition");
        keep.addProperty("expression", test.apply(item));
        keep.add("then", set(out, operand("append(" + out + ", " + item + ")")));
        pending.steps.add(each(list, item, List.of(keep)));
        return Result.ok(new Step(Expr.of(out, list.kind() == 'N' ? 'N' : 'l'), next));
    }

    /**
     * {@code list.filter_tag[test]}, {@code list.filter[attribute]}, {@code list.parse_tag[value]} and
     * {@code list.parse[attribute]}: the test or value read for each item as {@code <[filter_value]>} or
     * {@code <[parse_value]>}, by a loop before the command. A list of NPCs gives NPC items.
     */
    private Result<Step> perItem(Expr list, String name, String written, int next, Scope scope, Pending pending) {
        boolean filter = name.startsWith("filter");
        String out = converter.temporary(scope), item = converter.temporary(scope), word = filter ? "filter_value" : "parse_value";
        TreeMap<String, String> definitions = new TreeMap<>(scope.definitions);
        definitions.put(word, (list.kind() == 'N' ? "@npc:" : "@") + item);
        Scope inner = scope.loop(definitions, scope.loops);
        Pending body = new Pending();
        Result<Expr> value = value(name.endsWith("_tag") ? written : "<[" + word + "]." + written + ">", inner, body);
        if (value.failed()) return Result.fail(value.reason());
        pending.steps.add(set(out, new JsonArray()));
        List<JsonObject> steps = new ArrayList<>(body.steps);
        if (filter) {
            JsonObject keep = new JsonObject();
            keep.addProperty("type", "condition");
            keep.addProperty("expression", truth(value.value()));
            keep.add("then", set(out, operand("append(" + out + ", " + item + ")")));
            steps.add(keep);
        } else steps.add(set(out, operand("append(" + out + ", " + value.value().text() + ")")));
        pending.steps.add(each(list, item, steps));
        return Result.ok(new Step(Expr.of(out, filter && list.kind() == 'N' ? 'N' : 'l'), next));
    }

    private static JsonObject set(String variable, JsonElement value) {
        JsonObject node = new JsonObject();
        node.addProperty("type", "set");
        node.addProperty("variable", variable);
        node.add("value", value);
        return node;
    }

    private static JsonObject operand(String expression) {
        JsonObject operand = new JsonObject();
        operand.addProperty("expression", expression);
        return operand;
    }

    /** A loop over every item of a list, up to the behavior limit of 1024. */
    private static JsonObject each(Expr list, String item, List<JsonObject> steps) {
        JsonObject loop = new JsonObject();
        loop.addProperty("type", "foreach");
        loop.add("list", operand(list.text()));
        loop.addProperty("as", item);
        loop.addProperty("maximum", 1024);
        loop.add("body", DenizenConverter.sequence(steps));
        return loop;
    }

    /** A constant map set into a temporary variable before the command, since expressions have no map literal. */
    private Result<Step> hoist(JsonObject map, Scope scope, Pending pending) {
        if (map.size() == 1 && (map.has("variable") || map.has("expression") || map.has("template"))) return Result.fail("a one-key map named variable, expression or template reads as an operand");
        String temporary = converter.temporary(scope);
        JsonObject set = new JsonObject();
        set.addProperty("type", "set");
        set.addProperty("variable", temporary);
        set.add("value", map);
        pending.steps.add(set);
        return Result.ok(new Step(Expr.of(temporary, 'a'), 1));
    }

    private Result<Step> procedure(List<Part> parts, Scope scope, Pending pending) {
        if (parts.getFirst().parameter().isEmpty() || parts.getFirst().parameter().get().contains("<")) return Result.fail("a computed procedure name has no Animus expression");
        Container procedure = converter.containers.get(parts.getFirst().parameter().get().toLowerCase(Locale.ROOT));
        if (procedure == null || !procedure.type().equals("procedure")) return Result.fail("no procedure script named " + parts.getFirst().parameter().get());
        List<String> values = new ArrayList<>();
        int next = 1;
        if (parts.size() > 1 && parts.get(1).name().equalsIgnoreCase("context") && parts.get(1).parameter().isPresent()) {
            values = pipes(parts.get(1).parameter().get());
            next = 2;
        }
        List<String> names = DenizenConverter.definitionNames(procedure);
        if (values.size() > names.size()) return Result.fail("procedure " + procedure.name() + " takes " + names.size() + " definitions");
        JsonObject arguments = new JsonObject();
        for (int index = 0; index < values.size(); index++) {
            Result<JsonElement> operand = operand(values.get(index), scope, pending);
            if (operand.failed()) return Result.fail(operand.reason());
            String variable = DenizenConverter.definitionVariable(names.get(index));
            converter.declare(scope, variable, "any");
            arguments.add(variable, operand.value());
        }
        Result<String> routine = converter.procedureRoutine(procedure, scope);
        if (routine.failed()) return Result.fail(routine.reason());
        String temporary = converter.temporary(scope);
        JsonObject call = new JsonObject();
        call.addProperty("type", "call");
        call.addProperty("routine", routine.value());
        if (!arguments.isEmpty()) call.add("arguments", arguments);
        call.addProperty("result", temporary);
        pending.steps.add(call);
        return Result.ok(new Step(Expr.of(temporary, converter.returnKind(routine.value())), next));
    }

    private Result<Step> entry(List<Part> parts, Scope scope) {
        if (parts.getFirst().parameter().isEmpty()) return Result.fail("entry without a name");
        String variable = scope.saves.get(parts.getFirst().parameter().get().toLowerCase(Locale.ROOT));
        if (variable == null) return Result.fail("entry " + parts.getFirst().parameter().get() + " was not saved by a converted run");
        if (attributeName(parts, 1).filter("created_queue"::equals).isEmpty() || attributeName(parts, 2).filter("determination"::equals).isEmpty())
            return Result.fail("only <entry[...].created_queue.determination> has an Animus expression");
        int next = 3;
        if (attributeName(parts, 3).filter("first"::equals).isPresent()) next = 4;
        else if (parts.size() > 3 && parts.get(3).name().equalsIgnoreCase("get") && parts.get(3).parameter().filter("1"::equals).isPresent()) next = 4;
        return Result.ok(new Step(Expr.of(variable, 'a'), next));
    }

    /** A value for a {@code set}, {@code call} argument or return: a literal, or an operand object holding an expression or template. */
    Result<JsonElement> operand(String raw, Scope scope, Pending pending) {
        List<DenizenArgs.Segment> segments = DenizenArgs.segments(raw);
        boolean tags = false;
        for (DenizenArgs.Segment segment : segments) if (segment.tag() && literalTag(DenizenArgs.tag(segment.text()), false).isEmpty()) tags = true;
        if (!tags) {
            Result<Text> text = text(raw, scope, pending, false, 0);
            if (text.failed()) return Result.fail(text.reason());
            return Result.ok(DenizenFlags.scalar(text.value().literal()));
        }
        JsonObject operand = new JsonObject();
        if (segments.size() == 1) {
            Result<Expr> expr = value(raw, scope, pending);
            if (expr.failed()) return Result.fail(expr.reason());
            if (expr.value().text().length() > MAXIMUM_EXPRESSION) return Result.fail("expression longer than " + MAXIMUM_EXPRESSION + " characters");
            operand.addProperty("expression", expr.value().text());
            return Result.ok(operand);
        }
        Result<Text> text = text(raw, scope, pending, false, 0);
        if (text.failed()) return Result.fail(text.reason());
        if (!text.value().dynamic()) return Result.ok(DenizenFlags.scalar(text.value().literal()));
        operand.addProperty("template", text.value().template());
        return Result.ok(operand);
    }

    // ---- Attributes ----

    /** Attributes of a value holding a player: the fact player_info reads, its value when the player is offline, and the kind. */
    private static final Map<String, String[]> PLAYER_ATTRIBUTES = Map.of("name", new String[] {"name", "''", "s"}, "is_online", new String[] {"online", "false", "b"},
            "is_op", new String[] {"op", "false", "b"}, "health", new String[] {"health", "0", "n"}, "world", new String[] {"world", "''", "s"},
            "location", new String[] {"location", "''", "L"}, "gamemode", new String[] {"gamemode", "''", "s"});

    /** Attributes of a value holding an NPC: the fact npc_info reads and its value when the NPC is unknown. */
    private static final Map<String, String[]> NPC_ATTRIBUTES = Map.of("id", new String[] {"citizens_id", "''", "a"}, "name", new String[] {"name", "''", "s"},
            "is_spawned", new String[] {"spawned", "false", "b"}, "location", new String[] {"location", "''", "L"}, "world", new String[] {"world", "''", "s"},
            "health", new String[] {"health", "0", "n"});

    private Result<Step> attribute(Expr expr, List<Part> parts, int index, Scope scope, Pending pending) {
        Part part = parts.get(index);
        String name = part.name().toLowerCase(Locale.ROOT);
        if (part.parameter().isPresent() && List.of("filter_tag", "filter", "parse_tag", "parse").contains(name) && expr.kind() != 's' && expr.kind() != 'n' && expr.kind() != 'b')
            return perItem(expr, name, part.parameter().get(), index + 1, scope, pending);
        // A value holding an NPC, such as an item of server.npcs, reads that NPC's flags and facts.
        if (expr.kind() == 'e') {
            if ((name.equals("has_flag") || name.equals("flag")) && part.parameter().isPresent()) {
                Result<String> flag = flagNameExpression(part.parameter().get(), scope, pending);
                if (flag.failed()) return Result.fail(flag.reason());
                if (name.equals("has_flag")) return step("npc_has_flag(" + expr.text() + ", " + flag.value() + ")", 'b', index + 1);
                return Result.ok(new Step(Expr.flagRead("npc_flag(" + expr.text() + ", " + flag.value() + ", "), index + 1));
            }
            if (part.parameter().isEmpty() && NPC_ATTRIBUTES.containsKey(name)) {
                String[] fact = NPC_ATTRIBUTES.get(name);
                return step("get(npc_info(" + expr.text() + "), '" + fact[0] + "', " + fact[1] + ")", fact[2].charAt(0), index + 1);
            }
            if (part.parameter().isEmpty() && name.equals("uuid")) return step(expr.text(), 's', index + 1);
            // An NPC is its own entity, so its entity reads as the NPC.
            if (part.parameter().isEmpty() && name.equals("entity")) return step(expr.text(), 'e', index + 1);
            if (part.parameter().isEmpty() && name.equals("is_npc")) return step("has(npc_info(" + expr.text() + "), 'uuid')", 'b', index + 1);
        }
        // A location the facts write as x,y,z,world: its world, coordinates and distance to another location, and the flags of its block.
        if (expr.kind() == 'L') {
            if ((name.equals("has_flag") || name.equals("flag")) && part.parameter().isPresent()) {
                Result<String> flag = locationFlagName(part.parameter().get().toLowerCase(Locale.ROOT), scope, pending);
                if (flag.failed()) return Result.fail(flag.reason());
                String key = blockKey(expr.text()), set = "(has_flag('global', " + flag.value() + ") && has(flag('global', " + flag.value() + ", 0), " + key + "))";
                // Whether a block has a flag is always known, so a fallback after it is never used.
                if (name.equals("has_flag")) return Result.ok(new Step(Expr.guarded(set, 'b', "true"), index + 1));
                String before = "(" + set + " ? get(flag('global', " + flag.value() + ", 0), " + key + ") : ";
                return Result.ok(new Step(Expr.orElse(before + "'')", 'a', before, ")"), index + 1));
            }
            if (name.equals("distance") && part.parameter().isPresent()) {
                Result<Expr> other = value(part.parameter().get(), scope, pending);
                if (other.failed()) return Result.fail(other.reason());
                return step("distance(" + expr.text() + ", " + textual(other.value()) + ")", 'n', index + 1);
            }
            if (part.parameter().isEmpty() && name.equals("world"))
                return step("get(split(" + expr.text() + ", ','), 3, '')", 's', attributeName(parts, index + 1).filter("name"::equals).isPresent() ? index + 2 : index + 1);
            if (part.parameter().isEmpty() && List.of("x", "y", "z").contains(name))
                return step("num(get(split(" + expr.text() + ", ','), " + List.of("x", "y", "z").indexOf(name) + ", 0))", 'n', index + 1);
            // The block there, read before the command with animus:read-block.
            if (part.parameter().isEmpty() && name.equals("material")) {
                String result = converter.temporary(scope);
                JsonObject read = new JsonObject();
                read.addProperty("type", "action");
                read.addProperty("capability", "animus:read-block");
                JsonObject arguments = new JsonObject();
                arguments.add("location", com.google.gson.JsonNull.INSTANCE);
                read.add("arguments", arguments);
                JsonObject expressions = new JsonObject();
                expressions.addProperty("/location", textual(expr));
                read.add("expressions", expressions);
                read.addProperty("result", result);
                pending.steps.add(read);
                return step("replace(get(" + result + ", 'block', ''), 'minecraft:', '')", 's', attributeName(parts, index + 1).filter("name"::equals).isPresent() ? index + 2 : index + 1);
            }
            // Moved up or down, the players near it nearest first, and whether it is inside a noted area.
            if (name.equals("above") || name.equals("below")) {
                String by = "1";
                if (part.parameter().isPresent()) {
                    Result<Expr> amount = value(part.parameter().get(), scope, pending);
                    if (amount.failed()) return Result.fail(amount.reason());
                    by = numeric(amount.value());
                }
                return step("offset(" + expr.text() + ", 0, " + (name.equals("below") ? "-(" + by + ")" : by) + ", 0)", 'L', index + 1);
            }
            if (name.equals("find_players_within") && part.parameter().isPresent()) {
                Result<Expr> radius = value(part.parameter().get(), scope, pending);
                if (radius.failed()) return Result.fail(radius.reason());
                return step("players_near(" + expr.text() + ", " + numeric(radius.value()) + ")", 'l', index + 1);
            }
            if (name.equals("is_within") && part.parameter().isPresent()) {
                Result<Expr> area = value(part.parameter().get(), scope, pending);
                if (area.failed()) return Result.fail(area.reason());
                return step("within(" + expr.text() + ", " + area.value().text() + ")", 'b', index + 1);
            }
        }
        // An item: its material, amount, name and lore.
        if (expr.kind() == 'i' && part.parameter().isEmpty()) {
            switch (name) {
                case "material" -> { return step("replace(get(" + expr.text() + ", 'item', ''), 'minecraft:', '')", 's', attributeName(parts, index + 1).filter("name"::equals).isPresent() ? index + 2 : index + 1); }
                case "quantity" -> { return step("get(" + expr.text() + ", 'amount', 1)", 'n', index + 1); }
                // A display name is unset on an item without one, which a fallback after later attributes stands in for.
                case "display" -> { return Result.ok(new Step(Expr.guarded("get(" + expr.text() + ", 'name', '')", 's', "has(" + expr.text() + ", 'name')"), index + 1)); }
                case "lore" -> { return step("get(" + expr.text() + ", 'lore', [])", 'l', index + 1); }
                default -> { }
            }
        }
        if (part.parameter().isEmpty() && name.equals("inventory") && attributeName(parts, index + 1).filter("list_contents"::equals).isPresent() && expr.kind() != 's' && expr.kind() != 'l')
            return inventory(Optional.of(expr.text()), index + 2, scope, pending);
        // Only NPCs have an id, and both NPCs and online players are spawned.
        if (expr.kind() == 'a' && part.parameter().isEmpty() && name.equals("id")) return step("get(npc_info(" + expr.text() + "), 'citizens_id', '')", 'a', index + 1);
        if (expr.kind() == 'a' && part.parameter().isEmpty() && name.equals("is_spawned"))
            return step("get(npc_info(" + expr.text() + "), 'spawned', get(player_info(" + expr.text() + "), 'online', false))", 'b', index + 1);
        if (expr.kind() == 'a' && part.parameter().isEmpty() && name.equals("is_npc")) return step("has(npc_info(" + expr.text() + "), 'uuid')", 'b', index + 1);
        if (name.equals("flag_expiration") && part.parameter().isPresent() && expr.kind() != 'e' && expr.kind() != 'N' && expr.kind() != 'l') {
            Result<String> flag = flagNameExpression(part.parameter().get(), scope, pending);
            if (flag.failed()) return Result.fail(flag.reason());
            String read = "player_flag_expiry(" + expr.text() + ", " + flag.value() + ")";
            return Result.ok(new Step(Expr.guarded(read, 't', read + " > 0"), index + 1));
        }
        // A value holding a player, such as a fan-out's or a loop's player, reads that player's flags and facts.
        if ((name.equals("has_flag") || name.equals("flag")) && part.parameter().isPresent()) {
            Result<String> flag = flagNameExpression(part.parameter().get(), scope, pending);
            if (flag.failed()) return Result.fail(flag.reason());
            if (name.equals("has_flag")) return step("player_has_flag(" + expr.text() + ", " + flag.value() + ")", 'b', index + 1);
            String prefix = "player_flag(" + expr.text() + ", " + flag.value() + ", ";
            return Result.ok(new Step(Expr.flagRead(prefix), index + 1));
        }
        if (part.parameter().isEmpty() && expr.kind() != 's' && expr.kind() != 'l' && PLAYER_ATTRIBUTES.containsKey(name)) {
            String[] fact = PLAYER_ATTRIBUTES.get(name);
            return step("get(player_info(" + expr.text() + "), '" + fact[0] + "', " + fact[1] + ")", fact[2].charAt(0), index + 1);
        }
        if (part.parameter().isEmpty() && name.equals("uuid") && expr.kind() != 'l') return step("replace(text(" + expr.text() + "), 'p@', '')", 's', index + 1);
        Expr parameter = null;
        if (part.parameter().isPresent()) {
            Result<Expr> value = value(part.parameter().get(), scope, pending);
            if (value.failed()) return Result.fail(value.reason());
            parameter = value.value();
        }
        String e = expr.text();
        String p = parameter == null ? null : parameter.text();
        int next = index + 1;
        Optional<Result<Step>> timed = time(expr, name, part, parameter, next);
        if (timed.isPresent()) return timed.get();
        if (expr.kind() == 'L' && parameter == null && name.equals("simple")) return step(blockKey(e), 's', next);
        if (parameter != null && List.of("after", "before", "after_last", "before_last").contains(name)) return Result.ok(new Step(Expr.of(around(textual(expr), textual(parameter), name), 's'), next));
        if (parameter == null) {
            return switch (name) {
                case "length", "size", "count" -> step("len(" + e + ")", 'n', next);
                case "to_lowercase", "to_lower_case", "lower" -> step("lower(" + textual(expr) + ")", 's', next);
                case "to_uppercase", "to_upper_case", "upper" -> step("upper(" + textual(expr) + ")", 's', next);
                case "round" -> step("round(" + numeric(expr) + ")", 'n', next);
                case "round_down" -> step("floor(" + numeric(expr) + ")", 'n', next);
                case "round_up" -> step("ceil(" + numeric(expr) + ")", 'n', next);
                case "abs" -> step("abs(" + numeric(expr) + ")", 'n', next);
                case "sqrt" -> step("pow(" + numeric(expr) + ", 0.5)", 'n', next);
                case "trim" -> step("trim(" + textual(expr) + ")", 's', next);
                case "strip_color" -> step("plain(" + textual(expr) + ")", 's', next);
                case "parse_color" -> step(textual(expr), 's', next);
                case "split" -> step("split(" + textual(expr) + ", ' ')", 'l', next);
                case "first" -> Result.ok(new Step(Expr.orElse("first(" + e + ")", 'a', "get(" + e + ", 0, ", ")"), next));
                case "last" -> Result.ok(new Step(Expr.orElse("last(" + e + ")", 'a', "(len(" + e + ") == 0 ? ", " : last(" + e + "))"), next));
                case "reverse" -> step("reverse(" + e + ")", 'a', next);
                case "alphabetical", "numerical", "sort" -> step("sort(" + e + ")", 'l', next);
                case "space_separated" -> step("join(" + e + ", ' ')", 's', next);
                case "comma_separated" -> step("join(" + e + ", ', ')", 's', next);
                case "unseparated" -> step("join(" + e + ", '')", 's', next);
                case "sum" -> step("sum(" + e + ")", 'n', next);
                case "random" -> step("pick(" + e + ")", 'a', next);
                case "is_empty" -> step("(len(" + e + ") == 0)", 'b', next);
                case "exists" -> step("(len(" + textual(expr) + ") > 0)", 'b', next);
                case "any" -> step("(len(" + e + ") > 0)", 'b', next);
                case "keys" -> step("keys(" + e + ")", 'l', next);
                case "values" -> step("values(" + e + ")", 'l', next);
                case "is_truthy" -> step(truth(expr), 'b', next);
                case "not" -> step("!" + truth(expr), 'b', next);
                case "as_element", "as_list", "as_map", "name" -> name.equals("name") && expr.kind() != 's' ? Result.fail("tag attribute name has no Animus expression") : Result.ok(new Step(expr, next));
                default -> Result.fail("tag attribute " + name + " has no Animus expression");
            };
        }
        return switch (name) {
            case "add" -> step("(" + numeric(expr) + " + " + numeric(parameter) + ")", 'n', next);
            case "is_less_than" -> step("(" + numeric(expr) + " < " + numeric(parameter) + ")", 'b', next);
            case "is_more_than" -> step("(" + numeric(expr) + " > " + numeric(parameter) + ")", 'b', next);
            case "is_less_than_or_equal_to" -> step("(" + numeric(expr) + " <= " + numeric(parameter) + ")", 'b', next);
            case "is_more_than_or_equal_to" -> step("(" + numeric(expr) + " >= " + numeric(parameter) + ")", 'b', next);
            case "sub" -> step("(" + numeric(expr) + " - " + numeric(parameter) + ")", 'n', next);
            case "mul" -> step("(" + numeric(expr) + " * " + numeric(parameter) + ")", 'n', next);
            case "div" -> step("(" + numeric(expr) + " / " + numeric(parameter) + ")", 'n', next);
            case "mod" -> step("mod(" + numeric(expr) + ", " + numeric(parameter) + ")", 'n', next);
            case "power" -> step("pow(" + numeric(expr) + ", " + numeric(parameter) + ")", 'n', next);
            case "max" -> step("max(" + numeric(expr) + ", " + numeric(parameter) + ")", 'n', next);
            case "min" -> step("min(" + numeric(expr) + ", " + numeric(parameter) + ")", 'n', next);
            case "contains", "contains_text", "contains_any_text" -> step("contains(" + e + ", " + p + ")", 'b', next);
            case "starts_with" -> step("starts_with(" + textual(expr) + ", " + textual(parameter) + ")", 'b', next);
            case "ends_with" -> step("ends_with(" + textual(expr) + ", " + textual(parameter) + ")", 'b', next);
            case "split" -> step("split(" + textual(expr) + ", " + textual(parameter) + ")", 'l', next);
            case "separated_by" -> step("join(" + e + ", " + textual(parameter) + ")", 's', next);
            case "find", "find_text" -> step("(index_of(" + e + ", " + p + ") + 1)", 'n', next);
            case "include" -> step("append(" + e + ", " + p + ")", 'l', next);
            case "equals" -> step(equality(expr, parameter) + "(" + e + ", " + p + ")", 'b', next);
            case "get" -> {
                String key = parameter.kind() == 'n' ? numeric(parameter) + " - 1" : p;
                yield Result.ok(new Step(Expr.orElse("get(" + e + ", " + key + ")", 'a', "get(" + e + ", " + key + ", ", ")"), next));
            }
            case "substring" -> {
                String[] bounds = part.parameter().get().split(",", -1);
                if (bounds.length > 2) yield Result.fail("substring takes one or two positions");
                Result<Expr> from = value(bounds[0], scope, pending);
                if (from.failed()) yield Result.fail(from.reason());
                String to = "len(" + textual(expr) + ")";
                if (bounds.length == 2) {
                    Result<Expr> end = value(bounds[1], scope, pending);
                    if (end.failed()) yield Result.fail(end.reason());
                    to = numeric(end.value());
                }
                yield step("slice(" + textual(expr) + ", " + numeric(from.value()) + " - 1, " + to + ")", 's', next);
            }
            case "replace", "replace_text" -> {
                if (index + 1 >= parts.size() || !parts.get(index + 1).name().equalsIgnoreCase("with") || parts.get(index + 1).parameter().isEmpty())
                    yield step("replace(" + textual(expr) + ", " + textual(parameter) + ", '')", 's', next);
                Result<Expr> with = value(parts.get(index + 1).parameter().get(), scope, pending);
                if (with.failed()) yield Result.fail(with.reason());
                yield step("replace(" + textual(expr) + ", " + textual(parameter) + ", " + textual(with.value()) + ")", 's', next + 1);
            }
            case "if_true" -> {
                if (index + 1 >= parts.size() || !parts.get(index + 1).name().equalsIgnoreCase("if_false") || parts.get(index + 1).parameter().isEmpty())
                    yield Result.fail("if_true needs if_false");
                Result<Expr> otherwise = value(parts.get(index + 1).parameter().get(), scope, pending);
                if (otherwise.failed()) yield Result.fail(otherwise.reason());
                yield step("(" + truth(expr) + " ? " + p + " : " + otherwise.value().text() + ")", 'a', next + 1);
            }
            default -> Result.fail("tag attribute " + name + " has no Animus expression");
        };
    }

    private static Result<Step> step(String text, char kind, int next) { return Result.ok(new Step(Expr.of(text, kind), next)); }

    /** Seconds per unit of the duration reads Denizen gives, and the reads a value takes as a duration first. */
    private static final Map<String, String> DURATION_READS = Map.of("in_seconds", "", "in_milliseconds", " * 1000", "in_minutes", " / 60", "in_hours", " / 3600",
            "in_days", " / 86400", "in_weeks", " / 604800", "in_years", " / 31536000");

    /** A value as a number of seconds: a duration or a number as it is, written duration text as its seconds. */
    static String seconds(Expr value) {
        if (value.kind() == 'd' || value.kind() == 'n') return value.text();
        if (value.kind() == 's' && value.text().matches("'[^'\\]*'")) {
            java.util.OptionalLong ticks = DenizenArgs.ticks(value.text().substring(1, value.text().length() - 1));
            if (ticks.isPresent()) return trimmed(ticks.getAsLong() / 20.0);
        }
        return "duration_seconds(" + value.text() + ")";
    }

    /**
     * The attributes of a time ({@code t}, epoch milliseconds) and a duration ({@code d}, seconds), as Denizen's TimeTag
     * and DurationTag read them; a duration never goes below zero. Other values take {@code as[duration]} and the duration
     * reads as a duration written as text or seconds. Empty for any other attribute.
     */
    private Optional<Result<Step>> time(Expr expr, String name, Part part, Expr parameter, int next) {
        String e = expr.text();
        if (name.equals("as") && part.parameter().filter("duration"::equalsIgnoreCase).isPresent() || name.equals("as_duration") && parameter == null)
            return Optional.of(step(expr.kind() == 'd' ? e : seconds(expr), 'd', next));
        if (expr.kind() == 't') {
            if (parameter == null && name.equals("from_now")) return Optional.of(step("max(0, (" + e + " - now()) / 1000)", 'd', next));
            if (parameter == null && List.of("epoch_millis", "millis").contains(name)) return Optional.of(step(e, 'n', next));
            if (parameter != null && name.equals("duration_since")) return Optional.of(step("max(0, (" + e + " - " + numeric(parameter) + ") / 1000)", 'd', next));
            if (parameter != null && (name.equals("add") || name.equals("sub"))) return Optional.of(step("(" + e + (name.equals("add") ? " + " : " - ") + seconds(parameter) + " * 1000)", 't', next));
            if (parameter != null && (name.equals("is_after") || name.equals("is_before"))) return Optional.of(step("(" + e + (name.equals("is_after") ? " > " : " < ") + numeric(parameter) + ")", 'b', next));
            return Optional.empty();
        }
        if (expr.kind() != 'd' && (parameter != null || !DURATION_READS.containsKey(name) && !name.equals("in_ticks"))) return Optional.empty();
        String duration = expr.kind() == 'd' ? e : seconds(expr);
        if (parameter == null && DURATION_READS.containsKey(name)) return Optional.of(step(DURATION_READS.get(name).isEmpty() ? duration : "(" + duration + DURATION_READS.get(name) + ")", 'n', next));
        if (parameter == null && name.equals("in_ticks")) return Optional.of(step("floor(" + duration + " * 20)", 'n', next));
        if (parameter == null && name.equals("formatted")) return Optional.of(step("duration_text(" + duration + ")", 's', next));
        if (parameter != null && (name.equals("add") || name.equals("sub"))) return Optional.of(step(name.equals("add") ? "(" + duration + " + " + seconds(parameter) + ")" : "max(0, " + duration + " - " + seconds(parameter) + ")", 'd', next));
        Map<String, String> compare = Map.of("is_more_than", " > ", "is_less_than", " < ", "is_more_than_or_equal_to", " >= ", "is_less_than_or_equal_to", " <= ");
        if (parameter != null && compare.containsKey(name)) return Optional.of(step("(" + duration + compare.get(name) + seconds(parameter) + ")", 'b', next));
        return Optional.empty();
    }

    /**
     * {@code after}, {@code before}, {@code after_last} and {@code before_last}: the text around the first or last
     * place the other text appears, ignoring case. Where it does not appear, after gives nothing and before all the text.
     */
    static String around(String text, String other, String name) {
        String parts = "split(lower(" + text + "), lower(" + other + "))", found = "contains(lower(" + text + "), lower(" + other + "))";
        return switch (name) {
            case "after" -> "(" + found + " ? slice(" + text + ", len(first(" + parts + ")) + len(" + other + "), len(" + text + ")) : '')";
            case "before" -> "slice(" + text + ", 0, len(first(" + parts + ")))";
            case "after_last" -> "(" + found + " ? slice(" + text + ", len(" + text + ") - len(last(" + parts + ")), len(" + text + ")) : '')";
            default -> "(" + found + " ? slice(" + text + ", 0, len(" + text + ") - len(last(" + parts + ")) - len(" + other + ")) : " + text + ")";
        };
    }

    // ---- Conditions ----

    private static final List<String> OPERATORS = List.of("==", "!=", "<", "<=", ">", ">=", "contains", "in", "matches");

    /** The tokens of an if line after its command word, with parentheses split off as their own tokens. */
    static List<String> conditionTokens(List<String> raw) {
        List<String> tokens = new ArrayList<>();
        for (String token : raw) {
            String rest = token;
            while (rest.length() > 1 && (rest.startsWith("(") || rest.startsWith("!("))) {
                if (rest.startsWith("!(")) { tokens.add("!"); rest = rest.substring(1); }
                tokens.add("(");
                rest = rest.substring(1);
            }
            int closing = 0;
            while (rest.length() > 1 && rest.endsWith(")")) { closing++; rest = rest.substring(0, rest.length() - 1); }
            tokens.add(rest);
            for (int index = 0; index < closing; index++) tokens.add(")");
        }
        return tokens;
    }

    /** An if condition: comparisons joined by {@code &&}, {@code ||}, {@code !} and parentheses. */
    Result<Cond> condition(List<String> raw, Scope scope, Pending pending) {
        if (raw.isEmpty()) return Result.fail("if without a condition");
        List<String> tokens = conditionTokens(raw);
        int[] position = {0};
        Result<Cond> result = or(tokens, position, scope, pending);
        if (result.failed()) return result;
        if (position[0] != tokens.size()) return Result.fail("condition has unbalanced parentheses or a stray " + tokens.get(position[0]));
        if (!capabilities(result.value()) && render(result.value()).length() > MAXIMUM_EXPRESSION) return Result.fail("condition longer than " + MAXIMUM_EXPRESSION + " characters");
        return result;
    }

    private Result<Cond> or(List<String> tokens, int[] position, Scope scope, Pending pending) {
        Result<Cond> left = and(tokens, position, scope, pending);
        while (!left.failed() && position[0] < tokens.size() && tokens.get(position[0]).equals("||")) {
            position[0]++;
            Result<Cond> right = and(tokens, position, scope, pending);
            if (right.failed()) return right;
            left = Result.ok(new Or(left.value(), right.value()));
        }
        return left;
    }

    private Result<Cond> and(List<String> tokens, int[] position, Scope scope, Pending pending) {
        Result<Cond> left = unary(tokens, position, scope, pending);
        while (!left.failed() && position[0] < tokens.size() && tokens.get(position[0]).equals("&&")) {
            position[0]++;
            Result<Cond> right = unary(tokens, position, scope, pending);
            if (right.failed()) return right;
            left = Result.ok(new And(left.value(), right.value()));
        }
        return left;
    }

    private Result<Cond> unary(List<String> tokens, int[] position, Scope scope, Pending pending) {
        if (position[0] >= tokens.size()) return Result.fail("condition ends early");
        String token = tokens.get(position[0]);
        if (token.equals("!")) {
            position[0]++;
            Result<Cond> inner = unary(tokens, position, scope, pending);
            return inner.failed() ? inner : Result.ok(new Not(inner.value()));
        }
        if (token.equals("(")) {
            position[0]++;
            Result<Cond> inner = or(tokens, position, scope, pending);
            if (inner.failed()) return inner;
            if (position[0] >= tokens.size() || !tokens.get(position[0]).equals(")")) return Result.fail("condition has an unclosed parenthesis");
            position[0]++;
            return inner;
        }
        return comparison(tokens, position, scope, pending);
    }

    private Result<Cond> comparison(List<String> tokens, int[] position, Scope scope, Pending pending) {
        String left = tokens.get(position[0]++);
        if (List.of("&&", "||", ")").contains(left) || OPERATORS.contains(left)) return Result.fail("condition has " + left + " where a value belongs");
        boolean negated = left.length() > 1 && left.startsWith("!");
        if (negated) left = left.substring(1);
        String operator = position[0] < tokens.size() && OPERATORS.contains(tokens.get(position[0]).toLowerCase(Locale.ROOT)) ? tokens.get(position[0]).toLowerCase(Locale.ROOT) : null;
        Result<Cond> atom;
        if (operator == null) atom = single(left, scope, pending);
        else {
            position[0]++;
            if (position[0] >= tokens.size()) return Result.fail("operator " + operator + " without a right side");
            atom = compare(left, operator, tokens.get(position[0]++), scope, pending);
        }
        if (atom.failed() || !negated) return atom;
        return Result.ok(new Not(atom.value()));
    }

    private Result<Cond> single(String token, Scope scope, Pending pending) {
        if (token.startsWith("<") && token.endsWith(">") && DenizenArgs.tagEnd(token, 0) == token.length() - 1) {
            Tag tag = DenizenArgs.tag(token.substring(1, token.length() - 1));
            if (tag.parts().size() == 2 && tag.base().equalsIgnoreCase("player") && tag.parts().getFirst().parameter().isEmpty() && tag.fallback().isEmpty()
                    && tag.parts().get(1).name().equalsIgnoreCase("has_permission") && tag.parts().get(1).parameter().isPresent()) {
                String permission = tag.parts().get(1).parameter().get();
                if (permission.contains("<")) return Result.fail("a computed permission has no Animus condition");
                JsonObject arguments = new JsonObject();
                arguments.addProperty("permission", permission);
                return Result.ok(new Capability("animus:has-permission", arguments));
            }
            Optional<Result<Cond>> items = holds(tag, scope, pending);
            if (items.isPresent()) return items.get();
            Optional<Result<Cond>> room = fits(tag, scope, pending);
            if (room.isPresent()) return room.get();
        }
        Result<Expr> value = value(token, scope, pending);
        if (value.failed()) return Result.fail(value.reason());
        return Result.ok(new Atom(truth(value.value())));
    }

    /**
     * {@code <player.inventory.can_fit[<[item]>]>}, or the inventory of a player held in a definition: whether the item,
     * an item value or a material, fits without dropping any, as {@code animus:can-fit} checks it. Empty for any other tag.
     */
    private Optional<Result<Cond>> fits(Tag tag, Scope scope, Pending pending) {
        List<Part> parts = tag.parts();
        if (tag.fallback().isPresent() || parts.size() != 3 || !parts.get(1).name().equalsIgnoreCase("inventory") || parts.get(1).parameter().isPresent()
                || !parts.get(2).name().equalsIgnoreCase("can_fit") || parts.get(2).parameter().isEmpty()) return Optional.empty();
        boolean player = parts.getFirst().name().equalsIgnoreCase("player") && parts.getFirst().parameter().isEmpty();
        boolean held = parts.getFirst().name().isEmpty() && parts.getFirst().parameter().filter((String name) -> name.matches("[A-Za-z0-9_]{1,64}")).isPresent();
        if (!player && !held) return Optional.empty();
        Result<Expr> item = value(parts.get(2).parameter().get(), scope, pending);
        if (item.failed()) return Optional.of(Result.fail(item.reason()));
        JsonObject arguments = new JsonObject(), expressions = new JsonObject();
        arguments.add("stack", com.google.gson.JsonNull.INSTANCE);
        expressions.addProperty("/stack", stack(item.value(), scope, pending));
        if (held) {
            Result<Step> who = definition(parts.getFirst().parameter().get(), scope);
            if (who.failed()) return Optional.of(Result.fail(who.reason()));
            arguments.add("player", com.google.gson.JsonNull.INSTANCE);
            expressions.addProperty("/player", "get(players(" + who.value().expr().text() + "), 0, '')");
        }
        return Optional.of(Result.ok(new Capability("animus:can-fit", arguments, expressions)));
    }

    /**
     * {@code <player.inventory.contains_item[iron_ingot].quantity[3]>}, or the inventory of a player held in a
     * definition: whether the player holds that many of the material, as {@code animus:can-afford} checks it. Empty for
     * any other tag.
     */
    private Optional<Result<Cond>> holds(Tag tag, Scope scope, Pending pending) {
        List<Part> parts = tag.parts();
        if (tag.fallback().isPresent() || parts.size() < 3 || parts.size() > 4 || !parts.get(1).name().equalsIgnoreCase("inventory") || parts.get(1).parameter().isPresent()
                || !parts.get(2).name().equalsIgnoreCase("contains_item") || parts.get(2).parameter().isEmpty()) return Optional.empty();
        boolean player = parts.getFirst().name().equalsIgnoreCase("player") && parts.getFirst().parameter().isEmpty();
        boolean held = parts.getFirst().name().isEmpty() && parts.getFirst().parameter().filter((String name) -> name.matches("[A-Za-z0-9_]{1,64}")).isPresent();
        if (!player && !held) return Optional.empty();
        String item = parts.get(2).parameter().get().toLowerCase(Locale.ROOT);
        if (item.startsWith("minecraft:")) item = item.substring(10);
        Optional<String> computed = Optional.empty();
        if (item.contains("<")) {
            // A material read as the check runs; an item script name there finds nothing, as no item has that material.
            Result<Expr> named = value(parts.get(2).parameter().get(), scope, pending);
            if (named.failed()) return Optional.of(Result.fail(named.reason()));
            String material = "lower(replace(" + textual(named.value()) + ", 'i@', ''))";
            computed = Optional.of("(contains(" + material + ", ':') ? " + material + " : 'minecraft:' + " + material + ")");
        } else if (!item.matches("[a-z0-9_]{1,64}") || converter.containers.containsKey(item)) return Optional.of(Result.fail("contains_item checks only a plain material in Animus"));
        long amount = 1;
        if (parts.size() == 4) {
            if (!parts.get(3).name().equalsIgnoreCase("quantity") || parts.get(3).parameter().filter((String count) -> count.matches("[0-9]{1,4}")).isEmpty())
                return Optional.of(Result.fail("contains_item takes a literal quantity in Animus"));
            amount = Long.parseLong(parts.get(3).parameter().get());
            if (amount < 1 || amount > 6400) return Optional.of(Result.fail("quantity must be from 1 to 6400"));
        }
        JsonObject arguments = new JsonObject(), expressions = new JsonObject(), entry = new JsonObject(), id = new JsonObject();
        JsonArray list = new JsonArray();
        if (computed.isPresent()) {
            id.add("id", com.google.gson.JsonNull.INSTANCE);
            expressions.addProperty("/items/0/item/id", computed.get());
        } else id.addProperty("id", "minecraft:" + item);
        entry.add("item", id);
        entry.addProperty("amount", amount);
        list.add(entry);
        arguments.add("items", list);
        arguments.addProperty("match", "material");
        if (held) {
            Result<Step> who = definition(parts.getFirst().parameter().get(), scope);
            if (who.failed()) return Optional.of(Result.fail(who.reason()));
            arguments.add("player", com.google.gson.JsonNull.INSTANCE);
            expressions.addProperty("/player", "get(players(" + who.value().expr().text() + "), 0, '')");
        }
        return Optional.of(Result.ok(new Capability("animus:can-afford", arguments, expressions)));
    }

    private Result<Cond> compare(String leftRaw, String operator, String rightRaw, Scope scope, Pending pending) {
        Result<Expr> left = value(leftRaw, scope, pending);
        if (left.failed()) return Result.fail(left.reason());
        if (operator.equals("matches")) {
            if (rightRaw.contains("<")) return Result.fail("matches with a computed pattern has no Animus expression");
            return matcher(left.value(), rightRaw);
        }
        Result<Expr> right = value(rightRaw, scope, pending);
        if (right.failed()) return Result.fail(right.reason());
        Expr l = left.value(), r = right.value();
        return Result.ok(new Atom(switch (operator) {
            case "==" -> equality(l, r) + "(" + l.text() + ", " + r.text() + ")";
            case "!=" -> "!" + equality(l, r) + "(" + l.text() + ", " + r.text() + ")";
            case "contains" -> "contains(" + l.text() + ", " + r.text() + ")";
            case "in" -> "contains(" + r.text() + ", " + l.text() + ")";
            default -> numeric(l) + " " + operator + " " + numeric(r);
        }));
    }

    /** A world fact gives a Bukkit name or a dimension key by platform, so a comparison with one resolves both sides. */
    static boolean world(Expr expr) { return expr.text().equals("player.world") || expr.text().equals("npc.world") || expr.text().equals("context.world"); }

    static String equality(Expr left, Expr right) { return world(left) || world(right) ? "same_world" : "same"; }

    /** Denizen's advanced matcher for the simple forms: alternatives with {@code |} and {@code *} at either end, ignoring case. */
    private static Result<Cond> matcher(Expr value, String pattern) {
        List<String> tests = new ArrayList<>();
        String subject = "lower(" + textual(value) + ")";
        for (String alternative : pattern.toLowerCase(Locale.ROOT).split("\\|", -1)) {
            String core = alternative;
            boolean start = core.startsWith("*"), end = core.endsWith("*") && core.length() > 1;
            if (start) core = core.substring(1);
            if (end) core = core.substring(0, core.length() - 1);
            if (core.contains("*") || core.contains("[") || core.contains("?") || alternative.startsWith("regex:")) return Result.fail("matches pattern " + pattern + " uses matcher features behavior expressions lack");
            if (core.isEmpty()) return Result.ok(new Atom("true"));
            if (start && end) tests.add("contains(" + subject + ", " + quote(core) + ")");
            else if (start) tests.add("ends_with(" + subject + ", " + quote(core) + ")");
            else if (end) tests.add("starts_with(" + subject + ", " + quote(core) + ")");
            else tests.add(equality(value, value) + "(" + textual(value) + ", " + quote(core) + ")");
        }
        return Result.ok(new Atom(tests.size() == 1 ? tests.getFirst() : "(" + String.join(" || ", tests) + ")"));
    }

    static boolean capabilities(Cond cond) {
        return switch (cond) {
            case Atom ignored -> false;
            case Capability ignored -> true;
            case And and -> capabilities(and.left()) || capabilities(and.right());
            case Or or -> capabilities(or.left()) || capabilities(or.right());
            case Not not -> capabilities(not.inner());
        };
    }

    static String render(Cond cond) {
        return switch (cond) {
            case Atom atom -> atom.expression();
            case Capability ignored -> throw new IllegalStateException("capability conditions are not expressions");
            case And and -> "(" + render(and.left()) + " && " + render(and.right()) + ")";
            case Or or -> "(" + render(or.left()) + " || " + render(or.right()) + ")";
            case Not not -> "!(" + render(not.inner()) + ")";
        };
    }

    /** The condition node: one expression condition when no capability is involved, otherwise nested conditions. */
    static JsonObject build(Cond cond, JsonObject then, Optional<JsonObject> otherwise) {
        if (!capabilities(cond)) {
            JsonObject node = new JsonObject();
            node.addProperty("type", "condition");
            node.addProperty("expression", render(cond));
            node.add("then", then);
            otherwise.ifPresent((JsonObject value) -> node.add("else", value));
            return node;
        }
        return switch (cond) {
            case Capability capability -> {
                JsonObject node = new JsonObject();
                node.addProperty("type", "condition");
                node.addProperty("capability", capability.capability());
                node.add("arguments", capability.arguments().deepCopy());
                if (!capability.expressions().isEmpty()) node.add("expressions", capability.expressions().deepCopy());
                node.add("then", then);
                otherwise.ifPresent((JsonObject value) -> node.add("else", value));
                yield node;
            }
            case Not not -> build(not.inner(), otherwise.orElseGet(() -> DenizenConverter.sequence(List.of())), Optional.of(then));
            case And and -> build(and.left(), build(and.right(), then, otherwise.map(JsonObject::deepCopy)), otherwise);
            case Or or -> build(or.left(), then, Optional.of(build(or.right(), then.deepCopy(), otherwise)));
            case Atom ignored -> throw new IllegalStateException("handled above");
        };
    }
}
