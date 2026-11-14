package art.arcane.animus.core.migration;

import art.arcane.animus.core.combat.HealthSettings;
import art.arcane.animus.core.shop.ShopDocument;
import art.arcane.animus.core.npc.DefinitionBatch;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.DocsLink;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.storage.WriteNotices;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/**
 * The drop-in Citizens migration: find a Citizens save (and dtlTraders and Builder files beside it) with no path,
 * stage a reviewed application, apply it with everything it converted switched on, and roll the whole migration back.
 * Every step goes through {@link CitizensImporter}, {@link ImportApplications} and {@link ShopImporter}; this class
 * only finds files and keeps {@code imports/citizens/state.json}, the record of what the drop-in flow did.
 * Call it from the serial file lane.
 */
public final class CitizensDropIn {
    /** What the platform knows: its plugins folder, if it has one, and whether Citizens, Shopkeepers and Denizen are running. */
    public record Host(Optional<Path> pluginsDirectory, BooleanSupplier citizensEnabled, BooleanSupplier shopkeepersEnabled, BooleanSupplier denizenEnabled) {
        public static final Host NONE = new Host(Optional.empty(), () -> false, () -> false, () -> false);
    }
    /** A file or folder the flow found. {@code label} is how it is shown to the operator. */
    public record Found(Path path, String label) {}
    public record Sources(Optional<Found> saves, Optional<Found> traders, List<Found> schematics) {}
    public static final String STATE = "imports/citizens/state.json";
    private static final String ALLOWED_NAMESPACES = "commandNamespacesAdded", COMMAND_NAMESPACES = "commandNamespaces", APPLYING = "applying", COPIED = "copiedSchematics";
    static final String CITIZENS_RUNNING = "Citizens is still enabled, so imported NPCs stay off to avoid running twice; remove it from plugins/, restart and run this again";
    static final String INTERRUPTED = "an apply was interrupted; /animus import citizens apply finishes it, /animus import citizens rollback undoes it";
    private static final Set<String> DENIZEN = Set.of("assignment", "triggers", "constants", "denizen_flags", "denizenflags");

    private final DocumentStore store;
    private final NpcRepository npcs;
    private final Host host;
    private final ImportJournal journal;
    private final List<String> committed = new ArrayList<>();
    private boolean rolledBack, settingsChanged;

    /** {@code [combat] maximumNpcs}, so apply and review can say when more guards are imported than may fight at once; 0 when unknown. */
    private int combatLimit;

    public CitizensDropIn(DocumentStore store, NpcRepository npcs, Host host) { this.store = store; this.npcs = npcs; this.host = host; journal = new ImportJournal(store, STATE); }

    /** Names the server's combat limit for apply and review. */
    public CitizensDropIn combatLimit(int limit) { combatLimit = limit; return this; }

    /** The Citizens save first found among imports/saves.yml, imports/inbox/saves.yml and a left-over plugins/Citizens/saves.yml. */
    public Sources find() throws IOException {
        List<Found> saves = saveCandidates();
        List<Found> traders = new ArrayList<>(List.of(new Found(store.resolve("imports/dtltraders"), "imports/dtltraders"),
                new Found(store.resolve("imports/inbox/dtltraders"), "imports/inbox/dtltraders")));
        List<Found> schematics = new ArrayList<>(List.of(new Found(store.resolve("imports/schematics"), "imports/schematics")));
        if (host.pluginsDirectory().isPresent()) {
            Path plugins = host.pluginsDirectory().get();
            traders.add(new Found(plugins.resolve("dtlTraders"), "plugins/dtlTraders"));
            schematics.add(new Found(plugins.resolve("Builder/schematics"), "plugins/Builder/schematics"));
            schematics.add(new Found(plugins.resolve("Constructor/schematics"), "plugins/Constructor/schematics"));
        }
        Optional<Found> save = saves.stream().filter((Found found) -> Files.isRegularFile(found.path(), LinkOption.NOFOLLOW_LINKS)).findFirst();
        Optional<Found> trader = traders.stream().filter((Found found) -> Files.isDirectory(found.path(), LinkOption.NOFOLLOW_LINKS)).findFirst();
        List<Found> folders = schematics.stream().filter((Found found) -> Files.isDirectory(found.path(), LinkOption.NOFOLLOW_LINKS)).toList();
        return new Sources(save, trader, folders);
    }

    /** Where a Citizens save may be, in the order {@link #find} looks. */
    private List<Found> saveCandidates() throws IOException {
        List<Found> saves = new ArrayList<>(List.of(new Found(store.resolve("imports/saves.yml"), "imports/saves.yml"),
                new Found(store.resolve("imports/inbox/saves.yml"), "imports/inbox/saves.yml")));
        host.pluginsDirectory().ifPresent((Path plugins) -> saves.add(new Found(plugins.resolve("Citizens/saves.yml"), "plugins/Citizens/saves.yml")));
        return saves;
    }

    /** Refuses to apply a staged preview whose save was saved again since; NPCs saved in between would be lost. A save removed since is fine. */
    private void unchangedSource(JsonObject state) throws IOException {
        String label = state.get("source").getAsString();
        for (Found candidate : saveCandidates()) {
            if (!candidate.label().equals(label) || !Files.isRegularFile(candidate.path(), LinkOption.NOFOLLOW_LINKS)) continue;
            if (!JsonDocuments.hash(Files.readString(candidate.path())).equals(state.get("sourceSha256").getAsString()))
                throw new IOException(label + " changed since the preview; run /animus import citizens preview again, then apply");
        }
    }

    /** One startup line, or nothing when there is no Citizens save or its import is applied. */
    public Optional<String> startupNotice() throws IOException {
        Sources sources = find();
        JsonObject state = state();
        boolean citizens = host.citizensEnabled().getAsBoolean();
        if (citizens && (sources.saves().isPresent() || applied(state))) return Optional.of("Citizens migration: " + CITIZENS_RUNNING);
        if (interrupted(state)) return Optional.of("Citizens migration: " + INTERRUPTED);
        if (DenizenDropIn.staged(state) && host.denizenEnabled().getAsBoolean()) return Optional.of("Denizen migration: " + DenizenDropIn.RUNNING);
        Optional<ShopkeepersDropIn.Found> shopkeepers = ShopkeepersDropIn.find(store, host.pluginsDirectory());
        if (shopkeepers.isPresent() && !ShopkeepersDropIn.applied(state)) {
            if (host.shopkeepersEnabled().getAsBoolean()) return Optional.of("Shopkeepers migration: " + ShopkeepersDropIn.RUNNING);
            if (sources.saves().isEmpty() || applied(state))
                return Optional.of("Shopkeepers migration: found " + shopkeepers.get().save().label() + "; /animus import citizens preview checks it, nothing installs until apply");
        }
        if (applied(state)) return Optional.empty();
        if (sources.saves().isEmpty()) return Optional.empty();
        List<String> found = new ArrayList<>();
        found.add(sources.saves().get().label());
        if (!ShopkeepersDropIn.applied(state)) shopkeepers.ifPresent((ShopkeepersDropIn.Found save) -> found.add(save.save().label()));
        sources.traders().ifPresent((Found traders) -> found.add(traders.label()));
        sources.schematics().forEach((Found folder) -> found.add(folder.label()));
        DenizenDropIn.find(store, host.pluginsDirectory()).ifPresent((Found denizen) -> found.add(denizen.label() + "/scripts"));
        String source = JsonDocuments.hash(Files.readString(sources.saves().get().path()));
        if (state.has("sourceSha256") && state.get("sourceSha256").getAsString().equals(source) && state.has("applicationId"))
            return Optional.of("Citizens migration: " + listed(found) + " staged, not applied; review " + state.get("report").getAsString() + ", then /animus import citizens apply");
        return Optional.of("Citizens migration: found " + listed(found) + "; /animus import citizens preview checks " + (found.size() == 1 ? "it" : "them") + ", nothing installs until apply");
    }

    /** How far the drop-in flow is, for {@code /animus setup} and the join hint. */
    public enum Phase { NONE, FOUND, STAGED, DONE }

    /** {@code source} is the first save found, as shown to the operator. */
    public record Progress(Phase phase, Optional<String> source) {}

    /** NONE without data; FOUND when a save waits for a preview; STAGED once previewed (and after a rollback); DONE once applied. */
    public Progress progress() throws IOException {
        JsonObject state = state();
        Optional<String> source = find().saves().map(Found::label);
        if (source.isEmpty()) source = ShopkeepersDropIn.find(store, host.pluginsDirectory()).map((ShopkeepersDropIn.Found found) -> found.save().label());
        if (applied(state) || ShopkeepersDropIn.applied(state)) return new Progress(Phase.DONE, source);
        if (state.has("applicationId") || ShopkeepersDropIn.staged(state)) return new Progress(Phase.STAGED, source);
        return new Progress(startupNotice().isPresent() ? Phase.FOUND : Phase.NONE, source);
    }

    /** {@code a}, {@code a and b}, {@code a, b and c}. */
    private static String listed(List<String> items) {
        if (items.size() == 1) return items.getFirst();
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.getLast();
    }

    /**
     * Previews the saves this flow finds and stages one application plan; {@code mode} is reject, replace or duplicate with a
     * copy label. {@code factorGroups} moves settings two or more imported NPCs share into trait groups.
     */
    public List<String> preview(String mode, Optional<String> label, Map<String, String> worlds, boolean factorGroups) throws IOException {
        if (!List.of("reject", "replace", "duplicate").contains(mode)) throw new IOException("mode is reject, replace or duplicate " + DocsLink.COLLISIONS.url());
        if (mode.equals("duplicate") != label.isPresent()) throw new IOException(mode.equals("duplicate") ? "mode=duplicate needs a copy label, such as copy=town-copy" : "copy= is used only with mode=duplicate");
        return stage(mode, label.orElse(null), new TreeMap<>(worlds), factorGroups);
    }

    /** Installs the staged preview with everything it converted switched on. */
    public List<String> applyStaged() throws IOException { return recorded(this::apply); }

    /**
     * The namespaces the staged import's command behaviors need in {@code [behaviors.actions] commandNamespaces} that the
     * config does not allow yet. Apply adds them, so an in-game apply that would needs the commands permission.
     */
    public List<String> commandGrants() throws IOException {
        JsonObject state = state();
        if (!state.has(COMMAND_NAMESPACES) || applied(state)) return List.of();
        List<String> missing = new ArrayList<>();
        for (JsonElement namespace : state.getAsJsonArray(COMMAND_NAMESPACES)) missing.add(namespace.getAsString());
        missing.removeAll(CommandAllowList.allowed(CommandAllowList.read(store)));
        return missing;
    }

    public List<String> reviewApplied() throws IOException { return review(); }

    /** Every document the next apply writes, by data folder path, read from the staged previews; see {@link StagedWrites}. */
    public Map<String, String> stagedWrites() throws IOException { return StagedWrites.of(store, state()); }

    /** The Denizen behaviors held because a guard did not convert, with those lines; none is bound or enabled until released. */
    public List<String> heldDenizen() throws IOException {
        List<DenizenDropIn.Held> held = DenizenDropIn.held(store, state());
        if (held.isEmpty()) return List.of("No Denizen behavior is held");
        List<String> lines = new ArrayList<>();
        for (DenizenDropIn.Held behavior : held) {
            String targets = behavior.server() ? "server behavior" : behavior.npcs().isEmpty() ? "on no NPC" : "for " + String.join(", ", behavior.npcs());
            lines.add(behavior.id() + (behavior.released() ? " (released)" : "") + ": " + targets + (behavior.commands() ? ", runs commands" : ""));
            behavior.lines().stream().limit(8).forEach((String line) -> lines.add("  " + line));
            if (behavior.lines().size() > 8) lines.add("  " + (behavior.lines().size() - 8) + " more lines in the report");
        }
        lines.add("Next: read the behavior, then /animus import denizen release <behavior>");
        return lines;
    }

    /** Whether the held Denizen behavior runs console commands, which needs {@code animus.editor.commands} to release in game. */
    public boolean denizenRunsCommands(String behavior) throws IOException {
        return DenizenDropIn.held(store, state()).stream().anyMatch((DenizenDropIn.Held held) -> held.id().equals(behavior) && held.commands());
    }

    /**
     * Binds a held Denizen behavior to the NPCs it was made for and enables it as a server behavior when it is one, in
     * one transaction that the import's rollback also undoes. Without {@code confirmed} it only says what it would do.
     */
    public List<String> releaseDenizen(String behavior, boolean confirmed) throws IOException { return recorded(() -> release(behavior, confirmed)); }

    private List<String> release(String behavior, boolean confirmed) throws IOException {
        if (host.denizenEnabled().getAsBoolean()) throw new IOException(DenizenDropIn.RUNNING);
        JsonObject state = state();
        DenizenDropIn.Held held = DenizenDropIn.held(store, state).stream().filter((DenizenDropIn.Held candidate) -> candidate.id().equals(behavior)).findFirst()
                .orElseThrow(() -> new IOException(behavior + " is not a held Denizen behavior; /animus import denizen held lists them"));
        if (held.released()) return List.of(behavior + " is already released");
        if (!held.server() && held.npcs().isEmpty()) throw new IOException(behavior + " has no NPC or server list to join; list it where it is needed yourself");
        if (!confirmed) {
            List<String> lines = new ArrayList<>(List.of(behavior + " was held because these lines did not convert; releasing runs it as converted:"));
            held.lines().stream().limit(8).forEach((String line) -> lines.add("  " + line));
            lines.add("Read behaviors/" + behavior.replace(':', '/') + ".json first, then /animus import denizen release " + behavior + " confirm");
            return lines;
        }
        List<NpcDefinition> changed = new ArrayList<>();
        Map<String, String> expected = new LinkedHashMap<>();
        NpcRepository.Installed installed = npcs.installed();
        for (NpcDefinition definition : imported(state, installed.definitions())) {
            if (!held.npcs().contains(definition.id())) continue;
            JsonObject json = definition.json();
            JsonArray listed = json.has("behaviors") && json.get("behaviors").isJsonArray() ? json.getAsJsonArray("behaviors") : new JsonArray();
            if (listed.contains(new JsonPrimitive(behavior))) continue;
            if (definition.revision() == Long.MAX_VALUE) throw new IOException(definition.id() + ": revision exhausted");
            listed.add(behavior);
            json.add("behaviors", listed);
            json.addProperty("revision", definition.revision() + 1);
            changed.add(NpcDefinition.parse(JsonDocuments.write(json)));
            expected.put(definition.path(), installed.expectedHash(definition));
        }
        List<DocumentStore.Change> others = new ArrayList<>();
        if (held.server()) {
            String before = store.read(art.arcane.animus.core.behavior.catalog.ServerBehaviorList.PATH);
            String after = art.arcane.animus.core.behavior.catalog.ServerBehaviorList.append(before, List.of(behavior));
            if (!after.equals(before)) others.add(new DocumentStore.Change(art.arcane.animus.core.behavior.catalog.ServerBehaviorList.PATH,
                    before == null ? "missing" : store.hash(art.arcane.animus.core.behavior.catalog.ServerBehaviorList.PATH), after));
        }
        DenizenDropIn.released(state, behavior);
        String transaction = journal.commit(state, (String id) -> DefinitionBatch.commit(store, id, changed, expected, others));
        List<String> errors = npcs.reload();
        List<String> lines = new ArrayList<>(List.of(Palette.success("Released " + behavior + " on " + changed.size() + " NPCs"
                + (others.isEmpty() ? "" : " and enabled it in server-behaviors.json") + "; tx " + transaction)));
        if (!errors.isEmpty()) lines.add(problems(errors));
        if (!others.isEmpty()) lines.add("It starts once the behaviors reload");
        lines.add("/animus import citizens rollback undoes the import and every release");
        return lines;
    }

    public List<String> rollbackApplied() throws IOException { return rollback(); }

    /**
     * The transactions the last step committed, oldest first, even when it then failed. After a rollback these are
     * the inverse transactions. The caller reloads the catalogs they touched.
     */
    public List<String> committed() { return List.copyOf(committed); }

    /** Whether {@link #committed()} restored older revisions on purpose. */
    public boolean rolledBack() { return rolledBack; }
    /** True when the last {@link #execute} rewrote config.toml; the caller applies it. */
    public boolean settingsChanged() { return settingsChanged; }

    @FunctionalInterface private interface Step { List<String> run() throws IOException; }

    private List<String> recorded(Step step) throws IOException {
        Set<String> before = transactionIds();
        try { return step.run(); }
        finally { for (String transaction : transactionIds()) if (!before.contains(transaction)) committed.add(transaction); }
    }

    private Set<String> transactionIds() {
        try { return ids(state()); }
        catch (IOException | RuntimeException unreadable) { return new java.util.LinkedHashSet<>(); }
    }

    private static Set<String> ids(JsonObject state) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        if (state.has("transactions")) for (JsonElement value : state.getAsJsonArray("transactions")) ids.add(value.getAsString());
        return ids;
    }

    /** Preview the save, the dtlTraders folder and the schematics, and stage one application plan. Nothing is installed. */
    List<String> stage(String mode, String label, Map<String, String> worlds, boolean factorGroups) throws IOException {
        JsonObject previous = state();
        if (interrupted(previous)) throw new IOException("Not staged: " + INTERRUPTED + ", then preview again");
        Sources sources = find();
        Optional<ShopkeepersDropIn.Found> shopkeepers = ShopkeepersDropIn.find(store, host.pluginsDirectory());
        boolean shopkeepersApplied = ShopkeepersDropIn.applied(previous);
        if (applied(previous) || sources.saves().isEmpty()) {
            if (shopkeepers.isEmpty() || shopkeepersApplied) {
                if (applied(previous) || shopkeepersApplied) throw new IOException("A Citizens import is already applied; /animus import citizens rollback before staging another");
                throw new IOException("No Citizens save found and no Shopkeepers save; put saves.yml in imports/ or the Shopkeepers save.yml in imports/shopkeepers/ " + DocsLink.CITIZENS.url());
            }
            JsonObject state = applied(previous) ? previous : new JsonObject();
            state.addProperty("schemaVersion", 1);
            List<String> lines = new ArrayList<>(ShopkeepersDropIn.stage(store, shopkeepers.get(), sources.saves().map(Found::path), state));
            lines.add(applied(previous) ? "Shops bind to the Citizens NPCs already installed; nothing installed yet" : "No Citizens save; Citizens shopkeepers bind once a Citizens import is applied. Nothing installed yet");
            lines.add(host.shopkeepersEnabled().getAsBoolean() ? ShopkeepersDropIn.RUNNING : "Next: /animus import citizens apply");
            writeState(state);
            return lines;
        }
        Found save = sources.saves().get();
        StableSource.Read source = StableSource.read(save.path(), save.label());
        CitizensImporter.Preview preview = new CitizensImporter().disguises(LibsDisguisesSaves.read(store, host.pluginsDirectory())).preview(save.path(), store, worlds, factorGroups);
        if (!preview.summary().get("sourceSha256").getAsString().equals(source.sha256()))
            throw new IOException(save.label() + " changed while the preview read it; if Citizens is saving it, wait and run preview again");
        if (preview.valid() != preview.records())
            throw new IOException(preview.records() - preview.valid() + " of " + preview.records() + " records do not convert; see imports/" + preview.id() + "/report.json");
        ImportApplications.Preview application = ImportApplications.stage(store, preview.id(), mode, label);
        JsonObject state = new JsonObject();
        state.addProperty("schemaVersion", 1);
        state.addProperty("source", save.label());
        state.addProperty("sourceSha256", preview.summary().get("sourceSha256").getAsString());
        state.addProperty("importId", preview.id());
        state.addProperty("applicationId", application.applicationId());
        state.addProperty("mode", application.mode());
        if (label != null) state.addProperty("copyLabel", label);
        state.add("worldMap", preview.summary().get("worldMap"));
        state.addProperty("report", "imports/" + preview.id() + "/report.json");
        List<String> lines = new ArrayList<>();
        JsonObject conversions = preview.summary().has("conversions") ? preview.summary().getAsJsonObject("conversions") : new JsonObject();
        int skinsKept = conversions.has("skinsKept") ? conversions.get("skinsKept").getAsInt() : 0;
        lines.add("Citizens save " + save.label() + ": " + preview.records() + " NPCs, " + preview.valid() + " valid, " + preview.skins() + " unique skins"
                + (skinsKept > 0 ? ", " + skinsKept + " skins on non-player NPCs unused" : ""));
        lines.add("Folders: " + groups(preview.summary()));
        lines.addAll(summaryLines(preview));
        lines.add("Application " + application.mode() + ": " + application.records() + " NPCs, " + application.replacements() + " replacements");
        if (sources.traders().isPresent()) {
            ShopImporter.Preview shops = new ShopImporter().preview(sources.traders().get().path(), Optional.of(save.path()), store);
            state.addProperty("shopImportId", shops.id());
            state.addProperty("shopSource", sources.traders().get().label());
            lines.add("dtlTraders " + sources.traders().get().label() + ": " + shops.shops() + " shops, " + shops.entries() + " entries, " + shops.issues() + " issues; report imports/shops/" + shops.id() + "/report.json");
        }
        JsonArray schematics = new JsonArray();
        int legacy = 0;
        for (Found folder : sources.schematics()) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(folder.path())) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (name.endsWith(".schematic")) { legacy++; continue; }
                    if (!name.matches("[A-Za-z0-9_.-]{1,64}\\.(?:schem|nbt)")) continue;
                    JsonObject entry = new JsonObject();
                    entry.addProperty("source", folder.path().resolve(name).toString());
                    entry.addProperty("name", name);
                    schematics.add(entry);
                }
            }
        }
        state.add("schematics", schematics);
        if (!sources.schematics().isEmpty())
            lines.add("Schematics: " + schematics.size() + " .schem/.nbt files copy on apply" + (legacy > 0 ? "; " + legacy + " old .schematic files need saving again as .schem" : ""));
        if (shopkeepersApplied) {
            state.add("shopkeepers", previous.get("shopkeepers").deepCopy());
            state.add("transactions", previous.get("transactions").deepCopy());
            lines.add("Shopkeepers shops already installed; they bind to their Citizens NPCs on apply");
        } else if (shopkeepers.isPresent()) lines.addAll(ShopkeepersDropIn.stage(store, shopkeepers.get(), Optional.of(save.path()), state));
        Optional<Found> denizen = DenizenDropIn.find(store, host.pluginsDirectory());
        if (denizen.isPresent()) lines.addAll(DenizenDropIn.stage(store, denizen.get(), save.path(), state));
        DenizenDropIn.reasons(store, preview.directory(), state);
        List<String> commands = new ArrayList<>(commandBehaviors(GroupFactoring.staged(preview)));
        commands.addAll(shopCommands(preview.directory().resolve(CitizensShops.FOLDER)));
        commands.addAll(DenizenDropIn.stagedCommandBehaviors(store, state));
        JsonArray namespaces = new JsonArray();
        CommandAllowList.namespaces(commands).forEach(namespaces::add);
        state.add(COMMAND_NAMESPACES, namespaces);
        if (!commands.isEmpty()) lines.add("Commands: " + commands.size() + " imported behaviors and shops run commands as they did before (such as " + commands.getFirst()
                + "); apply allows " + String.join(", ", CommandAllowList.namespaces(commands)) + " in [behaviors.actions] commandNamespaces");
        lines.add("Nothing installed; report imports/" + preview.id() + "/report.json");
        if (host.citizensEnabled().getAsBoolean()) lines.add(CITIZENS_RUNNING);
        else if (ShopkeepersDropIn.staged(state) && host.shopkeepersEnabled().getAsBoolean()) lines.add(ShopkeepersDropIn.RUNNING);
        else if (DenizenDropIn.staged(state) && host.denizenEnabled().getAsBoolean()) lines.add(DenizenDropIn.RUNNING);
        else lines.add("Next: /animus import citizens apply");
        writeState(state);
        return lines;
    }

    List<String> apply() throws IOException {
        JsonObject state = state();
        boolean resuming = interrupted(state);
        boolean citizensPending = state.has("applicationId") && !applied(state);
        boolean shopkeepersPending = ShopkeepersDropIn.staged(state);
        boolean denizenPending = DenizenDropIn.staged(state) && (citizensPending || applied(state));
        if (!citizensPending && !shopkeepersPending && !denizenPending) {
            if (resuming) { finished(state); return List.of("Nothing left to apply", "Next: /animus import citizens review"); }
            if (applied(state) || ShopkeepersDropIn.applied(state)) throw new IOException("Already applied; use /animus import citizens review or rollback");
            throw new IOException("Nothing staged; run /animus import citizens preview first");
        }
        if (citizensPending) refuseWhileCitizensRuns();
        if (shopkeepersPending && host.shopkeepersEnabled().getAsBoolean()) throw new IOException(ShopkeepersDropIn.RUNNING);
        if (denizenPending && host.denizenEnabled().getAsBoolean()) throw new IOException(DenizenDropIn.RUNNING);
        if (!resuming && citizensPending) unchangedSource(state);
        if (!resuming && shopkeepersPending) ShopkeepersDropIn.unchanged(store, host.pluginsDirectory(), state);
        Set<String> before = ids(state);
        // Marked before the first commit, so a flow cut off part way is finished or rolled back, never staged over.
        state.addProperty(APPLYING, true);
        writeState(state);
        try { return applySteps(state, citizensPending, shopkeepersPending, denizenPending); }
        catch (IOException | RuntimeException failure) {
            if (failure instanceof CitizensImporter.OutdatedPreview outdated && !resuming && ids(state).equals(before)) {
                finished(state);
                throw outdated.next("run /animus import citizens preview again, then /animus import citizens apply");
            }
            if (resuming || !ids(state).equals(before))
                throw new IOException("Apply stopped part way: " + failure.getMessage() + "; fix the cause and run /animus import citizens apply again to finish, or /animus import citizens rollback to undo it", failure);
            finished(state);
            throw failure;
        }
    }

    private List<String> applySteps(JsonObject state, boolean citizensPending, boolean shopkeepersPending, boolean denizenPending) throws IOException {
        List<String> lines = citizensPending ? applyCitizens(state) : new ArrayList<>();
        try {
            if (shopkeepersPending) lines.addAll(ShopkeepersDropIn.apply(store, state, npcs.installed(), journal));
            else lines.addAll(ShopkeepersDropIn.bindPending(store, state, npcs.installed(), journal));
        } catch (IOException | RuntimeException failure) {
            if (!citizensPending) throw failure;
            lines.add(Palette.error("Shopkeepers import failed, none of it installed: " + failure.getMessage() + "; the Citizens import stays, fix the cause and apply again"));
            finished(state);
            return lines;
        }
        if (denizenPending) {
            // The Denizen part edits the definitions the Shopkeepers part may just have bound, so it reads them again.
            try {
                npcs.reload();
                lines.addAll(DenizenDropIn.apply(store, state, npcs.installed(), journal));
            }
            catch (IOException | RuntimeException failure) {
                if (!citizensPending && !shopkeepersPending) throw failure;
                lines.add(Palette.error("Denizen import failed, none of it installed: " + failure.getMessage() + "; the other imports stay, fix the cause and apply again"));
                finished(state);
                return lines;
            }
        }
        allowCommands(state).ifPresent(lines::add);
        finished(state);
        List<String> errors = npcs.reload();
        if (!errors.isEmpty()) lines.add(problems(errors));
        int guards = guards(imported(state));
        if (combatLimit > 0 && guards > combatLimit)
            lines.add("Combat: " + guards + " guards imported, at most " + combatLimit + " fight or scan at once ([combat] maximumNpcs); see /animus combat status");
        lines.add("Next: /animus import citizens review lists what converted; /animus import citizens rollback undoes the import");
        return lines;
    }

    private List<String> applyCitizens(JsonObject state) throws IOException {
        String importId = state.get("importId").getAsString(), applicationId = state.get("applicationId").getAsString();
        String definitions = journal.commit(state, (String transaction) -> ImportApplications.apply(store, importId, applicationId, transaction));
        List<String> lines = new ArrayList<>();
        if (state.has("shopImportId")) {
            String shopImportId = state.get("shopImportId").getAsString();
            try {
                String shops = journal.commit(state, (String transaction) -> new ShopImporter().apply(store, shopImportId, transaction));
                lines.add("Shops installed; tx " + shops);
            } catch (IOException | RuntimeException failure) {
                try { journal.undo(state, definitions); }
                catch (IOException | RuntimeException undo) {
                    failure.addSuppressed(undo);
                    throw new IOException("Shop import failed and the NPC import could not be rolled back (" + undo.getMessage() + "): " + failure.getMessage(), failure);
                }
                npcs.reloadAfterRollback();
                throw new IOException("Shop import failed and the NPC import was rolled back: " + failure.getMessage(), failure);
            }
        }
        int copied = copySchematics(state);
        state.addProperty("state", "applied");
        writeState(state);
        List<String> errors = npcs.reload();
        lines.addFirst(Palette.success("Installed import " + state.get("importId").getAsString().substring(0, 12) + "; NPCs spawn with their patrols, schedules, combat, riding, shops and builds on; tx " + definitions));
        if (copied > 0) lines.add("Copied " + copied + " schematics into schematics/");
        if (!errors.isEmpty()) lines.add(problems(errors));
        int behaviors = imported(state).stream().mapToInt((NpcDefinition definition) -> definition.json().has("behaviors") ? definition.json().getAsJsonArray("behaviors").size() : 0).sum();
        if (behaviors > 0) lines.add("Text and command behaviors in behaviors/citizens/ run now");
        return lines;
    }

    /**
     * Copies the staged schematics that are not in schematics/ yet. Each is written to a temporary file, recorded in the
     * state with its hash, then moved into place in one step, so a cut-off copy leaves no partial file and rollback
     * deletes exactly what this import wrote. Returns how many of the import's schematics are in place.
     */
    private int copySchematics(JsonObject state) throws IOException {
        JsonArray copied = state.has(COPIED) ? state.getAsJsonArray(COPIED) : new JsonArray();
        state.add(COPIED, copied);
        Path folder = store.resolve("schematics");
        if (Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS))
            try (DirectoryStream<Path> left = Files.newDirectoryStream(folder, ".animus-schematic-*.tmp")) { for (Path stale : left) Files.deleteIfExists(stale); }
        int count = 0;
        for (JsonElement value : state.getAsJsonArray("schematics")) {
            JsonObject schematic = value.getAsJsonObject();
            String relative = "schematics/" + schematic.get("name").getAsString();
            Path target = store.resolve(relative);
            Optional<JsonObject> recorded = copied.asList().stream().map(JsonElement::getAsJsonObject)
                    .filter((JsonObject entry) -> entry.get("path").getAsString().equals(relative)).findFirst();
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { if (recorded.isPresent()) count++; continue; }
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".animus-schematic-", ".tmp");
            try {
                Files.copy(Path.of(schematic.get("source").getAsString()), temporary, StandardCopyOption.REPLACE_EXISTING);
                try (FileChannel written = FileChannel.open(temporary, StandardOpenOption.WRITE)) { written.force(true); }
                JsonObject entry = recorded.orElseGet(() -> { JsonObject created = new JsonObject(); created.addProperty("path", relative); copied.add(created); return created; });
                entry.addProperty("sha256", sha256(temporary));
                writeState(state);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                WriteNotices.touched(target);
                count++;
            } finally { Files.deleteIfExists(temporary); }
        }
        return count;
    }

    List<String> rollback() throws IOException {
        JsonObject state = state();
        boolean citizens = applied(state);
        if (!citizens && !ShopkeepersDropIn.applied(state) && !interrupted(state)) throw new IOException("No applied Citizens import to roll back");
        List<String> lines = new ArrayList<>();
        JsonArray transactions = ImportJournal.transactions(state);
        String stopped = null;
        // Newest first; each undo leaves the list as soon as it commits, so a retry after a conflict skips what is undone.
        for (int index = transactions.size() - 1; index >= 0; index--) {
            String transaction = transactions.get(index).getAsString();
            if (!journal.committed(transaction)) { transactions.remove(index); writeState(state); continue; }
            try {
                String inverse = journal.undo(state, transaction);
                committed.add(inverse);
                rolledBack = true;
                lines.add("Rolled back " + shortId(transaction) + "; tx " + inverse);
            } catch (IOException | RuntimeException conflict) {
                stopped = "Rollback stopped at tx " + shortId(transaction) + ": " + conflict.getMessage() + "; " + transactions.size()
                        + " transactions are still installed, fix that and run /animus import citizens rollback again";
                break;
            }
        }
        disallowCommands(state).ifPresent(lines::add);
        int kept = 0;
        for (JsonElement value : state.has(COPIED) ? state.getAsJsonArray(COPIED) : new JsonArray()) {
            Path file = store.resolve(value.getAsJsonObject().get("path").getAsString());
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue;
            if (sha256(file).equals(value.getAsJsonObject().get("sha256").getAsString())) {
                Files.delete(file);
                WriteNotices.written(file, null);
            }
            else kept++;
        }
        if (kept > 0) lines.add(kept + " copied schematics changed since the import and were kept");
        state.remove(COPIED);
        if (stopped == null) {
            ShopkeepersDropIn.rolledBack(state);
            DenizenDropIn.rolledBack(state);
            state.remove("transactions");
            state.remove(APPLYING);
            if (citizens) state.addProperty("state", "rolled-back");
        }
        writeState(state);
        List<String> errors = npcs.reloadAfterRollback();
        if (!errors.isEmpty()) lines.add(problems(errors));
        lines.add(stopped != null ? Palette.error(stopped) : Palette.success("Citizens import undone; the preview stays staged and /animus import citizens apply reinstalls it"));
        return lines;
    }

    List<String> review() throws IOException {
        JsonObject state = state();
        boolean citizens = applied(state);
        if (!citizens && !ShopkeepersDropIn.applied(state)) throw new IOException("No applied Citizens import; run /animus import citizens preview, then apply");
        List<String> lines = new ArrayList<>();
        if (citizens) {
            lines.add("Citizens import " + state.get("importId").getAsString().substring(0, 12) + ": " + imported(state).size() + " NPCs installed");
            lines.add(converted(imported(state).stream().map(NpcDefinition::json).toList()));
            lines.add(health(imported(state).stream().map(NpcDefinition::json).toList()));
        }
        ShopkeepersDropIn.reviewLine(state).ifPresent(lines::add);
        DenizenDropIn.reviewLine(state).ifPresent(lines::add);
        int guards = guards(imported(state));
        if (combatLimit > 0 && guards > combatLimit)
            lines.add("Combat capacity: " + guards + " guards, at most " + combatLimit + " fight or scan at once ([combat] maximumNpcs); the rest take turns");
        Map<String, Integer> preserved = preserved(state);
        lines.add("Kept but not converted: " + (preserved.isEmpty() ? "nothing" : counts(preserved)) + " " + DocsLink.CITIZENS.url());
        if (state.has(ALLOWED_NAMESPACES) && !state.getAsJsonArray(ALLOWED_NAMESPACES).isEmpty())
            lines.add("Commands: the import added " + String.join(", ", names(state.getAsJsonArray(ALLOWED_NAMESPACES))) + " to [behaviors.actions] commandNamespaces; rollback removes " + (state.getAsJsonArray(ALLOWED_NAMESPACES).size() == 1 ? "it" : "them"));
        if (citizens) lines.add("Report " + state.get("report").getAsString() + ", per NPC under imports/" + state.get("importId").getAsString() + "/reports/");
        lines.add("/animus import citizens rollback undoes the import");
        return lines;
    }

    /**
     * Where the drop-in flow stands, for the editor API: {@code state} ({@code none}, {@code staged}, {@code applied} or
     * {@code rolled-back}), the staged imports and their sources, the command namespaces the import needs, and once applied
     * the data kept but not converted. Report paths are relative to the data folder.
     */
    public JsonObject status() throws IOException {
        JsonObject state = state();
        boolean citizensApplied = applied(state), shopkeepersApplied = ShopkeepersDropIn.applied(state);
        boolean staged = state.has("applicationId") || ShopkeepersDropIn.staged(state);
        String phase = citizensApplied || shopkeepersApplied ? "applied"
                : !staged ? "none"
                : state.has("state") && state.get("state").getAsString().equals("rolled-back") ? "rolled-back" : "staged";
        JsonObject status = new JsonObject();
        status.addProperty("state", phase);
        if (state.has("importId")) {
            JsonObject citizens = new JsonObject();
            citizens.addProperty("importId", state.get("importId").getAsString());
            citizens.addProperty("source", state.get("source").getAsString());
            citizens.addProperty("mode", state.get("mode").getAsString());
            citizens.addProperty("report", state.get("report").getAsString());
            status.add("citizens", citizens);
        }
        if (state.has("shopImportId")) {
            JsonObject traders = new JsonObject();
            traders.addProperty("importId", state.get("shopImportId").getAsString());
            traders.addProperty("source", state.get("shopSource").getAsString());
            traders.addProperty("report", "imports/shops/" + state.get("shopImportId").getAsString() + "/report.json");
            status.add("dtltraders", traders);
        }
        if (state.has("shopkeepers") && state.get("shopkeepers").isJsonObject()) {
            JsonObject part = state.getAsJsonObject("shopkeepers");
            JsonObject shopkeepers = new JsonObject();
            shopkeepers.addProperty("importId", part.get("importId").getAsString());
            shopkeepers.addProperty("source", part.get("source").getAsString());
            shopkeepers.addProperty("state", part.get("state").getAsString());
            shopkeepers.addProperty("report", part.get("report").getAsString());
            shopkeepers.addProperty("waiting", part.has("pending") ? part.getAsJsonArray("pending").size() : 0);
            status.add("shopkeepers", shopkeepers);
        }
        DenizenDropIn.status(state).ifPresent((JsonObject denizen) -> status.add("denizen", denizen));
        JsonArray commands = new JsonArray();
        if (state.has(COMMAND_NAMESPACES)) state.getAsJsonArray(COMMAND_NAMESPACES).forEach(commands::add);
        status.add("commandNamespaces", commands);
        if (phase.equals("applied")) {
            List<JsonObject> definitions = imported(state).stream().map(NpcDefinition::json).toList();
            JsonObject preserved = new JsonObject();
            preserved(definitions).forEach(preserved::addProperty);
            status.add("preserved", preserved);
            status.addProperty("npcs", definitions.size());
        }
        JsonArray transactions = new JsonArray();
        if (state.has("transactions")) state.getAsJsonArray("transactions").forEach(transactions::add);
        status.add("transactions", transactions);
        return status;
    }

    /** Imported NPCs with converted combat. */
    private static int guards(List<NpcDefinition> definitions) {
        int count = 0;
        for (NpcDefinition definition : definitions) {
            JsonObject modules = definition.json().has("modules") && definition.json().get("modules").isJsonObject() ? definition.json().getAsJsonObject("modules") : null;
            if (modules != null && modules.has("animus:combat") && modules.get("animus:combat").isJsonObject()) count++;
        }
        return count;
    }

    private Map<String, Integer> preserved(JsonObject state) { return preserved(imported(state).stream().map(NpcDefinition::json).toList()); }

    /** Citizens data that converts to nothing Animus runs: counted by the traits kept in citizens:review. */
    static Map<String, Integer> preserved(List<JsonObject> definitions) {
        Map<String, Integer> counts = new TreeMap<>();
        for (JsonObject definition : definitions) {
            JsonObject modules = definition.getAsJsonObject("modules");
            if (modules == null || !modules.has("citizens:review") || !modules.get("citizens:review").isJsonObject()) continue;
            JsonObject traits = modules.getAsJsonObject("citizens:review");
            List<String> behaviors = behaviors(definition);
            if (traits.has("waypoints") && !modules.has("animus:navigation") && hasPoints(traits.get("waypoints"))) counts.merge("waypoints", 1, Integer::sum);
            if (traits.has("commandtrait") && behaviors.stream().noneMatch((String id) -> id.startsWith("citizens:commands-"))) counts.merge("commands", 1, Integer::sum);
            if (traits.has("text") && behaviors.stream().noneMatch((String id) -> id.startsWith("citizens:text-"))) counts.merge("text", 1, Integer::sum);
            if (traits.has("lookclose") && !modules.has("animus:look-close")) counts.merge("lookclose", 1, Integer::sum);
            if (traits.keySet().stream().anyMatch((String trait) -> DENIZEN.contains(trait) || trait.startsWith("denizen"))) counts.merge("denizen", 1, Integer::sum);
        }
        return counts;
    }

    /**
     * True when a kept waypoints trait holds a point of any provider. Citizens saves the trait with only its provider, or a
     * guided link distance, for NPCs that never had a route; those convert to nothing because there is nothing to run.
     */
    static boolean hasPoints(JsonElement waypoints) {
        if (waypoints == null || !waypoints.isJsonObject()) return false;
        for (Map.Entry<String, JsonElement> provider : waypoints.getAsJsonObject().entrySet()) {
            if (!provider.getValue().isJsonObject()) continue;
            for (Map.Entry<String, JsonElement> list : provider.getValue().getAsJsonObject().entrySet()) {
                JsonElement value = list.getValue();
                if ((value.isJsonArray() && !value.getAsJsonArray().isEmpty()) || (value.isJsonObject() && !value.getAsJsonObject().isEmpty())) return true;
            }
        }
        return false;
    }

    /** Generated behaviors that may run commands: Citizens commands, and waypoint triggers that may hold command triggers. */
    private static boolean mayRunCommands(String id) { return id.startsWith("citizens:commands-") || id.startsWith("citizens:waypoints-"); }

    /**
     * What in these definitions runs commands: the behaviors they list that may, and as {@code citizens:schedule-<id>}
     * an NPC Destinations schedule with arrival commands, which run under the {@code citizens} namespace.
     */
    static List<String> commandBehaviors(List<JsonObject> definitions) {
        List<String> ids = new ArrayList<>();
        for (JsonObject definition : definitions) {
            for (String id : behaviors(definition)) if (mayRunCommands(id)) ids.add(id);
            JsonObject modules = definition.has("modules") && definition.get("modules").isJsonObject() ? definition.getAsJsonObject("modules") : new JsonObject();
            JsonElement schedule = modules.get(art.arcane.animus.core.schedule.ScheduleSettings.MODULE);
            JsonElement locations = schedule != null && schedule.isJsonObject() ? schedule.getAsJsonObject().get("locations") : null;
            if (locations == null || !locations.isJsonArray()) continue;
            for (JsonElement location : locations.getAsJsonArray()) {
                JsonElement commands = location.isJsonObject() ? location.getAsJsonObject().get("commands") : null;
                if (commands != null && commands.isJsonArray() && !commands.getAsJsonArray().isEmpty()) {
                    ids.add(art.arcane.animus.core.schedule.ScheduleService.IMPORTED_COMMANDS + ":schedule-" + definition.get("id").getAsString());
                    break;
                }
            }
        }
        return ids;
    }

    private static List<String> behaviors(JsonObject definition) {
        List<String> ids = new ArrayList<>();
        if (definition.has("behaviors") && definition.get("behaviors").isJsonArray())
            for (JsonElement id : definition.getAsJsonArray("behaviors")) if (id.isJsonPrimitive()) ids.add(id.getAsString());
        return ids;
    }

    /**
     * What converted into something Animus runs: routes with their waypoints and the guide points random routes walk
     * through, look-close that is on, and generated behaviors.
     */
    static String converted(List<JsonObject> definitions) {
        int routes = 0, points = 0, guides = 0, look = 0, text = 0, commands = 0;
        for (JsonObject definition : definitions) {
            JsonObject modules = definition.has("modules") && definition.get("modules").isJsonObject() ? definition.getAsJsonObject("modules") : new JsonObject();
            if (modules.has("animus:navigation") && modules.get("animus:navigation").isJsonObject()) {
                routes++;
                JsonObject navigation = modules.getAsJsonObject("animus:navigation");
                if (navigation.has("points") && navigation.get("points").isJsonArray()) points += navigation.getAsJsonArray("points").size();
                if (navigation.has("guides") && navigation.get("guides").isJsonArray()) guides += navigation.getAsJsonArray("guides").size();
            }
            JsonElement lookClose = modules.get("animus:look-close");
            if (lookClose != null && lookClose.isJsonObject() && !off(lookClose.getAsJsonObject().get("enabled"))) look++;
            for (String id : behaviors(definition)) {
                if (id.startsWith("citizens:text-")) text++;
                else if (id.startsWith("citizens:commands-")) commands++;
            }
        }
        return "Converted: " + routes + " patrol routes (" + points + " waypoints, " + guides + " guide points), look-close on " + look + " NPCs, " + text + " text and " + commands + " command behaviors";
    }

    /** How many NPCs take damage; Citizens protects an NPC unless {@code /npc vulnerable} turned that off. */
    static String health(List<JsonObject> definitions) {
        int killable = 0;
        for (JsonObject definition : definitions) {
            JsonElement modules = definition.get("modules");
            JsonElement health = modules != null && modules.isJsonObject() ? modules.getAsJsonObject().get(HealthSettings.MODULE) : null;
            JsonElement flag = health != null && health.isJsonObject() ? health.getAsJsonObject().get("killable") : null;
            if (flag != null && flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean() && flag.getAsBoolean()) killable++;
        }
        int kept = definitions.size() - killable;
        return "Health: " + killable + (killable == 1 ? " NPC can" : " NPCs can") + " be hurt and die; " + kept + (kept == 1 ? " NPC stays" : " NPCs stay")
                + " protected from damage as in Citizens (/animus npc health <npc> setting=killable value=on)";
    }

    /** An {@code enabled} flag set to false; look-close is on when the flag is left out. */
    private static boolean off(JsonElement enabled) { return enabled != null && enabled.isJsonPrimitive() && enabled.getAsJsonPrimitive().isBoolean() && !enabled.getAsBoolean(); }

    /**
     * Adds the namespaces of the applied import's command behaviors to {@code [behaviors.actions] commandNamespaces}, so
     * they run as they did under Citizens and Denizen, and remembers which ones it added, so rollback takes out exactly
     * those. Behaviors in any other namespace stay as the allow-list had them.
     */
    private Optional<String> allowCommands(JsonObject state) throws IOException {
        List<String> ids = new ArrayList<>(commandBehaviors(imported(state).stream().map(NpcDefinition::json).toList()));
        ids.addAll(DenizenDropIn.commandBehaviors(store, state));
        ids.addAll(shopCommands(store.resolve(CitizensShops.FOLDER)));
        List<String> needed = CommandAllowList.namespaces(ids);
        if (needed.isEmpty()) return Optional.empty();
        String text = CommandAllowList.read(store);
        List<String> next = new ArrayList<>(CommandAllowList.allowed(text));
        List<String> added = new ArrayList<>();
        for (String namespace : needed) if (!next.contains(namespace)) { next.add(namespace); added.add(namespace); }
        if (added.isEmpty()) return Optional.of("Commands: " + String.join(", ", needed) + " already allowed by [behaviors.actions] commandNamespaces");
        DocumentStore.atomic(store.resolve(CommandAllowList.CONFIG), CommandAllowList.with(text, next));
        settingsChanged = true;
        JsonArray remembered = state.has(ALLOWED_NAMESPACES) ? state.getAsJsonArray(ALLOWED_NAMESPACES) : new JsonArray();
        for (String namespace : added) remembered.add(namespace);
        state.add(ALLOWED_NAMESPACES, remembered);
        writeState(state);
        return Optional.of("Commands: added " + String.join(", ", added) + " to config.toml [behaviors.actions] commandNamespaces; " + ids.size()
                + " imported behaviors and shops run commands, rollback removes " + (added.size() == 1 ? "it" : "them"));
    }

    /** The Citizens shops in {@code folder} that run commands, which the same allow-list keeps from loading. */
    static List<String> shopCommands(Path folder) throws IOException {
        List<String> ids = new ArrayList<>();
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return ids;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.json")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                ShopDocument.Result shop = ShopDocument.parse(CitizensBehaviors.NAMESPACE + ":" + name.substring(0, name.length() - 5), JsonDocuments.read(file));
                if (shop.shop().isPresent() && shop.shop().get().hasCommands()) ids.add(shop.shop().get().id());
            }
        }
        return ids;
    }

    /** Takes the namespaces apply added back out of config.toml, leaving any the operator added since. */
    private Optional<String> disallowCommands(JsonObject state) throws IOException {
        if (!state.has(ALLOWED_NAMESPACES)) return Optional.empty();
        List<String> added = new ArrayList<>();
        for (JsonElement namespace : state.getAsJsonArray(ALLOWED_NAMESPACES)) added.add(namespace.getAsString());
        String text = CommandAllowList.read(store);
        List<String> next = new ArrayList<>(CommandAllowList.allowed(text));
        boolean removed = next.removeAll(added);
        state.remove(ALLOWED_NAMESPACES);
        if (!removed) return Optional.empty();
        DocumentStore.atomic(store.resolve(CommandAllowList.CONFIG), CommandAllowList.with(text, next));
        settingsChanged = true;
        return Optional.of("Removed " + String.join(", ", added) + " from [behaviors.actions] commandNamespaces in config.toml");
    }

    private List<NpcDefinition> imported(JsonObject state) { return imported(state, npcs.all()); }

    private static List<NpcDefinition> imported(JsonObject state, java.util.Collection<NpcDefinition> definitions) {
        Set<String> importIds = new java.util.HashSet<>();
        if (state.has("importId")) importIds.add(state.get("importId").getAsString());
        if (ShopkeepersDropIn.applied(state)) ShopkeepersDropIn.importId(state).ifPresent(importIds::add);
        return definitions.stream().filter((NpcDefinition definition) -> {
            JsonObject json = definition.json();
            return json.has("migration") && json.get("migration").isJsonObject() && json.getAsJsonObject("migration").has("importId")
                    && importIds.contains(json.getAsJsonObject("migration").get("importId").getAsString());
        }).toList();
    }

    static String groups(JsonObject summary) {
        Map<String, Integer> folders = new TreeMap<>();
        for (String path : summary.getAsJsonObject("documents").keySet())
            if (path.startsWith("npcs/")) folders.merge(path.substring(5, path.indexOf('/', 5)), 1, Integer::sum);
        return String.join(", ", folders.entrySet().stream().map((Map.Entry<String, Integer> entry) -> "npcs/" + entry.getKey() + "/ " + entry.getValue()).toList());
    }

    /** The review counts of a staged preview, the same numbers {@code review} shows after apply. */
    static List<String> summaryLines(CitizensImporter.Preview preview) throws IOException {
        JsonObject summary = preview.summary();
        List<JsonObject> staged = GroupFactoring.staged(preview);
        List<String> lines = new ArrayList<>(GroupFactoring.reviewLine(summary).stream().toList());
        lines.add(converted(staged));
        lines.add(health(staged));
        if (summary.has("companions")) lines.add("Also read beside the save: " + String.join(", ", names(summary.getAsJsonArray("companions")))
                + "; " + preview.shops() + " Citizens shops install in shops/citizens/");
        Map<String, Integer> preserved = preserved(staged);
        lines.add("Kept, not converted: " + (preserved.isEmpty() ? "nothing" : counts(preserved)));
        lines.add("Traits " + summary.getAsJsonObject("traits").size() + ", warnings " + summary.getAsJsonArray("migrationWarnings").size()
                + ", unresolved NPC references " + summary.get("unresolvedReferences").getAsInt());
        return lines;
    }

    private static List<String> names(JsonArray array) {
        List<String> names = new ArrayList<>();
        for (JsonElement name : array) names.add(name.getAsString());
        return names;
    }

    private static String counts(Map<String, Integer> counts) {
        return String.join(", ", counts.entrySet().stream().map((Map.Entry<String, Integer> entry) -> entry.getKey() + " " + entry.getValue()).toList());
    }

    /** Definition files the reload refused; each keeps its last valid version. */
    static String problems(List<String> errors) { return Palette.error("Refused on reload, last valid kept: " + String.join("; ", errors)); }

    /** The first characters of a transaction id, enough to tell it apart in a report. */
    static String shortId(String transaction) { return transaction.substring(0, Math.min(8, transaction.length())); }

    private void refuseWhileCitizensRuns() throws IOException { if (host.citizensEnabled().getAsBoolean()) throw new IOException(CITIZENS_RUNNING); }
    static boolean applied(JsonObject state) { return state.has("state") && state.get("state").getAsString().equals("applied"); }
    /** An apply that committed something and stopped before it finished. */
    private static boolean interrupted(JsonObject state) { return state.has(APPLYING); }
    private void finished(JsonObject state) throws IOException { state.remove(APPLYING); writeState(state); }
    JsonObject state() throws IOException {
        String text = store.read(STATE);
        return text == null ? new JsonObject() : JsonDocuments.object(text);
    }
    private void writeState(JsonObject state) throws IOException { journal.write(state); }
    private static String sha256(Path file) throws IOException {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
