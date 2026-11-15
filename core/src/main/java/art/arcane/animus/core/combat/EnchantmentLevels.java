package art.arcane.animus.core.combat;

import art.arcane.animus.core.platform.MinecraftIds;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Enchantment levels read from an item's component text, as a give command writes it:
 * {@code [minecraft:enchantments={"minecraft:sharpness":5}]}, the bare {@code enchantments={sharpness:5}} form, or the
 * older {@code enchantments={levels:{sharpness:5}}}. Stored enchantments on books do not count. Anything unreadable is ignored.
 */
public final class EnchantmentLevels {
    private EnchantmentLevels() {}

    /** Level by namespaced enchantment id; empty when the item has none. */
    public static Map<String, Integer> of(Optional<String> components) {
        if (components.isEmpty()) return Map.of();
        String text = components.get();
        int key = find(text);
        if (key < 0) return Map.of();
        int open = text.indexOf('{', key);
        if (open < 0) return Map.of();
        Map<String, Integer> levels = new HashMap<>();
        read(text, open, levels);
        return Map.copyOf(levels);
    }

    /** Where the value of the enchantments component starts, or -1. */
    private static int find(String text) {
        int from = 0;
        while (true) {
            int at = text.indexOf("enchantments", from);
            if (at < 0) return -1;
            int end = at + "enchantments".length();
            char before = at == 0 ? '[' : text.charAt(at - 1);
            boolean stored = text.startsWith("stored_", Math.max(0, at - 7)) && at >= 7;
            int equals = skip(text, end);
            if (!stored && (before == '[' || before == ',' || before == ':' || before == ' ') && equals < text.length() && text.charAt(equals) == '=') return equals + 1;
            from = end;
        }
    }

    /** Reads one {@code {key:value,...}} map at {@code open}; a nested {@code levels} map is read in place. Returns the index after it. */
    private static int read(String text, int open, Map<String, Integer> into) {
        int at = open + 1;
        while (at < text.length()) {
            at = skip(text, at);
            if (at >= text.length() || text.charAt(at) == '}') return at + 1;
            if (text.charAt(at) == ',') { at++; continue; }
            int keyEnd;
            String key;
            if (text.charAt(at) == '"' || text.charAt(at) == '\'') {
                char quote = text.charAt(at);
                keyEnd = text.indexOf(quote, at + 1);
                if (keyEnd < 0) return text.length();
                key = text.substring(at + 1, keyEnd);
                keyEnd++;
            } else {
                keyEnd = at;
                while (keyEnd < text.length() && ":,}=".indexOf(text.charAt(keyEnd)) < 0) keyEnd++;
                // A namespaced bare key such as minecraft:sharpness keeps its colon.
                if (keyEnd < text.length() && text.charAt(keyEnd) == ':' && keyEnd + 1 < text.length() && Character.isLetter(text.charAt(keyEnd + 1))) {
                    keyEnd++;
                    while (keyEnd < text.length() && ":,}=".indexOf(text.charAt(keyEnd)) < 0) keyEnd++;
                }
                key = text.substring(at, keyEnd).trim();
            }
            at = skip(text, keyEnd);
            if (at >= text.length() || text.charAt(at) != ':') return text.length();
            at = skip(text, at + 1);
            if (at < text.length() && text.charAt(at) == '{') {
                at = key.equals("levels") ? read(text, at, into) : skipValue(text, at);
                continue;
            }
            int valueEnd = at;
            while (valueEnd < text.length() && ",}".indexOf(text.charAt(valueEnd)) < 0) valueEnd++;
            String value = text.substring(at, valueEnd).trim().replaceAll("[bBsSiIlL]$", "");
            try {
                int level = Integer.parseInt(value);
                if (level > 0 && MinecraftIds.valid(key)) into.merge(MinecraftIds.normalize(key), Math.min(level, 255), Math::max);
            } catch (NumberFormatException ignored) { /* not a level */ }
            at = valueEnd;
        }
        return at;
    }

    private static int skipValue(String text, int open) {
        int depth = 0;
        for (int at = open; at < text.length(); at++) {
            char c = text.charAt(at);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return at + 1;
        }
        return text.length();
    }

    private static int skip(String text, int at) {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
        return at;
    }
}
