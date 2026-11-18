package art.arcane.animus.core.hotload;

import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.editor.EditorDocuments;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.text.PlainWords;
import art.arcane.animus.core.text.RichText;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data files and their problems as a reply says them: a file by the name players use ({@code NPC Guard},
 * {@code behavior int3:greet}, Settings) and a refusal in words, without file paths, JSON paths or range syntax.
 */
public final class FileWords {
    private static final int NAME_LENGTH = 48;
    private static final Map<String, String> WORDS = Map.of("npc", "NPC", "npcs", "NPCs", "gui", "GUI", "id", "ID", "uuid", "UUID",
            "tls", "TLS", "url", "URL", "api", "API", "dialogue", "conversation");
    private static final Pattern TOKEN = Pattern.compile("\\[(\\d{1,9})]|([^.\\[\\]]+)");
    private static final Pattern FIELD_NAME = Pattern.compile("[a-z][A-Za-z0-9]*");
    private static final Pattern MORE = Pattern.compile(" \\(and \\d+ more\\)$");
    /** A JSON syntax error as the reader words it, such as {@code End of input at line 1 column 146 path $.routines}. */
    private static final Pattern SYNTAX = Pattern.compile("^(.*?) at line (\\d+) column (\\d+)(?: path .*)?$", Pattern.DOTALL);
    private static final Pattern CONFIG_WHERE = Pattern.compile("^line (\\d+)(?:, column (\\d+))?: (.*)$", Pattern.DOTALL);
    private static final Pattern CONFIG_KEY = Pattern.compile("^(?:([a-zA-Z][\\w.]*): )?([a-zA-Z][\\w.]*)( (?:must|is|has|cannot) .*)$", Pattern.DOTALL);
    private static final Pattern RANGE = Pattern.compile("(?:within )?(-?\\d+(?:\\.\\d+)?)\\.\\.(-?\\d+(?:\\.\\d+)?)");
    /** A reload's note that the running version stays, such as {@code ; kept the last valid revision 3}. */
    /** Schematic reader refusals that carry a limit or a version worth saying in words. */
    private static final Pattern SCHEMATIC_SIZE = Pattern.compile("(data|decompressed) size exceeds (\\d+) bytes"),
            SCHEMATIC_VOLUME = Pattern.compile("the schematic volume (-?\\d+) exceeds the maximum volume of (\\d+) blocks"),
            SCHEMATIC_SIDE = Pattern.compile("the schematic (width|height|length) (-?\\d+) is outside 1\\.\\.(\\d+) \\(maximum side\\)", Pattern.CASE_INSENSITIVE),
            SCHEMATIC_PALETTE = Pattern.compile("the schematic palette has (\\d+) entries; it must have 1\\.\\.(\\d+) \\(maximum palette\\)"),
            SPONGE_VERSION = Pattern.compile("unsupported Sponge schematic version (-?\\d+)");
    /** A reference to something that is not there, such as {@code unknown group town}. */
    private static final Pattern UNKNOWN = Pattern.compile("unknown ([a-z]+) (\\S+)");
    private static final Pattern KEPT = Pattern.compile(";\\s*(?:kept the last valid revision|retained last valid revision) \\d+.*$", Pattern.DOTALL);
    private static final int LISTED_REFUSALS = 3, LISTED_PROBLEMS = 10;

    /** What a reply that names refused files follows, which decides what its lines say stays in use. */
    public enum After {
        /** A reload or an install: each line reads like a hotload refusal and says what stays in use. */
        RELOAD,
        /** A rollback put earlier files back that the reload then refused. */
        ROLLBACK,
        /** A check that loads nothing, such as {@code /animus validate}: the file and why, for someone fixing it. */
        CHECK
    }

    private FileWords() {}

    /** How players name the file at a data-folder path; a recovery copy names its NPC, and other paths read as their file name. */
    public static String named(String path, Function<String, String> read) {
        String file = file(path);
        return HotloadTargets.of(file).map((HotloadTargets.Target target) -> subject(target, read.apply(file))).orElse(file.substring(file.lastIndexOf('/') + 1));
    }

    /**
     * One file a reload refused, as a reply line. {@code diagnostic} is the reload's {@code path: problem} line and
     * {@code read} gives a data file's text, for its name.
     */
    public static String refusal(String diagnostic, Function<String, String> read, After after) {
        int colon = diagnostic.indexOf(": ");
        Optional<HotloadTargets.Target> target = colon < 0 ? Optional.empty() : HotloadTargets.of(diagnostic.substring(0, colon));
        String problem = target.isPresent() ? diagnostic.substring(colon + 2) : diagnostic;
        Matcher kept = KEPT.matcher(problem);
        boolean stays = kept.find();
        if (stays) problem = problem.substring(0, kept.start());
        String[] problems = problem.split("; (?=\\$)");
        String subject = target.map((HotloadTargets.Target found) -> subject(found, read.apply(found.path()))).orElse("a file");
        // A file at another schema version is the usual reason after a rollback undoes an upgrade.
        boolean format = problems[0].startsWith("$.schemaVersion:");
        String why = reason(problems[0] + (problems.length > 1 ? " (and " + (problems.length - 1) + " more)" : ""));
        return switch (after) {
            case ROLLBACK -> {
                String end = stays ? "the current one stays in use" : "it is not loaded";
                yield format ? "Restored the original file of " + subject + "; this version cannot load it, so " + end + "."
                        : "Restored the earlier file of " + subject + ", but it cannot load: " + why + "; " + end + ".";
            }
            case RELOAD -> "Refused " + subject + ": " + (format ? "this version cannot load its file" : why) + "; "
                    + (stays ? "the last working version stays" : "it was not loaded") + ".";
            case CHECK -> Character.toUpperCase(subject.charAt(0)) + subject.substring(1) + ": " + (format ? "this version cannot load its file" : why) + ".";
        };
    }

    /**
     * {@link #refusals} for the files an operation wrote, {@code touched}. A refused file it did not write is only
     * counted: the reply is about that operation, and {@code /animus validate} names the rest.
     */
    public static List<String> refusals(List<String> diagnostics, Set<String> touched, Function<String, String> read, After after) {
        Set<String> files = new HashSet<>();
        for (String path : touched) files.add(file(path));
        List<String> own = new ArrayList<>();
        int others = 0;
        for (String diagnostic : diagnostics) {
            int colon = diagnostic.indexOf(": ");
            if (colon > 0 && files.contains(diagnostic.substring(0, colon))) own.add(diagnostic);
            else others++;
        }
        List<String> lines = new ArrayList<>(refusals(own, read, after));
        if (others > 0) lines.add(others == 1 ? "1 other file cannot load; /animus validate names it." : others + " other files cannot load; /animus validate names them.");
        return lines;
    }

    /** A schematic refusal in words: its size against the build limits, an old format, or that Animus cannot read it at all. */
    static String schematicReason(String error) {
        String text = error.startsWith("$: ") ? error.substring(3) : error;
        // Hotload's own refusals of the file, such as its size, already read as words.
        if (text.startsWith("the file ")) return text;
        Matcher match = SCHEMATIC_SIZE.matcher(text);
        if (match.matches()) return "it is larger than " + match.group(2) + " bytes" + (match.group(1).equals("decompressed") ? " once unpacked" : "");
        match = SCHEMATIC_VOLUME.matcher(text);
        if (match.matches()) return "it holds " + match.group(1) + " blocks, more than the " + match.group(2) + " allowed";
        match = SCHEMATIC_SIDE.matcher(text);
        if (match.matches()) {
            String measure = switch (match.group(1).toLowerCase(java.util.Locale.ROOT)) {
                case "width" -> "wide";
                case "height" -> "tall";
                default -> "long";
            };
            return "it is " + match.group(2) + " blocks " + measure + "; each side must be from 1 to " + match.group(3);
        }
        match = SCHEMATIC_PALETTE.matcher(text);
        if (match.matches()) return "it uses " + match.group(1) + " block types; at most " + match.group(2) + " are allowed";
        if (text.startsWith("Legacy MCEdit")) return "it is an old MCEdit schematic, which Animus cannot read; save the build again as a .schem file";
        match = SPONGE_VERSION.matcher(text);
        if (match.matches()) return "it is a Sponge schematic version " + match.group(1) + ", which Animus cannot read";
        return "it is not a schematic file Animus can read";
    }

    /** A data-folder path as the file it stands for: an NPC's recovery copy reads as the NPC's own file. */
    private static String file(String path) {
        return path.startsWith(DocumentStore.DEFINITION_POINTERS) ? path.substring(DocumentStore.DEFINITION_POINTERS.length()) : path;
    }

    /** {@link #refusal} for each file, at most 3 after a reload or rollback and 10 for a check, then how many more the server log has. */
    public static List<String> refusals(List<String> diagnostics, Function<String, String> read, After after) {
        int listed = after == After.CHECK ? LISTED_PROBLEMS : LISTED_REFUSALS;
        List<String> lines = new ArrayList<>();
        for (String diagnostic : diagnostics.subList(0, Math.min(listed, diagnostics.size()))) lines.add(refusal(diagnostic, read, after));
        int more = diagnostics.size() - lines.size();
        if (more > 0) lines.add(more + (after == After.CHECK ? (more == 1 ? " more file has problems" : " more files have problems")
                : (more == 1 ? " more file was refused" : " more files were refused")) + "; the server log lists them.");
        return lines;
    }

    /** How players name the file: {@code NPC Guard}, {@code behavior int3:greet}, {@code Settings}, {@code schematic castle.schem}. */
    static String subject(HotloadTargets.Target target, String... texts) {
        if (target.area() == HotloadTargets.Area.CONFIG) return "Settings";
        String path = target.path(), file = path.substring(path.lastIndexOf('/') + 1);
        if (target.area() == HotloadTargets.Area.SCHEMATIC) return "schematic " + file;
        DocumentKind kind = target.kind().orElseThrow();
        if (kind == DocumentKind.PACK && !path.endsWith("/pack.json")) kind = DocumentKind.BEHAVIOR;
        String stem = file.endsWith(".json") ? file.substring(0, file.length() - 5) : file;
        Optional<JsonObject> json = object(texts);
        Optional<String> id = EditorDocuments.idFor(kind, path);
        String name = switch (kind) {
            case NPC -> text(json, "name").map(RichText::plain).filter((String plain) -> !plain.isBlank()).or(() -> text(json, "id")).orElse(stem);
            case SKIN -> text(json, "name").orElse(stem.substring(0, Math.min(8, stem.length())));
            case PRESETS, SERVER_BEHAVIORS -> "";
            default -> id.or(() -> text(json, "id")).orElse(stem);
        };
        String noun = switch (kind) {
            case NPC -> "NPC";
            case GROUP -> "trait group";
            case INVENTORY -> "chest GUI";
            case PRESETS -> "GUI presets";
            case SERVER_BEHAVIORS -> "server behavior list";
            default -> PlainWords.of(kind.wireName());
        };
        String shown = name.strip().replaceAll("\\s+", " ");
        if (shown.length() > NAME_LENGTH) shown = shown.substring(0, NAME_LENGTH - 3) + "...";
        return shown.isEmpty() ? noun : noun + " " + shown;
    }

    /** A document refusal in words: a JSON syntax error by its line and column, a field error by the field's name. */
    static String reason(String error) {
        Matcher kept = KEPT.matcher(error);
        if (kept.find()) error = error.substring(0, kept.start());
        Matcher more = MORE.matcher(error);
        String suffix = more.find() ? more.group() : "", text = error.substring(0, error.length() - suffix.length());
        if (text.startsWith("Invalid JSON: ")) text = text.substring("Invalid JSON: ".length());
        Matcher syntax = SYNTAX.matcher(text);
        if (syntax.matches()) {
            String problem = syntax.group(1).startsWith("End of input") ? "the file ends too early"
                    : syntax.group(1).startsWith("Unterminated string") ? "a quote is not closed" : "the file is not valid JSON";
            return problem + " at line " + syntax.group(2) + ", column " + syntax.group(3) + suffix;
        }
        int colon = text.indexOf(": ");
        if (!text.startsWith("$") || colon < 0) return ranges(text) + suffix;
        String path = text.substring(1, colon), message = text.substring(colon + 2);
        if (path.startsWith(".")) path = path.substring(1);
        String field = path.isEmpty() ? "the file" : words(path, true);
        Optional<String> predicate = predicate(message);
        if (predicate.isPresent()) return field + " " + predicate.get() + suffix;
        return (path.isEmpty() ? ranges(message) : field + ": " + ranges(message)) + suffix;
    }

    /** A {@code config.toml} refusal: its line first, then the setting in words. */
    static String configReason(String error) {
        Matcher where = CONFIG_WHERE.matcher(error);
        String location = where.matches() ? "line " + where.group(1) + (where.group(2) == null ? "" : ", column " + where.group(2)) + ", " : "";
        String rest = where.matches() ? where.group(3) : error;
        if (rest.startsWith("invalid TOML")) rest = "the file is not valid TOML";
        else {
            Matcher key = CONFIG_KEY.matcher(rest);
            if (key.matches()) rest = words((key.group(1) == null ? "" : key.group(1) + ".") + key.group(2), false) + nouns(key.group(3));
            else rest = nouns(rest);
        }
        return location + rest;
    }

    /** What a field error says about the field, worded to follow its name; empty when it does not read that way. */
    private static Optional<String> predicate(String message) {
        if (message.equals("unknown field")) return Optional.of("is not a known field");
        if (message.equals("unknown setting")) return Optional.of("is not a known setting");
        if (message.startsWith("duplicate ")) return Optional.of(message.substring("duplicate ".length()) + " is already used by another file");
        Matcher unknown = UNKNOWN.matcher(message);
        if (unknown.matches()) return Optional.of("names " + unknown.group(1) + " " + unknown.group(2) + ", which does not exist");
        if (message.equals("expected nonblank string")) return Optional.of("should not be blank");
        if (message.startsWith("expected ")) return Optional.of("should be " + nouns(message.substring("expected ".length())));
        if (message.matches("(?s)(?:must|is|has|cannot|should) .*")) return Optional.of(nouns(message));
        return Optional.empty();
    }

    /** Schema type names as the things a person types. */
    private static String nouns(String text) {
        String out = text.replaceFirst("^finite coordinate", "a coordinate").replaceFirst("^integer", "a whole number")
                .replaceFirst("^number", "a number").replaceFirst("^nonblank string", "text that is not blank").replaceFirst("^string", "text")
                .replaceFirst("^boolean", "true or false").replaceFirst("^object", "an object").replaceFirst("^array", "a list")
                .replace("an integer", "a whole number").replace("a boolean", "true or false").replace("a string", "text");
        return ranges(out);
    }

    private static String ranges(String text) { return RANGE.matcher(text).replaceAll("from $1 to $2"); }

    /**
     * A key path in words: {@code npcs.viewDistance} reads {@code NPCs view distance}. With {@code names}, a plural key
     * names its entry, so {@code routines.main.steps[0].ticks} reads {@code routine main step 1 ticks}.
     */
    static String words(String path, boolean names) {
        List<String> tokens = new ArrayList<>();
        List<Boolean> indexes = new ArrayList<>();
        Matcher token = TOKEN.matcher(path);
        while (token.find()) {
            indexes.add(token.group(1) != null);
            tokens.add(token.group(1) != null ? token.group(1) : token.group(2));
        }
        List<String> out = new ArrayList<>();
        boolean named = false;
        for (int index = 0; index < tokens.size(); index++) {
            String part = tokens.get(index);
            if (indexes.get(index)) {
                String number = Long.toString(Long.parseLong(part) + 1);
                if (out.isEmpty()) out.add(number);
                else out.set(out.size() - 1, singular(out.getLast()) + " " + number);
                named = false;
                continue;
            }
            if (named) { out.add(part); named = false; continue; }
            String word = field(part);
            named = names && index + 1 < tokens.size() && !indexes.get(index + 1) && part.length() > 2 && part.endsWith("s") && !part.endsWith("ss");
            out.add(named ? singular(word) : word);
        }
        return String.join(" ", out);
    }

    /** A camel-case field name as lower-case words; anything else, such as an id, as written. */
    private static String field(String name) {
        if (!FIELD_NAME.matcher(name).matches()) return name;
        List<String> words = new ArrayList<>();
        int start = 0;
        for (int index = 1; index <= name.length(); index++) {
            if (index < name.length() && !Character.isUpperCase(name.charAt(index))) continue;
            String word = name.substring(start, index).toLowerCase(java.util.Locale.ROOT);
            words.add(WORDS.getOrDefault(word, word));
            start = index;
        }
        return String.join(" ", words);
    }

    private static String singular(String words) {
        if (words.endsWith("NPCs")) return words.substring(0, words.length() - 1);
        if (words.endsWith("ies")) return words.substring(0, words.length() - 3) + "y";
        if (words.endsWith("s") && !words.endsWith("ss")) return words.substring(0, words.length() - 1);
        return words;
    }

    /** The first text that parses as a JSON object; null texts are skipped. */
    private static Optional<JsonObject> object(String... texts) {
        for (String text : texts) {
            if (text == null) continue;
            try {
                JsonElement parsed = JsonParser.parseString(text);
                if (parsed.isJsonObject()) return Optional.of(parsed.getAsJsonObject());
            } catch (RuntimeException unparsable) { /* The next text names the file, else its path does. */ }
        }
        return Optional.empty();
    }

    private static Optional<String> text(Optional<JsonObject> json, String key) {
        return json.map((JsonObject object) -> object.get(key)).filter((JsonElement value) -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())
                .map(JsonElement::getAsString);
    }
}
