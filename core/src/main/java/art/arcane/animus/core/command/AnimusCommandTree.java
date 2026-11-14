package art.arcane.animus.core.command;

import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.admin.Administration;
import art.arcane.animus.core.behavior.builtin.BuiltinCapabilities;
import art.arcane.animus.core.behavior.builtin.PendingChoices;
import art.arcane.animus.core.editor.EditorCommands;
import art.arcane.animus.core.integration.NpcTemplates;
import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.Subsystem;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.runtime.SelectionPick;
import art.arcane.animus.core.storage.DocumentStore;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.DocsLink;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.toolkit.Toolkit;
import art.arcane.animus.core.toolkit.ToolkitMode;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.WorldPosition;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Predicate;

/**
 * The {@code /animus} root: saved NPCs and selection, plus every command group. The same
 * tree runs on Bukkit, Folia and the mod loaders; a platform without a feature answers why it is unavailable.
 */
@Director(name = BuildIdentity.ID, description = "Manage virtual NPCs, their behaviors and imports")
public final class AnimusCommandTree extends CommandGroup {
    NpcCommands npc;
    WorldCommands.Navigation navigation;
    WorldCommands.Ride ride;
    ScheduleCommands schedule;
    WorldCommands.Combat combat;
    ContentCommands.Modules modules;
    ContentCommands.Rigs rigs;
    ContentCommands.Pack pack;
    ContentCommands.Import imports;
    RecordCommands record;
    ServiceCommands.Shop shop;
    ServiceCommands.Gui gui;
    ServiceCommands.Hologram hologram;
    QuestCommands quest;
    ServiceCommands.Build build;
    ServiceCommands.Editor editor;
    BehaviorTree.Behaviors behaviors;
    TraitGroupCommands groups;
    DiagnosticCommands.Debug debug;
    DiagnosticCommands.Proof proof;
    ToolkitCommands tools;
    SetupCommands setup;
    /** Each player's changes for {@code /animus undo} where the Animus menu does not run; see {@link #changes()}. */
    private final RecentChanges own = new RecentChanges();

    public AnimusCommandTree(CommandServices services) {
        super(services);
        npc = new NpcCommands(services);
        navigation = new WorldCommands.Navigation(services);
        ride = new WorldCommands.Ride(services);
        schedule = new ScheduleCommands(services);
        combat = new WorldCommands.Combat(services);
        modules = new ContentCommands.Modules(services);
        rigs = new ContentCommands.Rigs(services);
        pack = new ContentCommands.Pack(services);
        imports = new ContentCommands.Import(services);
        record = new RecordCommands(() -> services.recordings());
        shop = new ServiceCommands.Shop(services);
        gui = new ServiceCommands.Gui(services);
        hologram = new ServiceCommands.Hologram(services);
        quest = new QuestCommands(services);
        build = new ServiceCommands.Build(services);
        editor = new ServiceCommands.Editor(services);
        behaviors = new BehaviorTree.Behaviors(services);
        groups = new TraitGroupCommands(services);
        debug = new DiagnosticCommands.Debug(services);
        proof = new DiagnosticCommands.Proof(services);
        tools = new ToolkitCommands(services);
        setup = new SetupCommands(services);
    }

    @Director(description = "Show the first steps: make an NPC, change it, pair the web editor")
    @AnimusPermission(PermissionNodes.TOOLS)
    void setup(@Param(name = "dismiss", defaultValue = "false", description = "Turn the join hint off for every admin") boolean dismiss) { setup.run(actor(), dismiss); }

    /**
     * The engine for this tree, with completion values, running plugins and each player's menu mode from the services; a
     * tree without services runs every plugin and treats every player as Simple.
     */
    public AnimusDirector director() {
        return new AnimusDirector(this, this::values, services == null ? (String plugin) -> true : services.plugins(),
                (UUID player) -> services == null || services.mode(player) == ToolkitMode.SIMPLE, this::changes);
    }

    /**
     * Each player's changes for {@code /animus undo}, filled by {@link ReplyActor} as replies report them: the Animus
     * menu's, so a change made there is undone here too, else this tree's own.
     */
    RecentChanges changes() { return toolkit().map(Toolkit::changes).orElse(own); }

    /** Completion values by parameter name; {@code given} holds the values already typed. */
    Collection<String> values(String parameter, CommandActor actor, Map<String, String> given) { return new CommandCompletions(this).values(parameter, actor, given); }

    private Administration administration() { return new Administration(runtime()); }

    @Director(description = "Show what Animus runs and the settings in effect")
    @AnimusPermission("animus.status")
    void status() {
        CommandActor actor = actor();
        Administration administration = administration();
        try {
            services.files().execute(() -> {
                List<String> lines;
                try { lines = administration.status(services.editor().listenerState()); }
                catch (RuntimeException failure) { reply(actor, Palette.error("Status unavailable: " + failure.getMessage())); return; }
                if (!stopping()) actor.reply(() -> { if (!stopping()) status(actor, lines, living().status()); });
            });
        } catch (RejectedExecutionException full) { actor.tell(Palette.error(CommandActor.BUSY)); }
    }

    /**
     * A player gets one line, the headline and the runtime state, with the runtime counters and the settings in effect
     * behind {@code [Details]} for admins only; the console gets the headline, the runtime counters and the settings line.
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
        ChatLine line = ChatLine.reply(lines.getFirst() + " | " + state);
        actor.rich((actor.permitted(AnimusDirector.ADMIN) ? line.details("Details", details) : line).docs(DocsLink.COMMANDS));
    }

    @Director(description = "List saved NPCs; click an id to inspect it")
    @AnimusPermission(PermissionNodes.NPC_VIEW)
    @Positional("page")
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

    @Director(description = "Make an NPC where you stand, or at x y z")
    @AnimusPermission(PermissionNodes.NPC_CREATE)
    void create(@Param(name = "id", description = "The new NPC id; its file is npcs/<folder>/<id>.json") String id,
                @Param(name = "type", defaultValue = DEFAULT_TYPE, description = "The body type, such as minecraft:player") String type,
                @Param(name = "name", defaultValue = "", description = "Its display name, the id when left out; quote it to use spaces") String name,
                @Param(name = "folder", defaultValue = "", description = "The folder under npcs/ it lives in, such as world/town; npcs/ itself when left out") String folder,
                @Param(name = "world", defaultValue = "", description = "The world it stands in; yours when left out") String world,
                @Param(name = "x", defaultValue = "", description = "X position: a number, ~ or ~N; yours when left out") String x,
                @Param(name = "y", defaultValue = "", description = "Y position: a number, ~ or ~N; yours when left out") String y,
                @Param(name = "z", defaultValue = "", description = "Z position: a number, ~ or ~N; yours when left out") String z,
                @Param(name = "spawned", defaultValue = "true", description = "false saves it despawned") boolean spawned) {
        create(id, type, name, folder, world, x, y, z, spawned, "");
    }

    @Director(description = "Make an NPC with a MythicMobs mob's look and skills, where you stand or at x y z")
    @AnimusPermission(PermissionNodes.NPC_CREATE)
    @NeedsPlugin("MythicMobs")
    void mythic(@Param(name = "id", description = "The new NPC id; its file is npcs/<folder>/<id>.json") String id,
                @Param(name = "mythic-mob", description = "The MythicMobs mob type it takes its look and skills from, such as SkeletonKing") String mob,
                @Param(name = "name", defaultValue = "", description = "Its display name, the mob's when left out; quote it to use spaces") String name,
                @Param(name = "folder", defaultValue = "", description = "The folder under npcs/ it lives in, such as world/town; npcs/ itself when left out") String folder,
                @Param(name = "world", defaultValue = "", description = "The world it stands in; yours when left out") String world,
                @Param(name = "x", defaultValue = "", description = "X position: a number, ~ or ~N; yours when left out") String x,
                @Param(name = "y", defaultValue = "", description = "Y position: a number, ~ or ~N; yours when left out") String y,
                @Param(name = "z", defaultValue = "", description = "Z position: a number, ~ or ~N; yours when left out") String z,
                @Param(name = "spawned", defaultValue = "true", description = "false saves it despawned") boolean spawned) {
        create(id, DEFAULT_TYPE, name, folder, world, x, y, z, spawned, mob);
    }

    /** Creates the NPC, laying the Mythic mob {@code mythic} names over it unless that is empty. */
    private void create(String id, String type, String name, String folder, String world, String x, String y, String z, boolean spawned, String mythic) {
        CommandActor actor = actor();
        Placement placement;
        try { placement = placement(actor, world, x, y, z); }
        catch (IllegalArgumentException missing) { actor.tell(Palette.error(missing.getMessage())); return; }
        Optional<NpcTemplates.Template> look;
        try { look = mythic.isEmpty() ? Optional.empty() : Optional.of(services.runtime().templates().template(NpcTemplates.MYTHIC, mythic)); }
        catch (IllegalArgumentException refused) { actor.tell(Palette.error(refused.getMessage())); return; }
        String body = look.map((NpcTemplates.Template chosen) -> chosen.fields().get("type").getAsString()).orElse(type);
        String shown = name.isEmpty() ? id : name;
        float yaw = actor.facing().map(Coordinates.Facing::yaw).orElse(0f);
        Administration administration = administration();
        try {
            services.files().execute(() -> {
                Administration.Creation creation;
                try {
                    Administration.Creation plain = administration.creation(id, body, placement.world(), placement.point(), yaw, folder, shown, actor.player(), spawned);
                    creation = look.isEmpty() ? plain : administration.templated(plain, look.get().fields(), !name.isEmpty());
                }
                catch (IOException | RuntimeException failure) { reply(actor, Palette.error(reason(failure))); return; }
                String notes = look.map((NpcTemplates.Template chosen) -> chosen.notes().isEmpty() ? "" : "; " + String.join("; ", chosen.notes())).orElse("");
                String extra = (look.isPresent() ? " from Mythic mob " + mythic : "") + notes;
                if (!stopping()) actor.reply(() -> commit(actor, creation.definition(), creation.draft(), PermissionNodes.NPC_CREATE, "create", (String tx) -> {
                    actor.player().ifPresent((UUID player) -> toolkit().ifPresent((Toolkit<?, ?> found) -> found.selectOnly(player, id)));
                    return made(actor.player().isPresent(), id, id, extra, spawned, tx);
                }));
            });
        } catch (RejectedExecutionException full) { actor.tell(Palette.error(CommandActor.BUSY)); }
    }

    static final String DEFAULT_TYPE = "minecraft:player";

    /**
     * {@code Made guard [Edit] [Undo]}: {@code [Edit]} opens the new NPC's screen in the Animus menu; the console gets no
     * {@code [Edit]}. {@code shown} is how the line names it: the id a command was given, or the name the menu made it
     * with. A despawned NPC adds the command that spawns it.
     */
    public static ChatLine made(boolean player, String id, String shown, String extra, boolean spawned, String tx) {
        ChatLine line = ChatLine.success("Made " + shown + extra + (spawned ? "" : ", despawned")).transaction(tx);
        if (player) line.run("Edit", ToolkitCommands.edit(id), "Open it in the Animus menu");
        line.undo(tx);
        String spawn = "/" + BuildIdentity.ID + " spawn " + id;
        return spawned ? line : line.text("&a; Next: ").command(spawn, Palette.PINK, spawn, false, "Click to put it in your chat box");
    }

    /** The toolkit, where the menus and the wand keep each player's selection; empty without services. */
    private Optional<Toolkit<?, ?>> toolkit() { return services == null ? Optional.empty() : Toolkit.of(services.shops().screens()); }

    /** Where {@code create} puts an NPC. */
    record Placement(String world, Vec3 point) {}

    /**
     * The given world and coordinates, read as {@link Coordinates} does; each left out one is the player's own. The console
     * gives the world and numbers. Throws with the reply when one is missing or cannot be read.
     */
    static Placement placement(CommandActor actor, String world, String x, String y, String z) {
        Optional<WorldPosition> standing = actor.position();
        if (standing.isEmpty() && world.isEmpty()) throw new IllegalArgumentException("The console gives world=, x=, y= and z=, such as world=world x=0 y=64 z=0");
        return new Placement(world.isEmpty() ? standing.get().world() : world, Coordinates.point(x, y, z, Coordinates.origin(actor)));
    }

    @Director(description = "Show an NPC's definition and whether it is active")
    @AnimusPermission(PermissionNodes.NPC_VIEW)
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
        String problem = inspection.problem().map((String text) -> " | trait group problem: " + text).orElse("");
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

    @Director(description = "Merge a JSON object into an NPC's definition; null removes a field")
    @AnimusPermission(PermissionNodes.NPC_EDIT)
    void edit(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
              @Param(name = "patch", description = "A JSON merge patch: objects merge field by field, null removes; write patch={...} if it contains =") String patch) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> change(actor, id, Optional.of(patch)));
    }

    @Director(description = "Copy an NPC under a new id, despawned")
    @AnimusPermission(PermissionNodes.NPC_CREATE)
    void clone(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
               @Param(name = "id", description = "The copy's id") String id) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String source) -> files(actor, () -> {
            String line = administration().duplicate(source, id);
            living().refresh();
            return List.of(Palette.success(line + "; Next: /" + BuildIdentity.ID + " spawn " + id));
        }));
    }

    @Director(description = "Delete an NPC's file; undo brings it back")
    @AnimusPermission(PermissionNodes.NPC_DELETE)
    void delete(@Param(name = "npc", description = "The NPC id, or @selected") String npc) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> files(actor, () -> { String line = administration().delete(id); living().refresh(); return List.of(Palette.success(line)); }));
    }

    @Director(description = "Put an NPC in the world after checking it can run here")
    @AnimusPermission(PermissionNodes.NPC_EDIT)
    void spawn(@Param(name = "npc", description = "The NPC id, or @selected") String npc) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> change(actor, id, Optional.empty()));
    }

    @Director(description = "Take an NPC out of the world; its file stays")
    @AnimusPermission(PermissionNodes.NPC_EDIT)
    void despawn(@Param(name = "npc", description = "The NPC id, or @selected") String npc) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> files(actor, () -> {
            String line = administration().spawned(id, false);
            living().refresh();
            return List.of(Palette.success(line + "; Next: /" + BuildIdentity.ID + " spawn " + id));
        }));
    }

    @Director(description = "Check every definition file without loading it")
    @AnimusPermission(PermissionNodes.NPC_VIEW)
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

    @Director(description = "Undo any change by its id, the one after tx in a reply")
    void rollback(@Param(name = "transaction", description = "The id after tx in a reply, or its first characters") String transaction) {
        CommandActor actor = actor();
        files(actor, () -> rolledBack(transaction));
    }

    /** Rolls a transaction back and reloads what it touched. File lane. */
    private List<String> rolledBack(String transaction) throws IOException {
        AnimusMetrics metrics = AnimusMetrics.current();
        long started = metrics.start();
        try { return administration().rollback(transaction); }
        finally { metrics.stop(Subsystem.IMPORTS, started); living().refresh(); }
    }

    /**
     * Reverts the chosen change when the player still holds the node it needs (see {@link UndoPermission}). It leaves the
     * player's list whether or not it could be undone, so a change someone else already undid, later edited over, or one
     * the player may no longer make does not stand in front of the older ones; the reply says which happened.
     */
    @Director(description = "Undo your last change, or the one an [Undo] names")
    @AnimusPermission(PermissionNodes.TOOLS)
    @Positional("change")
    void undo(@Param(name = "change", defaultValue = "", description = "The id an [Undo] carries; your last change when left out") String change) {
        CommandActor actor = actor();
        RecentChanges changes = changes();
        undoing(actor, changes, change).ifPresent((RecentChanges.Change chosen) -> {
            Predicate<String> holds = holds(actor, chosen);
            files(actor, () -> {
                try {
                    List<String> lines = undo(chosen, holds, runtime().documents(), this::rolledBack);
                    actor.player().ifPresent((UUID player) -> toolkit().ifPresent((Toolkit<?, ?> menu) -> menu.undone(player, chosen.transaction())));
                    return lines;
                }
                finally { actor.player().ifPresent((UUID player) -> changes.forget(player, chosen.transaction())); }
            });
        });
    }

    /** The nodes the actor holds now, on its owner, for the file lane to check: every Animus node and the change's own. */
    private static Predicate<String> holds(CommandActor actor, RecentChanges.Change change) {
        Set<String> held = new HashSet<>();
        List<String> asked = new ArrayList<>(PermissionNodes.CHILDREN);
        asked.add(PermissionNodes.ADMIN);
        if (!change.node().isEmpty()) asked.add(change.node());
        for (String node : asked) if (actor.permitted(node)) held.add(node);
        return held::contains;
    }

    @FunctionalInterface interface Rollback { List<String> run(String transaction) throws IOException; }

    /** Reverts {@code change} when {@code holds} grants a node it needs, else names the node. File lane. */
    static List<String> undo(RecentChanges.Change change, Predicate<String> holds, DocumentStore documents, Rollback rollback) throws IOException {
        List<String> needed = UndoPermission.needed(change, documents);
        if (needed.stream().noneMatch(holds)) return List.of(Palette.error("No permission: " + needed.getFirst() + "; ask an operator to grant it"));
        return undone(change, rollback.run(change.transaction()));
    }

    static final String NOTHING_TO_UNDO = "Nothing to undo";

    /**
     * The change {@code /animus undo} reverts: the player's newest, or the one {@code given} names among their own. An
     * admin, and the console, may name any change; the console has no newest change of its own. Empty after saying why.
     */
    static Optional<RecentChanges.Change> undoing(CommandActor actor, RecentChanges changes, String given) {
        if (actor.player().isEmpty()) {
            if (!given.isBlank()) return Optional.of(new RecentChanges.Change(given, "", ""));
            actor.tell(Palette.error("The console has no last change; /animus rollback <id> undoes a change by its id"));
            return Optional.empty();
        }
        UUID player = actor.player().get();
        Optional<RecentChanges.Change> found = given.isBlank() ? changes.latest(player) : changes.find(player, given);
        if (found.isPresent()) return found;
        if (given.isBlank()) actor.tell(Palette.error(NOTHING_TO_UNDO));
        else if (actor.permitted(AnimusDirector.ADMIN)) return Optional.of(new RecentChanges.Change(given, "", ""));
        else actor.tell(Palette.error("Change " + given + " is not one of your recent changes; an admin can undo it with /animus rollback " + given));
        return Optional.empty();
    }

    /** {@code Undid: <what the change was>}, then any note the rollback added after its own line. */
    static List<String> undone(RecentChanges.Change change, List<String> rolledBack) {
        List<String> lines = new ArrayList<>(List.of(Palette.success("Undid: " + (change.what().isEmpty() ? "change " + ChatLine.shortId(change.transaction()) : change.what()))));
        lines.addAll(rolledBack.subList(Math.min(1, rolledBack.size()), rolledBack.size()));
        return lines;
    }

    /** What {@code /animus select none} reads as while no NPC is saved under that id: clear the selection. */
    static final String NONE = "none";

    @Director(description = "Select an NPC for the commands that take @selected: the one you look at, an id, or none to clear", origin = DirectorOrigin.PLAYER)
    @AnimusPermission(PermissionNodes.NPC_VIEW)
    @Positional("npc")
    void select(@Param(name = "npc", defaultValue = "", description = "The NPC id, @selected or none; the one you look at, or the nearest within 16 blocks, when left out") String npc) {
        CommandActor actor = actor();
        UUID player = actor.player().orElseThrow();
        Optional<Toolkit<?, ?>> toolkit = toolkit();
        select(actor, living(), npc, (String typed) -> npc(actor, typed), (String id) -> runtime().npcs().find(id).isPresent(),
                (String id) -> toolkit.ifPresent((Toolkit<?, ?> found) -> found.selectOnly(player, id)),
                () -> toolkit.ifPresent((Toolkit<?, ?> found) -> found.clear(player)));
    }

    /**
     * Selects {@code npc}, read by {@code named} as every command reads an NPC, or with none the NPC the player looks at or
     * stands nearest, and makes it glow for that player briefly. {@code chosen} makes it the toolkit's whole selection
     * too, so the wand and {@code @selected} agree; {@link #NONE} runs {@code cleared} instead, unless {@code saved} says
     * an NPC has that id.
     */
    static void select(CommandActor actor, NpcRuntime<?, ?> living, String npc, java.util.function.Function<String, Optional<String>> named,
                       Predicate<String> saved, java.util.function.Consumer<String> chosen, Runnable cleared) {
        if (npc.equalsIgnoreCase(NONE) && !saved.test(npc)) { cleared.run(); actor.tell(Palette.success("Selection cleared")); return; }
        UUID viewer = actor.player().orElseThrow();
        Optional<String> picked = npc.isEmpty() ? living.pick(viewer) : named.apply(npc);
        if (picked.isEmpty()) { if (npc.isEmpty()) actor.tell(Palette.error(NOTHING_IN_VIEW)); return; }
        try { living.select(viewer, picked.get()); }
        catch (RuntimeException refused) { actor.tell(Palette.error(reason(refused) + "; /animus list shows ids")); return; }
        chosen.accept(picked.get());
        living.highlight(viewer, picked.get(), SelectionPick.GLOW_TICKS);
        actor.rich(ChatLine.success("Selected ").copyable(picked.get()));
    }

    static final String NOTHING_IN_VIEW = "No NPC in view or within " + (int) SelectionPick.REACH + " blocks.";

    @Director(description = "Show your selected NPCs", origin = DirectorOrigin.PLAYER)
    @AnimusPermission(PermissionNodes.NPC_VIEW)
    void selection() {
        CommandActor actor = actor();
        Optional<ChatLine> reply = selection(selection(actor.player().orElseThrow()));
        if (reply.isPresent()) actor.rich(reply.get());
        else actor.tell("No NPC selected; /animus select <npc>, or click one with the wand (/animus tools wand)");
    }

    private static final int SELECTION_SHOWN = 10;

    /** Every selected NPC, the same list {@code @selected} reads. */
    static Optional<ChatLine> selection(List<String> all) {
        if (all.isEmpty()) return Optional.empty();
        if (all.size() == 1) return Optional.of(ChatLine.reply("Selected ").copyable(all.getFirst()));
        ChatLine line = ChatLine.reply("Selected " + all.size() + " NPCs: ");
        for (int index = 0; index < Math.min(SELECTION_SHOWN, all.size()); index++) {
            if (index > 0) line.text("&7, ");
            line.copyable(all.get(index));
        }
        if (all.size() > SELECTION_SHOWN) line.text("&7 +" + (all.size() - SELECTION_SHOWN));
        return Optional.of(line);
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
                    JsonObject fields = patch.isEmpty() ? null : Administration.patch(patch.get());
                    List<String> changes = List.of();
                    if (fields == null) document.addProperty("spawned", true);
                    else {
                        Optional<String> problem = Administration.patchProblem(runtime(), fields);
                        if (problem.isPresent()) { reply(actor, Palette.error("Cannot save " + id + ": " + problem.get())); return; }
                        changes = Administration.merge(document, fields);
                    }
                    NpcDefinition candidate;
                    try { candidate = NpcDefinition.parse(JsonDocuments.write(document)); }
                    catch (IOException refused) {
                        if (fields == null) throw refused;
                        reply(actor, Palette.error(Administration.refusal(id, fields, refused.getMessage())));
                        return;
                    }
                    NpcRepository.Draft draft = new NpcRepository.Draft(before.path(), before.expectedHash(), before.expectedRevision(), document);
                    String changed = Administration.describe(changes);
                    if (!stopping()) actor.reply(() -> {
                        if (patch.isEmpty()) commit(actor, candidate, draft, PermissionNodes.NPC_EDIT, "spawn",
                                (String tx) -> ChatLine.parse(Palette.success("Spawning " + id + "; tx " + tx + "; it becomes active once its chunk loads")));
                        else {
                            Optional<String> commands = EditorCommands.scheduleRefusal(actor, document);
                            if (commands.isPresent()) { actor.tell(Palette.error("Cannot save " + id + ": " + commands.get())); return; }
                            commit(actor, candidate, draft, PermissionNodes.NPC_EDIT, "save", (String tx) -> ChatLine.parse(Palette.success("Saved " + id + ": " + changed + "; tx " + tx)));
                        }
                    });
                } catch (IOException | RuntimeException e) { reply(actor, Palette.error(reason(e))); }
            });
        } catch (RejectedExecutionException e) { actor.tell(Palette.error(CommandActor.BUSY)); }
    }

    /**
     * On the actor's owner: refuses a spawned candidate this platform cannot run, then writes the draft with the revision it
     * was read at. {@code node} is checked again here, {@code verb} names the refusal, and {@code done} runs on the file
     * lane once the draft is saved and words the reply.
     */
    private void commit(CommandActor actor, NpcDefinition candidate, NpcRepository.Draft draft, String node, String verb, java.util.function.Function<String, ChatLine> done) {
        if (stopping() || !actor.permitted(node)) return;
        JsonElement spawned = draft.document().get("spawned");
        List<String> errors = spawned != null && spawned.isJsonPrimitive() && spawned.getAsBoolean() ? living().validateActivation(candidate) : List.of();
        if (!errors.isEmpty()) {
            actor.rich(ChatLine.error("Cannot " + verb + " " + candidate.id() + ": " + errors.getFirst() + (errors.size() == 1 ? "" : " (+" + (errors.size() - 1) + " more)")
                    + ("create".equals(verb) ? "; nothing was saved" : "; fix it with /animus edit " + candidate.id()))
                    .details("All", errors).docs(DocsLink.ACTIVATION));
            return;
        }
        try {
            services.files().execute(() -> {
                try {
                    String tx = runtime().npcs().apply(draft);
                    living().refresh();
                    ChatLine line = done.apply(tx);
                    if (!stopping()) actor.reply(() -> { if (!stopping()) actor.rich(line); });
                } catch (IOException | RuntimeException e) { reply(actor, Palette.error(reason(e))); }
            });
        } catch (RejectedExecutionException e) { actor.tell(Palette.error(CommandActor.BUSY + "; nothing was saved")); }
    }

    /** Builds this tree without services and renders its help, so a packaged jar proves Director loads without a server. */
    public static int selfCheck() {
        AnimusDirector director = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        AnimusHelp.Page page = AnimusHelp.page(director.engine().getRoot(), director.engine().getRoot().getChildren(), 0);
        int lines = AnimusHelp.lines(page).size();
        return lines + CommandWords.tokens("edit guard {\"name\":\"A B\"}").size();
    }
}
