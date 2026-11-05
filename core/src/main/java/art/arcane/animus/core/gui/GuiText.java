package art.arcane.animus.core.gui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;

/**
 * Turns document text into legacy section-coded text that every platform renders in window titles and item names:
 * {@code &} colour codes, {@code &#RRGGBB}, and the common MiniMessage colour and decoration tags.
 */
public final class GuiText {
    private static final char SECTION = '§';
    private static final String CODES = "0123456789abcdefklmnorABCDEFKLMNOR";
    private static final Map<String, Character> TAGS = Map.ofEntries(
            Map.entry("black", '0'), Map.entry("dark_blue", '1'), Map.entry("dark_green", '2'), Map.entry("dark_aqua", '3'),
            Map.entry("dark_red", '4'), Map.entry("dark_purple", '5'), Map.entry("gold", '6'), Map.entry("gray", '7'),
            Map.entry("grey", '7'), Map.entry("dark_gray", '8'), Map.entry("dark_grey", '8'), Map.entry("blue", '9'),
            Map.entry("green", 'a'), Map.entry("aqua", 'b'), Map.entry("red", 'c'), Map.entry("light_purple", 'd'),
            Map.entry("yellow", 'e'), Map.entry("white", 'f'), Map.entry("obfuscated", 'k'), Map.entry("bold", 'l'),
            Map.entry("b", 'l'), Map.entry("strikethrough", 'm'), Map.entry("st", 'm'), Map.entry("underlined", 'n'),
            Map.entry("u", 'n'), Map.entry("italic", 'o'), Map.entry("i", 'o'), Map.entry("em", 'o'), Map.entry("reset", 'r'));

    private GuiText() {}

    public static String colorize(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(text.length());
        Deque<String> open = new ArrayDeque<>();
        int index = 0;
        while (index < text.length()) {
            char glyph = text.charAt(index);
            if (glyph == '&' && index + 1 < text.length() && text.charAt(index + 1) == '#' && hex(text, index + 2)) {
                out.append(hexCode(text.substring(index + 2, index + 8)));
                index += 8;
                continue;
            }
            if (glyph == '&' && index + 1 < text.length() && CODES.indexOf(text.charAt(index + 1)) >= 0) {
                out.append(SECTION).append(Character.toLowerCase(text.charAt(index + 1)));
                index += 2;
                continue;
            }
            if (glyph == '<') {
                int end = text.indexOf('>', index);
                if (end > index + 1) {
                    String tag = text.substring(index + 1, end).toLowerCase(Locale.ROOT);
                    String code = tag(tag, open);
                    if (code != null) {
                        out.append(code);
                        index = end + 1;
                        continue;
                    }
                }
            }
            out.append(glyph);
            index++;
        }
        return out.toString();
    }

    /** Removes every colour and format code, for logs and plain comparisons. */
    public static String plain(String text) {
        String coloured = colorize(text);
        StringBuilder out = new StringBuilder(coloured.length());
        for (int index = 0; index < coloured.length(); index++) {
            char glyph = coloured.charAt(index);
            if (glyph == SECTION && index + 1 < coloured.length()) { index++; continue; }
            out.append(glyph);
        }
        return out.toString();
    }

    private static String tag(String tag, Deque<String> open) {
        if (tag.startsWith("/")) {
            String name = tag.substring(1);
            if (!TAGS.containsKey(name) && !name.startsWith("#") && !name.startsWith("color")) return null;
            open.remove(name);
            StringBuilder rebuilt = new StringBuilder().append(SECTION).append('r');
            open.descendingIterator().forEachRemaining((String still) -> rebuilt.append(tag(still, new ArrayDeque<>())));
            return rebuilt.toString();
        }
        if (tag.startsWith("#") && tag.length() == 7 && hex(tag, 1)) { open.push(tag); return hexCode(tag.substring(1)); }
        if (tag.startsWith("color:")) return tag(tag.substring(6), open);
        Character code = TAGS.get(tag);
        if (code == null) return null;
        if (code != 'r') open.push(tag); else open.clear();
        return String.valueOf(SECTION) + code;
    }

    private static boolean hex(String text, int from) {
        if (from + 6 > text.length()) return false;
        for (int index = from; index < from + 6; index++) if (Character.digit(text.charAt(index), 16) < 0) return false;
        return true;
    }

    private static String hexCode(String digits) {
        StringBuilder out = new StringBuilder().append(SECTION).append('x');
        for (char digit : digits.toLowerCase(Locale.ROOT).toCharArray()) out.append(SECTION).append(digit);
        return out.toString();
    }
}
