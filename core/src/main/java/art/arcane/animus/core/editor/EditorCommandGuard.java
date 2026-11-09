package art.arcane.animus.core.editor;

import art.arcane.animus.api.Capabilities;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which editor writes need {@link EditorScope#APPLY_COMMANDS}. A behavior, shop, chest GUI, menu or hologram whose written
 * content runs a command, or another privileged capability, needs it on every edit, even when its commands stay the same:
 * the text, variables and flow around a command decide what it runs. The check reads the JSON structure the parsers read,
 * so it errs toward asking for the scope: a {@code capability} naming a privileged key, a GUI action of {@code type}
 * {@code command}, or a non-empty shop {@code commands} list anywhere in the document.
 */
final class EditorCommandGuard {
    static final String COMMAND = "animus:command";
    private static final String COMMAND_TYPE = "command";

    private EditorCommandGuard() {}

    /** The privileged capability keys the live registry reports, always with {@code animus:command}. */
    static Set<String> privileged(Collection<Capabilities.Availability> capabilities) {
        Set<String> keys = new TreeSet<>(Set.of(COMMAND));
        for (Capabilities.Availability availability : capabilities)
            if (availability.descriptor().privileged()) keys.add(availability.descriptor().key().toString());
        return keys;
    }

    static boolean runsCommands(DocumentKind kind, JsonElement source, Set<String> privileged) {
        return switch (kind) {
            case BEHAVIOR -> any(source, (JsonObject object) -> text(object, "capability").map(privileged::contains).orElse(false));
            case INVENTORY, MENU, HOLOGRAM -> any(source, (JsonObject object) -> text(object, "type").map(COMMAND_TYPE::equals).orElse(false));
            case SHOP -> any(source, (JsonObject object) -> object.has("commands") && object.get("commands").isJsonArray() && !object.getAsJsonArray("commands").isEmpty());
            default -> false;
        };
    }

    /** Refuses a write of {@code kind} that runs commands unless the token holds the scope. */
    static void require(EditorTokens.Token token, DocumentKind kind, String id, JsonElement source, Set<String> privileged) throws EditorError {
        if (token.has(EditorScope.APPLY_COMMANDS) || !runsCommands(kind, source, privileged)) return;
        throw new EditorError(EditorError.Code.SCOPE_MISSING, kind.wireName() + " " + id + " runs commands, so changing it needs apply:commands;"
                + " pair with /animus editor pair scopes=...,apply:commands, which needs " + EditorCommands.COMMANDS_PERMISSION + ".");
    }

    /** Every written document of an apply; deletions write nothing and need no more than their apply scope. */
    static void requireForChanges(EditorTokens.Token token, List<EditorMessages.Change> changes, Set<String> privileged) throws EditorError {
        for (EditorMessages.Change change : changes)
            if (!change.delete()) require(token, change.kind(), change.id(), change.source().orElseThrow(), privileged);
    }

    /** The undo's scope check, plus this check for every document it writes back. */
    static EditorChanges.ScopeCheck forUndo(EditorTokens.Token token, EditorChanges.ScopeCheck scopes, Set<String> privileged) {
        return new EditorChanges.ScopeCheck() {
            @Override public void check(Set<DocumentKind> kinds, List<String> ids) throws EditorError { scopes.check(kinds, ids); }

            @Override public void restores(DocumentKind kind, String id, String content) throws EditorError {
                JsonElement source;
                // Text that is not JSON never loads, so it cannot run a command either.
                try { source = JsonParser.parseString(content); }
                catch (JsonParseException unreadable) { return; }
                require(token, kind, id, source, privileged);
            }
        };
    }

    /**
     * Import apply and accept through the editor. Accepting the {@code commands} category adds the imported namespaces to the
     * allow-list, and while the allow-list names anything an imported document may land under it, so both need the scope.
     */
    static void requireForImport(EditorTokens.Token token, String step, List<String> categories, boolean commandGrants) throws EditorError {
        if (token.has(EditorScope.APPLY_COMMANDS) || step.equals("rollback")) return;
        boolean commands = categories.stream().anyMatch((String category) -> category.equalsIgnoreCase(art.arcane.animus.core.migration.CitizensDropIn.COMMANDS));
        if (commands) throw new EditorError(EditorError.Code.SCOPE_MISSING, "Accepting commands lets imported behaviors run console commands, which needs apply:commands.");
        if (commandGrants) throw new EditorError(EditorError.Code.SCOPE_MISSING, "[behaviors.actions] lets some behaviors run commands and an import could add to them,"
                + " so this step needs apply:commands.");
    }

    @FunctionalInterface private interface Test { boolean matches(JsonObject object); }

    private static boolean any(JsonElement root, Test test) {
        ArrayDeque<JsonElement> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            JsonElement element = pending.removeFirst();
            if (element.isJsonArray()) element.getAsJsonArray().forEach(pending::add);
            else if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                if (test.matches(object)) return true;
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) pending.add(entry.getValue());
            }
        }
        return false;
    }

    private static Optional<String> text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? Optional.of(value.getAsString()) : Optional.empty();
    }
}
