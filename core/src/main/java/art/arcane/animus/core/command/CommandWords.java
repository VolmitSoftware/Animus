package art.arcane.animus.core.command;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits the text after {@code /animus} into Director tokens. Words split on spaces. A double-quoted value keeps its
 * spaces ({@code name="Town Guard"} or {@code "Town Guard"}), with {@code \"} and {@code \\} as escapes. A JSON object
 * ({@code {...}}, bare or after {@code key=}) is one token exactly as typed, braces and quotes included, so a patch reaches
 * the command unchanged. An unclosed quote or object runs to the end of the line.
 */
public final class CommandWords {
    private CommandWords() {}

    public static List<String> tokens(String[] arguments) { return tokens(String.join(" ", arguments)); }

    public static List<String> tokens(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean started = false;
        int index = 0;
        while (index < line.length()) {
            char glyph = line.charAt(index);
            boolean valueStart = current.isEmpty() || current.charAt(current.length() - 1) == '=';
            if (glyph == ' ') {
                if (started) tokens.add(current.toString());
                current.setLength(0);
                started = false;
                index++;
            } else if (glyph == '"' && valueStart) {
                started = true;
                index = quoted(line, index + 1, current);
            } else if (glyph == '{' && valueStart) {
                started = true;
                index = object(line, index, current);
            } else {
                started = true;
                current.append(glyph);
                index++;
            }
        }
        if (started) tokens.add(current.toString());
        return tokens;
    }

    /** Appends a quoted value without its quotes; returns the index after the closing quote. */
    private static int quoted(String line, int index, StringBuilder out) {
        while (index < line.length()) {
            char glyph = line.charAt(index);
            if (glyph == '\\' && index + 1 < line.length() && (line.charAt(index + 1) == '"' || line.charAt(index + 1) == '\\')) {
                out.append(line.charAt(index + 1));
                index += 2;
            } else if (glyph == '"') {
                return index + 1;
            } else {
                out.append(glyph);
                index++;
            }
        }
        return index;
    }

    /** Appends a balanced JSON object as typed, honouring JSON strings; returns the index after its closing brace. */
    private static int object(String line, int index, StringBuilder out) {
        int depth = 0;
        boolean string = false;
        while (index < line.length()) {
            char glyph = line.charAt(index);
            out.append(glyph);
            index++;
            if (string) {
                if (glyph == '\\' && index < line.length()) { out.append(line.charAt(index)); index++; }
                else if (glyph == '"') string = false;
            } else if (glyph == '"') string = true;
            else if (glyph == '{') depth++;
            else if (glyph == '}' && --depth == 0) return index;
        }
        return index;
    }
}
