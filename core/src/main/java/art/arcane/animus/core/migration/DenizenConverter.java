package art.arcane.animus.core.migration;

import art.arcane.animus.core.migration.DenizenArgs.Arg;
import art.arcane.animus.core.migration.DenizenArgs.Command;
import art.arcane.animus.core.migration.DenizenExpressions.Pending;
import art.arcane.animus.core.migration.DenizenExpressions.Result;
import art.arcane.animus.core.migration.DenizenExpressions.Text;
import art.arcane.animus.core.migration.DenizenScripts.Container;
import art.arcane.animus.core.migration.DenizenScripts.Entry;
import art.arcane.animus.core.migration.DenizenScripts.Item;
import art.arcane.animus.core.migration.DenizenScripts.Mapping;
import art.arcane.animus.core.migration.DenizenScripts.Node;
import art.arcane.animus.core.migration.DenizenScripts.Scalar;
import art.arcane.animus.core.migration.DenizenScripts.Sequence;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Converts Denizen scripts and flags into Animus behavior documents, chest GUIs and behavior state. It is a pure
 * function of its input, so the server and the browser converter (a line-for-line copy) stage the same bytes.
 * Assignment and interact scripts become NPC behaviors in the {@code denizen} namespace, bound to the Citizens NPCs that
 * had the assignment; task scripts become routines and manual-run behaviors, procedures routines that return a value;
 * world, command and book scripts become server behaviors; inventory scripts chest GUIs. Tags become expressions and
 * templates. Flags become typed {@code denizen} flags with their expiry, and interact steps relationship quest stages.
 * Everything else is kept and listed in the report with its reason. Generated behaviors stay off until the review
 * accepts them.
 */
final class DenizenConverter {
    static final int CONVERTER_VERSION = 7;
    /** The author a converted book gets when its Denizen author is blank. */
    static final String BLANK_BOOK_AUTHOR = "Server";
    static final String NAMESPACE = "denizen";
    static final int MAXIMUM_FILES = 512, MAXIMUM_PLAYERS = 10_000, MAXIMUM_STATE_KEYS = 128, MAXIMUM_TEXT = 1024, DEFAULT_RADIUS = 5;
    static final String STATE_ROOT = "state/behavior-state/", GUI_ROOT = "guis/denizen/";
    static final String NO_NPC = "server behaviors have no NPC";
    private static final String NAME = "[a-z0-9][a-z0-9_-]{0,63}";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    /** {@code notables} is the text of Denizen's notables.yml, whose noted cuboids and ellipsoids area events use. */
    record Input(TreeMap<String, String> scripts, Optional<String> citizens, Optional<String> serverFlags, TreeMap<String, String> playerFlags, Optional<String> notables) {
        Input(TreeMap<String, String> scripts, Optional<String> citizens, Optional<String> serverFlags, TreeMap<String, String> playerFlags) {
            this(scripts, citizens, serverFlags, playerFlags, Optional.empty());
        }
    }

    /** The staged files by path relative to the import directory, and the report also written as {@code report.json}. */
    record Output(String id, TreeMap<String, String> files, JsonObject report) {}

    private record Npc(String key, String id, String uuid, String name, List<String> assignments, JsonObject triggers, Map<String, String> flags, List<String> problems) {}

    private record Outcome(String file, int line, String container, String kind, String name, String text, boolean converted, String reason) {}

    record Interact(Container container, String slug, String quest, List<String> steps, List<String> order, int defaultIndex) {
        /** Stage of a step by its name, ignoring case and the default marker. */
        Optional<Integer> stage(String name) {
            String wanted = stepName(name);
            for (int index = 0; index < steps.size(); index++) if (stepName(steps.get(index)).equals(wanted)) return Optional.of(index);
            return Optional.empty();
        }
    }

    /** One behavior document under construction: its routines, declared variables and counters for generated names. */
    static final class Doc {
        final boolean server;
        final LinkedHashMap<String, JsonObject> routines = new LinkedHashMap<>();
        final TreeMap<String, String> variables = new TreeMap<>();
        int temporaries, loops;
        /** The longest {@code cooldown} an interact script's steps set, in ticks: per player, and {@code global} for everyone. */
        long playerCooldown = -1, globalCooldown = -1;
        /** Sources ({@code file:line}) of guards and stops that did not convert; any one holds the behavior back from binding. */
        final LinkedHashSet<String> held = new LinkedHashSet<>();

        Doc(boolean server) { this.server = server; }
    }

    /** Where the commands being converted come from and what they may read. */
    static final class Scope {
        final Doc doc;
        final Set<String> calling;
        final boolean record, server;
        final Container container;
        final Optional<Interact> interact;
        final int step;
        /** Definitions a loop gives another meaning, such as {@code loop_index}, by lowercase name. */
        final Map<String, String> definitions;
        /** The kinds of the enclosing loops, innermost last. */
        final List<String> loops;
        /** {@code save:} names of converted runs and the variables holding their results. */
        final Map<String, String> saves;
        /** Denizen context keys the trigger passes, and their Animus names. */
        final Map<String, String> context;
        /** The {@code determine cancelled} line an event's {@code consume} or {@code cancel} condition was made from; set on that event's scope only. */
        Item cancel;
        /** Whether this scope is a world event the platform can cancel. */
        boolean cancellable;

        Scope(Doc doc, Set<String> calling, boolean record, Container container, Optional<Interact> interact, int step, Map<String, String> definitions, List<String> loops, Map<String, String> saves, Map<String, String> context) {
            this.doc = doc; this.calling = calling; this.record = record; this.server = doc.server; this.container = container; this.interact = interact; this.step = step;
            this.definitions = definitions; this.loops = loops; this.saves = saves; this.context = context;
        }

        static Scope of(Doc doc, boolean record, Container container, Optional<Interact> interact, int step, Map<String, String> context) {
            return new Scope(doc, new HashSet<>(), record, container, interact, step, new TreeMap<>(), List.of(), new TreeMap<>(), context);
        }

        /** The scope of another script run from this one: same document, its own definitions, no loops and no event context. */
        Scope in(Container other) { return new Scope(doc, calling, false, other, Optional.empty(), -1, new TreeMap<>(), List.of(), new TreeMap<>(), Map.of()); }

        Scope loop(Map<String, String> inner, List<String> kinds) { return new Scope(doc, calling, record, container, interact, step, inner, kinds, saves, context); }
    }

    final TreeMap<String, Container> containers = new TreeMap<>();
    final TreeMap<String, Interact> interacts = new TreeMap<>();
    final TreeMap<String, String> sounds = new TreeMap<>();
    private final TreeMap<String, Outcome> outcomes = new TreeMap<>();
    private final List<String> issues = new ArrayList<>();
    private final JsonArray preserved = new JsonArray();
    private final TreeMap<String, String> slugs = new TreeMap<>();
    private final Set<String> usedSlugs = new HashSet<>();
    private final List<Npc> npcs = new ArrayList<>();
    private final TreeMap<String, String> guis = new TreeMap<>();
    private final TreeSet<String> serverBehaviors = new TreeSet<>();
    /** Behaviors with a guard or stop that did not convert, and those lines; never bound or enabled by the import. */
    private final TreeMap<String, Doc> heldBehaviors = new TreeMap<>();
    /** World behaviors of NPC events, bound to every NPC. */
    private final TreeSet<String> clickBehaviors = new TreeSet<>();
    /** Literal values scripts and NPC data give each flag, by {@code scope:name}; filled on first use. */
    private TreeMap<String, TreeSet<String>> flagValues;
    private final TreeMap<String, List<String>> guards = new TreeMap<>();
    /** Noted areas by lowercase name: notables.yml first, then literal {@code note ... as:name} lines of scripts. */
    final TreeMap<String, DenizenEvents.Note> notes = new TreeMap<>();
    private final DenizenExpressions expressions = new DenizenExpressions(this);
    private final DenizenCommands commands = new DenizenCommands(this, expressions);

    private DenizenConverter() {}

    static Output convert(Input input) throws IOException {
        return new DenizenConverter().run(input);
    }

    private Output run(Input input) throws IOException {
        if (input.scripts().size() > MAXIMUM_FILES) throw new IOException("More than " + MAXIMUM_FILES + " Denizen script files");
        if (input.playerFlags().size() > MAXIMUM_PLAYERS) throw new IOException("More than " + MAXIMUM_PLAYERS + " Denizen player flag files");
        JsonObject sources = new JsonObject();
        for (Map.Entry<String, String> file : input.scripts().entrySet()) sources.addProperty("scripts/" + file.getKey(), JsonDocuments.hash(file.getValue()));
        input.citizens().ifPresent((String text) -> sources.addProperty("citizens", JsonDocuments.hash(text)));
        input.serverFlags().ifPresent((String text) -> sources.addProperty("server_flags.dat", JsonDocuments.hash(text)));
        input.notables().ifPresent((String text) -> sources.addProperty("notables.yml", JsonDocuments.hash(text)));
        for (Map.Entry<String, String> file : input.playerFlags().entrySet()) sources.addProperty("player_flags/" + file.getKey() + ".dat", JsonDocuments.hash(file.getValue()));
        String id = JsonDocuments.hash("animus-denizen-v" + CONVERTER_VERSION + "\n" + sources);

        for (Map.Entry<String, String> file : input.scripts().entrySet()) {
            DenizenScripts.Parsed parsed = DenizenScripts.parse(file.getKey(), file.getValue());
            issues.addAll(parsed.issues());
            for (Container container : parsed.containers()) {
                String key = container.name().toLowerCase(Locale.ROOT);
                if (containers.containsKey(key)) {
                    issues.add(container.file() + ":" + container.line() + ": script " + container.name() + " is defined again; the first definition in " + containers.get(key).file() + " is used");
                    preserve(container.file(), container.line(), container.name(), "container", container.type(), "", "script name defined twice; Denizen also refuses duplicates");
                    continue;
                }
                containers.put(key, container);
            }
        }
        for (Container container : containers.values()) slug(container.name());
        for (Container container : containers.values()) if (container.type().equals("interact")) interacts.put(container.name().toLowerCase(Locale.ROOT), interact(container));
        if (input.citizens().isPresent()) readNpcs(input.citizens().get());
        if (input.notables().isPresent()) notes.putAll(DenizenEvents.notables(CitizensImporter.parseYaml(input.notables().get()), issues));
        for (Container container : containers.values()) collectNotes(container.body());

        TreeMap<String, DenizenFlags.Values> playerValues = new TreeMap<>();
        for (Map.Entry<String, String> file : input.playerFlags().entrySet()) playerValues.put(file.getKey(), DenizenFlags.file(file.getValue()));
        Optional<DenizenFlags.Values> serverValues = input.serverFlags().map(DenizenFlags::file);

        TreeMap<String, String> files = new TreeMap<>();
        TreeMap<String, List<String>> bindings = new TreeMap<>();
        TreeMap<String, List<String>> heldNpcs = new TreeMap<>();
        JsonObject behaviorStatus = new JsonObject();
        TreeMap<String, String> documents = new TreeMap<>();
        // Values come first: other scripts read data, give items and books and open inventories by name.
        for (Container container : containers.values()) {
            switch (container.type()) {
                case "data" -> record(container, container.line(), "container", "data", container.name(), true, "");
                case "item" -> itemContainer(container);
                case "book" -> bookContainer(container, documents);
                case "inventory" -> inventoryContainer(container);
                default -> { }
            }
        }
        for (Container container : containers.values()) {
            switch (container.type()) {
                case "data", "item", "book", "inventory" -> { }
                case "task" -> task(container, documents);
                case "procedure" -> procedureContainer(container);
                case "assignment" -> assignment(container, documents);
                case "interact" -> interactDocument(interacts.get(container.name().toLowerCase(Locale.ROOT)), documents);
                case "world" -> world(container, documents);
                case "command" -> commandContainer(container, documents);
                default -> preserveContainer(container, switch (container.type()) {
                    case "format" -> "format scripts shape Denizen's own chat output; Animus messages are written out in full";
                    case "economy" -> "economy scripts provide a Vault economy; Animus has none to provide";
                    case "enchantment" -> "custom enchantments have no Animus equivalent";
                    case "map" -> "map scripts draw on map items; Animus has no map renderer";
                    default -> "no Animus equivalent for " + container.type() + " scripts";
                });
            }
        }
        for (Npc npc : npcs) {
            List<String> bound = new ArrayList<>();
            for (String assignment : npc.assignments()) {
                Container container = containers.get(assignment);
                if (container == null || !container.type().equals("assignment")) {
                    issues.add(npc.id() + ": assignment " + assignment + " is not an assignment script here");
                    continue;
                }
                String own = behaviorId("assignment", container);
                if (documents.containsKey(own) && !bound.contains(own)) bound.add(own);
                for (Container listed : interactList(container)) {
                    String other = behaviorId("interact", listed);
                    if (documents.containsKey(other) && !bound.contains(other)) bound.add(other);
                }
            }
            for (String click : clickBehaviors) if (documents.containsKey(click) && !bound.contains(click)) bound.add(click);
            for (String problem : npc.problems()) issues.add(npc.id() + ": " + problem);
            for (String behavior : bound) if (heldBehaviors.containsKey(behavior)) heldNpcs.computeIfAbsent(behavior, (String ignored) -> new ArrayList<>()).add(npc.id());
            bound.removeIf(heldBehaviors::containsKey);
            if (!bound.isEmpty()) bindings.put(npc.id(), bound);
        }
        for (Map.Entry<String, String> document : documents.entrySet()) files.put("behaviors/" + document.getKey().replace(':', '/') + ".json", document.getValue());
        for (Map.Entry<String, String> gui : guis.entrySet()) files.put(GUI_ROOT + gui.getKey() + ".json", gui.getValue());

        TreeMap<String, TreeMap<String, JsonObject>> state = new TreeMap<>();
        TreeMap<String, Integer> imported = new TreeMap<>();
        for (String scope : List.of("global", "npc", "player", "relationship")) imported.put(scope, 0);
        int preservedFlags = 0;
        for (Map.Entry<String, DenizenFlags.Values> player : playerValues.entrySet()) {
            String source = "player_flags/" + player.getKey() + ".dat";
            if (!player.getKey().matches(UUID_PATTERN)) {
                issues.add(source + ": the file name is not a lowercase player UUID; its flags are kept");
                preservedFlags += player.getValue().flags().size();
                for (DenizenFlags.Flag flag : player.getValue().flags().values()) preserve(source, 0, "", "flag", flag.name(), "", "the file name is not a player UUID");
                continue;
            }
            preservedFlags += importFlags(source, player.getValue(), "player", Optional.empty(), Optional.of(player.getKey()), state, imported);
        }
        if (serverValues.isPresent()) preservedFlags += importFlags("server_flags.dat", serverValues.get(), "global", Optional.empty(), Optional.empty(), state, imported);
        for (Npc npc : npcs) {
            if (npc.flags().isEmpty()) continue;
            preservedFlags += importFlags("citizens:" + npc.id(), DenizenFlags.mapping(npc.flags()), "npc", Optional.of(npc.uuid()), Optional.empty(), state, imported);
        }
        for (Map.Entry<String, List<String>> guard : guards.entrySet()) {
            TreeMap<String, JsonObject> document = state.computeIfAbsent(statePath("npc", Optional.of(guard.getKey()), Optional.empty()), (String ignored) -> new TreeMap<>());
            for (String flag : guard.getValue()) {
                if (document.size() >= MAXIMUM_STATE_KEYS && !document.containsKey(flag)) { issues.add(guard.getKey() + ": no room for the guard flag " + flag + "; its on assignment runs again on the next spawn"); continue; }
                JsonObject value = new JsonObject();
                value.addProperty("type", "boolean");
                value.addProperty("value", true);
                document.put(flag, value);
                imported.merge("npc", 1, Integer::sum);
            }
        }
        for (Map.Entry<String, TreeMap<String, JsonObject>> document : state.entrySet()) files.put(document.getKey(), stateDocument(document.getKey(), document.getValue()));

        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", 1);
        report.addProperty("converterVersion", CONVERTER_VERSION);
        report.addProperty("importId", id);
        report.addProperty("notice", "Data conversion only. Generated behaviors are listed on no NPC and server behaviors stay off until the denizen review is accepted, and commands stay denied until allow-listed.");
        report.add("sources", sources);
        report.addProperty("behaviors", documents.size());
        report.add("containers", containerCounts());
        report.add("commands", counts("command", "converted", "preserved"));
        report.add("events", counts("event", "converted", "preserved"));
        report.add("tags", tagCounts());
        JsonObject flags = new JsonObject();
        JsonObject importedCounts = new JsonObject();
        for (Map.Entry<String, Integer> count : imported.entrySet()) importedCounts.addProperty(count.getKey(), count.getValue());
        flags.add("imported", importedCounts);
        flags.addProperty("preserved", preservedFlags);
        report.add("flags", flags);
        JsonObject bound = new JsonObject();
        for (Map.Entry<String, List<String>> binding : bindings.entrySet()) {
            JsonArray ids = new JsonArray();
            for (String behavior : binding.getValue()) ids.add(behavior);
            bound.add(binding.getKey(), ids);
        }
        report.add("bindings", bound);
        JsonObject npcUuids = new JsonObject();
        for (Npc npc : npcs) if (bindings.containsKey(npc.id())) npcUuids.addProperty(npc.id(), npc.uuid());
        report.add("npcUuids", npcUuids);
        for (Map.Entry<String, String> document : documents.entrySet())
            behaviorStatus.addProperty(document.getKey(), heldBehaviors.containsKey(document.getKey()) ? "held" : partial(document.getKey()) ? "partial" : "complete");
        report.add("behaviorStatus", behaviorStatus);
        JsonObject heldReport = new JsonObject();
        for (Map.Entry<String, Doc> held : heldBehaviors.entrySet()) {
            if (!documents.containsKey(held.getKey())) continue;
            JsonObject entry = new JsonObject();
            if (held.getValue().server) entry.addProperty("server", true);
            JsonArray lines = new JsonArray();
            for (String line : held.getValue().held) lines.add(line);
            entry.add("lines", lines);
            JsonArray heldOn = new JsonArray();
            for (String npc : heldNpcs.getOrDefault(held.getKey(), List.of())) heldOn.add(npc);
            entry.add("npcs", heldOn);
            heldReport.add(held.getKey(), entry);
        }
        report.add("heldBehaviors", heldReport);
        JsonArray commandBehaviors = new JsonArray();
        for (Map.Entry<String, String> document : documents.entrySet()) if (document.getValue().contains("\"animus:command\"")) commandBehaviors.add(document.getKey());
        report.add("commandBehaviors", commandBehaviors);
        JsonArray serverList = new JsonArray();
        for (String behavior : serverBehaviors) if (documents.containsKey(behavior)) serverList.add(behavior);
        report.add("serverBehaviors", serverList);
        JsonArray guiList = new JsonArray();
        for (String gui : guis.keySet()) guiList.add(GUI_ROOT + gui + ".json");
        report.add("guis", guiList);
        JsonObject soundMap = new JsonObject();
        for (Map.Entry<String, String> sound : sounds.entrySet()) soundMap.addProperty(sound.getKey(), sound.getValue());
        report.add("sounds", soundMap);
        JsonArray issueList = new JsonArray();
        for (String issue : issues) issueList.add(issue);
        report.add("issues", issueList);
        report.add("preserved", preserved);
        JsonObject manifest = new JsonObject();
        for (Map.Entry<String, String> file : files.entrySet()) manifest.addProperty(file.getKey(), JsonDocuments.hash(file.getValue()));
        report.add("documents", manifest);
        files.put("report.json", JsonDocuments.write(report));
        return new Output(id, files, report);
    }

    // ---- Citizens NPCs ----

    private void readNpcs(String text) throws IOException {
        JsonObject root = CitizensImporter.parseYaml(text);
        if (!root.has("npc") || !root.get("npc").isJsonObject()) throw new IOException("$.npc: expected Citizens NPC mapping");
        TreeMap<Long, Map.Entry<String, JsonElement>> ordered = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("npc").entrySet()) {
            if (!entry.getKey().matches("0|[1-9][0-9]{0,9}") || !entry.getValue().isJsonObject()) continue;
            ordered.put(Long.parseLong(entry.getKey()), entry);
        }
        for (Map.Entry<String, JsonElement> entry : ordered.values()) {
            String key = entry.getKey();
            JsonObject record = entry.getValue().getAsJsonObject();
            JsonObject traits = record.has("traits") && record.get("traits").isJsonObject() ? record.getAsJsonObject("traits") : new JsonObject();
            String uuid = scalar(record, "uuid").orElse(UUID.nameUUIDFromBytes(("animus:citizens:" + key).getBytes(StandardCharsets.UTF_8)).toString());
            List<String> assignments = new ArrayList<>();
            JsonElement assignment = traits.get("assignment");
            if (assignment != null && assignment.isJsonPrimitive()) assignments.add(assignment.getAsString().trim().toLowerCase(Locale.ROOT));
            else if (assignment != null && assignment.isJsonObject()) {
                JsonElement list = assignment.getAsJsonObject().get("assignment_list");
                if (list != null && list.isJsonArray()) for (JsonElement name : list.getAsJsonArray()) if (name.isJsonPrimitive()) assignments.add(name.getAsString().trim().toLowerCase(Locale.ROOT));
                JsonElement single = assignment.getAsJsonObject().get("assignment");
                if (single != null && single.isJsonPrimitive()) assignments.add(single.getAsString().trim().toLowerCase(Locale.ROOT));
            }
            JsonObject triggers = traits.has("triggers") && traits.get("triggers").isJsonObject() ? traits.getAsJsonObject("triggers") : new JsonObject();
            TreeMap<String, String> flags = new TreeMap<>();
            List<String> problems = new ArrayList<>();
            JsonElement denizen = traits.get("denizen_flags");
            if (denizen != null && denizen.isJsonObject() && denizen.getAsJsonObject().has("full_flag_data")) {
                JsonElement data = denizen.getAsJsonObject().get("full_flag_data");
                if (data.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> flag : data.getAsJsonObject().entrySet()) {
                        if (flag.getValue().isJsonPrimitive()) flags.put(flag.getKey(), flag.getValue().getAsString());
                        else problems.add("NPC flag " + flag.getKey() + " is not a single value; kept in citizens:review");
                    }
                }
            }
            if (traits.has("denizenflags")) problems.add("old denizenflags data is kept in citizens:review");
            npcs.add(new Npc(key, "citizens-" + key, uuid, scalar(record, "name").orElse("Citizen " + key), assignments, triggers, flags, problems));
        }
    }

    private static Optional<String> scalar(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? Optional.of(value.getAsString()) : Optional.empty();
    }

    /** NPCs whose assignments reach the container: the assignment itself or an assignment listing this interact script. */
    private List<Npc> boundNpcs(Container container) {
        String name = container.name().toLowerCase(Locale.ROOT);
        List<Npc> bound = new ArrayList<>();
        for (Npc npc : npcs) {
            for (String assignment : npc.assignments()) {
                Container script = containers.get(assignment);
                if (script == null || !script.type().equals("assignment")) continue;
                boolean reaches = assignment.equals(name);
                if (!reaches) for (Container listed : interactList(script)) if (listed.name().toLowerCase(Locale.ROOT).equals(name)) reaches = true;
                if (reaches) { bound.add(npc); break; }
            }
        }
        return bound;
    }

    /** The trigger setting of the NPCs reached by the container: enabled when any has it on, otherwise Denizen's defaults. */
    private boolean enabled(Container container, String trigger, Optional<Sequence> onAssignment) {
        boolean known = false;
        for (Npc npc : boundNpcs(container)) {
            JsonElement enabled = npc.triggers().get("enabled");
            if (enabled == null || !enabled.isJsonObject()) continue;
            known = true;
            JsonElement value = ignoringCase(enabled.getAsJsonObject(), trigger);
            if (value != null && value.isJsonPrimitive() && value.getAsString().equalsIgnoreCase("true")) return true;
        }
        if (known) return false;
        if (onAssignment.isPresent()) {
            for (Item item : onAssignment.get().items()) {
                Command command = DenizenArgs.command(item.text());
                if (!command.name().equals("trigger")) continue;
                Optional<Arg> name = command.prefixed("name");
                Optional<Arg> state = command.prefixed("state");
                if (name.isPresent() && name.get().value().equalsIgnoreCase(trigger)) return state.isEmpty() || state.get().value().equalsIgnoreCase("true");
            }
        }
        return trigger.equals("click") || trigger.equals("chat");
    }

    /** A number of seconds stored per trigger in the NPCs' triggers trait, from the first NPC that has one. */
    private Optional<Double> triggerSetting(Container container, String field, String trigger) {
        for (Npc npc : boundNpcs(container)) {
            JsonElement section = npc.triggers().get(field);
            if (section == null || !section.isJsonObject()) continue;
            JsonElement value = ignoringCase(section.getAsJsonObject(), trigger);
            if (value == null || !value.isJsonPrimitive()) continue;
            try { return Optional.of(Double.parseDouble(value.getAsString())); }
            catch (NumberFormatException ignored) { /* not a number: look further */ }
        }
        return Optional.empty();
    }

    /** Denizen saves trigger names in upper case ({@code CLICK}); older saves used lower case. */
    private static JsonElement ignoringCase(JsonObject object, String key) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
        return null;
    }

    private int radius(Container container, Optional<Sequence> onAssignment) {
        Optional<Double> stored = triggerSetting(container, "radius", "proximity");
        if (stored.isPresent()) return clampRadius(stored.get());
        if (onAssignment.isPresent()) {
            for (Item item : onAssignment.get().items()) {
                Command command = DenizenArgs.command(item.text());
                Optional<Arg> name = command.prefixed("name");
                Optional<Arg> radius = command.prefixed("radius");
                if (command.name().equals("trigger") && name.isPresent() && name.get().value().equalsIgnoreCase("proximity") && radius.isPresent() && radius.get().value().matches("[0-9]{1,3}"))
                    return clampRadius(Double.parseDouble(radius.get().value()));
            }
        }
        return DEFAULT_RADIUS;
    }

    private static int clampRadius(double radius) { return (int) Math.max(1, Math.min(64, Math.round(radius))); }

    private void cooldown(JsonObject trigger, Container container, String kind) {
        Optional<Double> seconds = triggerSetting(container, "duration", kind);
        if (seconds.isPresent() && seconds.get() > 0) trigger.addProperty("cooldownTicks", (int) Math.min(72_000, Math.round(seconds.get() * 20)));
    }

    // ---- Names ----

    /** A path segment for a script name: lowercase, other characters as dashes, at most 64 characters, unique. */
    private String slug(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        String known = slugs.get(key);
        if (known != null) return known;
        String base = key.replaceAll("[^a-z0-9_-]+", "-").replaceAll("^[^a-z0-9]+", "");
        if (base.length() > 60) base = base.substring(0, 60);
        if (base.isEmpty()) base = "script";
        String candidate = base;
        for (int suffix = 2; usedSlugs.contains(candidate); suffix++) candidate = base + "-" + suffix;
        usedSlugs.add(candidate);
        slugs.put(key, candidate);
        return candidate;
    }

    private String behaviorId(String kind, Container container) { return NAMESPACE + ":" + kind + "/" + slug(container.name()); }

    /** The Animus flag or quest name for a Denizen flag: lowercase, sub-flag dots as dashes. Empty when it cannot be one. */
    static Optional<String> flagName(String name) {
        String lowered = name.toLowerCase(Locale.ROOT);
        if (!lowered.matches("[a-z0-9_.]+")) return Optional.empty();
        String mapped = lowered.replace('.', '-');
        return mapped.matches(NAME) ? Optional.of(mapped) : Optional.empty();
    }

    /** A variable name from a prefix and a Denizen name: lowercase, other characters as {@code _}, at most 64 characters. */
    static String variable(String prefix, String name) {
        String text = prefix + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return text.length() > 64 ? text.substring(0, 64) : text;
    }

    /** The execution variable holding a Denizen definition. */
    static String definitionVariable(String name) { return variable("d_", name); }

    /** The definition names a task or procedure declares, in order: {@code definitions: a|b} or {@code a[description]|b}. */
    static List<String> definitionNames(Container container) {
        List<String> names = new ArrayList<>();
        Optional<String> text = container.body().text("definitions");
        if (text.isEmpty() || text.get().isBlank()) return names;
        for (String piece : text.get().split("\\|", -1)) {
            String name = piece.contains("[") ? piece.substring(0, piece.indexOf('[')) : piece;
            if (!name.trim().isEmpty()) names.add(name.trim().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static String stepName(String name) { return name.toLowerCase(Locale.ROOT).replace("*", "").trim(); }

    static String scopeOf(String target) {
        return switch (target) {
            case "npc" -> "npc";
            case "server" -> "global";
            default -> "player";
        };
    }

    // ---- Variables ----

    /** Declares an execution variable of the document once; {@code any} holds every value, {@code integer} loop counters. */
    void declare(Scope scope, String name, String type) { scope.doc.variables.putIfAbsent(name, type); }

    /** A fresh temporary variable of the document. */
    String temporary(Scope scope) {
        String name = "t_" + (++scope.doc.temporaries);
        declare(scope, name, "any");
        return name;
    }

    /** A fresh loop counter of the document; each loop has its own, since every routine of a run shares variables. */
    String loopIndex(Scope scope) {
        String name = "i_" + (++scope.doc.loops);
        declare(scope, name, "integer");
        return name;
    }

    // ---- Outcomes ----

    void issue(String text) { issues.add(text); }

    void outcome(Scope scope, Item item, String kind, String name, boolean converted, String reason) {
        if (!scope.record) return;
        record(scope.container, item.line(), kind, name, item.text(), converted, reason);
    }

    private void record(Container container, int line, String kind, String name, String text, boolean converted, String reason) {
        String key = container.file() + ":" + String.format(Locale.ROOT, "%07d", line);
        if (outcomes.containsKey(key)) return;
        outcomes.put(key, new Outcome(container.file(), line, container.name(), kind, name, text, converted, reason));
        if (!converted) preserve(container.file(), line, container.name(), kind, name, text, reason);
    }

    private void preserve(String file, int line, String container, String kind, String name, String text, String reason) {
        JsonObject entry = new JsonObject();
        entry.addProperty("source", line > 0 ? file + ":" + line : file);
        if (!container.isEmpty()) entry.addProperty("container", container);
        entry.addProperty("kind", kind);
        entry.addProperty("item", name);
        entry.addProperty("reason", reason);
        preserved.add(entry);
    }

    private void preserveContainer(Container container, String reason) {
        record(container, container.line(), "container", container.type(), container.name(), false, reason);
        // Only script-running containers hold command lines; lists in data, book or inventory scripts are values.
        if (!SCRIPT_TYPES.contains(container.type())) return;
        Scope scope = Scope.of(new Doc(false), true, container, Optional.empty(), -1, Map.of());
        preserveNode(scope, container.body(), reason);
    }

    private static final Set<String> SCRIPT_TYPES = Set.of("task", "procedure", "command", "assignment", "interact", "world");

    private void preserveNode(Scope scope, Node node, String reason) {
        if (node instanceof Mapping mapping) for (Entry entry : mapping.entries()) preserveNode(scope, entry.value(), reason);
        else if (node instanceof Sequence sequence) for (Item item : sequence.items()) commands.preserveTree(scope, item, reason);
    }

    private boolean partial(String document) {
        for (Outcome outcome : outcomes.values()) if (!outcome.converted() && documentsOf(outcome).contains(document)) return true;
        return false;
    }

    /** The behaviors an outcome's container makes; a world script makes one for server events and one for NPC events. */
    private List<String> documentsOf(Outcome outcome) {
        Container container = containers.get(outcome.container().toLowerCase(Locale.ROOT));
        if (container == null) return List.of();
        return switch (container.type()) {
            case "world" -> List.of(behaviorId("world", container), behaviorId("world-npc", container));
            case "task", "assignment", "interact", "command", "book" -> List.of(behaviorId(container.type(), container));
            default -> List.of();
        };
    }

    private List<Outcome> outcomesOf(Container container) {
        List<Outcome> found = new ArrayList<>();
        for (Outcome outcome : outcomes.values()) if (outcome.container().equalsIgnoreCase(container.name()) && outcome.file().equals(container.file())) found.add(outcome);
        return found;
    }

    /** Records every key of a container outside {@code allowed} as kept, with a reason naming it. */
    private void otherKeys(Container container, List<String> allowed, String what) {
        for (Entry entry : container.body().entries()) {
            String key = entry.key().toLowerCase(Locale.ROOT);
            if (allowed.contains(key)) continue;
            record(container, entry.line(), "event", key, entry.key(), false, what + " setting " + key + " has no Animus equivalent");
        }
    }

    // ---- Tasks and procedures ----

    private void task(Container container, TreeMap<String, String> documents) {
        Optional<Node> script = container.body().get("script");
        if (script.isEmpty() || !(script.get() instanceof Sequence sequence)) {
            preserveContainer(container, "task script without a script list");
            return;
        }
        Doc doc = new Doc(false);
        Scope scope = Scope.of(doc, true, container, Optional.empty(), -1, Map.of());
        scope.calling.add(routineFor(container, Optional.empty()));
        doc.routines.put("main", new JsonObject());
        // Other lists in a task are paths that run with path:; each becomes a routine of the task's own behavior.
        for (Entry entry : container.body().entries()) {
            String key = entry.key().toLowerCase(Locale.ROOT);
            if (key.equals("script") || !(entry.value() instanceof Sequence path)) continue;
            String routine = routineFor(container, Optional.of(key));
            if (!reserve(doc.routines, routine)) {
                record(container, entry.line(), "event", "task path", entry.key(), false, "more than 32 routines in one behavior");
                commands.preserveItems(scope, path.items(), 0, "inside a task path past the routine limit");
                continue;
            }
            scope.calling.add(routine);
            doc.routines.put(routine, sequence(commands.block(path.items(), 0, scope)));
            scope.calling.remove(routine);
        }
        doc.routines.put("main", sequence(commands.block(sequence.items(), 0, scope)));
        boolean empty = true;
        for (JsonObject routine : doc.routines.values()) if (!routine.equals(sequence(List.of()))) empty = false;
        if (empty) return;
        document(behaviorId("task", container), doc, container, new JsonArray(), documents);
    }

    String routineFor(Container task, Optional<String> path) {
        String routine = "task-" + slug(task.name()) + path.map((String value) -> "-" + value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-")).orElse("");
        return routine.length() > 64 ? routine.substring(0, 64) : routine;
    }

    /** A procedure's own lines are converted once to record what converts; its routine is made in each document that reads it. */
    private void procedureContainer(Container container) {
        Optional<Node> script = container.body().get("script");
        if (script.isEmpty() || !(script.get() instanceof Sequence sequence)) {
            preserveContainer(container, "procedure script without a script list");
            return;
        }
        Doc doc = new Doc(false);
        Scope scope = Scope.of(doc, true, container, Optional.empty(), -1, Map.of());
        scope.calling.add(procedureName(container));
        commands.block(sequence.items(), 0, scope);
        otherKeys(container, List.of("type", "script", "definitions", "debug"), "procedure");
        if (outcomesOf(container).isEmpty()) record(container, container.line(), "container", "procedure", container.name(), true, "");
    }

    private String procedureName(Container procedure) {
        String routine = "proc-" + slug(procedure.name());
        return routine.length() > 64 ? routine.substring(0, 64) : routine;
    }

    /** The routine of a procedure in the scope's document, made on first use. Its value comes back with {@code return}. */
    Result<String> procedureRoutine(Container procedure, Scope scope) {
        String routine = procedureName(procedure);
        if (scope.calling.contains(routine)) return Result.fail("recursive procedure calls have no Animus equivalent");
        if (scope.doc.routines.containsKey(routine)) return Result.ok(routine);
        Optional<Node> script = procedure.body().get("script");
        if (script.isEmpty() || !(script.get() instanceof Sequence sequence)) return Result.fail("procedure " + procedure.name() + " has no script list");
        if (!reserve(scope.doc.routines, routine)) return Result.fail("more than 32 routines in one behavior");
        scope.calling.add(routine);
        List<JsonObject> steps = commands.block(sequence.items(), 0, scope.in(procedure));
        scope.calling.remove(routine);
        scope.doc.routines.put(routine, sequence(steps));
        return Result.ok(routine);
    }

    // ---- Assignments ----

    private void assignment(Container container, TreeMap<String, String> documents) {
        Doc doc = new Doc(false);
        JsonArray triggers = new JsonArray();
        Optional<Node> actions = container.body().get("actions");
        Optional<Sequence> onAssignment = Optional.empty();
        if (actions.isPresent() && actions.get() instanceof Mapping mapping) {
            Optional<Node> assigned = mapping.get("on assignment");
            if (assigned.isPresent() && assigned.get() instanceof Sequence sequence) onAssignment = Optional.of(sequence);
        }
        int radius = radius(container, onAssignment);
        if (actions.isPresent() && actions.get() instanceof Mapping mapping) {
            for (Entry entry : mapping.entries()) {
                String event = entry.key().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
                if (!(entry.value() instanceof Sequence sequence)) {
                    record(container, entry.line(), "event", event, entry.key(), false, "action without a script list");
                    continue;
                }
                Scope scope = Scope.of(doc, true, container, Optional.empty(), -1, Map.of());
                if (event.equals("on assignment")) {
                    onAssignment(container, entry, sequence, scope, triggers);
                    continue;
                }
                String[] mapped = switch (event) {
                    case "on click" -> new String[] {"interaction", "interact", "click"};
                    case "on damage" -> new String[] {"interaction", "attack", "damage"};
                    case "on enter proximity" -> new String[] {"proximity", "enter", "proximity"};
                    case "on exit proximity" -> new String[] {"proximity", "leave", "proximity"};
                    case "on spawn" -> new String[] {"spawn", "", ""};
                    case "on despawn" -> new String[] {"despawn", "", ""};
                    case "on death" -> new String[] {"death", "", ""};
                    default -> null;
                };
                if (mapped == null) {
                    record(container, entry.line(), "event", event, entry.key(), false, "no Animus trigger for this action");
                    commands.preserveItems(scope, sequence.items(), 0, "inside an action that is kept: no Animus trigger for this action");
                    continue;
                }
                if (!mapped[2].isEmpty() && !enabled(container, mapped[2], onAssignment)) {
                    record(container, entry.line(), "event", event, entry.key(), false, "the " + mapped[2] + " trigger is off on every NPC with this assignment");
                    commands.preserveItems(scope, sequence.items(), 0, "inside an action whose trigger is off");
                    continue;
                }
                String routine = event.replace(' ', '-');
                if (doc.routines.size() >= BehaviorLimits.ROUTINES) {
                    record(container, entry.line(), "event", event, entry.key(), false, "more than 32 routines in one behavior");
                    commands.preserveItems(scope, sequence.items(), 0, "inside an action past the routine limit");
                    continue;
                }
                doc.routines.put(routine, new JsonObject());
                doc.routines.put(routine, sequence(commands.block(sequence.items(), 0, scope)));
                record(container, entry.line(), "event", event, entry.key(), true, "");
                JsonObject trigger = new JsonObject();
                trigger.addProperty("id", routine);
                trigger.addProperty("event", mapped[0]);
                if (mapped[0].equals("interaction")) trigger.addProperty("action", mapped[1]);
                if (mapped[0].equals("proximity")) { trigger.addProperty("phase", mapped[1]); trigger.addProperty("radius", radius); }
                trigger.addProperty("routine", routine);
                if (!mapped[2].isEmpty()) cooldown(trigger, container, mapped[2]);
                triggers.add(trigger);
            }
        } else if (actions.isPresent()) record(container, actions.get().line(), "event", "actions", "actions", false, "actions is not a list of actions");
        Optional<Node> list = container.body().get("interact scripts");
        if (list.isPresent() && list.get() instanceof Sequence sequence)
            for (Item item : sequence.items()) {
                boolean found = interactFor(item.text()).isPresent();
                record(container, item.line(), "event", "interact script", item.text(), found, found ? "" : "no interact script with this name");
            }
        otherKeys(container, List.of("type", "actions", "interact scripts", "debug"), "assignment");
        if (doc.routines.isEmpty()) {
            if (outcomesOf(container).isEmpty()) record(container, container.line(), "container", "assignment", container.name(), true, "");
            return;
        }
        if (document(behaviorId("assignment", container), doc, container, triggers, documents) && doc.routines.containsKey("on-assignment"))
            for (Npc npc : npcs) if (npc.assignments().contains(container.name().toLowerCase(Locale.ROOT))) guards.computeIfAbsent(npc.uuid(), (String ignored) -> new ArrayList<>()).add(guardFlag(container));
    }

    private String guardFlag(Container container) {
        String flag = "assigned-" + slug(container.name());
        return flag.length() > 64 ? flag.substring(0, 64) : flag;
    }

    /**
     * {@code on assignment}: trigger lines are read as settings; any other commands become the {@code on-assignment}
     * routine, run by a spawn trigger and guarded by the NPC flag {@code assigned-<slug>}, so it runs once per NPC. The
     * import sets that flag on the NPCs that already had the assignment.
     */
    private void onAssignment(Container container, Entry entry, Sequence sequence, Scope scope, JsonArray triggers) {
        record(container, entry.line(), "event", "on assignment", entry.key(), true, "");
        List<Item> rest = new ArrayList<>();
        for (Item item : sequence.items()) {
            if (DenizenArgs.command(item.text()).name().equals("trigger") && item.children().isEmpty()) outcome(scope, item, "command", "trigger", true, "");
            else rest.add(item);
        }
        if (rest.isEmpty()) return;
        if (!reserve(scope.doc.routines, "on-assignment")) {
            commands.preserveItems(scope, rest, 0, "inside on assignment past the routine limit");
            return;
        }
        String flag = guardFlag(container);
        List<JsonObject> steps = new ArrayList<>();
        JsonObject arguments = new JsonObject();
        arguments.addProperty("name", flag);
        arguments.addProperty("scope", "npc");
        steps.add(DenizenCommands.action("animus:set-flag", arguments));
        steps.addAll(commands.block(rest, 0, scope));
        JsonObject guard = new JsonObject();
        guard.addProperty("type", "condition");
        guard.addProperty("expression", "!has_flag('npc', " + DenizenExpressions.quote(flag) + ")");
        guard.add("then", sequence(steps));
        scope.doc.routines.put("on-assignment", guard);
        JsonObject trigger = new JsonObject();
        trigger.addProperty("id", "on-assignment");
        trigger.addProperty("event", "spawn");
        trigger.addProperty("routine", "on-assignment");
        triggers.add(trigger);
    }

    /** The interact scripts an assignment lists, in order, as {@code - name} or {@code - 10 name}. */
    private List<Container> interactList(Container assignment) {
        List<Container> found = new ArrayList<>();
        Optional<Node> list = assignment.body().get("interact scripts");
        if (list.isEmpty() || !(list.get() instanceof Sequence sequence)) return found;
        for (Item item : sequence.items()) interactFor(item.text()).ifPresent(found::add);
        return found;
    }

    private Optional<Container> interactFor(String text) {
        String name = text.trim();
        if (name.matches("[0-9]+ .+")) name = name.substring(name.indexOf(' ') + 1).trim();
        Container container = containers.get(name.toLowerCase(Locale.ROOT));
        return container != null && container.type().equals("interact") ? Optional.of(container) : Optional.empty();
    }

    // ---- Interact scripts ----

    private Interact interact(Container container) {
        List<String> order = new ArrayList<>();
        Optional<Node> steps = container.body().get("steps");
        if (steps.isPresent() && steps.get() instanceof Mapping mapping) for (Entry entry : mapping.entries()) order.add(entry.key());
        int defaultIndex = 0;
        for (int index = 0; index < order.size(); index++) if (order.get(index).contains("*")) { defaultIndex = index; break; }
        List<String> staged = new ArrayList<>();
        if (!order.isEmpty()) staged.add(order.get(defaultIndex));
        for (int index = 0; index < order.size(); index++) if (index != defaultIndex) staged.add(order.get(index));
        String slug = slug(container.name());
        String quest = ("interact-" + slug).length() > 64 ? ("interact-" + slug).substring(0, 64) : "interact-" + slug;
        return new Interact(container, slug, quest, staged, order, defaultIndex);
    }

    private void interactDocument(Interact interact, TreeMap<String, String> documents) {
        Container container = interact.container();
        Optional<Node> steps = container.body().get("steps");
        if (steps.isEmpty() || !(steps.get() instanceof Mapping mapping) || mapping.entries().isEmpty()) {
            preserveContainer(container, "interact script without steps");
            return;
        }
        Doc doc = new Doc(false);
        LinkedHashMap<String, JsonObject> routines = doc.routines;
        JsonArray triggers = new JsonArray();
        Optional<Sequence> assigned = onAssignmentFor(container);
        int radius = radius(container, assigned);
        TreeMap<String, List<Integer>> dispatch = new TreeMap<>();
        for (String phase : List.of("click", "enter", "exit")) dispatch.put(phase, new ArrayList<>());
        List<JsonObject> choiceTriggers = new ArrayList<>();
        String prefix = interact.slug().length() > 50 ? interact.slug().substring(0, 50) : interact.slug();
        for (Entry step : mapping.entries()) {
            int stage = interact.stage(step.key()).orElseThrow();
            int position = interact.order().indexOf(step.key());
            if (!(step.value() instanceof Mapping body)) {
                record(container, step.line(), "event", "step", step.key(), false, "step is not a list of triggers");
                continue;
            }
            Scope scope = Scope.of(doc, true, container, Optional.of(interact), position, Map.of());
            List<JsonObject> clickSteps = new ArrayList<>();
            boolean click = false;
            JsonArray options = new JsonArray();
            for (Entry trigger : body.entries()) {
                String name = trigger.key().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
                switch (name) {
                    case "click trigger" -> {
                        Optional<Sequence> script = scriptOf(trigger.value());
                        if (script.isEmpty()) { record(container, trigger.line(), "event", name, trigger.key(), false, "trigger without a script list"); continue; }
                        if (!enabled(container, "click", assigned)) {
                            record(container, trigger.line(), "event", name, trigger.key(), false, "the click trigger is off on every NPC with this script");
                            commands.preserveItems(scope, script.get().items(), 0, "inside a trigger that is off");
                            continue;
                        }
                        clickSteps.addAll(commands.block(script.get().items(), 0, scope));
                        click = true;
                        record(container, trigger.line(), "event", name, trigger.key(), true, "");
                    }
                    case "proximity trigger" -> {
                        if (!(trigger.value() instanceof Mapping phases)) { record(container, trigger.line(), "event", name, trigger.key(), false, "trigger without entry or exit"); continue; }
                        boolean on = enabled(container, "proximity", assigned);
                        for (Entry phase : phases.entries()) {
                            String which = phase.key().toLowerCase(Locale.ROOT);
                            Optional<Sequence> script = scriptOf(phase.value());
                            String label = name + " " + which;
                            if (!which.equals("entry") && !which.equals("exit") || script.isEmpty()) {
                                record(container, phase.line(), "event", label, phase.key(), false, which.equals("move") ? "no Animus trigger for movement inside the radius" : "no Animus trigger for this proximity phase");
                                if (script.isPresent()) commands.preserveItems(scope, script.get().items(), 0, "inside a proximity phase that is kept");
                                continue;
                            }
                            if (!on) {
                                record(container, phase.line(), "event", label, phase.key(), false, "the proximity trigger is off on every NPC with this script");
                                commands.preserveItems(scope, script.get().items(), 0, "inside a trigger that is off");
                                continue;
                            }
                            String routine = "s" + stage + "-" + (which.equals("entry") ? "enter" : "exit");
                            if (!reserve(routines, routine)) {
                                record(container, phase.line(), "event", label, phase.key(), false, "more than 32 routines in one behavior");
                                commands.preserveItems(scope, script.get().items(), 0, "inside a trigger past the routine limit");
                                continue;
                            }
                            routines.put(routine, sequence(commands.block(script.get().items(), 0, scope)));
                            dispatch.get(which.equals("entry") ? "enter" : "exit").add(stage);
                            record(container, phase.line(), "event", label, phase.key(), true, "");
                        }
                    }
                    case "chat trigger" -> {
                        if (!(trigger.value() instanceof Mapping chats)) { record(container, trigger.line(), "event", name, trigger.key(), false, "trigger without chat entries"); continue; }
                        boolean on = enabled(container, "chat", assigned);
                        int index = 0;
                        for (Entry chat : chats.entries()) {
                            index++;
                            Optional<Sequence> script = scriptOf(chat.value());
                            Optional<String> phrase = chat.value() instanceof Mapping entryBody ? entryBody.text("trigger") : Optional.empty();
                            String reason = null;
                            if (script.isEmpty() || phrase.isEmpty()) reason = "chat entry without trigger and script";
                            else if (!on) reason = "the chat trigger is off on every NPC with this script";
                            else if (phrase.get().contains("<") || phrase.get().toLowerCase(Locale.ROOT).contains("regex:") || phrase.get().contains("*")) reason = "a chat pattern or tag cannot become an answer button";
                            else if (options.size() >= 8) reason = "more than 8 chat answers in one step";
                            String text = phrase.orElse("").replace("/", "").trim().replaceAll("\\s+", " ");
                            if (reason == null && (text.isEmpty() || text.length() > 128)) reason = "chat answer text is empty or longer than 128 characters";
                            String routine = "s" + stage + "-chat-" + index;
                            if (reason == null && !reserve(routines, routine)) reason = "more than 32 routines in one behavior";
                            if (reason == null && triggers.size() + choiceTriggers.size() + 3 >= BehaviorLimits.TRIGGERS) reason = "more than 32 triggers in one behavior";
                            if (reason != null) {
                                routines.remove(routine);
                                record(container, chat.line(), "event", "chat trigger", chat.key(), false, reason);
                                if (script.isPresent()) commands.preserveItems(scope, script.get().items(), 0, "inside a chat answer that is kept");
                                continue;
                            }
                            routines.put(routine, sequence(commands.block(script.get().items(), 0, scope)));
                            String choice = "c" + index;
                            JsonObject option = new JsonObject();
                            option.addProperty("id", choice);
                            option.addProperty("text", text);
                            options.add(option);
                            JsonObject choiceTrigger = new JsonObject();
                            choiceTrigger.addProperty("id", "s" + stage + "-" + choice);
                            choiceTrigger.addProperty("event", "dialogue_choice");
                            choiceTrigger.addProperty("dialogue", prefix + "-s" + stage);
                            choiceTrigger.addProperty("choice", choice);
                            choiceTrigger.addProperty("routine", routine);
                            choiceTriggers.add(choiceTrigger);
                            record(container, chat.line(), "event", "chat trigger", chat.key(), true, "");
                        }
                    }
                    case "damage trigger" -> {
                        record(container, trigger.line(), "event", name, trigger.key(), false, "Animus damage triggers carry no player, so player steps cannot follow them");
                        scriptOf(trigger.value()).ifPresent((Sequence script) -> commands.preserveItems(scope, script.items(), 0, "inside a damage trigger that is kept"));
                    }
                    default -> {
                        record(container, trigger.line(), "event", name, trigger.key(), false, "no Animus trigger for " + name);
                        scriptOf(trigger.value()).ifPresent((Sequence script) -> commands.preserveItems(scope, script.items(), 0, "inside a trigger that is kept"));
                    }
                }
            }
            if (!options.isEmpty()) {
                JsonObject arguments = new JsonObject();
                arguments.addProperty("id", prefix + "-s" + stage);
                arguments.addProperty("prompt", "Choose a reply");
                arguments.add("options", options);
                clickSteps.add(DenizenCommands.action("animus:choices", arguments));
                click = true;
            }
            if (click) {
                String routine = "s" + stage + "-click";
                if (reserve(routines, routine)) {
                    routines.put(routine, sequence(clickSteps));
                    dispatch.get("click").add(stage);
                } else record(container, step.line(), "event", "click trigger", step.key(), false, "more than 32 routines in one behavior");
            }
        }
        boolean single = interact.steps().size() == 1;
        for (String phase : List.of("click", "enter", "exit")) {
            List<Integer> stages = new ArrayList<>(dispatch.get(phase));
            if (stages.isEmpty()) continue;
            String routine = single ? "s0-" + phase : phase;
            if (!single) {
                if (!reserve(routines, routine)) { issues.add(container.name() + ": no room for the " + routine + " step dispatcher; that trigger is not generated"); continue; }
                java.util.Collections.sort(stages);
                Optional<JsonObject> chain = Optional.empty();
                for (int index = stages.size() - 1; index >= 0; index--) {
                    JsonObject arguments = new JsonObject();
                    arguments.addProperty("name", interact.quest());
                    arguments.addProperty("scope", "relationship");
                    arguments.addProperty("equals", stages.get(index));
                    JsonObject call = new JsonObject();
                    call.addProperty("type", "call");
                    call.addProperty("routine", "s" + stages.get(index) + "-" + phase);
                    chain = Optional.of(DenizenExpressions.build(new DenizenExpressions.Capability("animus:quest-state", arguments), call, chain));
                }
                routines.put(routine, chain.orElseThrow());
            }
            JsonObject trigger = new JsonObject();
            trigger.addProperty("id", phase);
            if (phase.equals("click")) {
                trigger.addProperty("event", "interaction");
                trigger.addProperty("routine", routine);
                cooldown(trigger, container, "click");
                if (doc.globalCooldown >= 0 && (!trigger.has("cooldownTicks") || trigger.get("cooldownTicks").getAsLong() < doc.globalCooldown)) trigger.addProperty("cooldownTicks", doc.globalCooldown);
                if (doc.playerCooldown >= 0) trigger.addProperty("playerCooldownTicks", doc.playerCooldown);
            } else {
                trigger.addProperty("event", "proximity");
                trigger.addProperty("phase", phase.equals("enter") ? "enter" : "leave");
                trigger.addProperty("radius", radius);
                trigger.addProperty("routine", routine);
                cooldown(trigger, container, "proximity");
            }
            triggers.add(trigger);
        }
        for (JsonObject choice : choiceTriggers) triggers.add(choice);
        otherKeys(container, List.of("type", "steps", "debug"), "interact");
        if (routines.isEmpty()) {
            record(container, container.line(), "container", "interact", container.name(), false, "no step trigger converts");
            return;
        }
        // Dispatchers go first so the behavior's entry is the click dispatcher when there is one.
        LinkedHashMap<String, JsonObject> sorted = new LinkedHashMap<>();
        for (String first : List.of("click", "enter", "exit")) if (routines.containsKey(first)) sorted.put(first, routines.get(first));
        for (Map.Entry<String, JsonObject> routine : routines.entrySet()) if (!sorted.containsKey(routine.getKey())) sorted.put(routine.getKey(), routine.getValue());
        routines.clear();
        routines.putAll(sorted);
        document(behaviorId("interact", container), doc, container, triggers, documents);
    }

    /** The on assignment action of the first assignment, by name, that is this script or lists it. */
    private Optional<Sequence> onAssignmentFor(Container container) {
        String name = container.name().toLowerCase(Locale.ROOT);
        for (Container assignment : containers.values()) {
            if (!assignment.type().equals("assignment")) continue;
            boolean reaches = assignment.name().equalsIgnoreCase(name);
            for (Container listed : interactList(assignment)) if (listed.name().equalsIgnoreCase(name)) reaches = true;
            if (!reaches) continue;
            Optional<Node> actions = assignment.body().get("actions");
            if (actions.isEmpty() || !(actions.get() instanceof Mapping mapping)) continue;
            Optional<Node> assigned = mapping.get("on assignment");
            if (assigned.isPresent() && assigned.get() instanceof Sequence sequence) return Optional.of(sequence);
        }
        return Optional.empty();
    }

    private static Optional<Sequence> scriptOf(Node node) {
        if (node instanceof Sequence sequence) return Optional.of(sequence);
        if (node instanceof Mapping mapping) {
            Optional<Node> script = mapping.get("script");
            if (script.isPresent() && script.get() instanceof Sequence sequence) return Optional.of(sequence);
        }
        return Optional.empty();
    }

    private static boolean reserve(LinkedHashMap<String, JsonObject> routines, String routine) {
        if (routines.containsKey(routine)) return true;
        if (routines.size() >= BehaviorLimits.ROUTINES) return false;
        routines.put(routine, new JsonObject());
        return true;
    }

    // ---- Server behaviors ----

    /**
     * A world script: one server behavior whose routines are its events, each run by the matching server trigger, and
     * one NPC behavior, bound to every NPC, for its NPC click events. A cancellable event whose top level cancels it
     * after plain guards and rate limits gets a condition made of those guards: {@code consume} on an NPC click,
     * {@code cancel} on a server event.
     */
    private void world(Container container, TreeMap<String, String> documents) {
        Optional<Node> events = container.body().get("events");
        Doc server = new Doc(true), clicks = new Doc(false);
        JsonArray serverTriggers = new JsonArray(), clickTriggers = new JsonArray();
        if (events.isPresent() && events.get() instanceof Mapping mapping) {
            for (Entry entry : mapping.entries()) {
                String key = entry.key().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
                String label = "world " + key;
                Scope kept = Scope.of(server, true, container, Optional.empty(), -1, Map.of());
                if (!(entry.value() instanceof Sequence sequence)) {
                    record(container, entry.line(), "event", label, entry.key(), false, "event without a script list");
                    preserveNode(kept, entry.value(), "inside a world event that is kept");
                    continue;
                }
                DenizenEvents.Mapped mapped = DenizenEvents.event(key, notes, mentions(sequence, "<context.entity.is_npc"), mentions(sequence, "<player.target.is_npc"));
                String reason = mapped.reason();
                Doc doc = mapped.npc() ? clicks : server;
                JsonArray triggers = mapped.npc() ? clickTriggers : serverTriggers;
                String base = "on-" + DenizenEvents.phrase(key).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
                if (base.length() > 60) base = base.substring(0, 60);
                String routine = base;
                for (int suffix = 2; doc.routines.containsKey(routine); suffix++) routine = base + "-" + suffix;
                if (reason == null && triggers.size() >= BehaviorLimits.TRIGGERS) reason = "more than 32 triggers in one behavior";
                if (reason == null && !reserve(doc.routines, routine)) reason = "more than 32 routines in one behavior";
                if (reason != null) {
                    record(container, entry.line(), "event", label, entry.key(), false, reason);
                    commands.preserveItems(kept, sequence.items(), 0, "inside a world event that is kept");
                    continue;
                }
                record(container, entry.line(), "event", label, entry.key(), true, "");
                Scope scope = Scope.of(doc, true, container, Optional.empty(), -1, mapped.context());
                scope.cancellable = mapped.cancellable();
                Optional<DenizenCommands.Consume> consume = mapped.cancellable() ? commands.consume(sequence.items(), mapped.guards(), scope) : Optional.empty();
                consume.ifPresent((DenizenCommands.Consume found) -> scope.cancel = found.item());
                JsonObject body = sequence(commands.block(sequence.items(), 0, scope));
                for (int index = mapped.guards().size() - 1; index >= 0; index--) body = DenizenExpressions.build(mapped.guards().get(index), body, Optional.empty());
                doc.routines.put(routine, body);
                JsonObject trigger = new JsonObject();
                trigger.addProperty("id", routine);
                for (Map.Entry<String, JsonElement> field : mapped.trigger().entrySet()) trigger.add(field.getKey(), field.getValue());
                // A click event that leaves non-sneaking clicks at a plain guard takes sneaking clicks only, so it needs the sneak action.
                if (mapped.npc() && trigger.has("action") && List.of("interact", "attack").contains(trigger.get("action").getAsString()) && sneakingOnly(sequence))
                    trigger.addProperty("action", "sneak_" + trigger.get("action").getAsString());
                consume.ifPresent((DenizenCommands.Consume found) -> trigger.addProperty(mapped.npc() ? "consume" : "cancel", found.condition()));
                // A drop or swap an NPC trigger matches is consumed unless the trigger says otherwise; Denizen let it happen.
                if (consume.isEmpty() && mapped.context().containsKey(DenizenEvents.TARGET)) trigger.addProperty("consume", "false");
                trigger.addProperty("routine", routine);
                triggers.add(trigger);
            }
        } else record(container, container.line(), "container", "world", container.name(), false, "world script without events");
        otherKeys(container, List.of("type", "events", "debug", "enabled"), "world");
        if (!server.routines.isEmpty()) document(behaviorId("world", container), server, container, serverTriggers, documents);
        if (!clicks.routines.isEmpty() && document(behaviorId("world-npc", container), clicks, container, clickTriggers, documents)) clickBehaviors.add(behaviorId("world-npc", container));
    }

    /** The event's leading plain guards ({@code if ...: - stop}) include one that stops when the player is not sneaking. */
    private static boolean sneakingOnly(Sequence sequence) {
        for (Item item : sequence.items()) {
            Command command = DenizenArgs.command(item.text());
            if (!command.name().equals("if") || item.children().isEmpty() || item.children().get().items().size() != 1
                    || !DenizenArgs.command(item.children().get().items().getFirst().text()).name().equals("stop")) return false;
            String condition = item.text().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            if (condition.equals("if!<player.is_sneaking>:") || condition.equals("if!<player.is_sneaking>")) return true;
        }
        return false;
    }

    /** Whether a command line, or one inside it, contains {@code text}, ignoring case. */
    private static boolean mentions(Sequence sequence, String text) {
        for (Item item : sequence.items()) {
            if (item.text().toLowerCase(Locale.ROOT).contains(text)) return true;
            if (item.children().isPresent() && mentions(item.children().get(), text)) return true;
        }
        return false;
    }

    /** Literal {@code note <cuboid> as:name} lines name areas as notables.yml does; notables.yml wins on a clash. */
    private void collectNotes(Node node) {
        if (node instanceof Mapping mapping) for (Entry entry : mapping.entries()) collectNotes(entry.value());
        else if (node instanceof Sequence sequence) for (Item item : sequence.items()) {
            item.children().ifPresent(this::collectNotes);
            noteOf(DenizenArgs.command(item.text())).ifPresent((DenizenEvents.Note note) -> notes.putIfAbsent(note.name(), note));
        }
    }

    /** The area a literal {@code note} command names, if it is one. */
    static Optional<DenizenEvents.Note> noteOf(DenizenArgs.Command command) {
        if (!command.name().equals("note") || command.args().size() != 2) return Optional.empty();
        Arg area = command.args().get(0), name = command.args().get(1);
        if (!area.prefix().isEmpty() || !name.prefix().equals("as")) return Optional.empty();
        String lowered = name.value().toLowerCase(Locale.ROOT);
        if (!lowered.matches("[a-z0-9][a-z0-9_-]{0,63}")) return Optional.empty();
        return DenizenEvents.note(lowered, area.value());
    }

    /** The flag name a {@code ratelimit} line keeps its per-player window in. */
    String ratelimitFlag(Container container, int line) {
        String suffix = "-" + line, base = "ratelimit-" + slug(container.name());
        return (base.length() + suffix.length() > 64 ? base.substring(0, 64 - suffix.length()) : base) + suffix;
    }

    /**
     * The literal values scripts give the flag {@code name} of {@code scope} ({@code npc}, {@code player} or
     * {@code global}) with {@code flag <target> name:value}, and the values Citizens NPCs already hold, lowercased.
     */
    TreeSet<String> flagValues(String scope, String name) {
        if (flagValues == null) {
            flagValues = new TreeMap<>();
            for (Container container : containers.values()) collectFlags(container.body());
            for (Npc npc : npcs) for (Map.Entry<String, String> flag : npc.flags().entrySet()) {
                Optional<String> flagged = flagName(flag.getKey());
                if (flagged.isPresent() && !flag.getValue().contains("<")) flagValues.computeIfAbsent("npc:" + flagged.get(), (String ignored) -> new TreeSet<>()).add(flag.getValue().trim().toLowerCase(Locale.ROOT));
            }
        }
        return flagValues.getOrDefault(scope + ":" + name, new TreeSet<>());
    }

    private void collectFlags(Node node) {
        if (node instanceof Mapping mapping) for (Entry entry : mapping.entries()) collectFlags(entry.value());
        else if (node instanceof Sequence sequence) for (Item item : sequence.items()) {
            item.children().ifPresent(this::collectFlags);
            DenizenArgs.Command command = DenizenArgs.command(item.text());
            if (!command.name().equals("flag") || command.args().size() < 2 || !command.args().getFirst().prefix().isEmpty()) continue;
            String target = command.args().getFirst().value().toLowerCase(Locale.ROOT).replace("<", "").replace(">", "");
            if (!List.of("player", "npc", "server").contains(target)) continue;
            String raw = DenizenCommands.raw(command.args().get(1));
            int colon = raw.indexOf(':');
            if (colon < 0) continue;
            Optional<String> flagged = flagName(raw.substring(0, colon));
            String value = raw.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
            if (flagged.isEmpty() || !value.matches("[a-z0-9_]{1,64}")) continue;
            flagValues.computeIfAbsent(scopeOf(target) + ":" + flagged.get(), (String ignored) -> new TreeSet<>()).add(value);
        }
    }

    /** A command script: a server behavior with a {@code command} trigger whose run reads {@code context.args}. */
    private void commandContainer(Container container, TreeMap<String, String> documents) {
        Optional<Node> script = container.body().get("script");
        Optional<String> name = container.body().text("name").map((String text) -> text.trim().toLowerCase(Locale.ROOT));
        if (script.isEmpty() || !(script.get() instanceof Sequence sequence)) { preserveContainer(container, "command script without a script list"); return; }
        if (name.isEmpty() || !name.get().matches("[a-z0-9_-]{1,32}")) {
            preserveContainer(container, "command name " + name.orElse("(none)") + " is not 1-32 lowercase letters, digits, _ or -");
            return;
        }
        JsonObject trigger = new JsonObject();
        trigger.addProperty("id", "command");
        trigger.addProperty("event", "command");
        trigger.addProperty("name", name.get());
        Optional<Node> aliases = container.body().get("aliases");
        if (aliases.isPresent()) {
            JsonArray list = new JsonArray();
            List<String> written = new ArrayList<>();
            if (aliases.get() instanceof Sequence items) for (Item item : items.items()) written.add(item.text());
            else if (aliases.get() instanceof Scalar scalar) for (String alias : scalar.text().split("\\|", -1)) written.add(alias);
            for (String alias : written) {
                String lowered = alias.trim().toLowerCase(Locale.ROOT);
                if (!lowered.matches("[a-z0-9_-]{1,32}") || list.size() >= 8) issues.add(container.file() + ":" + aliases.get().line() + ": command alias " + alias + " is not used; Animus takes up to 8 lowercase aliases");
                else list.add(lowered);
            }
            if (!list.isEmpty()) trigger.add("aliases", list);
        }
        Doc doc = new Doc(true);
        for (String field : List.of("permission", "description", "usage")) {
            Optional<String> value = container.body().text(field);
            Optional<Node> node = container.body().get(field);
            // A permission the trigger cannot carry would open the command to everyone, so the command is held instead.
            if (field.equals("permission") && node.isPresent() && (value.isEmpty() || value.get().isBlank() || value.get().length() > 256)) {
                issues.add(container.file() + ":" + node.get().line() + ": command permission is not 1-256 characters of text; the command is held until it is fixed");
                record(container, node.get().line(), "event", field, field, false, "command permission is not 1-256 characters of text");
                doc.held.add(container.file() + ":" + node.get().line());
                continue;
            }
            if (value.isEmpty() || value.get().isBlank()) continue;
            if (value.get().length() > 256) { issues.add(container.file() + ":" + container.line() + ": command " + field + " is longer than 256 characters and is not used"); continue; }
            trigger.addProperty(field, value.get().trim());
        }
        trigger.addProperty("routine", "main");
        doc.routines.put("main", new JsonObject());
        Scope scope = Scope.of(doc, true, container, Optional.empty(), -1, DenizenEvents.commandContext());
        doc.routines.put("main", sequence(commands.block(sequence.items(), 0, scope)));
        otherKeys(container, List.of("type", "name", "script", "aliases", "permission", "description", "usage", "debug"), "command");
        JsonArray triggers = new JsonArray();
        triggers.add(trigger);
        document(behaviorId("command", container), doc, container, triggers, documents);
    }

    // ---- Books, items and inventories ----

    /** A book script as an {@code animus:give-book} action, or the reason it has none. */
    Result<JsonObject> bookAction(Container book, Scope scope, Pending pending) {
        DenizenCommands.Action action = new DenizenCommands.Action();
        for (String field : List.of("title", "author")) {
            Optional<String> value = book.body().text(field);
            if (value.isEmpty()) return Result.fail("book script without a " + field);
            Result<Text> text = expressions.text(value.get(), scope, pending, false, 0);
            if (text.failed()) return Result.fail(text.reason());
            if (!text.value().dynamic() && text.value().literal().length() > 32) return Result.fail("book " + field + " longer than 32 characters");
            // A signed book needs a visible title and author, so a blank one (often <&sp>) falls back to the script name or Server.
            if (!text.value().dynamic() && text.value().literal().isBlank()) action.put(field, field.equals("title") ? book.name().substring(0, Math.min(32, book.name().length())) : BLANK_BOOK_AUTHOR);
            else action.text(field, text.value());
        }
        Optional<Node> pages = book.body().get("text");
        if (pages.isEmpty() || !(pages.get() instanceof Sequence sequence) || sequence.items().isEmpty()) return Result.fail("book script without text pages");
        if (sequence.items().size() > 50) return Result.fail("book with more than 50 pages");
        JsonArray list = new JsonArray();
        for (int index = 0; index < sequence.items().size(); index++) {
            Result<Text> text = expressions.text(sequence.items().get(index).text(), scope, pending, false, 0);
            if (text.failed()) return Result.fail(text.reason());
            if (!text.value().dynamic()) {
                if (text.value().literal().length() > 1024) return Result.fail("book page longer than 1024 characters");
                list.add(text.value().literal());
            } else {
                list.add(JsonNull.INSTANCE);
                action.templates.addProperty("/pages/" + index, text.value().template());
            }
        }
        action.put("pages", list);
        return Result.ok(action.node("animus:give-book"));
    }

    Optional<JsonObject> book(Container book, Scope scope, Pending pending) {
        Result<JsonObject> action = bookAction(book, scope, pending);
        return action.failed() ? Optional.empty() : Optional.of(action.value());
    }

    private void bookContainer(Container container, TreeMap<String, String> documents) {
        Doc doc = new Doc(true);
        Scope scope = Scope.of(doc, true, container, Optional.empty(), -1, Map.of());
        Pending pending = new Pending();
        Result<JsonObject> action = bookAction(container, scope, pending);
        if (action.failed()) { preserveContainer(container, action.reason()); return; }
        List<JsonObject> steps = new ArrayList<>(pending.steps);
        steps.add(action.value());
        doc.routines.put("main", sequence(steps));
        record(container, container.line(), "container", "book", container.name(), true, "");
        otherKeys(container, List.of("type", "title", "author", "signed", "text", "debug"), "book");
        document(behaviorId("book", container), doc, container, new JsonArray(), documents);
    }

    private static final List<String> ITEM_KEYS = List.of("type", "material", "display name", "lore", "debug");

    /** Fills a give-item action from an item script with only a material, a display name and lore; otherwise the reason. */
    Optional<String> itemScript(Container item, DenizenCommands.Action action, Scope scope, Pending pending) {
        for (Entry entry : item.body().entries()) if (!ITEM_KEYS.contains(entry.key().toLowerCase(Locale.ROOT))) return Optional.of("item script " + item.name() + " sets " + entry.key() + ", which Animus items lack");
        Optional<String> material = item.body().text("material").flatMap(commands::material);
        if (material.isEmpty()) return Optional.of("item script " + item.name() + " has no plain material");
        action.put("item", material.get());
        Optional<String> name = item.body().text("display name");
        if (name.isPresent()) {
            Result<Text> text = expressions.text(name.get(), scope, pending, false, 0);
            if (text.failed()) return Optional.of(text.reason());
            action.text("name", text.value());
        }
        Optional<Node> lore = item.body().get("lore");
        if (lore.isPresent()) {
            if (!(lore.get() instanceof Sequence lines) || lines.items().size() > 16) return Optional.of("item lore must be a list of up to 16 lines");
            JsonArray list = new JsonArray();
            for (Item line : lines.items()) {
                Result<Text> text = expressions.text(line.text(), scope, pending, false, 0);
                if (text.failed()) return Optional.of(text.reason());
                if (text.value().dynamic()) return Optional.of("item lore with tags has no Animus equivalent");
                list.add(text.value().literal());
            }
            action.put("lore", list);
        }
        return Optional.empty();
    }

    private void itemContainer(Container container) {
        Optional<String> problem = itemScript(container, new DenizenCommands.Action(), Scope.of(new Doc(false), false, container, Optional.empty(), -1, Map.of()), new Pending());
        if (problem.isPresent()) record(container, container.line(), "container", "item", container.name(), false, problem.get() + "; only give converts item scripts");
        else record(container, container.line(), "container", "item", container.name(), true, "");
    }

    Optional<String> guiId(Container inventory) {
        String slug = slug(inventory.name());
        return guis.containsKey(slug) ? Optional.of(NAMESPACE + ":" + slug) : Optional.empty();
    }

    private static final String KEY_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /** An inventory script as a chest GUI: its title, size and fixed items as a mask of decoration keys. */
    private void inventoryContainer(Container container) {
        Scope scope = Scope.of(new Doc(false), true, container, Optional.empty(), -1, Map.of());
        Pending pending = new Pending();
        String type = container.body().text("inventory").orElse("chest").trim().toLowerCase(Locale.ROOT);
        int width = 9, rows;
        String resolution;
        switch (type) {
            case "chest" -> { resolution = null; }
            case "hopper" -> { width = 5; resolution = "5x1"; }
            case "dropper", "dispenser" -> { width = 3; resolution = "3x3"; }
            default -> { preserveContainer(container, "inventory type " + type + " has no Animus chest GUI"); return; }
        }
        Optional<Node> slots = container.body().get("slots");
        List<List<String>> lines = new ArrayList<>();
        if (slots.isPresent()) {
            if (!(slots.get() instanceof Sequence sequence)) { preserveContainer(container, "slots is not a list of rows"); return; }
            for (Item line : sequence.items()) {
                Optional<List<String>> cells = cells(line.text());
                if (cells.isEmpty() || cells.get().size() > width) { preserveContainer(container, "slot row " + line.text() + " is not up to " + width + " [item] cells"); return; }
                lines.add(cells.get());
            }
        }
        if (resolution == null) {
            Optional<String> size = container.body().text("size");
            rows = Math.max(1, lines.size());
            if (size.isPresent()) {
                if (!size.get().trim().matches("[0-9]{1,2}") || Integer.parseInt(size.get().trim()) % 9 != 0 || Integer.parseInt(size.get().trim()) < 9 || Integer.parseInt(size.get().trim()) > 54) {
                    preserveContainer(container, "size " + size.get() + " is not 9 to 54 in rows of 9"); return;
                }
                rows = Integer.parseInt(size.get().trim()) / 9;
            } else if (lines.isEmpty()) rows = 3;
            if (lines.size() > rows) { preserveContainer(container, "more slot rows than the size holds"); return; }
            if (rows > 6) { preserveContainer(container, "more than 6 rows"); return; }
            resolution = "9x" + rows;
        } else if (lines.size() > (width == 5 ? 1 : 3)) { preserveContainer(container, "more slot rows than a " + type + " holds"); return; }
        TreeMap<String, String> definitions = new TreeMap<>();
        Optional<Node> defined = container.body().get("definitions");
        if (defined.isPresent() && defined.get() instanceof Mapping mapping)
            for (Entry entry : mapping.entries()) if (entry.value() instanceof Scalar scalar) definitions.put(entry.key().toLowerCase(Locale.ROOT), scalar.text());
        LinkedHashMap<String, String> keys = new LinkedHashMap<>();
        JsonObject keyObjects = new JsonObject();
        JsonArray mask = new JsonArray();
        for (List<String> line : lines) {
            StringBuilder row = new StringBuilder();
            for (String cell : line) {
                String value = definitions.getOrDefault(cell.toLowerCase(Locale.ROOT), cell).trim();
                if (value.isEmpty() || value.equalsIgnoreCase("air")) { row.append(' '); continue; }
                Result<JsonObject> icon = icon(value, scope, pending);
                if (icon.failed()) { preserveContainer(container, icon.reason()); return; }
                String text = icon.value().toString();
                if (!keys.containsKey(text)) {
                    if (keys.size() >= KEY_CHARACTERS.length()) { preserveContainer(container, "more distinct items than mask keys"); return; }
                    String key = String.valueOf(KEY_CHARACTERS.charAt(keys.size()));
                    keys.put(text, key);
                    JsonObject component = new JsonObject();
                    component.addProperty("type", "decoration");
                    component.add("icon", icon.value());
                    keyObjects.add(key, component);
                }
                row.append(keys.get(text));
            }
            while (row.length() < width) row.append(' ');
            mask.add(row.toString());
        }
        int height = Integer.parseInt(resolution.substring(resolution.indexOf('x') + 1));
        while (!mask.isEmpty() && mask.size() < height) mask.add(" ".repeat(width));
        JsonObject gui = new JsonObject();
        gui.addProperty("schemaVersion", 1);
        gui.addProperty("revision", 1);
        Optional<String> title = container.body().text("title");
        if (title.isPresent()) {
            Result<Text> text = expressions.text(title.get(), scope, pending, false, 0);
            if (text.failed()) { preserveContainer(container, "title: " + text.reason()); return; }
            if (text.value().dynamic()) { preserveContainer(container, "a title with tags has no chest GUI equivalent"); return; }
            gui.addProperty("title", text.value().literal());
        }
        gui.addProperty("resolution", resolution);
        if (!mask.isEmpty()) {
            gui.add("mask", mask);
            gui.add("keys", keyObjects);
        }
        if (!pending.steps.isEmpty()) { preserveContainer(container, "inventory items read map constants or procedures"); return; }
        record(container, container.line(), "container", "inventory", container.name(), true, "");
        for (Entry entry : container.body().entries()) {
            String key = entry.key().toLowerCase(Locale.ROOT);
            if (List.of("type", "inventory", "title", "size", "slots", "definitions", "debug").contains(key)) continue;
            if (key.equals("gui") && entry.value() instanceof Scalar scalar && scalar.text().trim().equalsIgnoreCase("true")) continue;
            String reason = switch (key) {
                case "gui" -> "gui: false lets players move items; Animus windows never let items move";
                case "procedural items" -> "procedural items run a script to fill slots; Animus chest GUIs draw fixed items";
                default -> "inventory setting " + key + " has no Animus equivalent";
            };
            record(container, entry.line(), "event", key, entry.key(), false, reason);
        }
        guis.put(slug(container.name()), JsonDocuments.write(gui));
    }

    /** The {@code [item]} cells of a slot row; {@code []} is an empty cell. */
    private static Optional<List<String>> cells(String line) {
        List<String> cells = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char at : line.trim().toCharArray()) {
            if (at == '[') { if (depth++ > 0) current.append(at); continue; }
            if (at == ']') {
                if (depth == 0) return Optional.empty();
                if (--depth == 0) { cells.add(current.toString()); current.setLength(0); }
                else current.append(at);
                continue;
            }
            if (depth > 0) current.append(at);
            else if (at != ' ') return Optional.empty();
        }
        return depth == 0 ? Optional.of(cells) : Optional.empty();
    }

    /** A GUI icon: a material, a material with {@code [quantity=n]}, or a simple item script. */
    private Result<JsonObject> icon(String value, Scope scope, Pending pending) {
        String name = value;
        int count = 1;
        if (name.endsWith("]") && name.contains("[")) {
            String properties = name.substring(name.indexOf('[') + 1, name.length() - 1);
            name = name.substring(0, name.indexOf('['));
            if (!properties.toLowerCase(Locale.ROOT).matches("quantity=[0-9]{1,2}")) return Result.fail("item property " + properties + " has no chest GUI equivalent");
            count = Integer.parseInt(properties.substring(9));
            if (count < 1 || count > 64) return Result.fail("item quantity must be from 1 to 64");
        }
        JsonObject icon = new JsonObject();
        icon.addProperty("type", "item");
        Container script = containers.get(name.toLowerCase(Locale.ROOT));
        if (script != null && script.type().equals("item")) {
            DenizenCommands.Action action = new DenizenCommands.Action();
            Optional<String> problem = itemScript(script, action, scope, pending);
            if (problem.isPresent()) return Result.fail(problem.get());
            if (!action.templates.isEmpty()) return Result.fail("item script " + script.name() + " has a name with tags");
            icon.add("item", action.arguments.get("item"));
            if (count > 1) icon.addProperty("count", count);
            if (action.arguments.has("name")) icon.add("name", action.arguments.get("name"));
            if (action.arguments.has("lore")) icon.add("lore", action.arguments.get("lore"));
            return Result.ok(icon);
        }
        Optional<String> material = commands.material(name);
        if (material.isEmpty()) return Result.fail("slot item " + value + " is not a material or a simple item script");
        icon.addProperty("item", material.get());
        if (count > 1) icon.addProperty("count", count);
        return Result.ok(icon);
    }

    // ---- Documents ----

    /** Writes the document when it fits the compiler's limits; otherwise records why the container is kept. */
    private boolean document(String id, Doc doc, Container container, JsonArray triggers, TreeMap<String, String> documents) {
        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", 1);
        document.addProperty("revision", 1);
        document.addProperty("id", id);
        if (doc.server) document.addProperty("server", true);
        document.addProperty("entry", doc.routines.keySet().iterator().next());
        if (!doc.variables.isEmpty()) {
            JsonObject variables = new JsonObject();
            for (Map.Entry<String, String> variable : doc.variables.entrySet()) {
                JsonObject declaration = new JsonObject();
                declaration.addProperty("type", variable.getValue());
                if (variable.getValue().equals("integer")) declaration.addProperty("initial", 0);
                else declaration.addProperty("initial", "");
                variables.add(variable.getKey(), declaration);
            }
            document.add("variables", variables);
        }
        JsonObject body = new JsonObject();
        for (Map.Entry<String, JsonObject> routine : doc.routines.entrySet()) body.add(routine.getKey(), routine.getValue());
        document.add("routines", body);
        if (!triggers.isEmpty()) document.add("triggers", triggers);
        String text = JsonDocuments.write(document);
        Optional<String> problem = BehaviorLimits.check(document, text);
        if (problem.isPresent()) {
            issues.add(container.name() + ": the converted behavior " + problem.get() + "; it is not generated");
            record(container, container.line(), "container", container.type(), container.name(), false, "the converted behavior " + problem.get());
            return false;
        }
        documents.put(id, text);
        if (!doc.held.isEmpty()) heldBehaviors.put(id, doc);
        else if (doc.server) serverBehaviors.add(id);
        return true;
    }

    /** One step, or a sequence; more than 128 steps nest in sequences of at most 128. */
    static JsonObject sequence(List<JsonObject> steps) {
        if (steps.size() == 1) return steps.getFirst();
        List<JsonObject> current = steps;
        while (current.size() > 128) {
            List<JsonObject> grouped = new ArrayList<>();
            for (int index = 0; index < current.size(); index += 128) grouped.add(plain(current.subList(index, Math.min(current.size(), index + 128))));
            current = grouped;
        }
        return plain(current);
    }

    private static JsonObject plain(List<JsonObject> steps) {
        if (steps.size() == 1) return steps.getFirst();
        JsonObject node = new JsonObject();
        node.addProperty("type", "sequence");
        JsonArray array = new JsonArray();
        for (JsonObject step : steps) array.add(step);
        node.add("steps", array);
        return node;
    }

    /**
     * A Bukkit sound name such as {@code ENTITY_VILLAGER_YES} becomes {@code minecraft:entity.villager.yes}: the first
     * and last underscores become dots and the middle words keep theirs, which is how most sound names are built.
     * Names written as keys keep their key. Every name read is listed in the report's {@code sounds} for checking.
     */
    static Optional<String> soundKey(String name, boolean custom) {
        String lowered = name.toLowerCase(Locale.ROOT);
        String key;
        if (lowered.contains(":")) key = lowered;
        else if (lowered.contains(".") || custom) key = "minecraft:" + lowered;
        else {
            int first = lowered.indexOf('_'), last = lowered.lastIndexOf('_');
            if (first < 0 || first == last) return Optional.empty();
            key = "minecraft:" + lowered.substring(0, first) + "." + lowered.substring(first + 1, last) + "." + lowered.substring(last + 1);
        }
        return key.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? Optional.of(key) : Optional.empty();
    }

    // ---- Flag data ----

    /** Writes one subject's flags into staged state documents with their typed values and expiry, and returns how many were kept instead. */
    private int importFlags(String source, DenizenFlags.Values values, String scope, Optional<String> npc, Optional<String> player,
                            TreeMap<String, TreeMap<String, JsonObject>> state, TreeMap<String, Integer> imported) {
        int kept = 0;
        for (String problem : values.problems()) issues.add(source + ": " + problem);
        for (DenizenFlags.Flag flag : values.flags().values()) {
            if (flag.name().startsWith("__interact_step.") && player.isPresent()) { kept += interactStep(source, flag, player.get(), state, imported); continue; }
            String reason = null;
            Optional<String> name = flagName(flag.name());
            DenizenFlags.Typed typed = null;
            if (flag.name().startsWith("__")) reason = "Denizen's own bookkeeping flag";
            else if (name.isEmpty()) reason = "the name cannot be an Animus flag name";
            else if (flag.expires() && flag.expiresAt() == 0) reason = "the expiration time cannot be read";
            else {
                typed = DenizenFlags.typed(flag.value());
                if (!typed.reason().isEmpty()) reason = typed.reason();
            }
            if (reason == null) {
                TreeMap<String, JsonObject> document = state.computeIfAbsent(statePath(scope, npc, player), (String ignored) -> new TreeMap<>());
                if (document.size() >= MAXIMUM_STATE_KEYS && !document.containsKey(name.get())) reason = "more than 128 flags for one subject";
                else {
                    JsonObject value = new JsonObject();
                    value.addProperty("type", typed.type());
                    value.add("value", typed.value());
                    if (flag.expires()) value.addProperty("expires", flag.expiresAt());
                    document.put(name.get(), value);
                }
            }
            if (reason != null) { kept++; preserve(source, 0, "", "flag", flag.name(), "", reason); }
            else imported.merge(scope, 1, Integer::sum);
        }
        return kept;
    }

    private int interactStep(String source, DenizenFlags.Flag flag, String player, TreeMap<String, TreeMap<String, JsonObject>> state, TreeMap<String, Integer> imported) {
        String script = flag.name().substring("__interact_step.".length());
        Interact interact = interacts.get(script);
        String reason = null;
        Optional<Integer> stage = Optional.empty();
        if (interact == null) reason = "no interact script " + script;
        else if (flag.expires()) reason = "a step with a duration has no Animus equivalent";
        else {
            stage = interact.stage(flag.value());
            if (stage.isEmpty()) reason = "no step " + flag.value() + " in " + script;
        }
        List<Npc> bound = interact == null ? List.of() : boundNpcs(interact.container());
        if (reason == null && bound.isEmpty()) reason = "no NPC is assigned " + script;
        if (reason != null) { preserve(source, 0, "", "flag", flag.name(), "", reason); return 1; }
        for (Npc npc : bound) {
            String path = STATE_ROOT + "relationship/" + npc.uuid() + "/" + player + "/animus/quests/" + NAMESPACE + ".json";
            TreeMap<String, JsonObject> document = state.computeIfAbsent(path, (String ignored) -> new TreeMap<>());
            JsonObject value = new JsonObject();
            value.addProperty("type", "integer");
            value.addProperty("value", (long) stage.get());
            document.put(interact.quest(), value);
            imported.merge("relationship", 1, Integer::sum);
        }
        return 0;
    }

    /** The staged flag document of one subject. */
    private static String statePath(String scope, Optional<String> npc, Optional<String> player) {
        StringBuilder path = new StringBuilder(STATE_ROOT).append(scope).append('/');
        npc.ifPresent((String uuid) -> path.append(uuid).append('/'));
        player.ifPresent((String uuid) -> path.append(uuid).append('/'));
        return path.append("animus/flags/").append(NAMESPACE).append(".json").toString();
    }

    /** The same document {@code BehaviorStateStore} writes for these values. */
    private static String stateDocument(String path, TreeMap<String, JsonObject> values) {
        String[] parts = path.substring(STATE_ROOT.length()).split("/");
        String scope = parts[0];
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("behavior", "animus:" + parts[parts.length - 2] + "/" + NAMESPACE);
        root.addProperty("scope", scope);
        if (scope.equals("npc") || scope.equals("relationship")) root.addProperty("npc", parts[1]);
        if (scope.equals("player")) root.addProperty("player", parts[1]);
        if (scope.equals("relationship")) root.addProperty("player", parts[2]);
        JsonObject stored = new JsonObject();
        for (Map.Entry<String, JsonObject> value : values.entrySet()) stored.add(value.getKey(), value.getValue());
        root.add("values", stored);
        return JsonDocuments.write(root);
    }

    // ---- Report counts ----

    private JsonObject containerCounts() {
        TreeMap<String, int[]> counts = new TreeMap<>();
        for (Container container : containers.values()) {
            int[] count = counts.computeIfAbsent(container.type(), (String ignored) -> new int[4]);
            count[0]++;
            boolean any = false, all = true;
            for (Outcome outcome : outcomesOf(container)) {
                if (outcome.converted()) any = true;
                else all = false;
            }
            if (any && all) count[1]++;
            else if (any) count[2]++;
            else count[3]++;
        }
        JsonObject result = new JsonObject();
        for (Map.Entry<String, int[]> count : counts.entrySet()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("total", count.getValue()[0]);
            entry.addProperty("converted", count.getValue()[1]);
            entry.addProperty("partial", count.getValue()[2]);
            entry.addProperty("preserved", count.getValue()[3]);
            result.add(count.getKey(), entry);
        }
        return result;
    }

    private JsonObject counts(String kind, String yes, String no) {
        TreeMap<String, int[]> counts = new TreeMap<>();
        for (Outcome outcome : outcomes.values()) {
            if (!outcome.kind().equals(kind)) continue;
            counts.computeIfAbsent(outcome.name().isEmpty() ? "(empty)" : outcome.name(), (String ignored) -> new int[2])[outcome.converted() ? 0 : 1]++;
        }
        JsonObject result = new JsonObject();
        for (Map.Entry<String, int[]> count : counts.entrySet()) {
            JsonObject entry = new JsonObject();
            entry.addProperty(yes, count.getValue()[0]);
            entry.addProperty(no, count.getValue()[1]);
            result.add(count.getKey(), entry);
        }
        return result;
    }

    private JsonObject tagCounts() {
        TreeMap<String, int[]> counts = new TreeMap<>();
        for (Outcome outcome : outcomes.values()) {
            if (!outcome.kind().equals("command")) continue;
            for (DenizenArgs.Tag tag : DenizenArgs.tags(outcome.text())) counts.computeIfAbsent(tag.label(), (String ignored) -> new int[2])[outcome.converted() ? 0 : 1]++;
        }
        JsonObject result = new JsonObject();
        for (Map.Entry<String, int[]> count : counts.entrySet()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("mapped", count.getValue()[0]);
            entry.addProperty("unmapped", count.getValue()[1]);
            result.add(count.getKey(), entry);
        }
        return result;
    }
}
