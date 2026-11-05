package art.arcane.animus.core.migration;

import art.arcane.animus.core.migration.DenizenArgs.Arg;
import art.arcane.animus.core.migration.DenizenArgs.Command;
import art.arcane.animus.core.migration.DenizenArgs.Tag;
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
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Converts Denizen scripts and flags into Animus behavior documents and behavior state. It is a pure function of its
 * input, so the server and the browser converter (a line-for-line copy) stage the same bytes. Assignment and interact
 * scripts become behaviors in the {@code denizen} namespace, bound to the Citizens NPCs that had the assignment; task
 * scripts become routines and manual-run behaviors; flags become {@code denizen} flags (true or false) and quest stages
 * (whole numbers), and interact steps become relationship quest stages. Everything else is kept and listed in the
 * report with its reason. Conversion describes data; generated behaviors stay off until the review accepts them.
 */
final class DenizenConverter {
    static final int CONVERTER_VERSION = 1;
    static final String NAMESPACE = "denizen";
    static final int MAXIMUM_FILES = 512, MAXIMUM_PLAYERS = 10_000, MAXIMUM_STATE_KEYS = 128, MAXIMUM_TEXT = 1024, DEFAULT_RADIUS = 5;
    static final String STATE_ROOT = "state/behavior-state/";
    private static final String NAME = "[a-z0-9][a-z0-9_-]{0,63}";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    record Input(TreeMap<String, String> scripts, Optional<String> citizens, Optional<String> serverFlags, TreeMap<String, String> playerFlags) {}

    /** The staged files by path relative to the import directory, and the report also written as {@code report.json}. */
    record Output(String id, TreeMap<String, String> files, JsonObject report) {}

    private enum Kind { NEUTRAL, BOOLEAN, INTEGER, OTHER }

    private record Npc(String key, String id, String uuid, String name, List<String> assignments, JsonObject triggers, Map<String, String> flags, List<String> problems) {}

    private record Outcome(String file, int line, String container, String kind, String name, String text, boolean converted, String reason) {}

    private record Block(List<JsonObject> steps, boolean stops) {}

    /** A condition that builds a condition node around given branches. */
    private interface Condition { JsonObject build(JsonObject then, Optional<JsonObject> otherwise); }

    private record Interact(Container container, String slug, String quest, List<String> steps, List<String> order, int defaultIndex) {
        /** Stage of a step by its name, ignoring case and the default marker. */
        Optional<Integer> stage(String name) {
            String wanted = stepName(name);
            for (int index = 0; index < steps.size(); index++) if (stepName(steps.get(index)).equals(wanted)) return Optional.of(index);
            return Optional.empty();
        }
    }

    /** A document under construction and where the commands being converted come from. */
    private final class Scope {
        final LinkedHashMap<String, JsonObject> routines;
        final Set<String> calling;
        final Optional<String> npcName;
        final boolean record;
        final Container container;
        final Optional<Interact> interact;
        final int step;

        Scope(LinkedHashMap<String, JsonObject> routines, Set<String> calling, Optional<String> npcName, boolean record, Container container, Optional<Interact> interact, int step) {
            this.routines = routines; this.calling = calling; this.npcName = npcName; this.record = record; this.container = container; this.interact = interact; this.step = step;
        }

        Scope in(Container other) { return new Scope(routines, calling, npcName, false, other, Optional.empty(), -1); }
    }

    private final TreeMap<String, Container> containers = new TreeMap<>();
    private final TreeMap<String, Interact> interacts = new TreeMap<>();
    private final TreeMap<String, Kind> kinds = new TreeMap<>();
    private final TreeMap<String, Outcome> outcomes = new TreeMap<>();
    private final List<String> issues = new ArrayList<>();
    private final TreeMap<String, String> sounds = new TreeMap<>();
    private final JsonArray preserved = new JsonArray();
    private final TreeMap<String, String> slugs = new TreeMap<>();
    private final Set<String> usedSlugs = new HashSet<>();
    private final List<Npc> npcs = new ArrayList<>();

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

        TreeMap<String, DenizenFlags.Values> playerValues = new TreeMap<>();
        for (Map.Entry<String, String> file : input.playerFlags().entrySet()) playerValues.put(file.getKey(), DenizenFlags.file(file.getValue()));
        Optional<DenizenFlags.Values> serverValues = input.serverFlags().map(DenizenFlags::file);
        for (Container container : containers.values()) for (Entry entry : container.body().entries()) scanKinds(entry.value());
        for (DenizenFlags.Values values : playerValues.values()) dataKinds(values);
        serverValues.ifPresent(this::dataKinds);
        for (Npc npc : npcs) dataKinds(DenizenFlags.mapping(npc.flags()));

        TreeMap<String, String> files = new TreeMap<>();
        TreeMap<String, List<String>> bindings = new TreeMap<>();
        JsonObject behaviorStatus = new JsonObject();
        TreeMap<String, String> documents = new TreeMap<>();
        for (Container container : containers.values()) {
            switch (container.type()) {
                case "task" -> task(container, documents);
                case "assignment" -> assignment(container, documents);
                case "interact" -> interactDocument(interacts.get(container.name().toLowerCase(Locale.ROOT)), documents);
                case "world" -> world(container);
                default -> preserveContainer(container, switch (container.type()) {
                    case "procedure" -> "procedure scripts return values to tags; behaviors have no expressions";
                    case "command" -> "behaviors do not register commands";
                    case "data" -> "data scripts hold values; literal values that converted text reads were copied into it";
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
            for (String problem : npc.problems()) issues.add(npc.id() + ": " + problem);
            if (!bound.isEmpty()) bindings.put(npc.id(), bound);
        }
        for (Map.Entry<String, String> document : documents.entrySet()) {
            String path = "behaviors/" + document.getKey().replace(':', '/') + ".json";
            files.put(path, document.getValue());
        }

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
        for (Map.Entry<String, TreeMap<String, JsonObject>> document : state.entrySet()) {
            files.put(document.getKey(), stateDocument(document.getKey(), document.getValue()));
        }

        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", 1);
        report.addProperty("converterVersion", CONVERTER_VERSION);
        report.addProperty("importId", id);
        report.addProperty("notice", "Data conversion only. Generated behaviors are listed on no NPC until the denizen review is accepted, and commands stay denied until allow-listed.");
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
        for (Map.Entry<String, String> document : documents.entrySet()) behaviorStatus.addProperty(document.getKey(), partial(document.getKey()) ? "partial" : "complete");
        report.add("behaviorStatus", behaviorStatus);
        JsonArray commandBehaviors = new JsonArray();
        for (Map.Entry<String, String> document : documents.entrySet()) if (document.getValue().contains("\"animus:command\"")) commandBehaviors.add(document.getKey());
        report.add("commandBehaviors", commandBehaviors);
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

    private Optional<String> npcName(Container container) {
        TreeSet<String> names = new TreeSet<>();
        for (Npc npc : boundNpcs(container)) names.add(npc.name());
        return names.size() == 1 ? Optional.of(names.first()) : Optional.empty();
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

    private static String stepName(String name) { return name.toLowerCase(Locale.ROOT).replace("*", "").trim(); }

    // ---- Flag kinds ----

    private void kind(String flag, Kind kind) {
        String name = flag.toLowerCase(Locale.ROOT);
        Kind current = kinds.getOrDefault(name, Kind.NEUTRAL);
        Kind merged;
        if (current == Kind.OTHER || kind == Kind.OTHER) merged = Kind.OTHER;
        else if (current == Kind.NEUTRAL) merged = kind;
        else if (kind == Kind.NEUTRAL || kind == current) merged = current;
        else merged = Kind.OTHER;
        kinds.put(name, merged);
    }

    private Kind kindOf(String flag) {
        Kind kind = kinds.getOrDefault(flag.toLowerCase(Locale.ROOT), Kind.NEUTRAL);
        return kind == Kind.NEUTRAL ? Kind.BOOLEAN : kind;
    }

    private void scanKinds(Node node) {
        if (node instanceof Mapping mapping) for (Entry entry : mapping.entries()) scanKinds(entry.value());
        if (!(node instanceof Sequence sequence)) return;
        for (Item item : sequence.items()) {
            Command command = DenizenArgs.command(item.text());
            if (command.name().equals("flag") && command.args().size() >= 2) {
                String raw = raw(command.args().get(1));
                int colon = raw.indexOf(':');
                String name = colon < 0 ? raw : raw.substring(0, colon);
                if (!name.contains("<")) kind(name, colon < 0 ? Kind.BOOLEAN : valueKind(raw.substring(colon + 1)));
            }
            if (command.name().equals("if") || command.name().equals("else") || command.name().equals("choose")) {
                List<String> tokens = new ArrayList<>();
                DenizenArgs.split(item.text(), tokens, new ArrayList<>());
                for (int index = 1; index < tokens.size(); index++) {
                    String token = tokens.get(index).startsWith("!") ? tokens.get(index).substring(1) : tokens.get(index);
                    Optional<String[]> flag = flagTag(token);
                    if (flag.isEmpty()) continue;
                    String operator = index + 1 < tokens.size() ? tokens.get(index + 1) : "";
                    String operand = index + 2 < tokens.size() ? tokens.get(index + 2) : "";
                    if (command.name().equals("choose")) kind(flag.get()[1], Kind.INTEGER);
                    else if (List.of("==", "!=").contains(operator) && (operand.equalsIgnoreCase("true") || operand.equalsIgnoreCase("false"))) kind(flag.get()[1], Kind.BOOLEAN);
                    else if (List.of("==", "!=", ">=", "<=", ">", "<").contains(operator) && operand.matches("-?[0-9]+")) kind(flag.get()[1], Kind.INTEGER);
                    else kind(flag.get()[1], Kind.NEUTRAL);
                }
            }
            if (item.children().isPresent()) scanKinds(item.children().get());
        }
    }

    private static Kind valueKind(String value) {
        if (value.equals("!")) return Kind.NEUTRAL;
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) return Kind.BOOLEAN;
        if (value.equals("++") || value.equals("--") || value.startsWith("+:") || value.startsWith("-:") || value.startsWith("*:") || value.startsWith("/:") || value.matches("-?[0-9]+")) return Kind.INTEGER;
        return Kind.OTHER;
    }

    private void dataKinds(DenizenFlags.Values values) {
        for (DenizenFlags.Flag flag : values.flags().values()) {
            if (flag.name().startsWith("__") || flag.expires()) continue;
            if (flag.value().equals("true") || flag.value().equals("false")) kind(flag.name(), Kind.BOOLEAN);
            else if (DenizenFlags.stage(flag.value()).isPresent()) kind(flag.name(), Kind.INTEGER);
            else kind(flag.name(), Kind.OTHER);
        }
    }

    /** A flag tag such as {@code <player.has_flag[name]>} or {@code <npc.flag[name]||0>}: {target, name, attribute}. */
    private static Optional<String[]> flagTag(String token) {
        if (!token.startsWith("<") || !token.endsWith(">") || DenizenArgs.tagEnd(token, 0) != token.length() - 1) return Optional.empty();
        Tag tag = DenizenArgs.tag(token.substring(1, token.length() - 1));
        List<DenizenArgs.Part> parts = new ArrayList<>(tag.parts());
        if (parts.size() == 3 && parts.get(2).name().equals("if_null")) parts.removeLast();
        if (parts.size() != 2 || parts.getFirst().parameter().isPresent()) return Optional.empty();
        String target = parts.getFirst().name().toLowerCase(Locale.ROOT);
        String attribute = parts.get(1).name().toLowerCase(Locale.ROOT);
        if (!List.of("player", "npc", "server").contains(target) || !List.of("flag", "has_flag").contains(attribute) || parts.get(1).parameter().isEmpty()) return Optional.empty();
        String name = parts.get(1).parameter().get();
        if (name.contains("<")) return Optional.empty();
        return Optional.of(new String[] {target, name.toLowerCase(Locale.ROOT), attribute});
    }

    private static String raw(Arg arg) { return arg.prefix().isEmpty() ? arg.value() : arg.prefix() + ":" + arg.value(); }

    // ---- Outcomes ----

    private void outcome(Scope scope, Item item, String kind, String name, boolean converted, String reason) {
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

    private void preserveTree(Scope scope, Item item, String reason) {
        outcome(scope, item, "command", DenizenArgs.command(item.text()).name(), false, reason);
        if (item.children().isPresent()) preserveItems(scope, item.children().get().items(), 0, reason.startsWith("inside ") ? reason : "inside a block that is kept: " + reason);
    }

    private void preserveItems(Scope scope, List<Item> items, int from, String reason) {
        for (int index = from; index < items.size(); index++) preserveTree(scope, items.get(index), reason);
    }

    private void preserveContainer(Container container, String reason) {
        record(container, container.line(), "container", container.type(), container.name(), false, reason);
        // Only script-running containers hold command lines; lists in data, book or inventory scripts are values.
        if (!SCRIPT_TYPES.contains(container.type())) return;
        Scope scope = new Scope(new LinkedHashMap<>(), new HashSet<>(), Optional.empty(), true, container, Optional.empty(), -1);
        preserveNode(scope, container.body(), reason);
    }

    private static final Set<String> SCRIPT_TYPES = Set.of("task", "procedure", "command", "assignment", "interact", "world");

    private void preserveNode(Scope scope, Node node, String reason) {
        if (node instanceof Mapping mapping) for (Entry entry : mapping.entries()) preserveNode(scope, entry.value(), reason);
        else if (node instanceof Sequence sequence) for (Item item : sequence.items()) preserveTree(scope, item, reason);
    }

    private boolean partial(String document) {
        for (Outcome outcome : outcomes.values()) if (!outcome.converted() && document.equals(documentOf(outcome))) return true;
        return false;
    }

    private String documentOf(Outcome outcome) {
        Container container = containers.get(outcome.container().toLowerCase(Locale.ROOT));
        if (container == null) return "";
        return switch (container.type()) {
            case "task" -> behaviorId("task", container);
            case "assignment" -> behaviorId("assignment", container);
            case "interact" -> behaviorId("interact", container);
            default -> "";
        };
    }

    // ---- Containers ----

    private void task(Container container, TreeMap<String, String> documents) {
        Optional<Node> script = container.body().get("script");
        if (script.isEmpty() || !(script.get() instanceof Sequence sequence)) {
            preserveContainer(container, "task script without a script list");
            return;
        }
        LinkedHashMap<String, JsonObject> routines = new LinkedHashMap<>();
        Set<String> calling = new HashSet<>();
        calling.add(routineFor(container, Optional.empty()));
        routines.put("main", new JsonObject());
        Scope scope = new Scope(routines, calling, Optional.empty(), true, container, Optional.empty(), -1);
        // Other lists in a task are paths that run with path:; each becomes a routine of the task's own behavior.
        for (Entry entry : container.body().entries()) {
            String key = entry.key().toLowerCase(Locale.ROOT);
            if (key.equals("script") || !(entry.value() instanceof Sequence path)) continue;
            String routine = routineFor(container, Optional.of(key));
            if (!reserve(routines, routine)) {
                record(container, entry.line(), "event", "task path", entry.key(), false, "more than 32 routines in one behavior");
                preserveItems(scope, path.items(), 0, "inside a task path past the routine limit");
                continue;
            }
            calling.add(routine);
            routines.put(routine, sequence(block(path.items(), 0, scope).steps()));
            calling.remove(routine);
        }
        Block block = block(sequence.items(), 0, scope);
        routines.put("main", sequence(block.steps()));
        boolean empty = true;
        for (JsonObject routine : routines.values()) if (!routine.equals(sequence(List.of()))) empty = false;
        if (empty) return;
        document(behaviorId("task", container), container, routines, new JsonArray(), documents);
    }

    private void assignment(Container container, TreeMap<String, String> documents) {
        LinkedHashMap<String, JsonObject> routines = new LinkedHashMap<>();
        JsonArray triggers = new JsonArray();
        Optional<Node> actions = container.body().get("actions");
        Optional<Sequence> onAssignment = Optional.empty();
        if (actions.isPresent() && actions.get() instanceof Mapping mapping) {
            Optional<Node> assigned = mapping.get("on assignment");
            if (assigned.isPresent() && assigned.get() instanceof Sequence sequence) onAssignment = Optional.of(sequence);
        }
        Optional<String> npcName = npcName(container);
        int radius = radius(container, onAssignment);
        if (actions.isPresent() && actions.get() instanceof Mapping mapping) {
            for (Entry entry : mapping.entries()) {
                String event = entry.key().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
                if (!(entry.value() instanceof Sequence sequence)) {
                    record(container, entry.line(), "event", event, entry.key(), false, "action without a script list");
                    continue;
                }
                Scope scope = new Scope(routines, new HashSet<>(), npcName, true, container, Optional.empty(), -1);
                if (event.equals("on assignment")) {
                    record(container, entry.line(), "event", event, entry.key(), true, "");
                    for (Item item : sequence.items()) {
                        if (DenizenArgs.command(item.text()).name().equals("trigger") && item.children().isEmpty()) outcome(scope, item, "command", "trigger", true, "");
                        else preserveTree(scope, item, "on assignment runs once, when Denizen assigns the script; its trigger settings are read from the NPC's saved triggers");
                    }
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
                    preserveItems(scope, sequence.items(), 0, "inside an action that is kept: no Animus trigger for this action");
                    continue;
                }
                if (!mapped[2].isEmpty() && !enabled(container, mapped[2], onAssignment)) {
                    record(container, entry.line(), "event", event, entry.key(), false, "the " + mapped[2] + " trigger is off on every NPC with this assignment");
                    preserveItems(scope, sequence.items(), 0, "inside an action whose trigger is off");
                    continue;
                }
                String routine = event.replace(' ', '-');
                if (routines.size() >= BehaviorLimits.ROUTINES) {
                    record(container, entry.line(), "event", event, entry.key(), false, "more than 32 routines in one behavior");
                    preserveItems(scope, sequence.items(), 0, "inside an action past the routine limit");
                    continue;
                }
                routines.put(routine, new JsonObject());
                Block block = block(sequence.items(), 0, scope);
                routines.put(routine, sequence(block.steps()));
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
        for (Entry entry : container.body().entries()) {
            String key = entry.key().toLowerCase(Locale.ROOT);
            if (List.of("type", "actions", "interact scripts", "debug").contains(key)) continue;
            record(container, entry.line(), "event", key, entry.key(), false, "assignment setting " + key + " has no Animus equivalent");
        }
        if (routines.isEmpty()) {
            if (outcomesOf(container).isEmpty()) record(container, container.line(), "container", "assignment", container.name(), true, "");
            return;
        }
        document(behaviorId("assignment", container), container, routines, triggers, documents);
    }

    private List<Outcome> outcomesOf(Container container) {
        List<Outcome> found = new ArrayList<>();
        for (Outcome outcome : outcomes.values()) if (outcome.container().equalsIgnoreCase(container.name()) && outcome.file().equals(container.file())) found.add(outcome);
        return found;
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
        for (String flag : kinds.keySet())
            if (flagName(flag).isPresent() && flagName(flag).get().equals(interact.quest()))
                issues.add(container.name() + ": the step quest " + interact.quest() + " shares its name with the flag " + flag);
        LinkedHashMap<String, JsonObject> routines = new LinkedHashMap<>();
        JsonArray triggers = new JsonArray();
        Optional<String> npcName = npcName(container);
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
            Scope scope = new Scope(routines, new HashSet<>(), npcName, true, container, Optional.of(interact), position);
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
                            preserveItems(scope, script.get().items(), 0, "inside a trigger that is off");
                            continue;
                        }
                        clickSteps.addAll(block(script.get().items(), 0, scope).steps());
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
                                if (script.isPresent()) preserveItems(scope, script.get().items(), 0, "inside a proximity phase that is kept");
                                continue;
                            }
                            if (!on) {
                                record(container, phase.line(), "event", label, phase.key(), false, "the proximity trigger is off on every NPC with this script");
                                preserveItems(scope, script.get().items(), 0, "inside a trigger that is off");
                                continue;
                            }
                            String routine = "s" + stage + "-" + (which.equals("entry") ? "enter" : "exit");
                            if (!reserve(routines, routine)) {
                                record(container, phase.line(), "event", label, phase.key(), false, "more than 32 routines in one behavior");
                                preserveItems(scope, script.get().items(), 0, "inside a trigger past the routine limit");
                                continue;
                            }
                            routines.put(routine, sequence(block(script.get().items(), 0, scope).steps()));
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
                                if (script.isPresent()) preserveItems(scope, script.get().items(), 0, "inside a chat answer that is kept");
                                continue;
                            }
                            routines.put(routine, sequence(block(script.get().items(), 0, scope).steps()));
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
                        scriptOf(trigger.value()).ifPresent((Sequence script) -> preserveItems(scope, script.items(), 0, "inside a damage trigger that is kept"));
                    }
                    default -> {
                        record(container, trigger.line(), "event", name, trigger.key(), false, "no Animus trigger for " + name);
                        scriptOf(trigger.value()).ifPresent((Sequence script) -> preserveItems(scope, script.items(), 0, "inside a trigger that is kept"));
                    }
                }
            }
            if (!options.isEmpty()) {
                JsonObject choices = new JsonObject();
                choices.addProperty("type", "action");
                choices.addProperty("capability", "animus:choices");
                JsonObject arguments = new JsonObject();
                arguments.addProperty("id", prefix + "-s" + stage);
                arguments.addProperty("prompt", "Choose a reply");
                arguments.add("options", options);
                choices.add("arguments", arguments);
                clickSteps.add(choices);
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
                    chain = Optional.of(condition("animus:quest-state", arguments, call, chain));
                }
                routines.put(routine, chain.orElseThrow());
            }
            JsonObject trigger = new JsonObject();
            trigger.addProperty("id", phase);
            if (phase.equals("click")) {
                trigger.addProperty("event", "interaction");
                trigger.addProperty("routine", routine);
                cooldown(trigger, container, "click");
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
        JsonArray ordered = triggers;
        for (Entry entry : container.body().entries()) {
            String key = entry.key().toLowerCase(Locale.ROOT);
            if (List.of("type", "steps", "debug").contains(key)) continue;
            record(container, entry.line(), "event", key, entry.key(), false, "interact setting " + key + " has no Animus equivalent");
        }
        if (routines.isEmpty()) {
            record(container, container.line(), "container", "interact", container.name(), false, "no step trigger converts");
            return;
        }
        // Dispatchers go first so the behavior's entry is the click dispatcher when there is one.
        LinkedHashMap<String, JsonObject> sorted = new LinkedHashMap<>();
        for (String first : List.of("click", "enter", "exit")) if (routines.containsKey(first)) sorted.put(first, routines.get(first));
        for (Map.Entry<String, JsonObject> routine : routines.entrySet()) if (!sorted.containsKey(routine.getKey())) sorted.put(routine.getKey(), routine.getValue());
        document(behaviorId("interact", container), container, sorted, ordered, documents);
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

    private void world(Container container) {
        record(container, container.line(), "container", "world", container.name(), false, "world scripts react to server-wide events; Animus triggers belong to NPCs");
        Optional<Node> events = container.body().get("events");
        Scope scope = new Scope(new LinkedHashMap<>(), new HashSet<>(), Optional.empty(), true, container, Optional.empty(), -1);
        if (events.isPresent() && events.get() instanceof Mapping mapping) {
            for (Entry entry : mapping.entries()) {
                String event = "world " + entry.key().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
                record(container, entry.line(), "event", event, entry.key(), false, worldReason(entry.key().toLowerCase(Locale.ROOT)));
                preserveNode(scope, entry.value(), "inside a world event that is kept");
            }
        }
        for (Entry entry : container.body().entries()) if (!entry.key().equalsIgnoreCase("events")) preserveNode(scope, entry.value(), "inside a world script that is kept");
    }

    private static String worldReason(String event) {
        if (event.contains("delta time") || event.contains("system time") || event.contains("server start")) return "Animus timers run per NPC; there is no server-wide timer or startup trigger";
        if (event.contains("npc")) return "this event covers every NPC; Animus binds a behavior to each NPC instead";
        return "Animus has no trigger for this event; its triggers belong to NPCs";
    }

    private void document(String id, Container container, LinkedHashMap<String, JsonObject> routines, JsonArray triggers, TreeMap<String, String> documents) {
        JsonObject document = new JsonObject();
        document.addProperty("schemaVersion", 1);
        document.addProperty("revision", 1);
        document.addProperty("id", id);
        document.addProperty("entry", routines.keySet().iterator().next());
        JsonObject body = new JsonObject();
        for (Map.Entry<String, JsonObject> routine : routines.entrySet()) body.add(routine.getKey(), routine.getValue());
        document.add("routines", body);
        if (!triggers.isEmpty()) document.add("triggers", triggers);
        String text = JsonDocuments.write(document);
        Optional<String> problem = BehaviorLimits.check(document, text);
        if (problem.isPresent()) {
            issues.add(container.name() + ": the converted behavior " + problem.get() + "; it is not generated");
            record(container, container.line(), "container", container.type(), container.name(), false, "the converted behavior " + problem.get());
            return;
        }
        documents.put(id, text);
    }

    // ---- Commands ----

    private Block block(List<Item> items, int from, Scope scope) {
        List<JsonObject> steps = new ArrayList<>();
        for (int index = from; index < items.size(); index++) {
            Item item = items.get(index);
            Command command = DenizenArgs.command(item.text());
            switch (command.name()) {
                case "stop" -> {
                    outcome(scope, item, "command", "stop", true, "");
                    preserveItems(scope, items, index + 1, "never runs: after stop");
                    return new Block(steps, true);
                }
                case "determine" -> {
                    boolean passive = false;
                    for (Arg arg : command.args()) if (arg.is("passively")) passive = true;
                    outcome(scope, item, "command", "determine", false, "determine returns a value to Denizen; behaviors return none");
                    if (!passive) {
                        preserveItems(scope, items, index + 1, "never runs: after determine");
                        return new Block(steps, true);
                    }
                }
                case "if", "choose" -> {
                    int end = index + 1;
                    if (command.name().equals("if")) while (end < items.size() && DenizenArgs.command(items.get(end).text()).name().equals("else")) end++;
                    Optional<Chain> chain = chain(items, index, end, scope);
                    index = end - 1;
                    if (chain.isEmpty()) continue;
                    steps.add(chain.get().node());
                    if (chain.get().tail()) return new Block(steps, chain.get().stops());
                }
                case "else" -> preserveTree(scope, item, "else without a matching if");
                case "repeat" -> repeat(item, command, scope).ifPresent(steps::add);
                default -> {
                    if (item.children().isPresent()) {
                        preserveTree(scope, item, command.name().equals("foreach") || command.name().equals("while")
                                ? "loops over computed values have no Animus equivalent" : "no Animus equivalent for the " + command.name() + " block");
                        continue;
                    }
                    Converted converted = simple(command, scope);
                    outcome(scope, item, "command", command.name(), converted.reason().isEmpty(), converted.reason());
                    steps.addAll(converted.steps());
                }
            }
        }
        return new Block(steps, false);
    }

    private record Converted(List<JsonObject> steps, String reason) {
        static Converted of(JsonObject... steps) { return new Converted(List.of(steps), ""); }
        static Converted kept(String reason) { return new Converted(List.of(), reason); }
    }

    /** A converted chain. {@code tail} means it took in the rest of the block; {@code stops} that every path ends in stop. */
    private record Chain(JsonObject node, boolean tail, boolean stops) {}

    /**
     * An if/else-if/else chain or a choose block. When a branch ends in stop, the commands after the chain run only
     * in the branches that do not stop, so they move into those branches and the chain becomes the end of the block.
     */
    private Optional<Chain> chain(List<Item> items, int start, int end, Scope scope) {
        Item head = items.get(start);
        Command command = DenizenArgs.command(head.text());
        List<Condition> conditions = new ArrayList<>();
        List<Item> bodies = new ArrayList<>();
        Optional<Item> otherwise = Optional.empty();
        String reason = null;
        if (command.name().equals("choose")) {
            Optional<String[]> flag = command.args().size() == 1 ? flagTag(command.args().getFirst().value()) : Optional.empty();
            if (flag.isEmpty() || !flag.get()[2].equals("flag") || kindOf(flag.get()[1]) != Kind.INTEGER || flagName(flag.get()[1]).isEmpty()) reason = "choose works only on a whole-number flag";
            else if (head.children().isEmpty()) reason = "choose without cases";
            else {
                for (Item option : head.children().get().items()) {
                    Command when = DenizenArgs.command(option.text());
                    if (option.children().isEmpty()) { reason = "case without commands"; break; }
                    if (when.name().equals("default") && when.args().isEmpty()) { otherwise = Optional.of(option); continue; }
                    if (!when.name().equals("case") || when.args().size() != 1 || DenizenFlags.stage(when.args().getFirst().value()).isEmpty()) { reason = "case values must be whole numbers"; break; }
                    JsonObject arguments = new JsonObject();
                    arguments.addProperty("name", flagName(flag.get()[1]).get());
                    arguments.addProperty("scope", scopeOf(flag.get()[0]));
                    arguments.addProperty("equals", DenizenFlags.stage(when.args().getFirst().value()).get());
                    conditions.add(atom("animus:quest-state", arguments, false));
                    bodies.add(option);
                }
            }
        } else {
            for (int index = start; index < end; index++) {
                Item branch = items.get(index);
                List<String> tokens = new ArrayList<>();
                DenizenArgs.split(branch.text(), tokens, new ArrayList<>());
                boolean isElse = index > start;
                if (branch.children().isEmpty()) { reason = "if without a block"; break; }
                if (isElse && tokens.size() == 1) {
                    if (index != end - 1) { reason = "else before else if"; break; }
                    otherwise = Optional.of(branch);
                    continue;
                }
                List<String> expression = tokens.subList(isElse ? 2 : 1, tokens.size());
                if (isElse && !tokens.get(1).equalsIgnoreCase("if")) { reason = "unknown else form"; break; }
                Result<Condition> condition = condition(expression);
                if (condition.reason() != null) { reason = condition.reason(); break; }
                conditions.add(condition.value());
                bodies.add(branch);
            }
        }
        if (reason != null || conditions.isEmpty()) {
            String why = reason == null ? "nothing to choose between" : reason;
            for (int index = start; index < end; index++) preserveTree(scope, items.get(index), why);
            return Optional.empty();
        }
        for (int index = start; index < end; index++) outcome(scope, items.get(index), "command", DenizenArgs.command(items.get(index).text()).name(), true, "");
        if (command.name().equals("choose")) for (Item option : head.children().get().items()) outcome(scope, option, "command", DenizenArgs.command(option.text()).name(), true, "");
        List<Block> blocks = new ArrayList<>();
        boolean anyStop = false;
        for (Item body : bodies) { Block block = block(body.children().get().items(), 0, scope); blocks.add(block); anyStop |= block.stops(); }
        Optional<Block> elseBlock = otherwise.map((Item body) -> block(body.children().get().items(), 0, scope));
        if (elseBlock.isPresent()) anyStop |= elseBlock.get().stops();
        if (!anyStop) {
            Optional<JsonObject> tail = elseBlock.map((Block block) -> sequence(block.steps()));
            for (int index = conditions.size() - 1; index >= 0; index--) tail = Optional.of(conditions.get(index).build(sequence(blocks.get(index).steps()), tail));
            return Optional.of(new Chain(tail.orElseThrow(), false, false));
        }
        Block rest = block(items, end, scope);
        boolean allStop;
        Optional<JsonObject> tail;
        if (elseBlock.isPresent()) {
            tail = Optional.of(sequence(joined(elseBlock.get(), rest)));
            allStop = elseBlock.get().stops() || rest.stops();
        } else {
            tail = rest.steps().isEmpty() ? Optional.empty() : Optional.of(sequence(copies(rest.steps())));
            allStop = rest.stops();
        }
        for (int index = conditions.size() - 1; index >= 0; index--) {
            allStop &= blocks.get(index).stops() || rest.stops();
            tail = Optional.of(conditions.get(index).build(sequence(joined(blocks.get(index), rest)), tail));
        }
        return Optional.of(new Chain(tail.orElseThrow(), true, allStop));
    }

    private static List<JsonObject> joined(Block block, Block rest) {
        List<JsonObject> steps = new ArrayList<>(block.steps());
        if (!block.stops()) steps.addAll(copies(rest.steps()));
        return steps;
    }

    private static List<JsonObject> copies(List<JsonObject> steps) {
        List<JsonObject> copied = new ArrayList<>();
        for (JsonObject step : steps) copied.add(step.deepCopy());
        return copied;
    }

    private Optional<JsonObject> repeat(Item item, Command command, Scope scope) {
        if (item.children().isEmpty()) { preserveTree(scope, item, "repeat without a block"); return Optional.empty(); }
        if (command.args().size() != 1 || !command.args().getFirst().value().matches("[0-9]{1,3}") || Integer.parseInt(command.args().getFirst().value()) > 128) {
            preserveTree(scope, item, "repeat needs a literal count from 0 to 128");
            return Optional.empty();
        }
        if (controlsLoop(item.children().get())) { preserveTree(scope, item, "repeat stop and repeat next have no Animus equivalent"); return Optional.empty(); }
        outcome(scope, item, "command", "repeat", true, "");
        Block body = block(item.children().get().items(), 0, scope);
        if (body.stops()) {
            issues.add(scope.container.file() + ":" + item.line() + ": stop inside repeat only ends the current pass in the converted behavior");
        }
        JsonObject node = new JsonObject();
        node.addProperty("type", "repeat");
        node.addProperty("count", Integer.parseInt(command.args().getFirst().value()));
        node.add("body", sequence(body.steps()));
        return Optional.of(node);
    }

    private static boolean controlsLoop(Sequence sequence) {
        for (Item item : sequence.items()) {
            Command command = DenizenArgs.command(item.text());
            if (command.name().equals("repeat") && item.children().isEmpty()) return true;
            if (item.children().isPresent() && controlsLoop(item.children().get())) return true;
        }
        return false;
    }

    private record Result<T>(T value, String reason) {
        static <T> Result<T> ok(T value) { return new Result<>(value, null); }
        static <T> Result<T> fail(String reason) { return new Result<>(null, reason); }
    }

    private Result<Condition> condition(List<String> tokens) {
        if (tokens.isEmpty()) return Result.fail("if without a condition");
        boolean and = false, or = false;
        List<List<String>> groups = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String token : tokens) {
            if (token.equals("&&") || token.equals("||")) {
                if (token.equals("&&")) and = true; else or = true;
                groups.add(current);
                current = new ArrayList<>();
            } else if (token.startsWith("(") || token.endsWith(")")) return Result.fail("grouped conditions have no Animus equivalent");
            else current.add(token);
        }
        groups.add(current);
        if (and && or) return Result.fail("mixed && and || conditions have no Animus equivalent");
        List<Condition> atoms = new ArrayList<>();
        for (List<String> group : groups) {
            Result<Condition> atom = comparison(group);
            if (atom.reason() != null) return atom;
            atoms.add(atom.value());
        }
        Condition combined = atoms.getLast();
        for (int index = atoms.size() - 2; index >= 0; index--) {
            Condition left = atoms.get(index), right = combined;
            combined = and ? (JsonObject then, Optional<JsonObject> otherwise) -> left.build(right.build(then, otherwise.map(JsonObject::deepCopy)), otherwise)
                    : (JsonObject then, Optional<JsonObject> otherwise) -> left.build(then, Optional.of(right.build(then.deepCopy(), otherwise)));
        }
        return Result.ok(combined);
    }

    private Result<Condition> comparison(List<String> group) {
        if (group.size() != 1 && group.size() != 3) return Result.fail("condition form has no Animus equivalent");
        String left = group.getFirst();
        boolean negated = left.startsWith("!");
        if (negated) left = left.substring(1);
        if (!left.startsWith("<") || !left.endsWith(">") || DenizenArgs.tagEnd(left, 0) != left.length() - 1) return Result.fail("condition " + group.getFirst() + " has no Animus equivalent");
        Tag tag = DenizenArgs.tag(left.substring(1, left.length() - 1));
        if (group.size() == 1) {
            if (tag.parts().size() == 2 && tag.base().equalsIgnoreCase("player") && tag.parts().get(1).name().equalsIgnoreCase("has_permission") && tag.parts().get(1).parameter().isPresent() && !tag.parts().get(1).parameter().get().contains("<")) {
                JsonObject arguments = new JsonObject();
                arguments.addProperty("permission", tag.parts().get(1).parameter().get());
                return Result.ok(atom("animus:has-permission", arguments, negated));
            }
            if (tag.parts().size() == 2 && tag.base().equalsIgnoreCase("npc") && tag.parts().get(1).name().equalsIgnoreCase("is_spawned") && tag.parts().get(1).parameter().isEmpty()) {
                JsonObject arguments = new JsonObject();
                arguments.addProperty("spawned", true);
                return Result.ok(atom("animus:npc-state", arguments, negated));
            }
        }
        Optional<String[]> flag = flagTag(left);
        if (flag.isEmpty()) return Result.fail("condition tag " + tag.label() + " has no Animus equivalent");
        Optional<String> name = flagName(flag.get()[1]);
        if (name.isEmpty()) return Result.fail("flag name " + flag.get()[1] + " cannot be an Animus flag name");
        Kind kind = kindOf(flag.get()[1]);
        String scope = scopeOf(flag.get()[0]);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("name", name.get());
        arguments.addProperty("scope", scope);
        if (group.size() == 1) {
            if (kind == Kind.BOOLEAN) return Result.ok(atom("animus:flag-state", arguments, negated));
            if (kind == Kind.INTEGER) { arguments.addProperty("atLeast", 1); return Result.ok(atom("animus:quest-state", arguments, negated)); }
            return Result.fail("flag " + flag.get()[1] + " holds values Animus flags cannot");
        }
        if (negated) return Result.fail("negated comparison has no Animus equivalent");
        String operator = group.get(1), operand = group.get(2);
        if (kind == Kind.BOOLEAN && (operator.equals("==") || operator.equals("!=")) && (operand.equalsIgnoreCase("true") || operand.equalsIgnoreCase("false"))) {
            boolean wanted = operand.equalsIgnoreCase("true") == operator.equals("==");
            if (!wanted) arguments.addProperty("value", false);
            return Result.ok(atom("animus:flag-state", arguments, false));
        }
        if (kind != Kind.INTEGER || !operand.matches("[0-9]{1,13}") || Long.parseLong(operand) > 1_000_000_000_000L) return Result.fail("comparison with " + operand + " has no Animus equivalent");
        long number = Long.parseLong(operand);
        switch (operator) {
            case "==" -> arguments.addProperty("equals", number);
            case "!=" -> { arguments.addProperty("equals", number); return Result.ok(atom("animus:quest-state", arguments, true)); }
            case ">=" -> arguments.addProperty("atLeast", number);
            case "<=" -> arguments.addProperty("atMost", number);
            case ">" -> arguments.addProperty("atLeast", number + 1);
            case "<" -> {
                if (number == 0) return Result.fail("a quest stage is never below 0");
                arguments.addProperty("atMost", number - 1);
            }
            default -> { return Result.fail("operator " + operator + " has no Animus equivalent"); }
        }
        return Result.ok(atom("animus:quest-state", arguments, false));
    }

    private static Condition atom(String capability, JsonObject arguments, boolean negated) {
        return (JsonObject then, Optional<JsonObject> otherwise) -> negated
                ? condition(capability, arguments.deepCopy(), otherwise.orElseGet(() -> sequence(List.of())), Optional.of(then))
                : condition(capability, arguments.deepCopy(), then, otherwise);
    }

    private static JsonObject condition(String capability, JsonObject arguments, JsonObject then, Optional<JsonObject> otherwise) {
        JsonObject node = new JsonObject();
        node.addProperty("type", "condition");
        node.addProperty("capability", capability);
        node.add("arguments", arguments);
        node.add("then", then);
        otherwise.ifPresent((JsonObject value) -> node.add("else", value));
        return node;
    }

    private static String scopeOf(String target) {
        return switch (target) {
            case "npc" -> "npc";
            case "server" -> "global";
            default -> "player";
        };
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

    private static JsonObject action(String capability, JsonObject arguments) {
        JsonObject node = new JsonObject();
        node.addProperty("type", "action");
        node.addProperty("capability", capability);
        node.add("arguments", arguments);
        return node;
    }

    private Converted simple(Command command, Scope scope) {
        return switch (command.name()) {
            case "narrate" -> say(command, scope, "trigger");
            case "chat" -> say(command, scope, "nearby");
            case "wait" -> waitFor(command);
            case "flag" -> flag(command);
            case "zap" -> zap(command, scope);
            case "run", "inject" -> call(command, scope);
            case "execute" -> execute(command, scope);
            case "give" -> give(command);
            case "take" -> take(command);
            case "look" -> look(command);
            case "walk" -> walk(command);
            case "equip" -> equip(command);
            case "playsound" -> sound(command);
            case "playeffect" -> particle(command);
            case "actionbar" -> actionbar(command, scope);
            case "title" -> title(command, scope);
            case "debug" -> Converted.of();
            case "define" -> Converted.kept("definitions hold computed values; behaviors have no expressions");
            case "trigger" -> Converted.kept("trigger settings change only in on assignment; the NPC's saved triggers are used");
            case "teleport" -> Converted.kept("no Animus teleport action; an allow-listed animus:command with tp can replace it");
            case "" -> Converted.kept("empty command line");
            default -> Converted.kept("no Animus equivalent for " + command.name());
        };
    }

    private static boolean playerTarget(String value) {
        String lowered = value.toLowerCase(Locale.ROOT);
        return lowered.equals("<player>") || lowered.equals("player");
    }

    private Converted say(Command command, Scope scope, String audience) {
        List<String> words = new ArrayList<>();
        String chosen = audience;
        for (Arg arg : command.args()) {
            if (arg.prefix().isEmpty()) { if (!arg.is("per_player")) words.add(arg.value()); continue; }
            if (arg.prefix().equals("targets") && playerTarget(arg.value())) { chosen = "trigger"; continue; }
            return Converted.kept("argument " + arg.prefix() + ": has no Animus equivalent");
        }
        if (words.isEmpty()) return Converted.kept("nothing to say");
        Mapped text = text(String.join(" ", words), scope, false, 0);
        if (text.reason() != null) return Converted.kept(text.reason());
        if (text.value().isBlank()) return Converted.kept("nothing to say");
        if (text.value().length() > MAXIMUM_TEXT) return Converted.kept("text longer than 1024 characters");
        JsonObject arguments = new JsonObject();
        arguments.addProperty("text", text.value());
        if (chosen.equals("nearby")) arguments.addProperty("audience", "nearby");
        return Converted.of(action("animus:say", arguments));
    }

    private static Converted waitFor(Command command) {
        if (command.args().size() > 1 || command.args().size() == 1 && !command.args().getFirst().prefix().isEmpty()) return Converted.kept("wait argument has no Animus equivalent");
        OptionalLong ticks = command.args().isEmpty() ? OptionalLong.of(60) : DenizenArgs.ticks(command.args().getFirst().value());
        if (ticks.isEmpty()) return Converted.kept("wait needs a literal duration");
        if (ticks.getAsLong() == 0) return Converted.of();
        if (ticks.getAsLong() > 72_000) return Converted.kept("wait longer than an hour");
        JsonObject node = new JsonObject();
        node.addProperty("type", "wait");
        node.addProperty("ticks", ticks.getAsLong());
        return Converted.of(node);
    }

    private Converted flag(Command command) {
        if (command.args().size() < 2) return Converted.kept("flag needs a target and a name");
        if (command.args().size() > 2)
            return Converted.kept(command.args().get(2).prefix().equals("expire") ? "expiring flags have no Animus equivalent" : "flag argument has no Animus equivalent");
        String target = command.args().getFirst().value().toLowerCase(Locale.ROOT).replace("<", "").replace(">", "");
        if (!command.args().getFirst().prefix().isEmpty() || !List.of("player", "npc", "server").contains(target)) return Converted.kept("flags on other objects have no Animus equivalent");
        String raw = raw(command.args().get(1));
        int colon = raw.indexOf(':');
        String denizen = (colon < 0 ? raw : raw.substring(0, colon)).toLowerCase(Locale.ROOT);
        String value = colon < 0 ? "" : raw.substring(colon + 1);
        Optional<String> name = flagName(denizen);
        if (denizen.contains("<") || name.isEmpty()) return Converted.kept("flag name " + denizen + " cannot be an Animus flag name");
        JsonObject arguments = new JsonObject();
        arguments.addProperty("name", name.get());
        arguments.addProperty("scope", scopeOf(target));
        Kind kind = kindOf(denizen);
        if (kind == Kind.BOOLEAN) {
            if (colon < 0 || value.equalsIgnoreCase("true")) return Converted.of(action("animus:set-flag", arguments));
            if (value.equals("!") || value.equalsIgnoreCase("false")) { arguments.addProperty("value", false); return Converted.of(action("animus:set-flag", arguments)); }
        }
        if (kind == Kind.INTEGER) {
            if (value.equals("!")) { arguments.addProperty("value", 0); return Converted.of(action("animus:set-quest-state", arguments)); }
            if (DenizenFlags.stage(value).isPresent()) { arguments.addProperty("value", DenizenFlags.stage(value).get()); return Converted.of(action("animus:set-quest-state", arguments)); }
            if (value.equals("++")) return Converted.of(action("animus:advance-quest-state", arguments));
            if (value.startsWith("+:") && value.substring(2).matches("[0-9]{1,7}") && Integer.parseInt(value.substring(2)) >= 1 && Integer.parseInt(value.substring(2)) <= 1_000_000) {
                arguments.addProperty("by", Integer.parseInt(value.substring(2)));
                return Converted.of(action("animus:advance-quest-state", arguments));
            }
            return Converted.kept("quest stages only rise or are set; this flag change has no Animus equivalent");
        }
        return Converted.kept("flag " + denizen + " holds values Animus flags cannot");
    }

    private Converted zap(Command command, Scope scope) {
        Optional<Interact> target = scope.interact;
        String step = null;
        for (Arg arg : command.args()) {
            if (arg.prefix().equals("script")) { target = Optional.ofNullable(interacts.get(arg.value().toLowerCase(Locale.ROOT))); continue; }
            if (arg.prefix().equals("step")) { step = arg.value(); continue; }
            if (!arg.prefix().isEmpty()) return Converted.kept("zap argument " + arg.prefix() + ": has no Animus equivalent");
            if (interacts.containsKey(arg.value().toLowerCase(Locale.ROOT)) && step == null && target.equals(scope.interact)) { target = Optional.of(interacts.get(arg.value().toLowerCase(Locale.ROOT))); continue; }
            if (step != null) return Converted.kept("zap with a duration has no Animus equivalent");
            step = arg.value();
        }
        if (target.isEmpty()) return Converted.kept("zap outside an interact script needs the script's name");
        Interact interact = target.get();
        int stage;
        if (step == null) {
            if (scope.interact.isEmpty() || scope.interact.get() != interact || scope.step < 0 || scope.step + 1 >= interact.order().size()) return Converted.kept("zap to the next step needs a following step");
            stage = interact.stage(interact.order().get(scope.step + 1)).orElseThrow();
        } else {
            if (step.contains("<")) return Converted.kept("zap to a computed step has no Animus equivalent");
            Optional<Integer> found = interact.stage(step);
            if (found.isEmpty()) return Converted.kept("no step " + step + " in " + interact.container().name());
            stage = found.get();
        }
        JsonObject arguments = new JsonObject();
        arguments.addProperty("name", interact.quest());
        arguments.addProperty("scope", "relationship");
        arguments.addProperty("value", stage);
        return Converted.of(action("animus:set-quest-state", arguments));
    }

    private Converted call(Command command, Scope scope) {
        Optional<String> name = Optional.empty();
        Optional<String> path = Optional.empty();
        for (Arg arg : command.args()) {
            if (arg.prefix().isEmpty()) {
                if (arg.is("instantly") || arg.is("local")) continue;
                if (name.isPresent()) return Converted.kept("argument " + arg.value() + " has no Animus equivalent");
                name = Optional.of(arg.value());
                continue;
            }
            switch (arg.prefix()) {
                case "path" -> path = Optional.of(arg.value());
                case "npc" -> { if (!arg.value().equalsIgnoreCase("<npc>")) return Converted.kept("running for another NPC has no Animus equivalent"); }
                case "player" -> { if (!arg.value().equalsIgnoreCase("<player>")) return Converted.kept("running for another player has no Animus equivalent"); }
                case "def", "defmap", "definitions" -> { return Converted.kept("definitions passed to a task have no Animus equivalent"); }
                default -> { return Converted.kept("argument " + arg.prefix() + ": has no Animus equivalent"); }
            }
        }
        if (name.isEmpty() || name.get().contains("<")) return Converted.kept("running a computed script has no Animus equivalent");
        Container task = containers.get(name.get().toLowerCase(Locale.ROOT));
        if (task == null) return Converted.kept("no script named " + name.get());
        if (!task.type().equals("task")) return Converted.kept("only task scripts can become routines");
        String key = path.map((String value) -> value.toLowerCase(Locale.ROOT)).orElse("script");
        Optional<Node> body = task.body().get(key);
        if (body.isEmpty() || !(body.get() instanceof Sequence sequence)) return Converted.kept("task " + task.name() + " has no " + key + " list");
        String routine = routineFor(task, path);
        if (scope.calling.contains(routine)) return Converted.kept("recursive task calls have no Animus equivalent");
        JsonObject call = new JsonObject();
        call.addProperty("type", "call");
        call.addProperty("routine", routine);
        if (scope.routines.containsKey(routine)) return Converted.of(call);
        if (scope.routines.size() >= BehaviorLimits.ROUTINES) return Converted.kept("more than 32 routines in one behavior");
        scope.routines.put(routine, new JsonObject());
        scope.calling.add(routine);
        Block block = block(sequence.items(), 0, scope.in(task));
        scope.calling.remove(routine);
        scope.routines.put(routine, sequence(block.steps()));
        return Converted.of(call);
    }

    private String routineFor(Container task, Optional<String> path) {
        String routine = "task-" + slug(task.name()) + path.map((String value) -> "-" + value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-")).orElse("");
        return routine.length() > 64 ? routine.substring(0, 64) : routine;
    }

    private Converted execute(Command command, Scope scope) {
        String as = null;
        List<String> words = new ArrayList<>();
        for (Arg arg : command.args()) {
            if (arg.is("as_server") || arg.is("as_op")) { as = "console"; continue; }
            if (arg.is("as_player")) { as = "player"; continue; }
            if (arg.is("as_npc")) return Converted.kept("commands run as the NPC have no Animus equivalent");
            if (arg.is("silent")) continue;
            words.add(raw(arg));
        }
        if (as == null) return Converted.kept("execute needs as_server, as_op or as_player");
        Mapped text = text(String.join(" ", words), scope, true, 0);
        if (text.reason() != null) return Converted.kept(text.reason());
        String line = text.value().trim();
        if (line.startsWith("/")) line = line.substring(1);
        if (line.isEmpty() || line.length() > 1024) return Converted.kept("command is empty or longer than 1024 characters");
        JsonObject arguments = new JsonObject();
        arguments.addProperty("command", line);
        if (as.equals("player")) arguments.addProperty("as", "player");
        return Converted.of(action("animus:command", arguments));
    }

    private Optional<String> material(String value) {
        String lowered = value.toLowerCase(Locale.ROOT);
        if (lowered.startsWith("minecraft:")) lowered = lowered.substring(10);
        if (!lowered.matches("[a-z0-9_]{1,64}") || containers.containsKey(lowered)) return Optional.empty();
        return Optional.of("minecraft:" + lowered);
    }

    private Converted give(Command command) {
        Optional<String> item = Optional.empty();
        long quantity = 1;
        for (Arg arg : command.args()) {
            if (arg.prefix().equals("quantity")) {
                if (!arg.value().matches("[0-9]{1,4}") || Long.parseLong(arg.value()) < 1) return Converted.kept("quantity needs a literal number");
                quantity = Long.parseLong(arg.value());
            } else if (arg.prefix().isEmpty() && item.isEmpty()) {
                if (arg.value().equalsIgnoreCase("xp") || arg.value().equalsIgnoreCase("money")) return Converted.kept("giving " + arg.value() + " has no Animus equivalent");
                item = material(arg.value());
                if (item.isEmpty()) return Converted.kept("only plain materials can be given; item scripts and tags have no Animus equivalent");
            } else return Converted.kept("give argument has no Animus equivalent");
        }
        if (item.isEmpty()) return Converted.kept("nothing to give");
        JsonObject arguments = new JsonObject();
        arguments.addProperty("command", "give {player} " + item.get() + " " + quantity);
        return Converted.of(action("animus:command", arguments));
    }

    private Converted take(Command command) {
        Optional<String> item = Optional.empty();
        long quantity = 1;
        for (Arg arg : command.args()) {
            if (arg.prefix().equals("quantity")) {
                if (!arg.value().matches("[0-9]{1,4}") || Long.parseLong(arg.value()) < 1) return Converted.kept("quantity needs a literal number");
                quantity = Long.parseLong(arg.value());
            } else if ((arg.prefix().equals("item") || arg.prefix().isEmpty()) && item.isEmpty()) {
                item = material(arg.value());
                if (item.isEmpty()) return Converted.kept("only plain materials can be taken; item scripts, flags and tags have no Animus equivalent");
            } else return Converted.kept("take argument has no Animus equivalent");
        }
        if (item.isEmpty()) return Converted.kept("nothing to take");
        JsonObject arguments = new JsonObject();
        arguments.addProperty("command", "clear {player} " + item.get() + " " + quantity);
        return Converted.of(action("animus:command", arguments));
    }

    private static boolean npcTarget(String value) {
        String lowered = value.toLowerCase(Locale.ROOT);
        return lowered.equals("<npc>") || lowered.equals("npc");
    }

    private static Optional<double[]> location(String value) {
        String text = value;
        if (text.toLowerCase(Locale.ROOT).startsWith("l@")) text = text.substring(2);
        if (text.toLowerCase(Locale.ROOT).startsWith("<location[") && text.endsWith("]>")) text = text.substring(10, text.length() - 2);
        String[] parts = text.split(",", -1);
        if (parts.length < 3 || parts.length > 6) return Optional.empty();
        double[] coordinates = new double[3];
        for (int index = 0; index < 3; index++) {
            if (!parts[index].matches("-?[0-9]{1,8}(?:\\.[0-9]{1,6})?")) return Optional.empty();
            coordinates[index] = Double.parseDouble(parts[index]);
        }
        return Optional.of(coordinates);
    }

    private static Converted look(Command command) {
        List<Arg> plain = command.plain();
        if (plain.size() != command.args().size()) return Converted.kept("look argument has no Animus equivalent");
        if (plain.size() == 2 && npcTarget(plain.getFirst().value())) plain = plain.subList(1, 2);
        if (plain.size() != 1) return Converted.kept("look at more than one target has no Animus equivalent");
        String target = plain.getFirst().value().toLowerCase(Locale.ROOT);
        JsonObject arguments = new JsonObject();
        if (target.equals("<player>") || target.equals("<player.location>") || target.equals("<player.eye_location>")) arguments.addProperty("target", "player");
        else {
            Optional<double[]> at = location(plain.getFirst().value());
            if (at.isEmpty()) return Converted.kept("look target must be the player or a literal location");
            arguments.addProperty("x", at.get()[0]);
            arguments.addProperty("y", at.get()[1]);
            arguments.addProperty("z", at.get()[2]);
        }
        return Converted.of(action("animus:look", arguments));
    }

    private static Converted walk(Command command) {
        List<Arg> plain = command.plain();
        if (plain.size() != command.args().size()) return Converted.kept("walk argument has no Animus equivalent");
        if (plain.size() == 2 && npcTarget(plain.getFirst().value())) plain = plain.subList(1, 2);
        if (plain.size() != 1) return Converted.kept("walk needs one literal location");
        Optional<double[]> at = location(plain.getFirst().value());
        if (at.isEmpty()) return Converted.kept("walk needs a literal location");
        JsonObject arguments = new JsonObject();
        arguments.addProperty("x", at.get()[0]);
        arguments.addProperty("y", at.get()[1]);
        arguments.addProperty("z", at.get()[2]);
        return Converted.of(action("animus:navigate", arguments));
    }

    private Converted equip(Command command) {
        TreeMap<Integer, JsonObject> slots = new TreeMap<>();
        for (Arg arg : command.args()) {
            if (arg.prefix().isEmpty()) {
                if (npcTarget(arg.value())) continue;
                return Converted.kept("equipping another entity has no Animus equivalent");
            }
            int order;
            String slot;
            switch (arg.prefix()) {
                case "hand" -> { order = 0; slot = "main_hand"; }
                case "offhand" -> { order = 1; slot = "off_hand"; }
                case "head" -> { order = 2; slot = "head"; }
                case "chest" -> { order = 3; slot = "chest"; }
                case "legs" -> { order = 4; slot = "legs"; }
                case "boots" -> { order = 5; slot = "feet"; }
                default -> { return Converted.kept("equipment slot " + arg.prefix() + " has no Animus equivalent"); }
            }
            JsonObject arguments = new JsonObject();
            arguments.addProperty("slot", slot);
            if (!arg.value().equalsIgnoreCase("air")) {
                Optional<String> item = material(arg.value());
                if (item.isEmpty()) return Converted.kept("only plain materials can be equipped");
                arguments.addProperty("item", item.get());
            }
            slots.put(order, action("animus:set-equipment", arguments));
        }
        if (slots.isEmpty()) return Converted.kept("nothing to equip");
        return new Converted(new ArrayList<>(slots.values()), "");
    }

    private Converted sound(Command command) {
        Optional<String> sound = Optional.empty();
        String audience = null;
        boolean custom = false;
        JsonObject arguments = new JsonObject();
        Double volume = null, pitch = null;
        for (Arg arg : command.args()) {
            if (arg.is("custom")) { custom = true; continue; }
            switch (arg.prefix()) {
                case "sound" -> sound = Optional.of(arg.value());
                case "volume" -> { volume = number(arg.value(), 0, 4); if (volume == null) return Converted.kept("volume must be a number from 0 to 4"); }
                case "pitch" -> { pitch = number(arg.value(), 0.5, 2); if (pitch == null) return Converted.kept("pitch must be a number from 0.5 to 2"); }
                case "" -> {
                    String target = arg.value().toLowerCase(Locale.ROOT);
                    if (audience != null) return Converted.kept("sound for several targets has no Animus equivalent");
                    if (target.equals("<player>") || target.equals("<player.location>")) audience = "trigger";
                    else if (target.equals("<npc>") || target.equals("<npc.location>")) audience = "nearby";
                    else if (sound.isEmpty() && !target.startsWith("<")) sound = Optional.of(arg.value());
                    else return Converted.kept("sound location must be the NPC or the player");
                }
                default -> { return Converted.kept("playsound argument " + arg.prefix() + ": has no Animus equivalent"); }
            }
        }
        if (sound.isEmpty() || sound.get().contains("<")) return Converted.kept("playsound needs a literal sound");
        Optional<String> key = soundKey(sound.get(), custom);
        if (key.isEmpty()) return Converted.kept("sound " + sound.get() + " has no resource key");
        sounds.put(sound.get(), key.get());
        arguments.addProperty("sound", key.get());
        if (volume != null) arguments.addProperty("volume", volume);
        if (pitch != null) arguments.addProperty("pitch", pitch);
        if ("nearby".equals(audience)) arguments.addProperty("audience", "nearby");
        return Converted.of(action("animus:play-sound", arguments));
    }

    private static Double number(String value, double minimum, double maximum) {
        if (!value.matches("[0-9]{1,4}(?:\\.[0-9]{1,6})?")) return null;
        double number = Double.parseDouble(value);
        return number < minimum || number > maximum ? null : number;
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

    private static final Map<String, String> PARTICLE_NAMES = Map.ofEntries(
            Map.entry("villager_happy", "happy_villager"), Map.entry("villager_angry", "angry_villager"), Map.entry("spell_witch", "witch"),
            Map.entry("smoke_normal", "smoke"), Map.entry("smoke_large", "large_smoke"), Map.entry("explosion_normal", "poof"),
            Map.entry("explosion_large", "explosion"), Map.entry("explosion_huge", "explosion_emitter"), Map.entry("water_splash", "splash"),
            Map.entry("water_bubble", "bubble"), Map.entry("water_wake", "fishing"), Map.entry("suspended", "underwater"),
            Map.entry("suspended_depth", "underwater"), Map.entry("crit_magic", "enchanted_hit"), Map.entry("spell", "effect"),
            Map.entry("spell_instant", "instant_effect"), Map.entry("drip_water", "dripping_water"), Map.entry("drip_lava", "dripping_lava"),
            Map.entry("enchantment_table", "enchant"), Map.entry("town_aura", "mycelium"), Map.entry("snowball", "item_snowball"),
            Map.entry("snow_shovel", "item_snowball"), Map.entry("slime", "item_slime"), Map.entry("fireworks_spark", "firework"),
            Map.entry("totem", "totem_of_undying"), Map.entry("mob_appearance", "elder_guardian"), Map.entry("water_drop", "rain"));
    private static final Set<String> DATA_PARTICLES = Set.of("dust", "redstone", "block", "block_crack", "block_dust", "item", "item_crack", "falling_dust", "dust_color_transition",
            "vibration", "entity_effect", "spell_mob", "spell_mob_ambient", "block_marker", "sculk_charge", "shriek", "dust_pillar", "trail", "legacy_block_marker");

    private static Converted particle(Command command) {
        Optional<String> effect = Optional.empty();
        String audience = "nearby";
        JsonObject arguments = new JsonObject();
        Long count = null;
        Double spread = null;
        for (Arg arg : command.args()) {
            switch (arg.prefix()) {
                case "effect" -> effect = Optional.of(arg.value().toLowerCase(Locale.ROOT));
                case "at" -> {
                    String at = arg.value().toLowerCase(Locale.ROOT);
                    if (!at.equals("<npc>") && !at.equals("<npc.location>") && !at.equals("<npc.eye_location>") && !at.matches("<npc\\.location\\.above(\\[[0-9.]{1,6}])?>"))
                        return Converted.kept("particles show only at the NPC in Animus");
                }
                case "quantity" -> {
                    if (!arg.value().matches("[0-9]{1,2}") || Long.parseLong(arg.value()) < 1 || Long.parseLong(arg.value()) > 64) return Converted.kept("quantity must be from 1 to 64");
                    count = Long.parseLong(arg.value());
                }
                case "offset" -> {
                    double widest = 0;
                    for (String part : arg.value().split(",", -1)) {
                        Double value = number(part, 0, 8);
                        if (value == null) return Converted.kept("offset must be from 0 to 8 blocks");
                        widest = Math.max(widest, value);
                    }
                    spread = widest;
                }
                case "targets" -> { if (!playerTarget(arg.value())) return Converted.kept("particles for other targets have no Animus equivalent"); audience = "trigger"; }
                case "visibility", "data" -> { /* range and particle speed are not carried; the docs list this */ }
                default -> { return Converted.kept("playeffect argument " + (arg.prefix().isEmpty() ? arg.value() : arg.prefix() + ":") + " has no Animus equivalent"); }
            }
        }
        if (effect.isEmpty() || effect.get().contains("<")) return Converted.kept("playeffect needs a literal effect");
        String name = effect.get().startsWith("minecraft:") ? effect.get().substring(10) : effect.get();
        if (DATA_PARTICLES.contains(name)) return Converted.kept("particles that need extra data have no Animus equivalent");
        name = PARTICLE_NAMES.getOrDefault(name, name);
        if (!name.matches("[a-z0-9_]{1,64}")) return Converted.kept("effect " + effect.get() + " has no particle key");
        arguments.addProperty("particle", "minecraft:" + name);
        if (count != null) arguments.addProperty("count", count);
        if (spread != null) arguments.addProperty("spread", spread);
        if (audience.equals("nearby")) arguments.addProperty("audience", "nearby");
        return Converted.of(action("animus:spawn-particle", arguments));
    }

    private Converted actionbar(Command command, Scope scope) {
        List<String> words = new ArrayList<>();
        for (Arg arg : command.args()) {
            if (arg.prefix().isEmpty()) { words.add(arg.value()); continue; }
            if (arg.prefix().equals("targets") && playerTarget(arg.value())) continue;
            return Converted.kept("actionbar argument " + arg.prefix() + ": has no Animus equivalent");
        }
        Mapped text = text(String.join(" ", words), scope, true, 0);
        if (text.reason() != null) return Converted.kept(text.reason());
        JsonObject arguments = new JsonObject();
        arguments.addProperty("command", "minecraft:title {player} actionbar " + component(text.value()));
        return Converted.of(action("animus:command", arguments));
    }

    private Converted title(Command command, Scope scope) {
        String title = null, subtitle = null;
        List<Long> times = new ArrayList<>(List.of(10L, 70L, 20L));
        boolean timed = false;
        for (Arg arg : command.args()) {
            switch (arg.prefix()) {
                case "title" -> title = arg.value();
                case "subtitle" -> subtitle = arg.value();
                case "fade_in", "stay", "fade_out" -> {
                    OptionalLong ticks = DenizenArgs.ticks(arg.value());
                    if (ticks.isEmpty() || ticks.getAsLong() > 72_000) return Converted.kept("title timing needs a literal duration");
                    times.set(arg.prefix().equals("fade_in") ? 0 : arg.prefix().equals("stay") ? 1 : 2, ticks.getAsLong());
                    timed = true;
                }
                case "targets" -> { if (!playerTarget(arg.value())) return Converted.kept("titles for other targets have no Animus equivalent"); }
                default -> { return Converted.kept("title argument " + (arg.prefix().isEmpty() ? arg.value() : arg.prefix() + ":") + " has no Animus equivalent"); }
            }
        }
        if (title == null && subtitle == null) return Converted.kept("title without text");
        List<JsonObject> steps = new ArrayList<>();
        if (timed) steps.add(commandAction("minecraft:title {player} times " + times.get(0) + " " + times.get(1) + " " + times.get(2)));
        if (subtitle != null) {
            Mapped text = text(subtitle, scope, true, 0);
            if (text.reason() != null) return Converted.kept(text.reason());
            steps.add(commandAction("minecraft:title {player} subtitle " + component(text.value())));
        }
        Mapped main = text(title == null ? "" : title, scope, true, 0);
        if (main.reason() != null) return Converted.kept(main.reason());
        steps.add(commandAction("minecraft:title {player} title " + component(main.value())));
        for (JsonObject step : steps) if (step.getAsJsonObject("arguments").get("command").getAsString().length() > 1024) return Converted.kept("command longer than 1024 characters");
        return new Converted(steps, "");
    }

    private static JsonObject commandAction(String line) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("command", line);
        return action("animus:command", arguments);
    }

    /** A JSON text component; section colour codes inside it still colour the text. */
    private static String component(String text) {
        JsonObject component = new JsonObject();
        component.addProperty("text", text);
        return component.toString();
    }

    // ---- Text and tags ----

    private record Mapped(String value, String reason) {}

    private static final Map<String, String> ESCAPES = Map.ofEntries(
            Map.entry("&nl", "\n"), Map.entry("n", "\n"), Map.entry("&sp", " "), Map.entry("&co", ":"), Map.entry("&sc", ";"), Map.entry("&pipe", "|"),
            Map.entry("&lt", "<"), Map.entry("&gt", ">"), Map.entry("&amp", "&"), Map.entry("&lb", "["), Map.entry("&rb", "]"), Map.entry("&dq", "\""),
            Map.entry("&quo", "\""), Map.entry("&sq", "'"), Map.entry("&pc", "%"), Map.entry("&ns", "#"), Map.entry("&hash", "#"), Map.entry("&at", "@"),
            Map.entry("&dot", "."), Map.entry("&cm", ","), Map.entry("&exc", "!"), Map.entry("&ss", "§"), Map.entry("&bs", "\\"), Map.entry("&fs", "/"),
            Map.entry("&lc", "{"), Map.entry("&rc", "}"), Map.entry("&eq", "="), Map.entry("&nbsp", " "), Map.entry("&hrt", "❤"));
    private static final Map<String, String> COLORS = Map.ofEntries(
            Map.entry("black", "0"), Map.entry("dark_blue", "1"), Map.entry("dark_green", "2"), Map.entry("dark_aqua", "3"), Map.entry("dark_red", "4"),
            Map.entry("dark_purple", "5"), Map.entry("gold", "6"), Map.entry("gray", "7"), Map.entry("dark_gray", "8"), Map.entry("blue", "9"),
            Map.entry("green", "a"), Map.entry("aqua", "b"), Map.entry("red", "c"), Map.entry("light_purple", "d"), Map.entry("yellow", "e"),
            Map.entry("white", "f"), Map.entry("magic", "k"), Map.entry("bold", "l"), Map.entry("strikethrough", "m"), Map.entry("underline", "n"),
            Map.entry("italic", "o"), Map.entry("reset", "r"));

    /**
     * Text with its tags mapped: colour and escape tags become formatting codes ({@code &} for spoken text, section
     * signs inside commands), {@code <player.name>} becomes {@code {player_name}}, {@code <npc.name>} the NPC's name
     * when every NPC the behavior serves has the same one, and {@code <script.data_key[...]>} its literal value. Any
     * other tag keeps the whole line, named in the reason.
     */
    private Mapped text(String raw, Scope scope, boolean command, int depth) {
        StringBuilder out = new StringBuilder();
        for (DenizenArgs.Segment segment : DenizenArgs.segments(raw)) {
            if (!segment.tag()) { out.append(segment.text()); continue; }
            Tag tag = DenizenArgs.tag(segment.text());
            Optional<String> mapped = tag(tag, scope, command, depth);
            if (mapped.isEmpty()) return new Mapped(null, "tag " + tag.label() + " has no Animus placeholder");
            out.append(mapped.get());
        }
        return new Mapped(out.toString(), null);
    }

    private Optional<String> tag(Tag tag, Scope scope, boolean command, int depth) {
        List<DenizenArgs.Part> parts = tag.parts();
        String base = tag.base().toLowerCase(Locale.ROOT);
        String code = command ? "§" : "&";
        if (parts.size() == 1 && parts.getFirst().parameter().isEmpty()) {
            if (base.matches("&[0-9a-fk-or]")) return Optional.of(code + base.charAt(1));
            if (ESCAPES.containsKey(base)) return Optional.of(ESCAPES.get(base));
            if (COLORS.containsKey(base)) return Optional.of(code + COLORS.get(base));
        }
        if (parts.size() == 1 && parts.getFirst().parameter().isPresent()) {
            String parameter = parts.getFirst().parameter().get();
            if (base.equals("&color")) {
                if (parameter.matches("#[0-9a-fA-F]{6}")) {
                    if (!command) return Optional.of("&" + parameter);
                    StringBuilder hex = new StringBuilder("§x");
                    for (char digit : parameter.substring(1).toLowerCase(Locale.ROOT).toCharArray()) hex.append('§').append(digit);
                    return Optional.of(hex.toString());
                }
                if (COLORS.containsKey(parameter.toLowerCase(Locale.ROOT))) return Optional.of(code + COLORS.get(parameter.toLowerCase(Locale.ROOT)));
                return Optional.empty();
            }
            if (base.equals("&chr") && parameter.matches("[0-9a-fA-F]{1,6}")) {
                int point = Integer.parseInt(parameter, 16);
                if (point > 0x10FFFF || point >= 0xD800 && point <= 0xDFFF || point < 0x20) return Optional.empty();
                return Optional.of(new String(Character.toChars(point)));
            }
        }
        if (parts.size() == 2 && parts.getFirst().parameter().isEmpty() && parts.get(1).parameter().isEmpty()) {
            String attribute = parts.get(1).name().toLowerCase(Locale.ROOT);
            if (base.equals("player") && attribute.equals("name")) return Optional.of("{player_name}");
            if (base.equals("player") && attribute.equals("uuid") && command) return Optional.of("{player}");
            if (base.equals("npc") && attribute.equals("name") && scope.npcName.isPresent()) return Optional.of(scope.npcName.get());
        }
        if (base.equals("script") && depth < 4) {
            Container target = scope.container;
            int next = 1;
            if (parts.getFirst().parameter().isPresent()) {
                target = containers.get(parts.getFirst().parameter().get().toLowerCase(Locale.ROOT));
                if (target == null) return Optional.empty();
            }
            if (parts.size() != 2 || !List.of("data_key", "yaml_key").contains(parts.get(next).name().toLowerCase(Locale.ROOT)) || parts.get(next).parameter().isEmpty()) return Optional.empty();
            Node node = target.body();
            for (String key : parts.get(next).parameter().get().split("\\.", -1)) {
                if (!(node instanceof Mapping mapping)) return Optional.empty();
                Optional<Node> child = mapping.get(key.toLowerCase(Locale.ROOT));
                if (child.isEmpty()) return Optional.empty();
                node = child.get();
            }
            if (!(node instanceof Scalar scalar)) return Optional.empty();
            Mapped mapped = text(scalar.text(), new Scope(scope.routines, scope.calling, scope.npcName, false, target, scope.interact, scope.step), command, depth + 1);
            return mapped.reason() == null ? Optional.of(mapped.value()) : Optional.empty();
        }
        return Optional.empty();
    }

    // ---- Flag data ----

    /** Writes one subject's flags into staged state documents and returns how many were kept instead. */
    private int importFlags(String source, DenizenFlags.Values values, String scope, Optional<String> npc, Optional<String> player,
                            TreeMap<String, TreeMap<String, JsonObject>> state, TreeMap<String, Integer> imported) {
        int kept = 0;
        for (String problem : values.problems()) issues.add(source + ": " + problem);
        for (DenizenFlags.Flag flag : values.flags().values()) {
            if (flag.name().startsWith("__interact_step.") && player.isPresent()) { kept += interactStep(source, flag, player.get(), state, imported); continue; }
            String reason = null;
            Optional<String> name = flagName(flag.name());
            Kind kind = kindOf(flag.name());
            JsonObject value = new JsonObject();
            if (flag.name().startsWith("__")) reason = "Denizen's own bookkeeping flag";
            else if (flag.expires()) reason = "expiring flags have no Animus equivalent";
            else if (name.isEmpty()) reason = "the name cannot be an Animus flag name";
            else if (kind == Kind.BOOLEAN && (flag.value().equals("true") || flag.value().equals("false"))) { value.addProperty("type", "boolean"); value.addProperty("value", flag.value().equals("true")); }
            else if (kind == Kind.INTEGER && DenizenFlags.stage(flag.value()).isPresent()) { value.addProperty("type", "integer"); value.addProperty("value", DenizenFlags.stage(flag.value()).get()); }
            else reason = "the value is not true, false or a whole number from 0 to 10^12 used the same way everywhere";
            String path = null;
            if (reason == null) {
                path = statePath(scope, npc, player, kind == Kind.BOOLEAN ? "flags" : "quests");
                TreeMap<String, JsonObject> document = state.computeIfAbsent(path, (String ignored) -> new TreeMap<>());
                if (document.size() >= MAXIMUM_STATE_KEYS && !document.containsKey(name.get())) reason = "more than 128 flags for one subject";
                else document.put(name.get(), value);
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
            TreeMap<String, JsonObject> document = state.computeIfAbsent(statePath("relationship", Optional.of(npc.uuid()), Optional.of(player), "quests"), (String ignored) -> new TreeMap<>());
            JsonObject value = new JsonObject();
            value.addProperty("type", "integer");
            value.addProperty("value", (long) stage.get());
            document.put(interact.quest(), value);
            imported.merge("relationship", 1, Integer::sum);
        }
        return 0;
    }

    private static String statePath(String scope, Optional<String> npc, Optional<String> player, String kind) {
        StringBuilder path = new StringBuilder(STATE_ROOT).append(scope).append('/');
        npc.ifPresent((String uuid) -> path.append(uuid).append('/'));
        player.ifPresent((String uuid) -> path.append(uuid).append('/'));
        return path.append("animus/").append(kind).append('/').append(NAMESPACE).append(".json").toString();
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
            for (Tag tag : DenizenArgs.tags(outcome.text())) counts.computeIfAbsent(tag.label(), (String ignored) -> new int[2])[outcome.converted() ? 0 : 1]++;
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
