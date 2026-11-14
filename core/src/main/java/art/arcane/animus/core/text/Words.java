package art.arcane.animus.core.text;

/** Keys and file ids as words people read. */
public final class Words {
    private Words() {}

    /**
     * A raw key or id as words: the namespace goes, camel case and separators become spaces, and only the first letter
     * stays capital. {@code teamColor} reads {@code Team color}, {@code animus:combat} reads {@code Combat}, and the
     * route {@code market-loop} reads {@code Market loop}.
     */
    public static String readable(String key) {
        String bare = key.substring(key.indexOf(':') + 1);
        StringBuilder words = new StringBuilder();
        for (int index = 0; index < bare.length(); index++) {
            char at = bare.charAt(index);
            boolean gap = at == '_' || at == '-' || at == '.' || at == ' ';
            boolean hump = Character.isUpperCase(at) && index > 0 && Character.isLowerCase(bare.charAt(index - 1));
            if ((gap || hump) && !words.isEmpty() && words.charAt(words.length() - 1) != ' ') words.append(' ');
            if (!gap) words.append(Character.toLowerCase(at));
        }
        String text = words.toString().strip();
        return text.isEmpty() ? key : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
