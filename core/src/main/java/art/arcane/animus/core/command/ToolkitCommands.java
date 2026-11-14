package art.arcane.animus.core.command;

import art.arcane.animus.core.BuildIdentity;
import art.arcane.animus.core.admin.Administration;
import art.arcane.animus.core.behavior.runtime.BehaviorRuntime;
import art.arcane.animus.core.text.Palette;
import art.arcane.animus.core.toolkit.Toolkit;
import art.arcane.animus.core.toolkit.ToolkitMode;
import art.arcane.volmlib.util.director.DirectorOrigin;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * {@code /animus tools}: the wand, the menu mode, and the hidden commands the Animus menu's chips run. {@code /animus gui}
 * alone opens Home; {@code /animus tools} alone opens the selected NPC's screen, or Home when nothing is selected. The
 * same screens open on every platform.
 */
@Director(name = ToolkitCommands.GROUP, aliases = ToolkitCommands.ALIAS, description = "Open the selected NPC's screen, take the wand or set your menu mode")
@AnimusPermission(PermissionNodes.TOOLS)
final class ToolkitCommands extends CommandGroup {
    /**
     * {@code MENU} is the hidden command {@code /animus tools} alone runs, {@code HOME} the hidden one {@code /animus gui}
     * alone runs, and {@code CAPTURE} the hidden one the chat chips of a route being made run. Help and completion never
     * show them. {@code GUI} is the word that, alone, opens the menu; with more words it names the chest GUI commands.
     */
    static final String GROUP = "tools", ALIAS = "tool", MENU = "menu", HOME = "home", CAPTURE = "capture", STEP = "route-step", NAME = "name", GUI = "gui";
    static final List<String> STEPS = List.of("add", "undo", "save", "cancel");
    /** The parameter name; {@code mode} alone already completes shop modes. */
    static final String MENU_MODE = "menu-mode";

    /** Hooks the commands into the menu installed for these services, so a menu the wand opens works as one a command opens. */
    ToolkitCommands(CommandServices services) {
        super(services);
        if (services != null) Toolkit.of(services.shops().screens()).ifPresent((Toolkit<?, ?> toolkit) -> toolkit.hook(commands()));
    }

    /** {@code /animus tools} or {@code /animus tool} alone runs the hidden {@code /animus tools menu}. */
    static List<String> expand(List<String> tokens) {
        return tokens.size() == 1 && (tokens.getFirst().equalsIgnoreCase(GROUP) || tokens.getFirst().equalsIgnoreCase(ALIAS)) ? List.of(GROUP, MENU) : tokens;
    }

    /** The command an {@code [Edit]} chip runs: the NPC's own screen. */
    static String edit(String id) { return "/" + BuildIdentity.ID + " " + GROUP + " " + MENU + " " + id; }

    @Director(name = MENU, hidden = true, description = "Open the Animus menu", origin = DirectorOrigin.PLAYER)
    @Positional("npc")
    void menu(@Param(name = "npc", defaultValue = "", description = "The NPC whose screen opens, as the only one selected") String npc) {
        CommandActor actor = actor();
        UUID player = actor.player().orElseThrow();
        Optional<String> chosen = npc.isEmpty() ? Optional.empty() : npc(actor, npc);
        if (!npc.isEmpty() && chosen.isEmpty()) return;
        toolkit(actor).ifPresent((Toolkit<?, ?> toolkit) -> {
            chosen.ifPresent((String id) -> toolkit.selectOnly(player, id));
            toolkit.open(player);
        });
    }

    @Director(name = HOME, hidden = true, description = "Open the Animus menu at Home", origin = DirectorOrigin.PLAYER)
    void home() {
        CommandActor actor = actor();
        toolkit(actor).ifPresent((Toolkit<?, ?> toolkit) -> toolkit.home(actor.player().orElseThrow()));
    }

    @Director(name = CAPTURE, hidden = true, description = "Add a point to the route you are making, undo one, save it or cancel it", origin = DirectorOrigin.PLAYER)
    @Positional(STEP)
    void capture(@Param(name = STEP, description = "add, undo, save or cancel") String step,
                 @Param(name = NAME, defaultValue = "", description = "The name of the route the chip was sent for; another route's chip is refused") String route) {
        CommandActor actor = actor();
        toolkit(actor).ifPresent((Toolkit<?, ?> toolkit) -> toolkit.route(actor.player().orElseThrow(), step, route));
    }

    @Director(description = "Take the wand: right-click an NPC to edit it, sneak-click to add or remove", origin = DirectorOrigin.PLAYER)
    void wand() {
        CommandActor actor = actor();
        toolkit(actor).ifPresent((Toolkit<?, ?> toolkit) -> toolkit.wand(actor.player().orElseThrow()));
    }

    @Director(description = "Show or set your menu mode: Simple shows the common tasks, Advanced shows everything", origin = DirectorOrigin.PLAYER)
    @Positional(MENU_MODE)
    void mode(@Param(name = MENU_MODE, defaultValue = "", description = "simple or advanced; left out, shows your mode") String mode) {
        CommandActor actor = actor();
        toolkit(actor).ifPresent((Toolkit<?, ?> toolkit) -> menuMode(actor, toolkit, mode));
    }

    /** Replies the player's mode, or sets it from {@code word} first. */
    static void menuMode(CommandActor actor, Toolkit<?, ?> toolkit, String word) {
        UUID player = actor.player().orElseThrow();
        if (word.isBlank()) { actor.tell("Mode: " + toolkit.mode(player).label()); return; }
        Optional<ToolkitMode> chosen = ToolkitMode.parse(word);
        if (chosen.isEmpty()) { actor.tell(Palette.error("Mode is simple or advanced")); return; }
        toolkit.mode(player, chosen.get());
        actor.tell(Palette.success("Mode: " + chosen.get().label()));
    }

    /** What the menu takes from the command layer: inspect, pairing, revive, whether a player is recording, the running behavior runs and shop prices. */
    private Toolkit.Commands commands() {
        return new Toolkit.Commands(this::inspect, (CommandActor viewer) -> services.editor().pair(viewer, "", "", "", ""),
                (CommandActor viewer, String id) -> NpcCommands.revive(this, viewer, id), (UUID player) -> services.recordings().recording(player), runs(services.behaviors()),
                () -> services.shops().prices());
    }

    /** The running menu. */
    private Optional<Toolkit<?, ?>> toolkit(CommandActor actor) {
        Optional<Toolkit<?, ?>> found = Toolkit.of(services.shops().screens());
        if (found.isEmpty()) actor.tell(Palette.error("The Animus menu is not running on this server"));
        return found;
    }

    /** The runs active on an NPC, as {@code /animus behaviors trace} lists them, for the menu's Cancel a run; none before behaviors start. */
    static Toolkit.Runs runs(Supplier<BehaviorCommands> behaviors) {
        return (String npc) -> {
            BehaviorCommands found = behaviors.get();
            if (found == null) return CompletableFuture.completedFuture(List.of());
            return found.running(npc).thenApply((List<BehaviorRuntime.Execution> active) -> active.stream()
                    .map((BehaviorRuntime.Execution execution) -> new Toolkit.Run(execution.report().id(), execution.npcId(), execution.behaviorId())).toList());
        };
    }

    /** The same two lines {@code /animus inspect} answers. */
    private void inspect(CommandActor actor, String id) {
        Administration administration = new Administration(runtime());
        try {
            services.files().execute(() -> {
                Administration.Inspection inspection;
                try { inspection = administration.inspect(id); }
                catch (IOException | RuntimeException failure) { reply(actor, Palette.error(String.valueOf(failure.getMessage()))); return; }
                if (!stopping()) actor.reply(() -> { if (!stopping()) AnimusCommandTree.inspect(actor, inspection, living().inspect(id)); });
            });
        } catch (RejectedExecutionException full) { actor.tell(Palette.error(CommandActor.BUSY)); }
    }
}
