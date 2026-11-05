package art.arcane.animus.core.combat;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A case-insensitive regular expression that matches anywhere in a name. It is compiled once when the definition is
 * read. Matching is bounded: the pattern is at most {@link #MAXIMUM_LENGTH} characters, the text at most
 * {@link #MAXIMUM_TEXT} characters, and a match that reads more than {@link #MAXIMUM_STEPS} characters fails.
 * Equality is by source text, so unchanged settings compare equal.
 */
public final class NamePattern {
    public static final int MAXIMUM_LENGTH = 128;
    static final int MAXIMUM_TEXT = 256;
    static final int MAXIMUM_STEPS = 20_000;
    private final String source;
    private final Pattern pattern;

    private NamePattern(String source, Pattern pattern) {
        this.source = source;
        this.pattern = pattern;
    }

    /** @throws IllegalArgumentException naming the field when the pattern is too long or not a valid expression */
    public static NamePattern compile(String source, String field) {
        Objects.requireNonNull(source, "source");
        if (source.isEmpty() || source.length() > MAXIMUM_LENGTH)
            throw new IllegalArgumentException(field + ": pattern must be 1.." + MAXIMUM_LENGTH + " characters");
        try {
            return new NamePattern(source, Pattern.compile(source, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        } catch (PatternSyntaxException invalid) {
            throw new IllegalArgumentException(field + ": invalid pattern: " + invalid.getDescription().toLowerCase(Locale.ROOT));
        }
    }

    public String source() { return source; }

    /** Whether the pattern occurs in the text. False when the text is too long or the match runs out of steps. */
    public boolean matches(String text) {
        if (text == null || text.length() > MAXIMUM_TEXT) return false;
        Matcher matcher = pattern.matcher(new Bounded(text));
        try {
            return matcher.find();
        } catch (StepLimit exhausted) {
            return false;
        }
    }

    @Override public boolean equals(Object other) { return other instanceof NamePattern that && source.equals(that.source); }
    @Override public int hashCode() { return source.hashCode(); }
    @Override public String toString() { return source; }

    private static final class StepLimit extends RuntimeException {
        StepLimit() { super(null, null, false, false); }
    }

    /** Counts every character the regex engine reads, which bounds backtracking. */
    private static final class Bounded implements CharSequence {
        private final String text;
        private int steps;
        Bounded(String text) { this.text = text; }
        @Override public int length() { return text.length(); }
        @Override public char charAt(int index) {
            if (++steps > MAXIMUM_STEPS) throw new StepLimit();
            return text.charAt(index);
        }
        @Override public CharSequence subSequence(int start, int end) { return new Bounded(text.substring(start, end)); }
        @Override public String toString() { return text; }
    }
}
