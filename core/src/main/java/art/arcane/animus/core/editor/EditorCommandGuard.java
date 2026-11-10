package art.arcane.animus.core.editor;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.schedule.ScheduleSettings;
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
 * Which editor writes need {@link EditorScope#APPLY_COMMANDS}. A document whose written content runs a command or another
 * privileged capability, or creates items or money, needs it on every edit, even when those parts stay the same: the text,
 * variables, prices and flow around them decide what they do. The check reads the JSON structure the parsers read, so it errs
 * toward asking for the scope:
 * <ul>
 *   <li>a behavior with a {@code capability} naming a privileged key or {@code animus:give-item};</li>
 *   <li>a behavior in the namespace of a {@code rawTextBehaviors} entry, since flags and quest stages are shared by namespace
 *   and reach that behavior's raw command text;</li>
 *   <li>a chest GUI, menu or hologram with an action of {@code type} {@code command}, {@code give} or {@code economy};</li>
 *   <li>a shop with a non-empty {@code commands} list or any entry that buys, sells or trades, since its prices and goods
 *   create items and money;</li>
 *   <li>a quest whose rewards run commands or give items, money or experience, or a quest in the namespace of a
 *   {@code rawTextBehaviors} entry, since its reward flags and stages reach that behavior;</li>
 *   <li>an NPC or trait group with {@code animus:schedule} arrival commands.</li>
 * </ul>
 */
final class EditorCommandGuard {
    static final String COMMAND = "animus:command";
    private static final Set<String> VALUE_CAPABILITIES = Set.of("animus:give-item");
    private static final Set<String> GUI_TYPES = Set.of("command", "give", "economy");
    private static final Set<String> TRADING_ENTRIES = Set.of("buy", "sell", "trade", "command");
    private static final Set<String> QUEST_VALUE_REWARDS = Set.of("commands", "items", "money", "experience");

    /** The live registry's privileged keys and {@code [behaviors.actions] rawTextBehaviors}. */
    record Rules(Set<String> privileged, Set<String> rawTextNamespaces) {
        Rules {
            privileged = Set.copyOf(privileged);
            rawTextNamespaces = Set.copyOf(rawTextNamespaces);
        }

        static Rules of(EditorHost host) {
            Set<String> namespaces = new TreeSet<>();
            for (String behavior : host.rawTextBehaviors()) if (behavior.indexOf(':') > 0) namespaces.add(behavior.substring(0, behavior.indexOf(':')));
            return new Rules(EditorCommandGuard.privileged(host.capabilities()), namespaces);
        }
    }

    private EditorCommandGuard() {}

    /** The privileged capability keys the live registry reports, always with {@code animus:command}. */
    static Set<String> privileged(Collection<Capabilities.Availability> capabilities) {
        Set<String> keys = new TreeSet<>(Set.of(COMMAND));
        for (Capabilities.Availability availability : capabilities)
            if (availability.descriptor().privileged()) keys.add(availability.descriptor().key().toString());
        return keys;
    }

    /** Why writing the document needs the scope, or empty when its apply scope is enough. */
    static Optional<String> needsCommands(DocumentKind kind, String id, JsonElement source, Rules rules) {
        return switch (kind) {
            case BEHAVIOR -> {
                if (id.indexOf(':') > 0 && rules.rawTextNamespaces().contains(id.substring(0, id.indexOf(':'))))
                    yield Optional.of("shares flags with a behavior that has a raw text grant");
                if (any(source, (JsonObject object) -> text(object, "capability").map(rules.privileged()::contains).orElse(false))) yield Optional.of("runs commands");
                yield any(source, (JsonObject object) -> text(object, "capability").map(VALUE_CAPABILITIES::contains).orElse(false))
                        ? Optional.of("creates items") : Optional.empty();
            }
            case INVENTORY, MENU, HOLOGRAM -> any(source, (JsonObject object) -> text(object, "type").map(GUI_TYPES::contains).orElse(false))
                    ? Optional.of("runs commands or creates items or money") : Optional.empty();
            case SHOP -> any(source, (JsonObject object) -> (object.has("commands") && object.get("commands").isJsonArray() && !object.getAsJsonArray("commands").isEmpty())
                    || text(object, "type").map((String type) -> TRADING_ENTRIES.contains(type.toLowerCase(java.util.Locale.ROOT))).orElse(false))
                    ? Optional.of("runs commands or sets prices") : Optional.empty();
            case QUEST -> {
                if (id.indexOf(':') > 0 && rules.rawTextNamespaces().contains(id.substring(0, id.indexOf(':'))))
                    yield Optional.of("shares flags with a behavior that has a raw text grant");
                yield source.isJsonObject() && source.getAsJsonObject().has("rewards") && source.getAsJsonObject().get("rewards").isJsonObject()
                        && source.getAsJsonObject().getAsJsonObject("rewards").entrySet().stream().anyMatch((Map.Entry<String, JsonElement> reward) ->
                                QUEST_VALUE_REWARDS.contains(reward.getKey()))
                        ? Optional.of("runs commands or creates items, money or experience") : Optional.empty();
            }
            case NPC, GROUP -> ScheduleSettings.runsCommands(source) ? Optional.of("runs schedule arrival commands") : Optional.empty();
            default -> Optional.empty();
        };
    }

    /** Refuses a write of {@code kind} that runs commands or creates value unless the token holds the scope. */
    static void require(EditorTokens.Token token, DocumentKind kind, String id, JsonElement source, Rules rules) throws EditorError {
        if (token.has(EditorScope.APPLY_COMMANDS)) return;
        Optional<String> why = needsCommands(kind, id, source, rules);
        if (why.isEmpty()) return;
        throw new EditorError(EditorError.Code.SCOPE_MISSING, kind.wireName() + " " + id + " " + why.get() + ", so changing it needs apply:commands;"
                + " pair with /animus editor pair scopes=...,apply:commands, which needs " + EditorCommands.COMMANDS_PERMISSION + ".");
    }

    /** Every written document of an apply; deletions write nothing and need no more than their apply scope. */
    static void requireForChanges(EditorTokens.Token token, List<EditorMessages.Change> changes, Rules rules) throws EditorError {
        for (EditorMessages.Change change : changes)
            if (!change.delete()) require(token, change.kind(), change.id(), change.source().orElseThrow(), rules);
    }

    /** The undo's scope check, plus this check for every document it writes back. */
    static EditorChanges.ScopeCheck forUndo(EditorTokens.Token token, EditorChanges.ScopeCheck scopes, Rules rules) {
        return new EditorChanges.ScopeCheck() {
            @Override public void check(Set<DocumentKind> kinds, List<String> ids) throws EditorError { scopes.check(kinds, ids); }

            @Override public void restores(DocumentKind kind, String id, String content) throws EditorError {
                JsonElement source;
                // Text that is not JSON never loads, so it cannot run a command either.
                try { source = JsonParser.parseString(content); }
                catch (JsonParseException unreadable) { return; }
                require(token, kind, id, source, rules);
            }
        };
    }

    /**
     * Import apply through the editor. Apply adds the namespaces of the import's command behaviors ({@code grants}) to the
     * allow-list, and while the allow-list names anything an imported document may land under it, so both need the scope.
     */
    static void requireForImport(EditorTokens.Token token, String step, List<String> grants, boolean commandGrants) throws EditorError {
        if (token.has(EditorScope.APPLY_COMMANDS) || step.equals("rollback")) return;
        if (!grants.isEmpty()) throw new EditorError(EditorError.Code.SCOPE_MISSING, "This import runs console commands as it did before, so applying it adds "
                + String.join(", ", grants) + " to [behaviors.actions] commandNamespaces, which needs apply:commands.");
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
