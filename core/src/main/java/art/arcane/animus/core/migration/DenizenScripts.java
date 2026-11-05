package art.arcane.animus.core.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A bounded reader for Denizen script files ({@code .dsc} and {@code .yml}). Denizen scripts are YAML-shaped: script
 * containers at the left margin, keys with nested keys, and {@code script:} lists whose entries are commands, where an
 * entry ending in {@code :} opens a nested block ({@code - if <...>:}). This reader keeps command lines verbatim, which
 * is how Denizen reads them, instead of applying YAML scalar rules to them. The browser converter has a line-for-line
 * copy of this class; both must give the same tree for the same text.
 */
final class DenizenScripts {
    static final int MAXIMUM_CHARS = 1 << 20, MAXIMUM_LINES = 20_000, MAXIMUM_DEPTH = 32;

    private DenizenScripts() {}

    sealed interface Node permits Scalar, Mapping, Sequence {
        int line();
    }

    record Scalar(String text, int line) implements Node {}

    record Entry(String key, Node value, int line) {}

    record Mapping(List<Entry> entries, int line) implements Node {
        /** The first entry whose key equals {@code key} ignoring case, as Denizen looks keys up. */
        Optional<Node> get(String key) {
            for (Entry entry : entries) if (entry.key().toLowerCase(Locale.ROOT).equals(key)) return Optional.of(entry.value());
            return Optional.empty();
        }

        Optional<String> text(String key) {
            Optional<Node> value = get(key);
            return value.isPresent() && value.get() instanceof Scalar scalar ? Optional.of(scalar.text()) : Optional.empty();
        }
    }

    /** One list entry: its text and, for an entry ending in a colon, the nested block it opens. */
    record Item(String text, Optional<Sequence> children, int line) {}

    record Sequence(List<Item> items, int line) implements Node {}

    record Container(String file, String name, String type, Mapping body, int line) {}

    record Parsed(List<Container> containers, List<String> issues) {}

    static final class Invalid extends Exception {
        Invalid(String message) { super(message); }
    }

    private record Line(int number, int indent, String text) {
        boolean item() { return text.equals("-") || text.startsWith("- "); }
    }

    /** Reads one file. A file that cannot be read is one issue and no containers; other files are unaffected. */
    static Parsed parse(String file, String text) {
        List<Container> containers = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        Mapping root;
        try { root = new Reader(lines(text)).document(); }
        catch (Invalid invalid) {
            issues.add(file + ": " + invalid.getMessage());
            return new Parsed(containers, issues);
        }
        for (Entry entry : root.entries()) {
            if (!(entry.value() instanceof Mapping body)) {
                issues.add(file + ":" + entry.line() + ": top-level key " + entry.key() + " is not a script container");
                continue;
            }
            Optional<String> type = body.text("type");
            if (type.isEmpty() || type.get().isBlank()) {
                issues.add(file + ":" + entry.line() + ": script " + entry.key() + " has no type");
                continue;
            }
            containers.add(new Container(file, entry.key(), type.get().trim().toLowerCase(Locale.ROOT), body, entry.line()));
        }
        return new Parsed(containers, issues);
    }

    private static List<Line> lines(String text) throws Invalid {
        if (text.length() > MAXIMUM_CHARS) throw new Invalid("file exceeds " + MAXIMUM_CHARS + " characters");
        String body = text.startsWith("﻿") ? text.substring(1) : text;
        String[] raw = body.split("\n", -1);
        if (raw.length > MAXIMUM_LINES) throw new Invalid("file exceeds " + MAXIMUM_LINES + " lines");
        List<Line> lines = new ArrayList<>();
        for (int index = 0; index < raw.length; index++) {
            String line = raw[index];
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            int indent = 0;
            while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
                if (line.charAt(indent) == '\t') throw new Invalid("line " + (index + 1) + ": tabs are not allowed in indentation");
                indent++;
            }
            String content = line.substring(indent).stripTrailing();
            if (content.isEmpty() || content.startsWith("#")) continue;
            lines.add(new Line(index + 1, indent, content));
        }
        return lines;
    }

    private static final class Reader {
        private final List<Line> lines;
        private int position;

        Reader(List<Line> lines) { this.lines = lines; }

        Mapping document() throws Invalid {
            if (lines.isEmpty()) return new Mapping(List.of(), 1);
            if (lines.getFirst().indent() != 0) throw new Invalid("line " + lines.getFirst().number() + ": the first key must start at the left margin");
            Mapping root = mapping(0, 0);
            if (position < lines.size()) throw new Invalid("line " + lines.get(position).number() + ": unexpected indentation");
            return root;
        }

        private Line peek() { return position < lines.size() ? lines.get(position) : null; }

        private Node block(int indent, int depth) throws Invalid {
            if (depth > MAXIMUM_DEPTH) throw new Invalid("line " + peek().number() + ": nesting exceeds " + MAXIMUM_DEPTH);
            return peek().item() ? sequence(indent, depth) : mapping(indent, depth);
        }

        private Mapping mapping(int indent, int depth) throws Invalid {
            List<Entry> entries = new ArrayList<>();
            int first = peek().number();
            while (peek() != null && peek().indent() == indent && !peek().item()) {
                Line line = lines.get(position++);
                int split = separator(line.text());
                if (split < 0) throw new Invalid("line " + line.number() + ": expected key: value");
                String key = unquote(line.text().substring(0, split).trim());
                String value = split + 1 < line.text().length() ? line.text().substring(split + 1).trim() : "";
                Line next = peek();
                Node child;
                if (value.isEmpty() && next != null && (next.indent() > indent || (next.indent() == indent && next.item()))) child = block(next.indent(), depth + 1);
                else if (value.equals("|") || value.equals(">") || value.equals("|-") || value.equals(">-")) child = new Scalar(folded(indent, value.startsWith("|")), line.number());
                else child = new Scalar(unquote(value + continuation(indent)), line.number());
                entries.add(new Entry(key, child, line.number()));
            }
            return new Mapping(entries, first);
        }

        private Sequence sequence(int indent, int depth) throws Invalid {
            List<Item> items = new ArrayList<>();
            int first = peek().number();
            while (peek() != null && peek().indent() == indent && peek().item()) {
                Line line = lines.get(position++);
                String text = line.text().length() > 1 ? line.text().substring(2).trim() : "";
                Line next = peek();
                if (text.endsWith(":") && next != null && next.indent() > indent && next.item()) {
                    if (depth + 1 > MAXIMUM_DEPTH) throw new Invalid("line " + next.number() + ": nesting exceeds " + MAXIMUM_DEPTH);
                    Sequence children = sequence(next.indent(), depth + 1);
                    items.add(new Item(text.substring(0, text.length() - 1).trim(), Optional.of(children), line.number()));
                } else if (next != null && next.indent() > indent && !next.item() && (text.endsWith(":") || separator(text) >= 0)) {
                    // A mapping inside a data list, such as "- name: x" with more keys below. Commands never take this shape.
                    if (depth + 1 > MAXIMUM_DEPTH) throw new Invalid("line " + next.number() + ": nesting exceeds " + MAXIMUM_DEPTH);
                    mapping(next.indent(), depth + 1);
                    items.add(new Item(text, Optional.empty(), line.number()));
                } else {
                    String full = text + continuation(indent);
                    items.add(new Item(wholeQuote(full) ? unquote(full) : full, Optional.empty(), line.number()));
                }
            }
            return new Sequence(items, first);
        }

        /** Deeper plain lines continue the scalar above them, joined with single spaces as YAML folds them. */
        private String continuation(int indent) throws Invalid {
            StringBuilder text = new StringBuilder();
            while (peek() != null && peek().indent() > indent) {
                Line line = lines.get(position);
                if (line.item() || separator(line.text()) >= 0 && !wholeQuote(line.text()))
                    throw new Invalid("line " + line.number() + ": unexpected indentation");
                text.append(' ').append(line.text());
                position++;
            }
            return text.toString();
        }

        private String folded(int indent, boolean literal) {
            StringBuilder text = new StringBuilder();
            while (peek() != null && peek().indent() > indent) {
                if (!text.isEmpty()) text.append(literal ? '\n' : ' ');
                text.append(lines.get(position++).text());
            }
            return text.toString();
        }
    }

    /** The colon that ends a key: the first one followed by a space, or a final one. Quoted keys end at their closing quote. */
    static int separator(String text) {
        int start = 0;
        if (!text.isEmpty() && (text.charAt(0) == '\'' || text.charAt(0) == '"')) {
            int close = text.indexOf(text.charAt(0), 1);
            if (close < 0) return -1;
            start = close + 1;
        }
        for (int index = start; index < text.length(); index++) {
            if (text.charAt(index) != ':') continue;
            if (index + 1 == text.length() || text.charAt(index + 1) == ' ') return index;
        }
        return -1;
    }

    static boolean wholeQuote(String text) {
        if (text.length() < 2) return false;
        char quote = text.charAt(0);
        if ((quote != '\'' && quote != '"') || text.charAt(text.length() - 1) != quote) return false;
        for (int index = 1; index < text.length() - 1; index++) {
            char at = text.charAt(index);
            if (quote == '\'' && at == '\'') { if (index + 1 < text.length() - 1 && text.charAt(index + 1) == '\'') { index++; continue; } return false; }
            if (quote == '"' && at == '\\') { index++; continue; }
            if (quote == '"' && at == '"') return false;
        }
        return true;
    }

    /** YAML quoting on a whole scalar: {@code 'it''s'} and {@code "a \"b\""}; anything else is returned as written. */
    static String unquote(String text) {
        if (!wholeQuote(text)) return text;
        String inner = text.substring(1, text.length() - 1);
        if (text.charAt(0) == '\'') return inner.replace("''", "'");
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < inner.length(); index++) {
            char at = inner.charAt(index);
            if (at == '\\' && index + 1 < inner.length()) {
                char next = inner.charAt(++index);
                out.append(switch (next) { case 'n' -> '\n'; case 't' -> '\t'; default -> next; });
            } else out.append(at);
        }
        return out.toString();
    }
}
