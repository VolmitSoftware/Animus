package art.arcane.animus.core.hotload;

import art.arcane.animus.core.behavior.catalog.BehaviorPath;
import art.arcane.animus.core.config.PluginSettings;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.editor.EditorDocuments;
import art.arcane.animus.core.gui.GuiKind;
import art.arcane.animus.core.gui.GuiValidator;
import art.arcane.animus.core.gui.HoloCatalog;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Checks one changed file with the same parsers and validators the editor and {@code /animus validate} use, before
 * anything reloads. A refusal names the exact field; the running state keeps the last valid version.
 */
public final class HotloadValidation {
    /**
     * @param kind the catalog the change reloads, empty for config and schematics
     * @param removedBehavior a behavior whose source file was deleted on purpose
     * @param npc the parsed NPC, which still needs its activation check on the server
     */
    public record Verdict(Optional<String> error, Optional<DocumentKind> kind, Optional<String> removedBehavior,
                          Optional<PluginSettings> settings, Optional<NpcDefinition> npc) {
        static Verdict refused(String error) { return new Verdict(Optional.of(error), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()); }
        static Verdict reload(DocumentKind kind) { return new Verdict(Optional.empty(), Optional.of(kind), Optional.empty(), Optional.empty(), Optional.empty()); }
        static final Verdict ACCEPTED = new Verdict(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        public boolean valid() { return error.isEmpty(); }
    }

    /** What validation reads from the running server: the data folder, for animation references, and the command allow-list. */
    public record Context(Path data, Predicate<String> commandsAllowed) {}

    private HotloadValidation() {}

    public static Verdict check(HotloadTargets.Target target, HotloadEngine.Snapshot snapshot, Context context) {
        if (snapshot.failure() != null) return Verdict.refused(snapshot.failure());
        return switch (target.area()) {
            case CONFIG -> config(snapshot);
            case SCHEMATIC -> schematic(snapshot);
            case DOCUMENT -> document(target, snapshot, context);
        };
    }

    private static Verdict config(HotloadEngine.Snapshot snapshot) {
        if (snapshot.deleted()) return Verdict.ACCEPTED;
        try {
            PluginSettings settings = PluginSettings.parse(snapshot.content(), Path.of(HotloadTargets.CONFIG));
            return new Verdict(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(settings), Optional.empty());
        } catch (IOException | RuntimeException invalid) {
            return Verdict.refused(strip(String.valueOf(invalid.getMessage()), HotloadTargets.CONFIG + ": "));
        }
    }

    private static Verdict schematic(HotloadEngine.Snapshot snapshot) {
        if (snapshot.deleted()) return Verdict.ACCEPTED;
        try {
            JsonObject summary = JsonParser.parseString(snapshot.content()).getAsJsonObject();
            JsonElement error = summary.get("error");
            return error == null ? Verdict.ACCEPTED : Verdict.refused("$: " + error.getAsString());
        } catch (RuntimeException unreadable) { return Verdict.refused("$: the schematic cannot be read"); }
    }

    private static Verdict document(HotloadTargets.Target target, HotloadEngine.Snapshot snapshot, Context context) {
        String path = target.path();
        DocumentKind kind = target.kind().orElseThrow();
        if (kind == DocumentKind.PACK && !path.endsWith("/pack.json")) kind = DocumentKind.BEHAVIOR;
        if (snapshot.deleted()) {
            Optional<String> removed = Optional.empty();
            if (kind == DocumentKind.BEHAVIOR) {
                try { removed = Optional.of(BehaviorPath.idFor(path)); }
                catch (IllegalArgumentException notBehavior) { /* Not a behavior source, so nothing was published from it. */ }
            }
            return new Verdict(Optional.empty(), Optional.of(kind), removed, Optional.empty(), Optional.empty());
        }
        JsonObject source;
        try { source = JsonDocuments.object(snapshot.content()); }
        catch (IOException invalid) { return Verdict.refused(String.valueOf(invalid.getMessage())); }
        Optional<String> id = EditorDocuments.idFor(kind, path);
        if (id.isEmpty()) return Verdict.refused("$: " + path + " is not where a " + kind.wireName() + " document belongs");
        GuiValidator gui = HoloCatalog.validator(context.data(), context.commandsAllowed());
        List<String> errors = switch (kind) {
            case INVENTORY -> gui.problems(GuiKind.INVENTORY, id.get(), snapshot.content());
            case MENU -> gui.problems(GuiKind.MENU, id.get(), snapshot.content());
            case HOLOGRAM -> gui.problems(GuiKind.HOLOGRAM, id.get(), snapshot.content());
            case TEXT_ANIMATION -> gui.problems(GuiKind.ANIMATION, id.get(), snapshot.content());
            case SHOP -> shop(id.get(), snapshot.content(), context.commandsAllowed());
            default -> errors(EditorDocuments.validate(kind, id.get(), source));
        };
        if (!errors.isEmpty()) return Verdict.refused(errors.getFirst() + (errors.size() > 1 ? " (and " + (errors.size() - 1) + " more)" : ""));
        if (kind != DocumentKind.NPC) return Verdict.reload(kind);
        try {
            return new Verdict(Optional.empty(), Optional.of(kind), Optional.empty(), Optional.empty(), Optional.of(NpcDefinition.parse(snapshot.content())));
        } catch (IOException invalid) { return Verdict.refused(String.valueOf(invalid.getMessage())); }
    }

    private static List<String> shop(String id, String json, Predicate<String> commandsAllowed) {
        ShopDocument.Result result = ShopDocument.parse(id, json);
        List<String> problems = new ArrayList<>(result.problems());
        result.shop().filter((ShopDocument shop) -> shop.hasCommands() && !commandsAllowed.test(id)).ifPresent((ShopDocument shop) -> problems.add("$: runs commands, so add "
                + id + " to commandBehaviors or its namespace to commandNamespaces under [behaviors.actions] in config.toml"));
        return problems;
    }

    private static List<String> errors(JsonArray diagnostics) {
        List<String> errors = new ArrayList<>();
        for (JsonElement element : diagnostics) {
            JsonObject diagnostic = element.getAsJsonObject();
            if (diagnostic.get("severity").getAsString().equals("error"))
                errors.add(diagnostic.get("path").getAsString() + ": " + diagnostic.get("message").getAsString());
        }
        return errors;
    }

    private static String strip(String message, String prefix) {
        return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
    }
}
