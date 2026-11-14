package art.arcane.animus.core.editor;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.core.combat.HealthSettings;
import art.arcane.animus.core.npc.GroupDocument;
import art.arcane.animus.core.npc.GroupMerge;
import art.arcane.animus.core.schedule.ScheduleSettings;
import art.arcane.animus.core.shop.ShopDocument;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which editor writes need {@link EditorScope#APPLY_COMMANDS}. A document whose written content runs a command or another
 * privileged capability, or creates items or money, needs it on every edit, even when those parts stay the same: the text,
 * variables, prices and flow around them decide what they do. Each kind is read the way its loader reads it, erring toward
 * asking for the scope:
 * <ul>
 *   <li>a behavior with a {@code capability} naming a privileged key or {@code animus:give-item}, or one that pays a quest's
 *   rewards: {@code animus:advance-quest}, {@code animus:complete-quest} unless {@code rewards} is false, and
 *   {@code animus:start-quest} unless {@code force} is false;</li>
 *   <li>a behavior in the namespace of a {@code rawTextBehaviors} entry, since flags and quest stages are shared by namespace
 *   and reach that behavior's raw command text;</li>
 *   <li>a chest GUI, menu or hologram with an action of {@code type} {@code command}, {@code give} or {@code economy};</li>
 *   <li>a shop, as {@link ShopDocument#parse} reads it, with an entry that runs commands (type {@code command} or a
 *   {@code commands} list) or hands out goods for nothing (a buy at price 0, now or in a discount, and 0 levels; a trade with
 *   no needs). Buys, sells and trades at a price are the shop's own job;</li>
 *   <li>a quest whose rewards run commands or give items, money or experience, or a quest in the namespace of a
 *   {@code rawTextBehaviors} entry, since its reward flags and stages reach that behavior;</li>
 *   <li>an NPC, with its trait groups merged as the registry merges them, or a trait group, that has {@code animus:schedule}
 *   arrival commands, {@code animus:health} death drops of items, experience or equipment, or a {@code rawTextBehaviors}
 *   behavior, whose raw command text can read the NPC's name and place;</li>
 *   <li>a {@code server-behaviors.json} that enables a behavior which itself needs the scope.</li>
 * </ul>
 */
final class EditorCommandGuard {
    static final String COMMAND = "animus:command";
    private static final Set<String> VALUE_CAPABILITIES = Set.of("animus:give-item");
    private static final Set<String> GUI_TYPES = Set.of("command", "give", "economy");
    private static final Set<String> QUEST_VALUE_REWARDS = Set.of("commands", "items", "money", "experience");
    private static final String RAW_TEXT_SHARED = "shares flags with a behavior that has a raw text grant";

    /** A stored document's text, for checks that read past the document being written: an NPC's groups, a list's behaviors. */
    @FunctionalInterface interface Stored { Optional<String> read(DocumentKind kind, String id) throws IOException; }

    /** The live registry's privileged keys, {@code [behaviors.actions] rawTextBehaviors} and the stored documents. */
    record Rules(Set<String> privileged, Set<String> rawTextBehaviors, Stored stored) {
        Rules {
            privileged = Set.copyOf(privileged);
            rawTextBehaviors = Set.copyOf(rawTextBehaviors);
            Objects.requireNonNull(stored);
        }

        /** Reads the store, so call on the file lane. */
        static Rules of(EditorHost host, EditorDocuments documents) {
            return new Rules(EditorCommandGuard.privileged(host.capabilities()), Set.copyOf(host.rawTextBehaviors()), (DocumentKind kind, String id) -> {
                try { return Optional.ofNullable(documents.read(documents.locate(kind, id)).content()); }
                catch (EditorError notAnId) { return Optional.empty(); }
            });
        }

        /** The same rules reading {@code staged}, by data folder path, before the store. */
        Rules staged(Map<String, String> staged) {
            return new Rules(privileged, rawTextBehaviors, (DocumentKind kind, String id) -> {
                String path;
                try { path = EditorDocuments.pathFor(kind, id); }
                catch (EditorError notAnId) { return Optional.empty(); }
                return staged.containsKey(path) ? Optional.of(staged.get(path)) : stored.read(kind, id);
            });
        }

        boolean rawTextNamespace(String id) {
            int colon = id.indexOf(':');
            if (colon < 1) return false;
            for (String behavior : rawTextBehaviors) if (behavior.startsWith(id.substring(0, colon + 1))) return true;
            return false;
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
    static Optional<String> needsCommands(DocumentKind kind, String id, JsonElement source, Rules rules) throws IOException {
        return switch (kind) {
            case BEHAVIOR -> behavior(id, source, rules);
            case INVENTORY, MENU, HOLOGRAM -> any(source, (JsonObject object) -> text(object, "type").map(GUI_TYPES::contains).orElse(false))
                    ? Optional.of("runs commands or creates items or money") : Optional.empty();
            case SHOP -> shop(id, source);
            case QUEST -> {
                if (rules.rawTextNamespace(id)) yield Optional.of(RAW_TEXT_SHARED);
                yield source.isJsonObject() && source.getAsJsonObject().get("rewards") instanceof JsonObject rewards
                        && rewards.keySet().stream().anyMatch(QUEST_VALUE_REWARDS::contains)
                        ? Optional.of("runs commands or creates items, money or experience") : Optional.empty();
            }
            case NPC -> source.isJsonObject() ? definition(effective(source.getAsJsonObject(), rules), rules) : Optional.empty();
            case GROUP -> {
                if (ScheduleSettings.runsCommands(source)) yield Optional.of("runs schedule arrival commands");
                yield source.isJsonObject() && source.getAsJsonObject().get("values") instanceof JsonObject values ? definition(values, rules) : Optional.empty();
            }
            case SERVER_BEHAVIORS -> serverBehaviors(source, rules);
            default -> Optional.empty();
        };
    }

    /** Refuses a write of {@code kind} that runs commands or creates value unless the token holds the scope. */
    static void require(EditorTokens.Token token, DocumentKind kind, String id, JsonElement source, Rules rules) throws EditorError, IOException {
        if (token.has(EditorScope.APPLY_COMMANDS)) return;
        Optional<String> why = needsCommands(kind, id, source, rules);
        if (why.isEmpty()) return;
        throw EditorError.scopeMissing(EditorScope.APPLY_COMMANDS, kind.wireName() + " " + id + " " + why.get() + ", so changing it needs apply:commands;"
                + " pair with /animus editor pair scopes=...,apply:commands, which needs " + EditorCommands.COMMANDS_PERMISSION + ".");
    }

    /** {@link #require} for every document an apply or undo writes; deletions write nothing and need no more than their apply scope. */
    static EditorChanges.WriteCheck writes(EditorTokens.Token token, Rules rules) {
        return (DocumentKind kind, String id, String content) -> {
            JsonElement source;
            // Text that is not JSON never loads, so it cannot run a command either.
            try { source = JsonParser.parseString(content); }
            catch (JsonParseException unreadable) { return; }
            require(token, kind, id, source, rules);
        };
    }

    /**
     * Import apply through the editor. Apply adds the namespaces of the import's command behaviors ({@code grants}) to the
     * allow-list, and while the allow-list names anything an imported document may land under it, so both need the scope.
     */
    static void requireForImport(EditorTokens.Token token, String step, List<String> grants, boolean commandGrants) throws EditorError {
        if (token.has(EditorScope.APPLY_COMMANDS) || step.equals("rollback")) return;
        if (!grants.isEmpty()) throw EditorError.scopeMissing(EditorScope.APPLY_COMMANDS, "This import runs console commands as it did before, so applying it adds "
                + String.join(", ", grants) + " to [behaviors.actions] commandNamespaces, which needs apply:commands.");
        if (commandGrants) throw EditorError.scopeMissing(EditorScope.APPLY_COMMANDS, "[behaviors.actions] lets some behaviors run commands and an import could add to them,"
                + " so this step needs apply:commands.");
    }

    /**
     * Every document an import apply would write, by data folder path, checked as {@code /v1/changes} checks them before
     * anything is written. An import's documents read each other first, so an imported NPC sees its imported groups.
     */
    static void requireForImport(EditorTokens.Token token, Map<String, String> staged, Rules rules) throws EditorError, IOException {
        if (token.has(EditorScope.APPLY_COMMANDS)) return;
        EditorChanges.WriteCheck check = writes(token, rules.staged(staged));
        for (Map.Entry<String, String> document : staged.entrySet()) {
            Optional<DocumentKind> kind = DocumentKind.forPath(document.getKey());
            if (kind.isEmpty()) continue;
            Optional<String> id = EditorDocuments.idFor(kind.get(), document.getKey());
            if (id.isPresent()) check.writes(kind.get(), id.get(), document.getValue());
        }
    }

    private static Optional<String> behavior(String id, JsonElement source, Rules rules) {
        if (rules.rawTextNamespace(id)) return Optional.of(RAW_TEXT_SHARED);
        if (any(source, (JsonObject object) -> text(object, "capability").map(rules.privileged()::contains).orElse(false))) return Optional.of("runs commands");
        if (any(source, (JsonObject object) -> text(object, "capability").map(VALUE_CAPABILITIES::contains).orElse(false))) return Optional.of("creates items");
        return any(source, EditorCommandGuard::paysQuest) ? Optional.of("pays a quest's rewards") : Optional.empty();
    }

    /** A node that can pay a quest's rewards: advancing past the last stage completes it, and a forced start repeats it. */
    private static boolean paysQuest(JsonObject node) {
        return switch (text(node, "capability").orElse("")) {
            case "animus:advance-quest" -> true;
            case "animus:complete-quest" -> !off(node, "rewards", false);
            case "animus:start-quest" -> !off(node, "force", true);
            default -> false;
        };
    }

    /**
     * Whether a boolean argument is surely false: written as {@code false}, or left out when {@code absent} says that means
     * false, and filled by no binding, expression or template.
     */
    private static boolean off(JsonObject node, String argument, boolean absent) {
        if (bound(node, argument)) return false;
        JsonElement literal = node.get("arguments") instanceof JsonObject arguments ? arguments.get(argument) : null;
        if (literal == null) return absent;
        return literal.isJsonPrimitive() && literal.getAsJsonPrimitive().isBoolean() && !literal.getAsBoolean();
    }

    /** Whether a binding, expression or template fills the argument; slot keys are JSON Pointers into the arguments. */
    private static boolean bound(JsonObject node, String argument) {
        for (String slots : List.of("bindings", "expressions", "templates")) {
            JsonElement value = node.get(slots);
            if (value == null) continue;
            if (!value.isJsonObject()) return true;
            for (String pointer : value.getAsJsonObject().keySet()) if (pointer.equals("/" + argument) || pointer.startsWith("/" + argument + "/")) return true;
        }
        return false;
    }

    /** The NPC as the registry runs it: its stored groups merged under its own fields. A group that is missing or invalid adds nothing. */
    private static JsonObject effective(JsonObject npc, Rules rules) throws IOException {
        if (!(npc.get("groups") instanceof JsonArray listed)) return npc;
        List<GroupDocument> groups = new ArrayList<>();
        for (JsonElement group : listed) {
            if (!group.isJsonPrimitive() || !group.getAsJsonPrimitive().isString()) continue;
            Optional<String> stored = rules.stored().read(DocumentKind.GROUP, group.getAsString());
            if (stored.isPresent()) GroupDocument.parse(group.getAsString(), stored.get()).group().ifPresent(groups::add);
        }
        return groups.isEmpty() ? npc : GroupMerge.merge(npc, groups).effective();
    }

    /** An NPC definition or a group's values: what its schedule, death and behaviors do. */
    private static Optional<String> definition(JsonObject definition, Rules rules) {
        if (ScheduleSettings.runsCommands(definition)) return Optional.of("runs schedule arrival commands");
        if (definition.get("modules") instanceof JsonObject modules && modules.get(HealthSettings.MODULE) instanceof JsonObject health
                && health.get("death") instanceof JsonObject death && drops(death)) return Optional.of("drops items or experience when it dies");
        if (definition.get("behaviors") instanceof JsonArray behaviors)
            for (JsonElement behavior : behaviors)
                if (behavior.isJsonPrimitive() && rules.rawTextBehaviors().contains(behavior.getAsString()))
                    return Optional.of("runs " + behavior.getAsString() + ", whose raw command text can read the NPC");
        return Optional.empty();
    }

    /** {@code death} drops something: items, experience above 0 or its equipment. Anything malformed counts. */
    private static boolean drops(JsonObject death) {
        JsonElement items = death.get("drops"), experience = death.get("experience"), equipment = death.get("dropEquipment");
        return items != null && !(items.isJsonArray() && items.getAsJsonArray().isEmpty())
                || experience != null && !(experience.isJsonPrimitive() && experience.getAsJsonPrimitive().isNumber() && experience.getAsDouble() == 0)
                || equipment != null && !(equipment.isJsonPrimitive() && equipment.getAsJsonPrimitive().isBoolean() && !equipment.getAsBoolean());
    }

    /** The list enables what its behaviors do, so it needs the scope while it names a stored behavior that does. */
    private static Optional<String> serverBehaviors(JsonElement source, Rules rules) throws IOException {
        if (!source.isJsonObject() || !(source.getAsJsonObject().get("behaviors") instanceof JsonArray listed)) return Optional.empty();
        for (JsonElement behavior : listed) {
            if (!behavior.isJsonPrimitive() || !behavior.getAsJsonPrimitive().isString()) continue;
            Optional<String> stored = rules.stored().read(DocumentKind.BEHAVIOR, behavior.getAsString());
            if (stored.isEmpty()) continue;
            JsonElement parsed;
            try { parsed = JsonParser.parseString(stored.get()); }
            catch (JsonParseException unreadable) { continue; }
            Optional<String> why = behavior(behavior.getAsString(), parsed, rules);
            if (why.isPresent()) return Optional.of("enables " + behavior.getAsString() + ", which " + why.get());
        }
        return Optional.empty();
    }

    /**
     * A shop as {@link ShopDocument#parse} reads it: entries that run commands or hand out goods for nothing. A shop that
     * does not parse never loads, so it is read structurally and refused only for a {@code commands} list or a type the
     * parser would not take.
     */
    private static Optional<String> shop(String id, JsonElement source) {
        if (!source.isJsonObject()) return Optional.empty();
        Optional<ShopDocument> parsed = ShopDocument.parse(id, source.getAsJsonObject()).shop();
        if (parsed.isEmpty()) return unreadShop(source.getAsJsonObject());
        for (ShopDocument.Page page : parsed.get().pages())
            for (Map<Integer, ShopDocument.Entry> grid : page.grids().values())
                for (ShopDocument.Entry entry : grid.values()) {
                    if (entry.type() == ShopDocument.Type.COMMAND || !entry.commands().isEmpty()) return Optional.of("runs commands");
                    if (free(entry)) return Optional.of("hands out goods for nothing");
                }
        return Optional.empty();
    }

    /** A buy entry at no price and no levels, now or during a discount, or a trade that asks for nothing. */
    private static boolean free(ShopDocument.Entry entry) {
        return switch (entry.type()) {
            case BUY -> entry.levels() == 0 && (entry.price().signum() == 0 || entry.discount().map((ShopDocument.Discount discount) -> discount.price().signum() == 0).orElse(false));
            case TRADE -> entry.needs().isEmpty();
            default -> false;
        };
    }

    private static Optional<String> unreadShop(JsonObject shop) {
        if (!(shop.get("pages") instanceof JsonArray pages)) return Optional.empty();
        for (JsonElement page : pages) {
            if (!page.isJsonObject()) continue;
            for (JsonElement grid : page.getAsJsonObject().asMap().values()) {
                if (!grid.isJsonObject()) continue;
                for (JsonElement entry : grid.getAsJsonObject().asMap().values()) {
                    if (!entry.isJsonObject()) continue;
                    JsonObject object = entry.getAsJsonObject();
                    JsonElement commands = object.get("commands");
                    if (commands != null && !(commands.isJsonArray() && commands.getAsJsonArray().isEmpty())) return Optional.of("runs commands");
                    JsonElement type = object.get("type");
                    if (type == null) continue;
                    Optional<ShopDocument.Type> named = type.isJsonPrimitive() && type.getAsJsonPrimitive().isString() ? ShopDocument.Type.of(type.getAsString()) : Optional.empty();
                    if (named.isEmpty() || named.get() == ShopDocument.Type.COMMAND) return Optional.of("may run commands");
                }
            }
        }
        return Optional.empty();
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
