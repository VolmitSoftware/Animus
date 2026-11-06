package art.arcane.animus.core.text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The one formatter for player-facing text on every platform. Source text may use {@code &} colour and style codes,
 * {@code &#RRGGBB}, {@code &x&R&R&G&G&B&B}, Gloss's {@code [RRGGBB]}, section codes, backslash-u escapes such as {@code \\u2764}, literal Unicode symbols and the
 * common MiniMessage colour and decoration tags. {@link #colorize} turns it into section-coded text and
 * {@link #runs} splits that into styled runs a platform maps onto its native chat components.
 */
public final class RichText {
    public static final char SECTION = '§';
    private static final String CODES = "0123456789abcdefklmnorABCDEFKLMNOR";
    private static final String COLOURS = "0123456789abcdef";
    private static final int[] PALETTE = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
    private static final Map<String, Character> TAGS = Map.ofEntries(
            Map.entry("black", '0'), Map.entry("dark_blue", '1'), Map.entry("dark_green", '2'), Map.entry("dark_aqua", '3'),
            Map.entry("dark_red", '4'), Map.entry("dark_purple", '5'), Map.entry("gold", '6'), Map.entry("gray", '7'),
            Map.entry("grey", '7'), Map.entry("dark_gray", '8'), Map.entry("dark_grey", '8'), Map.entry("blue", '9'),
            Map.entry("green", 'a'), Map.entry("aqua", 'b'), Map.entry("red", 'c'), Map.entry("light_purple", 'd'),
            Map.entry("yellow", 'e'), Map.entry("white", 'f'), Map.entry("obfuscated", 'k'), Map.entry("bold", 'l'),
            Map.entry("b", 'l'), Map.entry("strikethrough", 'm'), Map.entry("st", 'm'), Map.entry("underlined", 'n'),
            Map.entry("u", 'n'), Map.entry("italic", 'o'), Map.entry("i", 'o'), Map.entry("em", 'o'), Map.entry("reset", 'r'));

    /** One stretch of text with a single style. An empty colour keeps the receiver's default colour. */
    public record Run(String text, OptionalInt rgb, boolean bold, boolean italic, boolean underlined, boolean strikethrough, boolean obfuscated) {
        public Run { Objects.requireNonNull(text); Objects.requireNonNull(rgb); }
    }

    private RichText() {}

    public static String colorize(String text) {
        if (text == null || text.isEmpty()) return "";
        String source = unescape(text);
        StringBuilder out = new StringBuilder(source.length());
        Deque<String> open = new ArrayDeque<>();
        int index = 0;
        while (index < source.length()) {
            char glyph = source.charAt(index);
            if (glyph == '&' && index + 1 < source.length() && source.charAt(index + 1) == '#' && hex(source, index + 2)) {
                out.append(hexCode(source.substring(index + 2, index + 8)));
                index += 8;
                continue;
            }
            if (glyph == '[' && index + 7 < source.length() && source.charAt(index + 7) == ']' && hex(source, index + 1)) {
                out.append(hexCode(source.substring(index + 1, index + 7)));
                index += 8;
                continue;
            }
            if ((glyph == '&' || glyph == SECTION) && repeatedHex(source, index, glyph)) {
                StringBuilder digits = new StringBuilder(6);
                for (int digit = 0; digit < 6; digit++) digits.append(source.charAt(index + 3 + digit * 2));
                out.append(hexCode(digits.toString()));
                index += 14;
                continue;
            }
            if (glyph == '&' && index + 1 < source.length() && CODES.indexOf(source.charAt(index + 1)) >= 0) {
                out.append(SECTION).append(Character.toLowerCase(source.charAt(index + 1)));
                index += 2;
                continue;
            }
            if (glyph == '<') {
                int end = source.indexOf('>', index);
                if (end > index + 1) {
                    String tag = source.substring(index + 1, end).toLowerCase(Locale.ROOT);
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
        StringBuilder out = new StringBuilder();
        for (Run run : runs(text)) out.append(run.text());
        return out.toString();
    }

    /** True when the text carries any colour or format code once colorized. */
    public static boolean formatted(String text) {
        String coloured = colorize(text);
        for (int index = 0; index + 1 < coloured.length(); index++)
            if (coloured.charAt(index) == SECTION && (CODES.indexOf(coloured.charAt(index + 1)) >= 0 || coloured.charAt(index + 1) == 'x')) return true;
        return false;
    }

    /**
     * Colorizes {@code template}, then fills each {@code {name}} with its value as plain text: a value's own colour codes,
     * tags and section signs are shown, never applied. Send the result through {@link #sectionRuns}, not {@link #runs}.
     */
    public static String format(String template, Map<String, String> values) {
        String coloured = colorize(template);
        StringBuilder out = new StringBuilder(coloured.length() + 64);
        int index = 0;
        while (index < coloured.length()) {
            char glyph = coloured.charAt(index);
            int end = glyph == '{' ? coloured.indexOf('}', index) : -1;
            String value = end > index ? values.get(coloured.substring(index + 1, end)) : null;
            if (value == null) { out.append(glyph); index++; continue; }
            out.append(value.replace(SECTION, '?'));
            index = end + 1;
        }
        return out.toString();
    }

    /** Splits colorized source text into styled runs. A colour code clears the styles before it, as in vanilla. */
    public static List<Run> runs(String text) { return sectionRuns(colorize(text)); }

    /** Splits text that is already section-coded, such as {@link #format} output, without reading {@code &} codes again. */
    public static List<Run> sectionRuns(String coloured) {
        if (coloured == null) return List.of();
        List<Run> runs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        OptionalInt rgb = OptionalInt.empty();
        boolean bold = false, italic = false, underlined = false, strikethrough = false, obfuscated = false;
        int index = 0;
        while (index < coloured.length()) {
            char glyph = coloured.charAt(index);
            if (glyph != SECTION || index + 1 >= coloured.length()) { current.append(glyph); index++; continue; }
            char code = Character.toLowerCase(coloured.charAt(index + 1));
            int consumed = 2;
            OptionalInt colour = OptionalInt.empty();
            if (code == 'x' && sectionHex(coloured, index)) {
                StringBuilder digits = new StringBuilder(6);
                for (int digit = 0; digit < 6; digit++) digits.append(coloured.charAt(index + 3 + digit * 2));
                colour = OptionalInt.of(Integer.parseInt(digits.toString(), 16));
                consumed = 14;
            } else if (COLOURS.indexOf(code) >= 0) {
                colour = OptionalInt.of(PALETTE[COLOURS.indexOf(code)]);
            } else if ("klmnor".indexOf(code) < 0) {
                current.append(glyph);
                index++;
                continue;
            }
            if (!current.isEmpty()) {
                runs.add(new Run(current.toString(), rgb, bold, italic, underlined, strikethrough, obfuscated));
                current.setLength(0);
            }
            if (colour.isPresent() || code == 'r') {
                rgb = colour;
                bold = italic = underlined = strikethrough = obfuscated = false;
            }
            switch (code) {
                case 'k' -> obfuscated = true;
                case 'l' -> bold = true;
                case 'm' -> strikethrough = true;
                case 'n' -> underlined = true;
                case 'o' -> italic = true;
                default -> {}
            }
            index += consumed;
        }
        if (!current.isEmpty()) runs.add(new Run(current.toString(), rgb, bold, italic, underlined, strikethrough, obfuscated));
        return runs;
    }

    /** A colour written as a name ({@code green}), a code ({@code a}) or {@code #RRGGBB}; empty when it is none of them. */
    public static Optional<String> colourCode(String colour) {
        String value = colour.trim().toLowerCase(Locale.ROOT);
        if (value.length() == 7 && value.charAt(0) == '#' && hex(value, 1)) return Optional.of(hexCode(value.substring(1)));
        if (value.length() == 1 && COLOURS.indexOf(value.charAt(0)) >= 0) return Optional.of(String.valueOf(SECTION) + value);
        Character code = TAGS.get(value);
        if (code == null || COLOURS.indexOf(code) < 0) return Optional.empty();
        return Optional.of(String.valueOf(SECTION) + code);
    }

    /** The RGB of a vanilla colour code such as {@code d}. */
    public static int legacyRgb(char code) {
        int index = COLOURS.indexOf(Character.toLowerCase(code));
        if (index < 0) throw new IllegalArgumentException("Not a colour code: " + code);
        return PALETTE[index];
    }

    /** The vanilla colour code closest to {@code rgb}. */
    public static char nearestLegacy(int rgb) {
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int index = 0; index < PALETTE.length; index++) {
            int red = (rgb >> 16 & 0xFF) - (PALETTE[index] >> 16 & 0xFF), green = (rgb >> 8 & 0xFF) - (PALETTE[index] >> 8 & 0xFF), blue = (rgb & 0xFF) - (PALETTE[index] & 0xFF);
            long distance = (long) red * red + (long) green * green + (long) blue * blue;
            if (distance < bestDistance) { bestDistance = distance; best = index; }
        }
        return COLOURS.charAt(best);
    }

    /** Section-coded text with every hex colour replaced by its legacy fallback (see {@link Palette#legacyOf}), for receivers without hex. */
    public static String legacy(String coloured) {
        if (coloured == null || coloured.indexOf(SECTION) < 0) return coloured;
        StringBuilder out = new StringBuilder(coloured.length());
        int index = 0;
        while (index < coloured.length()) {
            if (coloured.charAt(index) == SECTION && sectionHex(coloured, index)) {
                StringBuilder digits = new StringBuilder(6);
                for (int digit = 0; digit < 6; digit++) digits.append(coloured.charAt(index + 3 + digit * 2));
                out.append(SECTION).append(Palette.legacyOf(Integer.parseInt(digits.toString(), 16)));
                index += 14;
                continue;
            }
            out.append(coloured.charAt(index));
            index++;
        }
        return out.toString();
    }

    private static String unescape(String text) {
        if (text.indexOf('\\') < 0) return text;
        StringBuilder out = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            char glyph = text.charAt(index);
            if (glyph == '\\' && index + 1 < text.length() && Character.toLowerCase(text.charAt(index + 1)) == 'u' && digits(text, index + 2, 4)) {
                out.append((char) Integer.parseInt(text.substring(index + 2, index + 6), 16));
                index += 6;
                continue;
            }
            out.append(glyph);
            index++;
        }
        return out.toString();
    }

    private static String tag(String tag, Deque<String> open) {
        if (tag.startsWith("/")) {
            String name = tag.substring(1);
            if (!TAGS.containsKey(name) && !name.startsWith("#") && !name.startsWith("color") && !name.startsWith("colour")) return null;
            open.remove(name);
            StringBuilder rebuilt = new StringBuilder().append(SECTION).append('r');
            open.descendingIterator().forEachRemaining((String still) -> rebuilt.append(tag(still, new ArrayDeque<>())));
            return rebuilt.toString();
        }
        if (tag.startsWith("#") && tag.length() == 7 && hex(tag, 1)) { open.push(tag); return hexCode(tag.substring(1)); }
        if (tag.startsWith("color:")) return tag(tag.substring(6), open);
        if (tag.startsWith("colour:")) return tag(tag.substring(7), open);
        Character code = TAGS.get(tag);
        if (code == null) return null;
        if (code != 'r') open.push(tag); else open.clear();
        return String.valueOf(SECTION) + code;
    }

    private static boolean repeatedHex(String text, int from, char marker) {
        if (from + 14 > text.length() || Character.toLowerCase(text.charAt(from + 1)) != 'x') return false;
        for (int digit = 0; digit < 6; digit++) {
            int at = from + 2 + digit * 2;
            if (text.charAt(at) != marker || Character.digit(text.charAt(at + 1), 16) < 0) return false;
        }
        return true;
    }

    private static boolean sectionHex(String text, int from) { return repeatedHex(text, from, SECTION); }

    private static boolean hex(String text, int from) { return digits(text, from, 6); }

    private static boolean digits(String text, int from, int count) {
        if (from + count > text.length()) return false;
        for (int index = from; index < from + count; index++) if (Character.digit(text.charAt(index), 16) < 0) return false;
        return true;
    }

    private static String hexCode(String digits) {
        StringBuilder out = new StringBuilder().append(SECTION).append('x');
        for (char digit : digits.toLowerCase(Locale.ROOT).toCharArray()) out.append(SECTION).append(digit);
        return out.toString();
    }
}
