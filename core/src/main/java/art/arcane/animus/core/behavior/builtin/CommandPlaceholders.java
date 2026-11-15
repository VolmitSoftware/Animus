package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.behavior.ArgumentTemplate;
import art.arcane.animus.core.extension.CapabilityRefusal;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Placeholder plugin tokens in a command, such as PlaceholderAPI's {@code %player_level%}, filled one at a time for one
 * player. A command is privileged, so each filled value must pass the rule {@code {{ }}} holes in commands pass: letters,
 * digits and {@code _ . : + -}, or for a behavior on {@code rawTextBehaviors} anything without a new line or control
 * character. A token the plugin leaves unchanged stays as the author wrote it.
 */
public final class CommandPlaceholders {
    private static final Pattern TOKEN = Pattern.compile("%[^%\\s]+%");

    private CommandPlaceholders() {}

    /** Fills each token through {@code fill}; refuses with {@code arguments-invalid} when a value breaks the rule. */
    public static String fill(String command, UnaryOperator<String> fill, boolean rawText) {
        Matcher tokens = TOKEN.matcher(command);
        StringBuilder filled = new StringBuilder();
        while (tokens.find()) {
            String token = tokens.group();
            String value = fill.apply(token);
            if (value == null) value = token;
            if (!value.equals(token) && !allowed(value, rawText)) throw refused(token, rawText);
            tokens.appendReplacement(filled, Matcher.quoteReplacement(value));
        }
        tokens.appendTail(filled);
        String result = filled.toString();
        if (result.startsWith("/")) throw new CapabilityRefusal(Outcome.Status.INVALID, "arguments-invalid", "A filled placeholder made the command start with a slash");
        return result;
    }

    private static boolean allowed(String value, boolean rawText) {
        return rawText ? value.codePoints().noneMatch(Character::isISOControl) : ArgumentTemplate.SAFE_TEXT.matcher(value).matches();
    }

    private static CapabilityRefusal refused(String token, boolean rawText) {
        return new CapabilityRefusal(Outcome.Status.INVALID, "arguments-invalid", rawText
                ? token + " filled a value with a new line or control character, which a command cannot hold"
                : token + " filled a value with characters other than letters, digits and _ . : + -; list the behavior in [behaviors.actions] rawTextBehaviors to allow more");
    }
}
