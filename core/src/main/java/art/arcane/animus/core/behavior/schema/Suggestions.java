package art.arcane.animus.core.behavior.schema;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/** Nearest-word hints for diagnostics. Bounded: words over 128 characters are never compared. */
public final class Suggestions {
    private static final int MAXIMUM_LENGTH = 128;
    private Suggestions() {}

    /** {@code "; did you mean x?"} for a close option, otherwise empty. */
    public static String didYouMean(String word, Collection<String> options) {
        if (word == null || word.length() > MAXIMUM_LENGTH) return "";
        int threshold = Math.max(1, Math.min(3, word.length() / 3));
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String option : new TreeSet<>(options)) {
            if (option.equals(word) || option.length() > MAXIMUM_LENGTH) continue;
            int distance = distance(word, option);
            if (distance < bestDistance) { best = option; bestDistance = distance; }
        }
        return best != null && bestDistance <= threshold ? "; did you mean " + best + "?" : "";
    }

    /** Comma-separated list in the given order. */
    public static String list(List<String> values) { return String.join(", ", values); }

    /** Optimal string alignment distance, so a swapped pair of letters counts once. */
    static int distance(String left, String right) {
        int[][] table = new int[left.length() + 1][right.length() + 1];
        for (int i = 0; i <= left.length(); i++) table[i][0] = i;
        for (int j = 0; j <= right.length(); j++) table[0][j] = j;
        for (int i = 1; i <= left.length(); i++) {
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                int value = Math.min(Math.min(table[i - 1][j] + 1, table[i][j - 1] + 1), table[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && left.charAt(i - 1) == right.charAt(j - 2) && left.charAt(i - 2) == right.charAt(j - 1))
                    value = Math.min(value, table[i - 2][j - 2] + 1);
                table[i][j] = value;
            }
        }
        return table[left.length()][right.length()];
    }
}
