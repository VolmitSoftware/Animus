package art.arcane.animus.mod;

/** The text after {@code /animus}, split into the same words a Bukkit command receives. */
final class ModCommandLine {
    private ModCommandLine() {}

    /** Words of a command to run. Repeated spaces keep their empty words, as on Bukkit. */
    static String[] words(String text) {
        return text.isEmpty() ? new String[0] : text.split(" ", -1);
    }

    /** Words of a command being typed; the last word is the one to complete and may be empty. */
    static String[] completionWords(String text) {
        return text.split(" ", -1);
    }

    /** Where the word being typed starts, so a suggestion replaces only that word. */
    static int lastWordStart(String text) {
        return text.lastIndexOf(' ') + 1;
    }
}
