package art.arcane.animus.core.text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reply text as a person reads it: a file is named from the Animus folder, such as {@code imports/inbox/saves.yml},
 * never by the machine's absolute path; a field is named as written in the file, without the {@code $.} of a schema
 * path; a range reads {@code from 1 to 2048}. Every chat line passes through here, so a message built from an exception
 * or a validator cannot show the server's folders. Commands, links and relative paths stay as written.
 */
public final class ReplyText {
    /** A Unix path of two or more parts that no word, colon or slash leads into, or a Windows path with its drive. */
    private static final Pattern ABSOLUTE = Pattern.compile("(?<![\\w:/.~-])(?:/[^/\\s;,'\"()\\[\\]<>]+){2,}|\\b[A-Za-z]:\\\\[^\\s;,'\"()\\[\\]<>]+");
    /** The folder Animus keeps its files in: plugins/Animus on Bukkit, config/animus or animus on mod servers. */
    private static final Pattern DATA = Pattern.compile("(?i)(?:^|.*[/\\\\])animus[/\\\\]");
    private static final Pattern SCHEMA = Pattern.compile("(?<![\\w$])\\$\\.");
    private static final Pattern RANGE = Pattern.compile("within (-?\\d+(?:\\.\\d+)?)\\.\\.(-?\\d+(?:\\.\\d+)?)");
    /** A Java exception's name, with the colon that leads into its message when it has one. */
    private static final Pattern EXCEPTION = Pattern.compile("\\b(?:[a-z]\\w*\\.)*([A-Z]\\w*(?:Exception|Error))\\b(: )?");
    /** A point as a runtime status line prints it, {@code Vec3[x=1.5, y=64.0, z=-3.5]}. */
    private static final Pattern POINT = Pattern.compile("Vec3\\[x=([^,\\]]+), y=([^,\\]]+), z=([^\\]]+)]");

    private ReplyText() {}

    public static String plain(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        if (out.indexOf('/') >= 0 || out.indexOf('\\') >= 0) {
            Matcher path = ABSOLUTE.matcher(out);
            StringBuilder relative = new StringBuilder();
            while (path.find()) path.appendReplacement(relative, Matcher.quoteReplacement(inside(path.group())));
            path.appendTail(relative);
            out = relative.toString();
        }
        if (out.indexOf('$') >= 0) out = SCHEMA.matcher(out).replaceAll("");
        if (out.contains("Exception") || out.contains("Error")) {
            Matcher exception = EXCEPTION.matcher(out);
            StringBuilder words = new StringBuilder();
            while (exception.find()) exception.appendReplacement(words, Matcher.quoteReplacement(exception.group(2) != null ? "" : failure(exception.group(1))));
            exception.appendTail(words);
            out = words.toString();
        }
        if (out.contains("Vec3[")) {
            Matcher point = POINT.matcher(out);
            StringBuilder words = new StringBuilder();
            while (point.find()) point.appendReplacement(words, Matcher.quoteReplacement(number(point.group(1)) + " " + number(point.group(2)) + " " + number(point.group(3))));
            point.appendTail(words);
            out = words.toString();
        }
        if (out.contains("within ")) {
            Matcher range = RANGE.matcher(out);
            StringBuilder words = new StringBuilder();
            while (range.find()) range.appendReplacement(words, Matcher.quoteReplacement("from " + number(range.group(1)) + " to " + number(range.group(2))));
            range.appendTail(words);
            out = words.toString();
        }
        return out;
    }

    /** What a failure named only by its exception means, in words. */
    private static String failure(String exception) {
        if (exception.contains("Timeout")) return "it timed out";
        if (exception.startsWith("Connect") || exception.contains("NoRoute")) return "it could not connect";
        if (exception.startsWith("UnknownHost")) return "the host is unknown";
        if (exception.contains("NoSuchFile") || exception.contains("FileNotFound")) return "the file is missing";
        if (exception.contains("AccessDenied")) return "access was denied";
        return "an error the server log names";
    }

    /** The part of an absolute path inside the Animus folder, with forward slashes, else its file name. */
    private static String inside(String absolute) {
        String path = absolute.replace('\\', '/');
        Matcher data = DATA.matcher(path);
        if (data.lookingAt()) return path.substring(data.end());
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String number(String value) { return value.endsWith(".0") ? value.substring(0, value.length() - 2) : value; }
}
