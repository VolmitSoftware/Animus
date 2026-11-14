package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The running runs of one behavior on the selected NPCs (Advanced), from Cancel a run. A click stops one through
 * {@code /animus behaviors cancel}, after asking once, and the list reads again.
 */
final class BehaviorRunsScreen<P, S> extends ToolScreen<P, S> {
    static final int RELOAD = ACTIONS;
    /** Ticks before the list reads again after a cancel, so the runtime has stopped the run. */
    static final int CANCEL_DELAY = 10;
    private final String behavior;
    private final List<String> ids;
    private List<Toolkit.Run> runs;

    BehaviorRunsScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String behavior, List<String> ids) {
        super(toolkit, viewer, commands);
        this.behavior = behavior;
        this.ids = List.copyOf(ids);
    }

    @Override void begin() { reload(); }

    @Override String title() { return "Runs of " + behavior; }
    @Override String node() { return PermissionNodes.BEHAVIORS; }
    @Override int rows() { return 6; }

    /** Asks the command layer's run source for every selected NPC, then shows this behavior's runs on the player's owner. */
    private void reload() {
        List<CompletableFuture<List<Toolkit.Run>>> asked = new ArrayList<>();
        for (String id : ids) asked.add(commands.runs().running(id).toCompletableFuture());
        CompletableFuture.allOf(asked.toArray(CompletableFuture[]::new)).whenComplete((Void done, Throwable failed) -> {
            List<Toolkit.Run> found = new ArrayList<>();
            if (failed == null) for (CompletionStage<List<Toolkit.Run>> stage : asked)
                stage.toCompletableFuture().join().stream().filter((Toolkit.Run run) -> run.behavior().equals(behavior)).forEach(found::add);
            toolkit.living().players().deliver(viewer.id(), (P online) -> {
                if (failed != null) refuse("Runs could not be read; the server log has the cause");
                runs = found;
                viewer.refresh();
            });
        });
    }

    @Override void draw() {
        if (runs == null) icon(22, "minecraft:clock", "&7Reading runs", List.of(), Optional.empty());
        else list(runs, (Toolkit.Run run) -> run.npc() + " " + run.id(), "Nothing running. Run starts one", (Integer slot, Toolkit.Run run) ->
                button(slot, "minecraft:repeater", title("On " + run.npc()), lore(List.of("&8" + run.id()), any("stop it")),
                        (GuiModel.Click click) -> ask(slot, "Stops this run of " + behavior + " on " + run.npc(), 1, true, () -> {
                            viewer.host().command(Optional.of(viewer.player()), "animus behaviors cancel execution=" + run.id());
                            viewer.schedule(CANCEL_DELAY, this::reload);
                        })));
        button(RELOAD, "minecraft:repeater", title("Reload"), lore(List.of("&7Reads the running runs again"), any("reload")), (GuiModel.Click click) -> reload());
        back();
        help("These runs of " + behavior + " are running on the selected NPCs now. Click one to stop it.", "21-in-game-toolkit", "behavior-controls");
    }
}
