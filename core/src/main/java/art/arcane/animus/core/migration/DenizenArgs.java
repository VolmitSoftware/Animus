package art.arcane.animus.core.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Denizen command lines: arguments split on spaces outside quotes and tags, {@code prefix:value} arguments, tags found
 * in text, and durations. The browser converter has a line-for-line copy.
 */
final class DenizenArgs {
    private DenizenArgs() {}

    /** One argument. {@code prefix} is lowercase, or empty when the argument has none. */
    record Arg(String prefix, String value, boolean quoted) {
        boolean is(String word) { return prefix.isEmpty() && !quoted && value.equalsIgnoreCase(word); }
    }

    /** A command line: its lowercase name, without a leading {@code ~}, and its arguments. */
    record Command(String name, List<Arg> args) {
        Optional<Arg> prefixed(String prefix) {
            for (Arg arg : args) if (arg.prefix().equals(prefix)) return Optional.of(arg);
            return Optional.empty();
        }

        List<Arg> plain() {
            List<Arg> plain = new ArrayList<>();
            for (Arg arg : args) if (arg.prefix().isEmpty()) plain.add(arg);
            return plain;
        }
    }

    static Command command(String line) {
        List<String> tokens = new ArrayList<>();
        List<Boolean> quoted = new ArrayList<>();
        split(line, tokens, quoted);
        if (tokens.isEmpty()) return new Command("", List.of());
        String name = tokens.getFirst().toLowerCase(Locale.ROOT);
        if (name.startsWith("~")) name = name.substring(1);
        List<Arg> args = new ArrayList<>();
        for (int index = 1; index < tokens.size(); index++) args.add(arg(tokens.get(index), quoted.get(index)));
        return new Command(name, args);
    }

    private static Arg arg(String token, boolean quoted) {
        if (!quoted) {
            int colon = token.indexOf(':');
            if (colon > 0 && token.substring(0, colon).matches("[A-Za-z_][A-Za-z0-9_]*")) return new Arg(token.substring(0, colon).toLowerCase(Locale.ROOT), token.substring(colon + 1), false);
        }
        return new Arg("", token, quoted);
    }

    /**
     * Splits on spaces outside quotes and outside {@code <...>} tags and their {@code [...]} parameters. A quote opens
     * only at the start of an argument or right after its {@code prefix:}, and closes before a space or the end.
     * Quoted arguments that carry a prefix keep it: {@code text:"a b"} gives {@code text:a b}.
     */
    static void split(String line, List<String> tokens, List<Boolean> quoted) {
        StringBuilder current = new StringBuilder();
        boolean wasQuoted = false;
        char quote = 0;
        StringBuilder stack = new StringBuilder();
        for (int index = 0; index < line.length(); index++) {
            char at = line.charAt(index);
            if (quote != 0) {
                if (at == quote && stack.isEmpty() && (index + 1 == line.length() || line.charAt(index + 1) == ' ')) { quote = 0; continue; }
                current.append(at);
                trackTag(line, index, at, stack);
                continue;
            }
            if (at == ' ' && stack.isEmpty()) {
                if (!current.isEmpty() || wasQuoted) { tokens.add(current.toString()); quoted.add(wasQuoted); }
                current.setLength(0);
                wasQuoted = false;
                continue;
            }
            if ((at == '"' || at == '\'') && stack.isEmpty() && (current.isEmpty() || prefixOpen(current))) {
                quote = at;
                wasQuoted = current.isEmpty();
                continue;
            }
            current.append(at);
            trackTag(line, index, at, stack);
        }
        if (!current.isEmpty() || wasQuoted) { tokens.add(current.toString()); quoted.add(wasQuoted); }
    }

    private static boolean prefixOpen(StringBuilder current) {
        return current.length() > 1 && current.charAt(current.length() - 1) == ':' && current.substring(0, current.length() - 1).matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private static void trackTag(String line, int index, char at, StringBuilder stack) {
        if (at == '<' && opensTag(line, index)) stack.append('<');
        else if (at == '[' && !stack.isEmpty()) stack.append('[');
        else if (at == ']' && !stack.isEmpty() && stack.charAt(stack.length() - 1) == '[') stack.setLength(stack.length() - 1);
        else if (at == '>' && !stack.isEmpty() && stack.charAt(stack.length() - 1) == '<') stack.setLength(stack.length() - 1);
    }

    /** A {@code <} starts a tag when a non-space follows that is not {@code =} or {@code <}, so {@code a < b} and {@code <=} stay text. */
    static boolean opensTag(String text, int index) {
        if (index + 1 >= text.length()) return false;
        char next = text.charAt(index + 1);
        return next != ' ' && next != '=' && next != '<' && next != '>';
    }

    /** Text broken into literal runs and tags. A tag's content is what lies between its outer angle brackets. */
    record Segment(String text, boolean tag) {}

    static List<Segment> segments(String text) {
        List<Segment> segments = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            char at = text.charAt(index);
            if (at == '<' && opensTag(text, index)) {
                int end = tagEnd(text, index);
                if (end > 0) {
                    if (!literal.isEmpty()) { segments.add(new Segment(literal.toString(), false)); literal.setLength(0); }
                    segments.add(new Segment(text.substring(index + 1, end), true));
                    index = end + 1;
                    continue;
                }
            }
            literal.append(at);
            index++;
        }
        if (!literal.isEmpty()) segments.add(new Segment(literal.toString(), false));
        return segments;
    }

    /** The index of the {@code >} closing the tag opened at {@code start}, or -1. */
    static int tagEnd(String text, int start) {
        StringBuilder stack = new StringBuilder("<");
        for (int index = start + 1; index < text.length(); index++) {
            char at = text.charAt(index);
            trackTag(text, index, at, stack);
            if (stack.isEmpty()) return index;
        }
        return -1;
    }

    /** A tag split into dot-separated parts outside brackets, each with its bracket parameter, and its {@code ||} fallback. */
    record Part(String name, Optional<String> parameter) {}

    record Tag(List<Part> parts, Optional<String> fallback) {
        String base() { return parts.isEmpty() ? "" : parts.getFirst().name(); }

        /** A short name for reports: the base and its first attribute, without parameters, such as {@code player.flag}. */
        String label() {
            if (parts.isEmpty()) return "";
            Part first = parts.getFirst();
            if (first.name().isEmpty() && first.parameter().isPresent()) return "[definition]";
            String base = first.name().toLowerCase(Locale.ROOT);
            if (base.startsWith("&") || parts.size() == 1) return base;
            return base + "." + parts.get(1).name().toLowerCase(Locale.ROOT);
        }
    }

    static Tag tag(String content) {
        String body = content;
        Optional<String> fallback = Optional.empty();
        int depth = 0;
        for (int index = 0; index + 1 < content.length(); index++) {
            char at = content.charAt(index);
            if (at == '[') depth++;
            else if (at == ']' && depth > 0) depth--;
            else if (depth == 0 && at == '|' && content.charAt(index + 1) == '|') {
                body = content.substring(0, index);
                fallback = Optional.of(content.substring(index + 2));
                break;
            }
        }
        List<Part> parts = new ArrayList<>();
        StringBuilder name = new StringBuilder();
        StringBuilder parameter = null;
        depth = 0;
        for (int index = 0; index < body.length(); index++) {
            char at = body.charAt(index);
            if (depth == 0 && at == '.') {
                parts.add(new Part(name.toString(), parameter == null ? Optional.empty() : Optional.of(parameter.toString())));
                name.setLength(0);
                parameter = null;
                continue;
            }
            if (at == '[') {
                depth++;
                if (depth == 1) { parameter = new StringBuilder(); continue; }
            } else if (at == ']' && depth > 0) {
                depth--;
                if (depth == 0) continue;
            }
            if (depth > 0) parameter.append(at);
            else name.append(at);
        }
        parts.add(new Part(name.toString(), parameter == null ? Optional.empty() : Optional.of(parameter.toString())));
        return new Tag(parts, fallback);
    }

    /** Every tag in the text, outermost only. */
    static List<Tag> tags(String text) {
        List<Tag> tags = new ArrayList<>();
        for (Segment segment : segments(text)) if (segment.tag()) tags.add(tag(segment.text()));
        return tags;
    }

    /** A Denizen duration in ticks: {@code 20t}, {@code 2s}, {@code 1.5s}, {@code 3m}, {@code 1h}, {@code 2d}, or a bare number of seconds. */
    static OptionalLong ticks(String text) {
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9]{1,7}(?:\\.[0-9]{1,4})?[tsmhd]?")) return OptionalLong.empty();
        char unit = Character.isDigit(value.charAt(value.length() - 1)) ? 's' : value.charAt(value.length() - 1);
        String number = Character.isDigit(value.charAt(value.length() - 1)) ? value : value.substring(0, value.length() - 1);
        java.math.BigDecimal amount = new java.math.BigDecimal(number);
        java.math.BigDecimal factor = switch (unit) {
            case 't' -> java.math.BigDecimal.ONE;
            case 's' -> java.math.BigDecimal.valueOf(20);
            case 'm' -> java.math.BigDecimal.valueOf(1200);
            case 'h' -> java.math.BigDecimal.valueOf(72000);
            default -> java.math.BigDecimal.valueOf(1_728_000);
        };
        java.math.BigDecimal ticks = amount.multiply(factor).setScale(0, java.math.RoundingMode.HALF_UP);
        if (ticks.compareTo(java.math.BigDecimal.valueOf(10_000_000)) > 0) return OptionalLong.empty();
        return OptionalLong.of(ticks.longValueExact());
    }
}
