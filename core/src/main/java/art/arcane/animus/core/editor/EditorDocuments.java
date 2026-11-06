package art.arcane.animus.core.editor;

import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.core.behavior.BehaviorCompiler;
import art.arcane.animus.core.behavior.BehaviorProgram;
import art.arcane.animus.core.behavior.catalog.BehaviorPath;
import art.arcane.animus.core.behavior.catalog.PackManifest;
import art.arcane.animus.core.behavior.catalog.ServerBehaviorList;
import art.arcane.animus.core.gui.GuiDocument;
import art.arcane.animus.core.navigation.WaypointSettings;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocumentTree;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where each editor document kind lives under the data folder, how its id maps to a path, its revision and hash, and the
 * Java parsers that accept or refuse it. Call on the file lane.
 */
public final class EditorDocuments {
    /** Kinds whose documents carry a {@code revision} the server increments on every apply. */
    public static final Set<DocumentKind> REVISIONED = EnumSet.of(DocumentKind.NPC, DocumentKind.BEHAVIOR, DocumentKind.SHOP, DocumentKind.INVENTORY,
            DocumentKind.HOLOGRAM, DocumentKind.PRESETS, DocumentKind.RIG, DocumentKind.ANIMATION);
    private static final String SEGMENT = "[a-z0-9][a-z0-9_-]{0,63}";
    private static final Pattern PROBLEM = Pattern.compile("^(\\$[^:\\s]*):\\s*(.+)$", Pattern.DOTALL);

    /** A document's place. {@code path} is relative to the data folder with / separators. */
    public record Location(DocumentKind kind, String id, String path) {}

    /** A stored document; {@code content} is null when the document does not exist. {@code hash} is {@code sha256:<hex>}. */
    public record Stored(Location location, String content, long revision, String hash) {
        public boolean exists() { return content != null; }
    }

    private final DocumentStore store;

    public EditorDocuments(DocumentStore store) { this.store = Objects.requireNonNull(store); }

    public DocumentStore store() { return store; }

    /** Every document of the kinds, sorted by kind then path. Files that map to no id are left out. */
    public List<Location> list(Collection<DocumentKind> kinds) throws IOException {
        List<Location> found = new ArrayList<>();
        for (DocumentKind kind : DocumentKind.values()) {
            if (!kinds.contains(kind)) continue;
            if (kind == DocumentKind.PRESETS) {
                if (store.read("gui/presets.json") != null) found.add(new Location(kind, "presets", "gui/presets.json"));
                continue;
            }
            if (kind == DocumentKind.SERVER_BEHAVIORS) {
                if (store.read(ServerBehaviorList.PATH) != null) found.add(new Location(kind, kind.wireName(), ServerBehaviorList.PATH));
                continue;
            }
            List<String> roots = new ArrayList<>(List.of(kind.folder()));
            if (kind == DocumentKind.BEHAVIOR || kind == DocumentKind.PACK) {
                Path packs = store.resolve("packs");
                for (Path file : JsonDocumentTree.files(packs, 18)) {
                    String relative = "packs/" + packs.relativize(file).toString().replace('\\', '/');
                    idFor(kind, relative).ifPresent((String id) -> found.add(new Location(kind, id, relative)));
                }
                if (kind == DocumentKind.PACK) continue;
            }
            for (String root : roots) {
                Path directory = store.resolve(root);
                for (Path file : JsonDocumentTree.files(directory, 16)) {
                    String relative = root + "/" + directory.relativize(file).toString().replace('\\', '/');
                    idFor(kind, relative).ifPresent((String id) -> found.add(new Location(kind, id, relative)));
                }
            }
        }
        found.sort((Location a, Location b) -> a.kind() != b.kind() ? Integer.compare(a.kind().ordinal(), b.kind().ordinal()) : a.path().compareTo(b.path()));
        return found;
    }

    /** The id a path has for a kind, or empty when the path is not one of that kind's documents. */
    public static Optional<String> idFor(DocumentKind kind, String path) {
        if (!path.endsWith(".json")) return Optional.empty();
        String folder = kind.folder() + "/";
        try {
            return switch (kind) {
                case NPC -> path.matches("npcs/" + SEGMENT + "/" + SEGMENT + "\\.json") ? Optional.of(path.substring(5, path.length() - 5)) : Optional.empty();
                case ROUTE -> path.matches("routes/" + SEGMENT + "\\.json") ? Optional.of(path.substring(7, path.length() - 5)) : Optional.empty();
                case SKIN -> path.matches("skins/[a-f0-9]{64}\\.json") ? Optional.of(path.substring(6, 70)) : Optional.empty();
                case BEHAVIOR -> Optional.of(BehaviorPath.idFor(path));
                case PACK -> path.matches("packs/" + SEGMENT + "/pack\\.json") ? Optional.of(path.substring(6, path.length() - 10)) : Optional.empty();
                case PRESETS -> path.equals("gui/presets.json") ? Optional.of("presets") : Optional.empty();
                case SERVER_BEHAVIORS -> path.equals(ServerBehaviorList.PATH) ? Optional.of(kind.wireName()) : Optional.empty();
                default -> {
                    String rest = path.startsWith(folder) ? path.substring(folder.length(), path.length() - 5) : "";
                    int slash = rest.indexOf('/');
                    if (slash < 1 || !rest.matches(SEGMENT + "(?:/" + SEGMENT + "){1,15}")) yield Optional.empty();
                    yield Optional.of(rest.substring(0, slash) + ":" + rest.substring(slash + 1));
                }
            };
        } catch (IllegalArgumentException notAnId) { return Optional.empty(); }
    }

    /** The path a new document of this id gets. Behaviors outside packs go under {@code behaviors/}. */
    public static String pathFor(DocumentKind kind, String id) throws EditorError {
        String path = switch (kind) {
            case NPC, ROUTE -> kind.folder() + "/" + id + ".json";
            case SKIN -> "skins/" + id + ".json";
            case BEHAVIOR -> {
                try { yield BehaviorPath.forId(id); }
                catch (IllegalArgumentException invalid) { yield ""; }
            }
            case PACK -> "packs/" + id + "/pack.json";
            case PRESETS -> "gui/presets.json";
            case SERVER_BEHAVIORS -> ServerBehaviorList.PATH;
            default -> kind.folder() + "/" + id.replace(':', '/') + ".json";
        };
        if (!idFor(kind, path).map(id::equals).orElse(false))
            throw new EditorError(EditorError.Code.INVALID_REQUEST, "'" + id + "' is not a valid " + kind.wireName() + " id.");
        return path;
    }

    /** Where an existing document is, or where a new one would go. A behavior inside a pack is found by searching the packs. */
    public Location locate(DocumentKind kind, String id) throws EditorError, IOException {
        String path = pathFor(kind, id);
        if (kind == DocumentKind.BEHAVIOR && store.read(path) == null) {
            for (Location candidate : list(List.of(DocumentKind.BEHAVIOR)))
                if (candidate.id().equals(id)) return candidate;
        }
        return new Location(kind, id, path);
    }

    public Stored read(Location location) throws IOException {
        String content = store.read(location.path());
        return new Stored(location, content, revision(content), content == null ? null : "sha256:" + JsonDocuments.hash(content));
    }

    /** The document's integer {@code revision}, or 0 when it has none or cannot be parsed. */
    public static long revision(String content) {
        if (content == null) return 0;
        try {
            JsonElement revision = JsonDocuments.object(content).get("revision");
            if (revision == null || !revision.isJsonPrimitive() || !revision.getAsJsonPrimitive().isNumber()) return 0;
            long value = revision.getAsBigDecimal().longValueExact();
            return Math.max(0, value);
        } catch (IOException | ArithmeticException unreadable) { return 0; }
    }

    /** The stored document parsed, or an empty object when its JSON is unreadable. */
    public static JsonObject source(String content) {
        try { return JsonDocuments.object(content); }
        catch (IOException unreadable) { return new JsonObject(); }
    }

    /** Field diagnostics from the Java parser for one candidate document; an error refuses it. */
    public static JsonArray validate(DocumentKind kind, String id, JsonObject source) {
        JsonArray diagnostics = new JsonArray();
        String text = JsonDocuments.write(source);
        switch (kind) {
            case NPC -> {
                try {
                    NpcDefinition definition = NpcDefinition.parse(text);
                    if (!(definition.group() + "/" + definition.id()).equals(id))
                        diagnostics.add(diagnostic("error", "$.id", "group/id is " + definition.group() + "/" + definition.id() + " but the document is " + id, "id-mismatch"));
                } catch (IOException invalid) { problems(diagnostics, List.of(invalid.getMessage().split("; "))); }
            }
            case BEHAVIOR -> {
                JsonObject copy = source.deepCopy();
                JsonElement revision = copy.remove("revision");
                if (revision == null || !revision.isJsonPrimitive() || !revision.getAsJsonPrimitive().isNumber() || revision.getAsBigDecimal().signum() <= 0)
                    diagnostics.add(diagnostic("error", "$.revision", "expected integer of at least 1", "revision"));
                BehaviorCompiler.Result result = BehaviorCompiler.compile(copy.toString());
                for (Diagnostic problem : result.diagnostics()) diagnostics.add(from(problem));
                result.program().ifPresent((BehaviorProgram program) -> {
                    if (!program.id().equals(id)) diagnostics.add(diagnostic("error", "$.id", "the behavior id is " + program.id() + " but the document is " + id, "id-mismatch"));
                    for (Diagnostic warning : BehaviorCompiler.warnings(program)) diagnostics.add(from(warning));
                });
            }
            case PACK -> {
                try { PackManifest.parse(text, id); }
                catch (IllegalArgumentException invalid) { problems(diagnostics, List.of(invalid.getMessage())); }
            }
            case SHOP -> problems(diagnostics, ShopDocument.parse(id, text).problems());
            case INVENTORY -> problems(diagnostics, GuiDocument.parse(id, text).problems());
            case RIG -> problems(diagnostics, art.arcane.animus.core.rig.RigDocument.parse(id, text).problems());
            case ANIMATION -> problems(diagnostics, art.arcane.animus.core.rig.AnimationDocument.parse(id, text).problems());
            case ROUTE -> {
                try { WaypointSettings.document(text); }
                catch (IOException | RuntimeException invalid) { problems(diagnostics, List.of(String.valueOf(invalid.getMessage()))); }
            }
            case SERVER_BEHAVIORS -> {
                try { ServerBehaviorList.parse(text); }
                catch (IOException invalid) { problems(diagnostics, List.of(String.valueOf(invalid.getMessage()))); }
            }
            case SKIN -> {
                if (!JsonDocuments.hash(text).equals(id))
                    diagnostics.add(diagnostic("error", "$", "skins are named by the SHA-256 of their bytes; this content hashes to " + JsonDocuments.hash(text), "skin-hash"));
            }
            default -> {
                // Menus, holograms, text animations and presets have no Java parser in this build; the shared schemas check their shape.
            }
        }
        return diagnostics;
    }

    public static boolean hasErrors(JsonArray diagnostics) {
        for (JsonElement element : diagnostics) if (element.getAsJsonObject().get("severity").getAsString().equals("error")) return true;
        return false;
    }

    public static JsonObject diagnostic(String severity, String path, String message, String code) {
        JsonObject diagnostic = new JsonObject();
        diagnostic.addProperty("severity", severity);
        diagnostic.addProperty("path", path.startsWith("$") ? path : "$");
        String bounded = message == null || message.isBlank() ? "invalid" : message;
        diagnostic.addProperty("message", bounded.length() > 2048 ? bounded.substring(0, 2048) : bounded);
        if (code != null) {
            String cleaned = code.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
            diagnostic.addProperty("code", cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned);
        }
        return diagnostic;
    }

    private static JsonObject from(Diagnostic problem) {
        String severity = switch (problem.severity()) { case ERROR -> "error"; case WARNING -> "warning"; case INFO -> "info"; };
        String field = problem.field().startsWith("$") ? problem.field() : "$." + problem.field();
        return diagnostic(severity, field, problem.message(), problem.code());
    }

    private static void problems(JsonArray diagnostics, List<String> problems) {
        for (String problem : problems) {
            Matcher matcher = PROBLEM.matcher(problem.strip());
            if (matcher.matches()) diagnostics.add(diagnostic("error", matcher.group(1), matcher.group(2), null));
            else diagnostics.add(diagnostic("error", "$", problem, null));
        }
    }
}
