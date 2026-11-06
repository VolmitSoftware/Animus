package art.arcane.animus.core.text;

import java.util.Locale;

/**
 * Animus's own colours: pinks on black and greys. Every plugin-chosen colour in chat, bars, menus and the console splash
 * comes from here; semantic colours (green success, red error, yellow warning) stay as they are where they carry meaning.
 * A token's {@link #toString()} is its {@link RichText} source code, so templates read {@code Palette.PINK + "Animus"}.
 */
public enum Palette {
    PINK(0xFF5FA8, 'd'),
    PINK_DEEP(0xE0408C, '5'),
    PINK_LIGHT(0xFFB3D6, 'd'),
    TEXT(0xFFFFFF, 'f'),
    GREY(0xAAAAAA, '7'),
    DARK_GREY(0x555555, '8'),
    BLACK(0x000000, '0');

    /** The chat prefix on Animus's own operator lines. */
    public static final String PREFIX = DARK_GREY + "[" + PINK + "Animus" + DARK_GREY + "]" + GREY + ": ";

    private final int rgb;
    private final char legacy;

    Palette(int rgb, char legacy) {
        this.rgb = rgb;
        this.legacy = legacy;
    }

    public int rgb() { return rgb; }

    /** The nearest vanilla colour code, for receivers that cannot show hex (a Spigot console). */
    public char legacy() { return legacy; }

    /** {@code &#RRGGBB} for the pinks; the greys and black are exactly vanilla colours, so they keep their {@code &} code. */
    public String code() { return rgb == RichText.legacyRgb(legacy) ? "&" + legacy : "&#" + String.format(Locale.ROOT, "%06X", rgb); }

    @Override public String toString() { return code(); }

    /** The legacy code a hex colour falls back to: a palette colour's own fallback, otherwise the nearest vanilla colour. */
    public static char legacyOf(int rgb) {
        for (Palette tone : values()) if (tone.rgb == rgb) return tone.legacy;
        return RichText.nearestLegacy(rgb);
    }

    /**
     * The Animus look of a plain command reply. A reply that starts with {@code Animus} or {@code Animus <area>:} gets that
     * lead in pink and the rest in grey; any other plain reply gets {@link #PREFIX}. The reply's own text is left exactly
     * as written, and a reply that already carries section codes is returned unchanged. Pass the result through
     * {@link RichText#legacy} for a receiver without hex.
     */
    public static String reply(String message) {
        if (message == null || message.indexOf(RichText.SECTION) >= 0) return message;
        int lead = "Animus".length();
        if (!message.startsWith("Animus") || message.length() > lead && Character.isLetterOrDigit(message.charAt(lead)))
            return RichText.colorize(PREFIX) + message;
        int colon = message.indexOf(':');
        boolean area = colon > 0 && colon <= 40 && message.substring(lead, colon).chars().allMatch((int c) -> c == ' ' || Character.isLetter(c));
        int end = area ? colon : lead;
        String rest = message.substring(area ? colon + 1 : lead);
        String head = RichText.colorize(PINK + message.substring(0, end) + (area ? DARK_GREY + ":" : "") + GREY);
        return head + rest;
    }

    /** A refusal: the prefix, then the reason in semantic red. */
    public static String error(String message) { return RichText.colorize(PREFIX + "&c") + message; }

    /** A finished change: the prefix, then the result in semantic green. */
    public static String success(String message) { return RichText.colorize(PREFIX + "&a") + message; }
}
