package art.arcane.animus.core.gui;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Gloss's text animation functions, with Gloss's arguments, limits and results: {@code marquee}, {@code timeline},
 * {@code typewriter}, {@code flash}, {@code wipe}, {@code scanner}, {@code scramble}, {@code odometer} and {@code wave}.
 * They are pure; the caller supplies the step, usually from {@code time.seconds}.
 */
final class GuiTextFunctions {
    static final Set<String> NAMES = Set.of("marquee", "timeline", "typewriter", "flash", "wipe", "scanner", "scramble", "odometer", "wave");
    private static final int MAXIMUM_TEXT = 256, MAXIMUM_STYLED = 64, MAXIMUM_WINDOW = 64, MAXIMUM_STEPS = 64, MAXIMUM_STYLES = 16;
    private static final double MAXIMUM_TIMELINE_SECONDS = 3600, SAFE_WHOLE = 9_007_199_254_740_991.0;
    private static final int[] SCRAMBLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#$?".codePoints().toArray();
    private static final Pattern FORMATTING = Pattern.compile("(?:&[0-9A-Fa-fK-Ok-oRr]|§[0-9A-Fa-fK-Ok-oRr]|\\[[0-9A-Fa-f]{6}]|%[^%\\s]+%)");
    private static final Pattern STYLE = Pattern.compile("(?:&[0-9A-Fa-fRr]|\\[[0-9A-Fa-f]{6}])(?:&[0-9A-Fa-fK-Ok-oRr]|\\[[0-9A-Fa-f]{6}])?");

    private GuiTextFunctions() {}

    static String call(String name, List<Object> args) {
        return switch (name) {
            case "marquee" -> {
                count(name, args, 3);
                int[] text = plain(name, string(name, args, 0), MAXIMUM_TEXT);
                int width = whole(name, args, 1, 1, MAXIMUM_WINDOW);
                int start = step(number(name, args, 2), text.length + width);
                StringBuilder out = new StringBuilder(width);
                for (int index = 0; index < width; index++) {
                    int source = (start + index) % (text.length + width);
                    out.appendCodePoint(source < text.length ? text[source] : ' ');
                }
                yield out.toString();
            }
            case "timeline" -> timeline(name, args);
            case "typewriter" -> {
                count(name, args, 3);
                int[] text = plain(name, string(name, args, 0), MAXIMUM_TEXT);
                if (text.length == 0) yield "";
                int hold = whole(name, args, 2, 0, 1200);
                int phase = step(number(name, args, 1), text.length * 2 + hold);
                int visible = phase <= text.length ? phase : phase < text.length + hold ? text.length : text.length * 2 + hold - phase;
                yield prefix(text, visible, false);
            }
            case "flash" -> {
                count(name, args, 3);
                String first = checked(name, string(name, args, 0)), second = checked(name, string(name, args, 1));
                yield step(number(name, args, 2), 2) == 0 ? first : second;
            }
            case "wipe" -> {
                count(name, args, 2);
                int[] text = plain(name, string(name, args, 0), MAXIMUM_TEXT);
                if (text.length == 0) yield "";
                int phase = step(number(name, args, 1), text.length * 2);
                yield prefix(text, phase <= text.length ? phase : text.length * 2 - phase, true);
            }
            case "scanner" -> {
                count(name, args, 4);
                int[] text = plain(name, string(name, args, 0), MAXIMUM_STYLED);
                if (text.length == 0) yield "";
                String base = style(name, string(name, args, 1)), highlight = style(name, string(name, args, 2));
                int active = step(number(name, args, 3), text.length);
                StringBuilder out = new StringBuilder().append(active == 0 ? highlight : base);
                for (int index = 0; index < text.length; index++) {
                    out.appendCodePoint(text[index]);
                    if (index == active) out.append(base);
                    else if (index + 1 == active) out.append(highlight);
                }
                yield out.toString();
            }
            case "scramble" -> {
                count(name, args, 2);
                int[] text = plain(name, string(name, args, 0), MAXIMUM_TEXT);
                if (text.length == 0) yield "";
                int phase = step(number(name, args, 1), text.length + 2);
                int resolved = Math.min(phase, text.length);
                StringBuilder out = new StringBuilder(text.length);
                for (int index = 0; index < text.length; index++) {
                    int glyph = text[index];
                    if (index < resolved || glyph == 0x09 || glyph == 0x0B || glyph == 0x0C || glyph == 0x20) out.appendCodePoint(glyph);
                    else out.appendCodePoint(SCRAMBLE[Math.floorMod(phase * 31 + index * 17 + glyph, SCRAMBLE.length)]);
                }
                yield out.toString();
            }
            case "odometer" -> odometer(name, args);
            case "wave" -> {
                count(name, args, 3);
                int[] text = plain(name, string(name, args, 0), MAXIMUM_STYLED);
                if (text.length == 0) yield "";
                if (!(args.get(1) instanceof List<?> raw) || raw.isEmpty() || raw.size() > MAXIMUM_STYLES)
                    throw new GuiExpression.Invalid(name + " argument 2 must contain between 1 and " + MAXIMUM_STYLES + " styles");
                String[] styles = new String[raw.size()];
                for (int index = 0; index < raw.size(); index++) {
                    if (!(raw.get(index) instanceof String style)) throw new GuiExpression.Invalid(name + " argument 2 entries must be strings");
                    styles[index] = style(name, style);
                }
                int start = step(number(name, args, 2), styles.length);
                StringBuilder out = new StringBuilder();
                for (int index = 0; index < text.length; index++) out.append(styles[(start + index) % styles.length]).appendCodePoint(text[index]);
                yield out.append(styles[0]).toString();
            }
            default -> throw new GuiExpression.Invalid("unknown function '" + name + "'");
        };
    }

    private static String timeline(String name, List<Object> args) {
        count(name, args, 2);
        if (!(args.get(0) instanceof List<?> steps)) throw new GuiExpression.Invalid(name + " argument 1 must be a list");
        if (steps.isEmpty() || steps.size() > MAXIMUM_STEPS) throw new GuiExpression.Invalid(name + " argument 1 must contain between 1 and " + MAXIMUM_STEPS + " steps");
        String[] texts = new String[steps.size()];
        double[] durations = new double[steps.size()];
        double total = 0;
        for (int index = 0; index < steps.size(); index++) {
            if (!(steps.get(index) instanceof List<?> step) || step.size() != 2 || !(step.get(0) instanceof String text)
                    || !(step.get(1) instanceof Double duration) || !Double.isFinite(duration) || duration <= 0)
                throw new GuiExpression.Invalid(name + " step " + (index + 1) + " must be [text, positiveSeconds]");
            texts[index] = checked(name, text);
            durations[index] = duration;
            total += duration;
            if (total > MAXIMUM_TIMELINE_SECONDS) throw new GuiExpression.Invalid(name + " total duration must not exceed " + (int) MAXIMUM_TIMELINE_SECONDS + " seconds");
        }
        double elapsed = number(name, args, 1);
        if (!Double.isFinite(elapsed)) throw new GuiExpression.Invalid(name + " argument 2 must be finite");
        double position = elapsed % total;
        if (position < 0) position += total;
        for (int index = 0; index < durations.length; index++) {
            if (position < durations[index]) return texts[index];
            position -= durations[index];
        }
        return texts[texts.length - 1];
    }

    private static String odometer(String name, List<Object> args) {
        count(name, args, 4);
        double from = number(name, args, 0), to = number(name, args, 1), progress = number(name, args, 2);
        if (!Double.isFinite(from) || !Double.isFinite(to) || !Double.isFinite(progress)) throw new GuiExpression.Invalid(name + " numeric arguments must be finite");
        if (Math.abs(from) > SAFE_WHOLE || Math.abs(to) > SAFE_WHOLE) throw new GuiExpression.Invalid(name + " endpoints must stay within the safe whole-number range");
        if (from != Math.rint(from) || to != Math.rint(to)) throw new GuiExpression.Invalid(name + " endpoints must be whole numbers");
        int digits = whole(name, args, 3, 1, 16);
        long value = Math.round(from + (to - from) * Math.clamp(progress, 0, 1));
        String raw = Long.toString(value);
        boolean negative = raw.charAt(0) == '-';
        String magnitude = negative ? raw.substring(1) : raw;
        String padded = "0".repeat(Math.max(0, digits - magnitude.length())) + magnitude;
        return negative ? "-" + padded : padded;
    }

    private static String checked(String name, String text) { points(name, text, MAXIMUM_TEXT); return text; }

    private static int[] points(String name, String text, int maximum) {
        line(name, text);
        if (text.codePointCount(0, text.length()) > maximum) throw new GuiExpression.Invalid(name + " text must not exceed " + maximum + " characters");
        return text.codePoints().toArray();
    }

    private static int[] plain(String name, String text, int maximum) {
        if (FORMATTING.matcher(text).find()) throw new GuiExpression.Invalid(name + " text must be plain; put formatting outside the text argument");
        int[] points = points(name, text, maximum);
        for (int point : points)
            if (point == 0x200D || point >= 0x0300 && point <= 0x036F || point >= 0x1AB0 && point <= 0x1AFF || point >= 0x1DC0 && point <= 0x1DFF
                    || point >= 0x20D0 && point <= 0x20FF || point >= 0xFE00 && point <= 0xFE0F || point >= 0xFE20 && point <= 0xFE2F
                    || point >= 0x1F1E6 && point <= 0x1F1FF || point >= 0x1F3FB && point <= 0x1F3FF || point >= 0xE0020 && point <= 0xE007F
                    || point >= 0xE0100 && point <= 0xE01EF)
                throw new GuiExpression.Invalid(name + " text must use standalone characters, not combined emoji or marks");
        return points;
    }

    private static String style(String name, String value) {
        line(name, value);
        if (!STYLE.matcher(value).matches()) throw new GuiExpression.Invalid(name + " styles must start with a color or reset and contain at most two formatting codes");
        return value;
    }

    private static void line(String name, String value) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf(0x85) >= 0 || value.indexOf(0x2028) >= 0 || value.indexOf(0x2029) >= 0)
            throw new GuiExpression.Invalid(name + " text must stay on one line");
    }

    private static String prefix(int[] text, int visible, boolean pad) {
        StringBuilder out = new StringBuilder(text.length);
        for (int index = 0; index < visible; index++) out.appendCodePoint(text[index]);
        if (pad) out.append(" ".repeat(text.length - visible));
        return out.toString();
    }

    private static int step(double value, int divisor) {
        if (!Double.isFinite(value)) throw new GuiExpression.Invalid("animation step must be finite");
        return (int) Math.floorMod((long) Math.floor(value), (long) divisor);
    }

    private static int whole(String name, List<Object> args, int index, int minimum, int maximum) {
        double value = number(name, args, index);
        if (value != Math.rint(value) || value < minimum || value > maximum)
            throw new GuiExpression.Invalid(name + " argument " + (index + 1) + " must be a whole number in [" + minimum + ", " + maximum + "]");
        return (int) value;
    }

    private static void count(String name, List<Object> args, int expected) {
        if (args.size() != expected) throw new GuiExpression.Invalid(name + " expects " + expected + " argument(s), got " + args.size());
    }

    private static double number(String name, List<Object> args, int index) {
        if (args.get(index) instanceof Double value) return value;
        throw new GuiExpression.Invalid(name + " argument " + (index + 1) + " must be a number");
    }

    private static String string(String name, List<Object> args, int index) {
        if (args.get(index) instanceof String value) return value;
        throw new GuiExpression.Invalid(name + " argument " + (index + 1) + " must be a string");
    }
}
