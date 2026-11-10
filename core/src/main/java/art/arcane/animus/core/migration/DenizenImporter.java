package art.arcane.animus.core.migration;

import art.arcane.animus.core.behavior.catalog.ServerBehaviorList;
import art.arcane.animus.core.npc.DefinitionBatch;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Stages and applies a Denizen import. The preview goes to {@code imports/denizen/<import-id>/}: generated behaviors
 * under {@code behaviors/denizen/}, chest GUIs under {@code guis/denizen/} (installed in {@code gui/inventories/denizen/}),
 * imported flag state under {@code state/behavior-state/}, and {@code report.json}
 * with the NPC bindings. Applying installs the behaviors and the state, lists the bound behaviors on their NPCs and adds
 * the server behaviors to {@code server-behaviors.json}. Rolling back the transaction removes all of it.
 */
public final class DenizenImporter {
    public static final String IMPORTS = "imports/denizen";
    static final long MAXIMUM_FILE_BYTES = 1 << 20, MAXIMUM_TOTAL_BYTES = 32L << 20;
    private static final String STAGED_PATH = "(?:behaviors/denizen/[a-z0-9][a-z0-9_/-]*|guis/denizen/[a-z0-9][a-z0-9_/-]*|state/behavior-state/[a-z0-9_/-]+/animus/(?:flags|quests)/denizen)\\.json";
    /** Staged chest GUIs install into the chest GUI folder under the {@code denizen} namespace. */
    static final String STAGED_GUIS = "guis/denizen/", INSTALLED_GUIS = "gui/inventories/denizen/";

    /** A staged preview and its report. */
    public record Preview(String id, Path directory, JsonObject report) {
        public Preview { report = report.deepCopy(); }
        @Override public JsonObject report() { return report.deepCopy(); }
        public int behaviors() { return report.get("behaviors").getAsInt(); }
        public int bindings() { return report.getAsJsonObject("bindings").size(); }
        public int flagsImported() {
            int total = 0;
            for (Map.Entry<String, JsonElement> count : report.getAsJsonObject("flags").getAsJsonObject("imported").entrySet()) total += count.getValue().getAsInt();
            return total;
        }
        public int flagsPreserved() { return report.getAsJsonObject("flags").get("preserved").getAsInt(); }
        public int preserved() { return report.getAsJsonArray("preserved").size(); }
        /** Behaviors with a guard or stop that did not convert; applying leaves them unbound and off. */
        public int held() { return report.has("heldBehaviors") && report.get("heldBehaviors").isJsonObject() ? report.getAsJsonObject("heldBehaviors").size() : 0; }
    }

    public record Applied(String transaction, int behaviors, int states, int bound, int servers, List<String> problems) {
        public Applied { problems = List.copyOf(problems); }
    }

    /**
     * Reads a Denizen data folder: every {@code .dsc} and {@code .yml} under {@code scripts/}, {@code server_flags.dat},
     * {@code player_flags/*.dat} and {@code notables.yml}. Symbolic links are skipped; files must be UTF-8.
     */
    static DenizenConverter.Input read(Path folder, Optional<Path> citizens) throws IOException {
        TreeMap<String, String> scripts = new TreeMap<>();
        long[] total = {0};
        Path root = folder.resolve("scripts");
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> paths = Files.walk(root, 8)) {
                for (Path path : paths.sorted().toList()) {
                    String name = path.getFileName().toString();
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !(name.endsWith(".dsc") || name.endsWith(".yml"))) continue;
                    if (scripts.size() >= DenizenConverter.MAXIMUM_FILES) throw new IOException("More than " + DenizenConverter.MAXIMUM_FILES + " Denizen script files");
                    scripts.put(root.relativize(path).toString().replace('\\', '/'), text(path, total));
                }
            }
        }
        Path server = folder.resolve("server_flags.dat");
        Optional<String> serverFlags = Files.isRegularFile(server, LinkOption.NOFOLLOW_LINKS) ? Optional.of(text(server, total)) : Optional.empty();
        TreeMap<String, String> players = new TreeMap<>();
        Path playerFolder = folder.resolve("player_flags");
        if (Files.isDirectory(playerFolder, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> paths = Files.list(playerFolder)) {
                for (Path path : paths.sorted().toList()) {
                    String name = path.getFileName().toString();
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !name.endsWith(".dat")) continue;
                    if (players.size() >= DenizenConverter.MAXIMUM_PLAYERS) throw new IOException("More than " + DenizenConverter.MAXIMUM_PLAYERS + " Denizen player flag files");
                    players.put(name.substring(0, name.length() - 4), text(path, total));
                }
            }
        }
        Path notes = folder.resolve("notables.yml");
        Optional<String> notables = Files.isRegularFile(notes, LinkOption.NOFOLLOW_LINKS) ? Optional.of(text(notes, total)) : Optional.empty();
        Optional<String> save = citizens.isPresent() ? Optional.of(text(citizens.get(), new long[] {0})) : Optional.empty();
        return new DenizenConverter.Input(scripts, save, serverFlags, players, notables);
    }

    private static String text(Path path, long[] total) throws IOException {
        long size = Files.size(path);
        if (size > MAXIMUM_FILE_BYTES && !path.getFileName().toString().equals("saves.yml")) throw new IOException(path + " is larger than 1 MiB");
        total[0] += size;
        if (total[0] > MAXIMUM_TOTAL_BYTES) throw new IOException("Denizen files exceed 32 MiB together");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString();
        } catch (CharacterCodingException invalid) { throw new IOException(path + " is not UTF-8 text", invalid); }
    }

    public Preview preview(Path folder, Optional<Path> citizens, DocumentStore store) throws IOException {
        DenizenConverter.Output output = DenizenConverter.convert(read(folder, citizens));
        Path imports = Files.createDirectories(store.resolve(IMPORTS));
        Path staged = store.resolve(IMPORTS + "/" + output.id());
        if (Files.exists(staged)) return read(staged, output.id());
        Path temporary = Files.createTempDirectory(imports, ".preview-");
        boolean published = false;
        try {
            for (Map.Entry<String, String> file : output.files().entrySet()) DocumentStore.atomic(temporary.resolve(file.getKey()), file.getValue());
            Files.move(temporary, staged, StandardCopyOption.ATOMIC_MOVE);
            published = true;
            return new Preview(output.id(), staged, output.report());
        } finally {
            if (!published) CitizensImporter.deleteTemporary(temporary);
        }
    }

    static Preview read(Path directory, String id) throws IOException {
        try {
            JsonObject report = JsonDocuments.object(JsonDocuments.read(directory.resolve("report.json")));
            if (report.get("schemaVersion").getAsInt() != 1 || report.get("converterVersion").getAsInt() != DenizenConverter.CONVERTER_VERSION
                    || !report.get("importId").getAsString().equals(id) || !report.get("documents").isJsonObject() || !report.get("bindings").isJsonObject())
                throw new IOException("Invalid Denizen import report: " + directory.resolve("report.json"));
            return new Preview(id, directory, report);
        } catch (RuntimeException invalid) { throw new IOException("Invalid Denizen import report: " + directory.resolve("report.json"), invalid); }
    }

    /**
     * Installs the staged behaviors and state and records each binding on its installed NPC, in one transaction. A
     * behavior with other content, or behavior state already stored for the {@code denizen} flags or quests of a
     * subject, stops the whole apply. A bound NPC that is not installed is listed and skipped.
     */
    public Applied apply(DocumentStore store, String id, NpcRepository.Installed installed) throws IOException {
        if (!id.matches("[a-f0-9]{64}")) throw new IOException("Invalid import ID");
        if (store.read(IMPORTS + "/" + id + "/receipt.json") != null) throw new IOException("This Denizen import is already applied");
        return apply(store, id, installed, UUID.randomUUID().toString());
    }

    /**
     * {@link #apply} under {@code transaction}. When the import is already installed, returns what its receipt recorded,
     * with the transaction that installed it, so a retried apply records the real id.
     */
    public Applied apply(DocumentStore store, String id, NpcRepository.Installed installed, String transaction) throws IOException {
        if (!id.matches("[a-f0-9]{64}")) throw new IOException("Invalid import ID");
        Path staged = store.resolve(IMPORTS + "/" + id);
        Preview preview = read(staged, id);
        String receiptPath = IMPORTS + "/" + id + "/receipt.json";
        String existing = store.read(receiptPath);
        if (existing != null) {
            JsonObject recorded = JsonDocuments.object(existing);
            if (!recorded.has("transaction")) throw new IOException("This Denizen import was installed by an older Animus that did not record its transaction; run preview again");
            return new Applied(recorded.get("transaction").getAsString(), recorded.get("behaviors").getAsInt(), recorded.get("states").getAsInt(), recorded.get("bound").getAsInt(),
                    recorded.get("servers").getAsInt(), List.of());
        }
        List<DocumentStore.Change> changes = new ArrayList<>();
        int behaviors = 0, states = 0;
        for (Map.Entry<String, JsonElement> document : preview.report().getAsJsonObject("documents").entrySet()) {
            String relative = document.getKey();
            if (!relative.matches(STAGED_PATH)) throw new IOException("Invalid bundle path: " + relative);
            String content = JsonDocuments.read(staged.resolve(relative));
            if (!JsonDocuments.hash(content).equals(document.getValue().getAsString())) throw new IOException("Preview changed: " + relative + "; make a new preview");
            String target = relative.startsWith(STAGED_GUIS) ? INSTALLED_GUIS + relative.substring(STAGED_GUIS.length()) : relative;
            String before = store.read(target);
            if (relative.startsWith("behaviors/") || relative.startsWith(STAGED_GUIS)) {
                behaviors++;
                if (before != null && before.equals(content)) continue;
                if (before != null) throw new IOException("Behavior collision: " + target + " already exists with other content");
            } else {
                states++;
                if (before != null) throw new IOException(stateConflict(relative));
            }
            changes.add(new DocumentStore.Change(target, "missing", content));
        }
        Map<String, NpcDefinition> byId = new HashMap<>();
        for (NpcDefinition definition : installed.definitions()) byId.put(definition.id(), definition);
        List<NpcDefinition> edited = new ArrayList<>();
        Map<String, String> expected = new HashMap<>();
        List<String> problems = new ArrayList<>();
        JsonObject uuids = preview.report().getAsJsonObject("npcUuids");
        for (Map.Entry<String, JsonElement> binding : preview.report().getAsJsonObject("bindings").entrySet()) {
            NpcDefinition target = byId.get(binding.getKey());
            if (target == null || !uuids.has(binding.getKey()) || !target.uuid().toString().equals(uuids.get(binding.getKey()).getAsString())) {
                problems.add(binding.getKey() + " is not installed; its Denizen behaviors are not bound");
                continue;
            }
            if (target.revision() == Long.MAX_VALUE) { problems.add(target.id() + ": revision exhausted"); continue; }
            JsonObject json = target.json();
            JsonArray listed = json.has("behaviors") && json.get("behaviors").isJsonArray() ? json.getAsJsonArray("behaviors") : new JsonArray();
            for (JsonElement behavior : binding.getValue().getAsJsonArray()) if (!listed.contains(behavior)) listed.add(behavior.deepCopy());
            json.add("behaviors", listed);
            json.addProperty("revision", target.revision() + 1);
            edited.add(NpcDefinition.parse(JsonDocuments.write(json)));
            expected.put(target.path(), installed.expectedHash(target));
        }
        List<String> servers = new ArrayList<>();
        for (JsonElement server : serverBehaviors(preview.report())) servers.add(server.getAsString());
        String before = store.read(ServerBehaviorList.PATH), after = ServerBehaviorList.append(before, servers);
        if (!after.equals(before)) changes.add(new DocumentStore.Change(ServerBehaviorList.PATH, before == null ? "missing" : store.hash(ServerBehaviorList.PATH), after));
        JsonObject receipt = new JsonObject();
        receipt.addProperty("importId", id);
        receipt.addProperty("behaviors", behaviors);
        receipt.addProperty("states", states);
        receipt.addProperty("bound", edited.size());
        receipt.addProperty("servers", servers.size());
        receipt.addProperty("transaction", transaction);
        changes.add(new DocumentStore.Change(receiptPath, "missing", JsonDocuments.write(receipt)));
        return new Applied(DefinitionBatch.commit(store, transaction, edited, expected, changes), behaviors, states, edited.size(), servers.size(), problems);
    }

    /** The commands that delete the {@code denizen} flags and quests an apply would otherwise overwrite. */
    public static List<String> stateDeleteCommands() {
        return List.of("/animus behaviors state delete behavior animus:flags/denizen", "/animus behaviors state delete behavior animus:quests/denizen");
    }

    /** Why an apply stops at state already stored for a subject, and the commands that clear it. */
    public static String stateConflict(String relative) {
        return "Behavior state already stored at " + relative + "; delete the denizen flags and quests first with " + String.join(" and ", stateDeleteCommands());
    }

    /** The report's server behaviors, which applying enables; empty for reports without the field. */
    static JsonArray serverBehaviors(JsonObject report) {
        JsonArray listed = new JsonArray();
        JsonElement field = report.get("serverBehaviors");
        if (field == null || !field.isJsonArray()) return listed;
        for (JsonElement id : field.getAsJsonArray()) {
            if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) continue;
            try { art.arcane.animus.core.behavior.catalog.BehaviorPath.forId(id.getAsString()); }
            catch (IllegalArgumentException invalid) { continue; }
            if (!listed.contains(id)) listed.add(id.deepCopy());
        }
        return listed;
    }
}
