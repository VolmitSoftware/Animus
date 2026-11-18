package art.arcane.animus.core.command;

import art.arcane.volmlib.util.director.parse.DirectorConfidence;
import art.arcane.volmlib.util.director.parse.DirectorParser;
import art.arcane.volmlib.util.director.parse.DirectorParserRegistry;
import art.arcane.volmlib.util.director.parse.DirectorValue;
import art.arcane.volmlib.util.director.runtime.DirectorParameterDescriptor;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeParameter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Where an {@code /animus} command written into a reply ends, read from the command tree. After the group and command
 * names, each word must be a value the command still takes: a required value, then the optional ones its
 * {@link Positional} lists, three coordinates where it takes x, y and z, or {@code key=value} for one of its own values.
 * A placeholder such as {@code <npc>} or {@code [page]} stands for a value; a bare word must convert to the value's
 * type, so prose after {@code /animus list} is not a page. A colour code, or punctuation after a word, ends the command.
 * Help reads as the Director runs it: {@code help}, {@code help all}, {@code help N} or {@code help} and a topic.
 */
public final class CommandSyntax {
    private static final Set<String> HELP = Set.of("help", "?");
    /** Ends a command when it closes a word: the clause goes on as text. */
    private static final String CLOSERS = ".,;:!?)'\"";
    private final DirectorRuntimeNode root;
    private final DirectorParserRegistry parsers;
    private final String head;

    CommandSyntax(DirectorRuntimeEngine engine) {
        root = Objects.requireNonNull(engine.getRoot());
        parsers = engine.getParsers();
        head = "/" + root.getDescriptor().getName();
    }

    /** The {@code /animus} tree. Its shape is the same on every platform and without services, so one is built for replies. */
    public static CommandSyntax animus() { return Tree.SYNTAX; }

    private static final class Tree {
        static final CommandSyntax SYNTAX = new CommandSyntax(new AnimusCommandTree(null).director().engine());
    }

    /** The end of the command at {@code start}, where {@code text} has {@code /animus}: just past its last word. */
    public int end(String text, int start) {
        if (!text.startsWith(head, start)) throw new IllegalArgumentException("No " + head + " at " + start);
        int end = start + head.length();
        DirectorRuntimeNode node = root;
        while (!node.isInvocable()) {
            Word word = word(text, end);
            if (word == null) return end;
            if (HELP.contains(word.text().toLowerCase(Locale.ROOT))) return help(text, word, node);
            DirectorRuntimeNode child = AnimusDirector.named(node, word.text());
            if (child == null) return end;
            node = child;
            end = word.end();
            if (word.closes()) return end;
        }
        Values values = new Values(node);
        for (Word word = word(text, end); word != null && values.take(word.text()); word = word(text, end)) {
            end = word.end();
            if (word.closes()) break;
        }
        return end;
    }

    /** {@code help} after {@code group}, then {@code all}, a page number, or at the root a topic's names. */
    private int help(String text, Word help, DirectorRuntimeNode group) {
        int end = help.end();
        if (help.closes()) return end;
        Word next = word(text, end);
        if (next == null) return end;
        if (next.text().matches("\\d{1,4}") || group == root && next.text().equalsIgnoreCase("all")) return next.end();
        if (group != root) return end;
        DirectorRuntimeNode topic = root;
        for (Word word = next; word != null && !topic.isInvocable(); word = word(text, end)) {
            DirectorRuntimeNode child = AnimusDirector.named(topic, word.text());
            if (child == null) break;
            topic = child;
            end = word.end();
            if (word.closes()) break;
        }
        return end;
    }

    /**
     * The word after the space at {@code from}: a quoted value with its quotes, a {@code <placeholder>} or {@code [placeholder]}
     * with any spaces inside, or the characters up to the next space or colour code, without punctuation that closes it.
     */
    private static Word word(String text, int from) {
        if (from + 1 >= text.length() || text.charAt(from) != ' ') return null;
        int start = from + 1, index = start;
        char first = text.charAt(start);
        if (first == ' ' || first == '§') return null;
        int equals = text.indexOf('=', start);
        int value = Character.isLetter(first) && equals > start && equals < spaceOrEnd(text, start) ? equals + 1 : start;
        if (value < text.length() && text.charAt(value) == '"') index = closing(text, value + 1, '"');
        else if (first == '<') index = closing(text, start + 1, '>');
        else if (first == '[') index = closing(text, start + 1, ']');
        if (index < 0) return null;
        while (index < text.length() && text.charAt(index) != ' ' && text.charAt(index) != '§') index++;
        int end = index;
        while (end > start && CLOSERS.indexOf(text.charAt(end - 1)) >= 0 && !closedQuote(text, start, end)) end--;
        if (end == start) return null;
        boolean closes = end < index || index == text.length() || text.charAt(index) == '§';
        return new Word(text.substring(start, end), end, closes);
    }

    /** A quoted value keeps its closing quote; only punctuation after it closes the clause. */
    private static boolean closedQuote(String text, int start, int end) {
        return text.charAt(end - 1) == '"' && text.indexOf('"', start) < end - 1;
    }

    private static int spaceOrEnd(String text, int from) {
        int space = text.indexOf(' ', from);
        return space < 0 ? text.length() : space;
    }

    /** The index just past {@code close}, honouring {@code \"} in a quoted value; -1 when it never closes. */
    private static int closing(String text, int from, char close) {
        for (int index = from; index < text.length(); index++) {
            char glyph = text.charAt(index);
            if (glyph == '\\' && close == '"') { index++; continue; }
            if (glyph == close) return index + 1;
            if (glyph == '§') return -1;
        }
        return -1;
    }

    /** One word of a command: its text without closing punctuation, where it ends, and whether it ends the command. */
    private record Word(String text, int end, boolean closes) {}

    /** The values a command still takes as the words of a reply give them. */
    private final class Values {
        private final DirectorRuntimeNode node;
        private final Set<DirectorRuntimeParameter> given = new HashSet<>();
        private final List<DirectorRuntimeParameter> positional = new ArrayList<>();
        private final boolean coordinates;
        private int coordinatesGiven;

        Values(DirectorRuntimeNode node) {
            this.node = node;
            Positional listed = node.getMethod() == null ? null : node.getMethod().getAnnotation(Positional.class);
            if (listed != null) for (String name : listed.value()) {
                DirectorRuntimeParameter parameter = AnimusDirector.parameter(node, name);
                if (parameter != null) positional.add(parameter);
            }
            coordinates = Coordinates.AXES.stream().map((String axis) -> AnimusDirector.parameter(node, axis))
                    .allMatch((DirectorRuntimeParameter axis) -> axis != null && !axis.getDescriptor().isRequired());
        }

        /** Whether {@code word} is a value of this command; when it is, the value counts as given. */
        boolean take(String word) {
            if (word.startsWith("[") && word.endsWith("]")) return optional(word.substring(1, word.length() - 1));
            int equals = word.indexOf('=');
            if (equals > 0 && !word.startsWith("<") && !word.startsWith("\"")) {
                DirectorRuntimeParameter keyed = AnimusDirector.parameter(node, word.substring(0, equals));
                return keyed != null && !keyed.getDescriptor().isContextual() && given.add(keyed);
            }
            boolean placeholder = word.startsWith("<") && word.endsWith(">");
            Optional<DirectorRuntimeParameter> next = next();
            if (next.isEmpty() || !placeholder && required().isEmpty() && coordinate(word)) return coordinates(word);
            if (!placeholder && !fits(next.get(), word)) return false;
            given.add(next.get());
            return true;
        }

        /** {@code [name]} or {@code [name=value]} for one of its optional values, or {@code [value]} for the next it takes. */
        private boolean optional(String inside) {
            int equals = inside.indexOf('=');
            DirectorRuntimeParameter named = AnimusDirector.parameter(node, equals > 0 ? inside.substring(0, equals) : inside);
            if (named != null && !named.getDescriptor().isRequired() && !named.getDescriptor().isContextual()) return given.add(named);
            Optional<DirectorRuntimeParameter> next = next();
            if (next.isEmpty() || next.get().getDescriptor().isRequired()) return false;
            given.add(next.get());
            return true;
        }

        /** The value a bare word fills next: a required one, then those {@link Positional} lists. */
        private Optional<DirectorRuntimeParameter> next() {
            List<DirectorRuntimeParameter> required = required();
            if (!required.isEmpty()) return Optional.of(required.getFirst());
            return positional.stream().filter((DirectorRuntimeParameter parameter) -> !given.contains(parameter)).findFirst();
        }

        private List<DirectorRuntimeParameter> required() {
            return node.getParameters().stream().filter((DirectorRuntimeParameter parameter) -> parameter.getDescriptor().isRequired()
                    && !parameter.getDescriptor().isContextual() && !given.contains(parameter)).toList();
        }

        private boolean coordinate(String word) { return coordinates && coordinatesGiven < Coordinates.AXES.size() && Coordinates.coordinate(word); }

        private boolean coordinates(String word) {
            if (!coordinate(word)) return false;
            coordinatesGiven++;
            return true;
        }

        /** Whether a bare word converts to the value's type: any word is text, an enum takes one of its names exactly. */
        @SuppressWarnings("unchecked")
        private boolean fits(DirectorRuntimeParameter parameter, String word) {
            DirectorParameterDescriptor descriptor = parameter.getDescriptor();
            Class<?> type = descriptor.getType();
            if (type.isEnum()) {
                for (Object constant : type.getEnumConstants()) if (((Enum<?>) constant).name().equalsIgnoreCase(word)) return true;
                return false;
            }
            Optional<DirectorParser<Object>> parser = parsers.get((Class<Object>) type);
            if (parser.isEmpty()) return type == String.class;
            DirectorValue<Object> value = parser.get().parse(word.startsWith("\"") ? word.substring(1, word.length() - 1) : word);
            return value != null && value.getConfidence() != DirectorConfidence.INVALID;
        }
    }
}
