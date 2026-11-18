package art.arcane.animus.core.text;

import java.util.List;
import java.util.Locale;

/** Internal codes as the words a reply shows a person: no constant names, no hyphenated codes. */
public final class PlainWords {
    private PlainWords() {}

    /** {@code WAITING_MODULES} or {@code dependency-unavailable} as {@code waiting modules} or {@code dependency unavailable}. */
    public static String of(String code) { return code == null ? "" : code.replace('_', ' ').replace('-', ' ').toLowerCase(Locale.ROOT); }

    public static String of(Enum<?> value) { return of(value.name()); }

    /**
     * A reason that may start with a code, such as {@code module-not-found: town}: the code in words, then the rest as
     * written. A reason that is already a sentence stays as it is.
     */
    public static String reason(String reason) {
        if (reason == null) return "";
        int colon = reason.indexOf(':');
        String head = colon < 0 ? reason : reason.substring(0, colon);
        if (!head.matches("[a-z0-9]+(?:[-_][a-z0-9]+)+|[A-Z0-9]+(?:_[A-Z0-9]+)*")) return reason;
        return of(head) + (colon < 0 ? "" : reason.substring(colon));
    }

    /** {@code a}, {@code a and b}, or {@code a, b and c}. */
    public static String list(List<String> items) {
        if (items.size() <= 1) return String.join("", items);
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.getLast();
    }

    /** {@code 1 call} or {@code 3 calls}. */
    public static String count(long count, String one, String many) { return count + " " + (count == 1 ? one : many); }
}
