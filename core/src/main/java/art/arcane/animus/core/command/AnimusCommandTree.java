package art.arcane.animus.core.command;

import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.admin.Administration;
import art.arcane.animus.core.behavior.builtin.BuiltinCapabilities;
import art.arcane.animus.core.behavior.builtin.PendingChoices;
import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.Subsystem;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.DocsLink;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.virtual.WorldPosition;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * The {@code /animus} root: saved NPCs and selection, plus every command group. The same
 * tree runs on Bukkit, Folia and the mod loaders; a platform without a feature answers why it is unavailable.
 */
@Director(name = BuildIdentity.ID, description = "Manage virtual NPCs, their behaviors and imports")
public final class AnimusCommandTree extends CommandGroup {
    NpcCommands npc;
    WorldCommands.Navigation navigation;
    WorldCommands.Ride ride;
    WorldCommands.Combat combat;
    ContentCommands.Modules modules;
    ContentCommands.Rigs rigs;
    ContentCommands.Pack pack;
    ContentCommands.Import imports;
    RecordCommands record;
    ServiceCommands.Shop shop;
    ServiceCommands.Gui gui;
    ServiceCommands.Build build;
    ServiceCommands.Editor editor;
    BehaviorTree.Behaviors behaviors;
    TraitGroupCommands groups;
    DiagnosticCommands.Debug debug;
    DiagnosticCommands.Proof proof;
    ToolkitCommands tools;
    SetupCommands setup;

    public AnimusCommandTree(CommandServices services) {
        super(services);
        npc = new NpcCommands(services);
        navigation = new WorldCommands.Navigation(services);
        ride = new WorldCommands.Ride(services);
        combat = new WorldCommands.Combat(services);
        modules = new ContentCommands.Modules(services);
        rigs = new ContentCommands.Rigs(services);
        pack = new ContentCommands.Pack(services);
        imports = new ContentCommands.Import(services);
        record = new RecordCommands(() -> services.recordings());
        shop = new ServiceCommands.Shop(services);
        gui = new ServiceCommands.Gui(services);
        build = new ServiceCommands.Build(services);
        editor = new ServiceCommands.Editor(services);
        behaviors = new BehaviorTree.Behaviors(services);
        groups = new TraitGroupCommands(services);
        debug = new DiagnosticCommands.Debug(services);
        proof = new DiagnosticCommands.Proof(services);
        tools = new ToolkitCommands(services);
        setup = new SetupCommands(services);
    }

    @Director(description = "Show the first steps: import, create an NPC, set its skin, pair the web editor")
    void setup(@Param(name = "dismiss", defaultValue = "false", description = "Turn the join hint off for every admin") boolean dismiss) { setup.run(actor(), dismiss); }

    /** The engine for this tree, with completion values from the running services. */
    public AnimusDirector director() { return new AnimusDirector(this, this::values); }

    /** Completion values by parameter name. */
    Collection<String> values(String parameter, CommandActor actor) { return new CommandCompletions(this).values(parameter, actor); }

    private Administration administration() { return new Administration(runtime()); }

    @Director(description = "Show what Animus runs and the settings in effect")
    @AnimusPermission("animus.status")
    void status() {
        CommandActor actor = actor();
        Administration administration = administration();
        try {
            services.files().execute(() -> {
                List<String> lines;
                try { lines = administration.status(); }
                catch (RuntimeException failure) { reply(actor, Palette.error("Status unavailable: " + failure.getMessage())); return; }
                if (!stopping()) actor.reply(() -> { if (!stopping()) status(actor, lines, living().status()); });
            });
        } catch (RejectedExecutionException full) { actor.tell(Palette.error(CommandActor.BUSY)); }
    }

    /**
     * A player gets one line, the headline and the runtime state, with the runtime counters and the settings in effect
     * behind {@code [Details]}; the console gets the headline, the runtime counters and the settings line.
     */
    static void status(CommandActor actor, List<String> lines, String runtime) {
        int split = runtime.indexOf(" | ");
        String state = split < 0 ? runtime : runtime.substring(0, split);
        List<String> details = new ArrayList<>(List.of(runtime));
        details.addAll(lines.subList(1, lines.size()));
        if (actor.player().isEmpty()) {
            actor.tell(lines.getFirst());
            details.subList(0, Math.min(2, details.size())).forEach(actor::tell);
            return;
        }
        actor.rich(ChatLine.reply(lines.getFirst() + " | " + state).details("Details", details).docs(DocsLink.COMMANDS));
    }

    @Director(description = "List saved NPCs; click an id to inspect it")
    void list(@Param(name = "page", defaultValue = "1", description = "The page to show") int page) {
        CommandActor actor = actor();
        Administration administration = administration();
        try {
            services.files().execute(() -> {
                List<String> rows = administration.rows();
                if (!stopping()) actor.reply(() -> listing(actor, "NPCs (" + rows.size() + ")", "/" + BuildIdentity.ID + " list", rows, page,
                        "No NPCs yet; /animus create <id> adds one where you stand", CommandGroup::inspectCommand));
            });
        } catch (RejectedExecutionException full) { actor.tell(Palette.error(CommandActor.BUSY)); }
    }

    @Director(description = "Save a new despawned NPC where you stand; the console gives world, x, y and z")
    void create(@Param(name = "id", description = "The new NPC id; its file is npcs/<folder>/<id>.json") String id,
                @Param(name = "type", defaultValue = DEFAULT_TYPE, description = "The entity type, such as minecraft:villager") String type,
                @Param(name = "name", defaultValue = "", description = "Its display name, the id when left out; quote it to use spaces") String name,
                @Param(name = "folder", defaultValue = "", description = "The folder under npcs/ it lives in, such as world/town; npcs/ itself when left out") String folder,
                @Param(name = "world", defaultValue = "", description = "The world it stands in; yours when left out") String world,
                @Param(name = "x", defaultValue = "", description = "X position; yours when left out") String x,
                @Param(name = "y", defaultValue = "", description = "Y position; yours when left out") String y,
                @Param(name = "z", defaultValue = "", description = "Z position; yours when left out") String z) {
        CommandActor actor = actor();
        Placement placement;
        try { placement = placement(actor.position(), world, x, y, z); }
        catch (IllegalArgumentException missing) { actor.tell(Palette.error(missing.getMessage())); return; }
        String shown = name.isEmpty() ? id : name;
        files(actor, () -> {
            String line = administration().create(id, type, placement.world(), placement.x(), placement.y(), placement.z(), folder, shown, actor.player());
            living().refresh();
            return List.of(Palette.success(line + "; /" + BuildIdentity.ID + " spawn " + id + " shows it"));
        });
    }

    static final String DEFAULT_TYPE = "minecraft:player";

    /** Where {@code create} puts an NPC: the given values, each missing one taken from where the player stands. */
    record Placement(String world, double x, double y, double z) {}

    /**
     * The given world and coordinates, each left out one from {@code standing}. Without a standing position, as for the
     * console, every value must be given. Throws with the reply when one is missing or not a number.
     */
    static Placement placement(Optional<WorldPosition> standing, String world, String x, String y, String z) {
        if (standing.isEmpty() && (world.isEmpty() || x.isEmpty() || y.isEmpty() || z.isEmpty()))
            throw new IllegalArgumentException("The console gives world=, x=, y= and z=, such as world=world x=0 y=64 z=0");
        return new Placement(world.isEmpty() ? standing.get().world() : world,
                coordinate("x", x, standing.map((WorldPosition at) -> at.point().x())),
                coordinate("y", y, standing.map((WorldPosition at) -> at.point().y())),
                coordinate("z", z, standing.map((WorldPosition at) -> at.point().z())));
    }

    private static double coordinate(String axis, String given, Optional<Double> standing) {
        if (given.isEmpty()) return Math.round(standing.orElseThrow() * 100) / 100.0;
        try {
            double value = Double.parseDouble(given);
            if (Double.isFinite(value)) return value;
        } catch (NumberFormatException notNumber) { /* answered below */ }
        throw new IllegalArgumentException(axis + " must be a number, such as " + axis + "=12.5");
    }

    @Director(description = "Show an NPC's definition and whether it is active")
    void inspect(@Param(name = "npc", description = "The NPC id, or @selected") String npc) {
        CommandActor actor = actor();
        Administration administration = administration();
        npc(actor, npc).ifPresent((String id) -> {
            try {
                services.files().execute(() -> {
                    Administration.Inspection inspection;
                    try { inspection = administration.inspect(id); }
                    catch (IOException | RuntimeException failure) { reply(actor, Palette.error(reason(failure))); return; }
                    if (!stopping()) actor.reply(() -> { if (!stopping()) inspect(actor, inspection, living().inspect(id)); });
                });
            } catch (RejectedExecutionException full) { actor.tell(Palette.error(CommandActor.BUSY)); }
        });
    }

    /**
     * Two lines. A player gets the summary with the id and position as copy chips and the JSON behind a copy chip, then the
     * runtime state with its counters behind {@code [Details]}; the console gets the summary and the full runtime line.
     */
    static void inspect(CommandActor actor, Administration.Inspection inspection, String runtime) {
        String summary = inspection.summary(), id = inspection.effective().qualifiedId(), position = inspection.position();
        String problem = inspection.problem().map((String text) -> " | group problem: " + text).orElse("");
        if (actor.player().isEmpty()) {
            actor.tell(summary + (position.isEmpty() ? "" : " | " + position) + problem);
            actor.tell(runtime);
            return;
        }
        ChatLine head = ChatLine.reply("").copyable(id).text(Palette.GREY + summary.substring(id.length()));
        if (!position.isEmpty()) head.text(Palette.GREY + " | ").copyable(position);
        head.text(Palette.GREY + problem).copy("[JSON]", inspection.stored(), "Copy the stored JSON");
        if (!inspection.provenance().isEmpty()) head.copy("[Merged]", inspection.merged(), "Copy the JSON after trait groups");
        actor.rich(head);
        int split = runtime.indexOf(';');
        List<String> details = new ArrayList<>(List.of(runtime));
        details.addAll(inspection.provenance());
        actor.rich(ChatLine.reply(split < 0 ? runtime : runtime.substring(0, split)).details("Details", details));
    }

    @Director(description = "Merge a JSON object into an NPC's definition")
    void edit(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
              @Param(name = "patch", description = "A JSON object whose top-level fields replace the definition's; write patch={...} if it contains =") String patch) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> change(actor, id, Optional.of(patch)));
    }

    @Director(description = "Copy an NPC under a new id, despawned")
    void clone(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
               @Param(name = "id", description = "The copy's id") String id) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String source) -> files(actor, () -> {
            String line = administration().duplicate(source, id);
            living().refresh();
            return List.of(Palette.success(line + "; /" + BuildIdentity.ID + " spawn " + id + " shows it"));
        }));
    }

    @Director(description = "Delete an NPC's file; rollback restores it")
    void delete(@Param(name = "npc", description = "The NPC id, or @selected") String npc) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> files(actor, () -> { String line = administration().delete(id); living().refresh(); return List.of(Palette.success(line)); }));
    }

    @Director(description = "Mark an NPC spawned after checking it can run here")
    void spawn(@Param(name = "npc", description = "The NPC id, or @selected") String npc) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> change(actor, id, Optional.empty()));
    }

    @Director(description = "Mark an NPC despawned; its file stays")
    void despawn(@Param(name = "npc", description = "The NPC id, or @selected") String npc) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> files(actor, () -> { String line = administration().spawned(id, false); living().refresh(); return List.of(Palette.success(line)); }));
    }

    @Director(description = "Check every definition file without loading it")
    void validate() { CommandActor actor = actor(); files(actor, administration()::validate); }

    @Director(description = "Reload NPC definitions and rigs from disk")
    void reload() {
        CommandActor actor = actor();
        files(actor, () -> {
            List<String> lines = new ArrayList<>(administration().reload());
            lines.add(ContentCommands.rigSummary(runtime().rigs().reload()));
            living().refresh();
            return lines;
        });
    }

    @Director(description = "Undo a change by its transaction id")
    void rollback(@Param(name = "transaction", description = "The transaction id a change reported") String transaction) {
        CommandActor actor = actor();
        files(actor, () -> {
            AnimusMetrics metrics = AnimusMetrics.current();
            long started = metrics.start();
            try { return administration().rollback(transaction); }
            finally { metrics.stop(Subsystem.IMPORTS, started); living().refresh(); }
        });
    }

    @Director(description = "Select an NPC for the commands that take @selected", origin = DirectorOrigin.PLAYER)
    void select(@Param(name = "npc", description = "The NPC id") String npc) {
        CommandActor actor = actor();
        try {
            living().select(actor.player().orElseThrow(), npc);
            actor.rich(ChatLine.success("Selected ").copyable(npc));
        } catch (RuntimeException refused) { actor.tell(Palette.error(reason(refused) + "; /animus list shows ids")); }
    }

    @Director(description = "Show your selected NPC", origin = DirectorOrigin.PLAYER)
    void selection() {
        CommandActor actor = actor();
        Optional<String> selected = living().selection(actor.player().orElseThrow());
        if (selected.isPresent()) actor.rich(ChatLine.reply("Selected ").copyable(selected.get()));
        else actor.tell("No NPC selected; /animus select <npc> or right-click one");
    }

    @Director(hidden = true, description = "Answer a dialogue choice")
    @AnimusPermission("")
    void choice(@Param(name = "token", description = "The prompt token") String token,
                @Param(name = "option", description = "The chosen option") String option) {
        CommandActor actor = actor();
        BuiltinCapabilities builtins = services.builtins();
        ChoiceCommands.choose(actor, token, option, builtins == null
                ? (UUID player, String prompt, String chosen) -> PendingChoices.Selection.UNKNOWN : builtins::selectChoice);
    }

    /**
     * Prepares the changed definition off the owner, checks on the owner that a spawned NPC can run on this platform, then
     * commits with the revision it was read at. {@code patch} empty means spawn.
     */
    private void change(CommandActor actor, String id, Optional<String> patch) {
        try {
            services.files().execute(() -> {
                try {
                    NpcRepository.Draft before = runtime().npcs().draft(id);
                    JsonObject document = before.document();
                    JsonObject fields = patch.isEmpty() ? null : JsonDocuments.object(patch.get());
                    if (fields == null) document.addProperty("spawned", true);
                    else Administration.merge(document, fields);
                    NpcDefinition candidate;
                    try { candidate = NpcDefinition.parse(JsonDocuments.write(document)); }
                    catch (IOException refused) {
                        if (fields == null) throw refused;
                        reply(actor, Palette.error(Administration.refusal(id, fields, refused.getMessage())));
                        return;
                    }
                    NpcRepository.Draft draft = new NpcRepository.Draft(before.path(), before.expectedHash(), before.expectedRevision(), document);
                    if (!stopping()) actor.reply(() -> commit(actor, candidate, draft, patch.isEmpty()));
                } catch (IOException | RuntimeException e) { reply(actor, Palette.error(reason(e))); }
            });
        } catch (RejectedExecutionException e) { actor.tell(Palette.error(CommandActor.BUSY)); }
    }

    private void commit(CommandActor actor, NpcDefinition candidate, NpcRepository.Draft draft, boolean spawn) {
        if (stopping() || !actor.permitted(AnimusDirector.ADMIN)) return;
        JsonElement spawned = draft.document().get("spawned");
        List<String> errors = spawned != null && spawned.isJsonPrimitive() && spawned.getAsBoolean() ? living().validateActivation(candidate) : List.of();
        if (!errors.isEmpty()) {
            actor.rich(ChatLine.error((spawn ? "Cannot spawn " : "Cannot save ") + candidate.id() + ": " + errors.getFirst() + (errors.size() == 1 ? "" : " (+" + (errors.size() - 1) + " more)")
                    + "; fix it with /animus edit " + candidate.id())
                    .details("All", errors).docs(DocsLink.ACTIVATION));
            return;
        }
        try {
            services.files().execute(() -> {
                try {
                    String tx = runtime().npcs().apply(draft);
                    living().refresh();
                    reply(actor, Palette.success((spawn ? "Spawning " : "Saved ") + candidate.id() + "; tx " + tx + (spawn ? "; it becomes active once its chunk loads" : "")));
                } catch (IOException | RuntimeException e) { reply(actor, Palette.error(reason(e))); }
            });
        } catch (RejectedExecutionException e) { actor.tell(Palette.error(CommandActor.BUSY + "; nothing was saved")); }
    }

    /** Builds this tree without services and renders its help, so a packaged jar proves Director loads without a server. */
    public static int selfCheck() {
        AnimusDirector director = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        AnimusHelp.Page page = AnimusHelp.page(director.engine().getRoot(), director.engine().getRoot().getChildren(), 0);
        int lines = AnimusHelp.lines(page).stream().map(ChatLine::component).toList().size();
        return lines + CommandWords.tokens("edit guard {\"name\":\"A B\"}").size();
    }
}
