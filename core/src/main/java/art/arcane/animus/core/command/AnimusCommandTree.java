package art.arcane.animus.core.command;

import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.admin.Administration;
import art.arcane.animus.core.behavior.builtin.BuiltinCapabilities;
import art.arcane.animus.core.behavior.builtin.PendingChoices;
import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.Subsystem;
import art.arcane.animus.core.migration.CitizensDropIn;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.JsonDocuments;
import art.arcane.animus.core.text.Palette;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * The {@code /animus} root: NPC definitions, selection, movement and manual combat, plus every command group. The same
 * tree runs on Bukkit, Folia and the mod loaders; a platform without a feature answers why it is unavailable.
 */
@Director(name = BuildIdentity.ID, description = "Manage virtual NPCs, their behaviors and imports")
public final class AnimusCommandTree extends CommandGroup {
    WorldCommands.Navigation navigation;
    WorldCommands.Ride ride;
    WorldCommands.Combat combat;
    ContentCommands.Modules modules;
    ContentCommands.Rigs rigs;
    ContentCommands.Pack pack;
    ContentCommands.Import imports;
    ServiceCommands.Shop shop;
    ServiceCommands.Gui gui;
    ServiceCommands.Build build;
    ServiceCommands.Editor editor;
    BehaviorTree.Behaviors behaviors;
    DiagnosticCommands.Debug debug;
    DiagnosticCommands.Proof proof;
    DiagnosticCommands.FoliaProof foliaproof;

    public AnimusCommandTree(CommandServices services) {
        super(services);
        navigation = new WorldCommands.Navigation(services);
        ride = new WorldCommands.Ride(services);
        combat = new WorldCommands.Combat(services);
        modules = new ContentCommands.Modules(services);
        rigs = new ContentCommands.Rigs(services);
        pack = new ContentCommands.Pack(services);
        imports = new ContentCommands.Import(services);
        shop = new ServiceCommands.Shop(services);
        gui = new ServiceCommands.Gui(services);
        build = new ServiceCommands.Build(services);
        editor = new ServiceCommands.Editor(services);
        behaviors = new BehaviorTree.Behaviors(services);
        debug = new DiagnosticCommands.Debug(services);
        proof = new DiagnosticCommands.Proof(services);
        foliaproof = new DiagnosticCommands.FoliaProof(services);
    }

    /** The engine for this tree, with completion values from the running services. */
    public AnimusDirector director() { return new AnimusDirector(this, this::values); }

    /** Completion values by parameter name. */
    Collection<String> values(String parameter, CommandActor actor) {
        return switch (parameter) {
            case "npc", "build" -> {
                List<String> ids = new ArrayList<>(runtime().npcs().all().stream().map(NpcDefinition::id).limit(100).toList());
                if (actor.player().isPresent() && living().selection(actor.player().get()).isPresent()) ids.add(SELECTED);
                yield ids;
            }
            case "behavior" -> { BehaviorCommands current = services.behaviors().get(); yield current == null ? List.of() : current.behaviorIds(); }
            case "state" -> { BehaviorCommands current = services.behaviors().get(); yield current == null ? List.of() : current.stateBehaviors(); }
            case "execution" -> { BehaviorCommands current = services.behaviors().get(); yield current == null ? List.of() : current.executionIds(); }
            case "shop" -> services.shops().shopIds();
            case "gui" -> services.guis().guiIds();
            case "menu" -> services.guis().menuIds();
            case "schematic" -> services.builds().schematicNames();
            case "animation" -> runtime().rigs().catalog().animations().keySet().stream().limit(100).toList();
            case "capability" -> services.extensions() == null ? List.of() : services.extensions().capabilities().stream()
                    .map((art.arcane.animus.api.Capabilities.Availability capability) -> capability.descriptor().key().toString()).limit(100).toList();
            case "token" -> services.editor().revocable();
            case "player" -> services.players().get();
            case "viewer" -> services.debug().viewers();
            case "enabled" -> List.of("on", "off", "toggle");
            case "categories" -> { List<String> categories = new ArrayList<>(CitizensDropIn.CATEGORIES); categories.add("all"); categories.add(CitizensDropIn.COMMANDS); yield categories; }
            default -> List.of();
        };
    }

    private Administration administration() { return new Administration(runtime()); }

    @Director(description = "Show what Animus runs and the settings in effect")
    @AnimusPermission("animus.status")
    void status() {
        CommandActor actor = actor();
        files(actor, administration()::status, () -> actor.tell(living().status()));
    }

    @Director(description = "List NPC definitions")
    void list(@Param(name = "page", defaultValue = "1", description = "The page to show") int page) {
        CommandActor actor = actor();
        Administration administration = administration();
        try {
            services.files().execute(() -> {
                List<String> rows = administration.rows();
                if (!stopping()) actor.reply(() -> listing(actor, "NPC definitions (" + rows.size() + ")", "/" + BuildIdentity.ID + " list", rows, page, "No NPC definitions yet; /animus create adds one."));
            });
        } catch (RejectedExecutionException full) { actor.tell(Palette.error("The Animus file queue is full; retry shortly.")); }
    }

    @Director(description = "Create a dormant NPC definition")
    void create(@Param(name = "id", description = "The new NPC id") String id,
                @Param(name = "type", description = "The entity type, such as minecraft:player") String type,
                @Param(name = "world", description = "The world it stands in") String world,
                @Param(name = "x", description = "X position") double x,
                @Param(name = "y", description = "Y position") double y,
                @Param(name = "z", description = "Z position") double z,
                @Param(name = "group", description = "The group it belongs to") String group,
                @Param(name = "name", description = "Its display name; quote it to use spaces") String name) {
        CommandActor actor = actor();
        files(actor, () -> { String line = administration().create(id, type, world, x, y, z, group, name); living().refresh(); return List.of(Palette.success(line)); });
    }

    @Director(description = "Show an NPC's definition and runtime state")
    void inspect(@Param(name = "npc", description = "The NPC id, or @selected") String npc) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> files(actor, () -> List.of(administration().inspect(id)), () -> actor.tell(living().inspect(id))));
    }

    @Director(description = "Merge a JSON object into an NPC's definition")
    void edit(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
              @Param(name = "patch", description = "A JSON object whose top-level fields replace the definition's; write patch={...} if it contains =") String patch) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> change(actor, id, Optional.of(patch)));
    }

    @Director(description = "Copy an NPC definition under a new id, dormant")
    void clone(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
               @Param(name = "id", description = "The copy's id") String id) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String source) -> files(actor, () -> { String line = administration().duplicate(source, id); living().refresh(); return List.of(Palette.success(line)); }));
    }

    @Director(description = "Delete an NPC definition; rollback restores it")
    void delete(@Param(name = "npc", description = "The NPC id, or @selected") String npc) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> files(actor, () -> { String line = administration().delete(id); living().refresh(); return List.of(Palette.success(line)); }));
    }

    @Director(description = "Spawn an NPC after checking it can run here")
    void spawn(@Param(name = "npc", description = "The NPC id, or @selected") String npc) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> change(actor, id, Optional.empty()));
    }

    @Director(description = "Despawn an NPC; its definition stays")
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

    @Director(description = "Undo a transaction by its id")
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
            actor.tell(Palette.success("Selected " + npc));
        } catch (RuntimeException refused) { actor.tell(Palette.error(String.valueOf(refused.getMessage()))); }
    }

    @Director(description = "Show your selected NPC", origin = DirectorOrigin.PLAYER)
    void selection() {
        CommandActor actor = actor();
        actor.tell(living().selection(actor.player().orElseThrow()).map((String id) -> "Selected " + id)
                .orElse("No NPC selected. Use /animus select <npc> or interact with a visible NPC."));
    }

    @Director(description = "Walk, swim or fly an NPC to a point; whole-number x and z mean the block centre")
    void navigate(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
                  @Param(name = "x", description = "Target X") double x,
                  @Param(name = "y", description = "Target Y") double y,
                  @Param(name = "z", description = "Target Z") double z,
                  @Param(name = "mode", defaultValue = "walk", description = "walk, swim or fly") MovementProfile.Mode mode,
                  @Param(name = "speed", defaultValue = "2.0", description = "Blocks per second") double speed) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> onNpcOwner(actor, id, "Animus navigation", () -> {
            WorldPosition destination = new WorldPosition(living().navigationOrigin(id).world(), new Vec3(blockCenter(x), y, blockCenter(z)));
            return "Navigation requested for " + id + "; route=" + living().navigate(id, destination, mode, speed);
        }));
    }

    /**
     * A whole-number x or z names a block, so the goal is its centre; a goal on the block corner makes the body clip the
     * next block. A fractional value is an exact point and stays.
     */
    static double blockCenter(double coordinate) { return coordinate == Math.floor(coordinate) ? coordinate + 0.5 : coordinate; }

    @Director(description = "Turn an NPC to look at a point")
    void look(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
              @Param(name = "x", description = "Target X") double x,
              @Param(name = "y", description = "Target Y") double y,
              @Param(name = "z", description = "Target Z") double z) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> onNpcOwner(actor, id, "Animus navigation", () -> {
            living().lookAt(id, new WorldPosition(living().navigationOrigin(id).world(), new Vec3(x, y, z)));
            return "Look target set for " + id;
        }));
    }

    @Director(description = "Make an NPC hit a living entity once")
    void attack(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
                @Param(name = "target", description = "The target's UUID") UUID target,
                @Param(name = "damage", defaultValue = "1", description = "Damage dealt") double damage) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> WorldCommands.combat(actor, () -> services.combat().attack(id, target, damage)));
    }

    @Director(description = "Lend a mob to an NPC's control, or stop with stop")
    void target(@Param(name = "npc", description = "The NPC id, or @selected") String npc,
                @Param(name = "mob", description = "The mob's UUID, or stop to end every control") String mob) {
        CommandActor actor = actor();
        npc(actor, npc).ifPresent((String id) -> WorldCommands.combat(actor, () -> mob.equalsIgnoreCase("stop")
                ? services.combat().releaseMobs(id) : services.combat().control(id, UUID.fromString(mob))));
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
                    if (patch.isEmpty()) document.addProperty("spawned", true);
                    else for (Map.Entry<String, JsonElement> entry : JsonDocuments.object(patch.get()).entrySet()) document.add(entry.getKey(), entry.getValue());
                    NpcDefinition candidate = NpcDefinition.parse(JsonDocuments.write(document));
                    NpcRepository.Draft draft = new NpcRepository.Draft(before.path(), before.expectedHash(), before.expectedRevision(), document);
                    if (!stopping()) actor.reply(() -> commit(actor, candidate, draft, patch.isEmpty()));
                } catch (IOException | RuntimeException e) { reply(actor, Palette.error(String.valueOf(e.getMessage()))); }
            });
        } catch (RejectedExecutionException e) { actor.tell(Palette.error("The Animus file queue is full; retry shortly.")); }
    }

    private void commit(CommandActor actor, NpcDefinition candidate, NpcRepository.Draft draft, boolean spawn) {
        if (stopping() || !actor.permitted(AnimusDirector.ADMIN)) return;
        JsonElement spawned = draft.document().get("spawned");
        List<String> errors = spawned != null && spawned.isJsonPrimitive() && spawned.getAsBoolean() ? living().validateActivation(candidate) : List.of();
        if (!errors.isEmpty()) { errors.stream().limit(12).forEach((String error) -> actor.tell(Palette.error("Cannot activate: " + error))); return; }
        try {
            services.files().execute(() -> {
                try {
                    String tx = runtime().npcs().apply(draft);
                    living().refresh();
                    reply(actor, Palette.success((spawn ? "Spawn requested for " + candidate.id() : "Saved") + "; transaction " + tx));
                } catch (IOException | RuntimeException e) { reply(actor, Palette.error(String.valueOf(e.getMessage()))); }
            });
        } catch (RejectedExecutionException e) { actor.tell(Palette.error("The Animus file queue is full; no edit was applied. Retry shortly.")); }
    }

    /** Builds this tree without services and renders its help, so a packaged jar proves Director loads without a server. */
    public static int selfCheck() {
        AnimusDirector director = new AnimusDirector(new AnimusCommandTree(null), (String parameter, CommandActor actor) -> List.of());
        int lines = art.arcane.volmlib.util.director.help.DirectorMiniMenu.render(
                new art.arcane.volmlib.util.director.help.DirectorMiniMenu.DirectorHelpPage(director.engine().getRoot(), director.engine().getRoot().getChildren(), 0,
                        art.arcane.volmlib.util.director.help.DirectorMiniMenu.MAX_ENTRIES_PER_PAGE), AnimusDirector.THEME, AnimusDirector.TEXT).size();
        return lines + CommandWords.tokens("edit guard {\"name\":\"A B\"}").size();
    }
}
