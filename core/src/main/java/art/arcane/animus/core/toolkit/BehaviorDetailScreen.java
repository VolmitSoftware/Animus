package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import com.google.gson.JsonElement;
import java.util.List;
import java.util.Optional;

/**
 * One behavior's controls (Advanced). Run, Trace and Validate go through {@code /animus behaviors}, so the replies are
 * the commands' own; Cancel a run lists the selection's running runs of this behavior to pick from.
 */
final class BehaviorDetailScreen<P, S> extends ToolScreen<P, S> {
    static final int HEADER = 4, RUN = 10, TRACE = 11, CANCEL = 12, VALIDATE = 14, CONVERSATION = 19, REMOVE = 35;
    private final BehaviorFiles.Behavior behavior;
    private final Runnable changed;

    /** {@code changed} runs after Remove from NPC is saved, so the list behind reads its counts again. */
    BehaviorDetailScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, BehaviorFiles.Behavior behavior, Runnable changed) {
        super(toolkit, viewer, commands);
        this.behavior = behavior; this.changed = changed;
    }

    @Override String title() { return behavior.id(); }
    @Override String node() { return PermissionNodes.BEHAVIORS; }
    @Override int rows() { return 6; }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        String id = behavior.id();
        icon(HEADER, "minecraft:book", title(id), List.of("&7" + behavior.pack().map((String pack) -> "Pack " + pack).orElse("Loose file") + ", " + behavior.triggers(),
                "&fNow: on " + listing(selected) + " of " + selected.size() + " selected", "&8" + behavior.path()), Optional.empty());
        confirm(RUN, "minecraft:lime_dye", "Run", "Runs it once on each selected NPC", selected.size(), false,
                () -> chosen().ifPresent((List<String> ids) -> { for (String npc : ids) command("animus behaviors run npc=" + npc + " behavior=" + id); }));
        button(TRACE, "minecraft:spyglass", title("Trace"), lore(List.of("&7Running and recent runs", "&7Shows in chat"), any("trace")),
                (GuiModel.Click click) -> chosen().ifPresent((List<String> ids) -> ask(TRACE, "Traces " + named(ids), ids.size(), false, () -> {
                    viewer.close();
                    for (String npc : ids) command("animus behaviors trace npc=" + npc);
                })));
        button(CANCEL, "minecraft:barrier", title("Cancel a run"), lore(List.of("&7Pick a running run to stop"), any("list runs")),
                (GuiModel.Click click) -> chosen().ifPresent((List<String> ids) -> push(new BehaviorRunsScreen<>(toolkit, viewer, commands, id, ids))));
        button(VALIDATE, "minecraft:name_tag", title("Validate"), lore(List.of("&7Checks the file without loading it", "&7Shows in chat"), any("validate")),
                (GuiModel.Click click) -> command("animus behaviors validate behavior=" + id));
        if (behavior.misfit().isEmpty())
            button(CONVERSATION, "minecraft:writable_book", title("Open as conversation"), lore(List.of("&7Lines and answers"), any("edit it")),
                    (GuiModel.Click click) -> toolkit.later(viewer, (ToolkitFiles ignored) -> BehaviorScreen.files(toolkit).conversation(id),
                            (BehaviorFiles.Loaded loaded) -> push(new ConversationScreen<>(toolkit, viewer, commands, loaded))));
        else icon(CONVERSATION, "minecraft:paper", "&7Not a conversation", List.of("&7" + behavior.misfit().get(), "&7Edit its nodes in the web editor"), Optional.empty());
        button(REMOVE, "minecraft:shears", title("Remove from NPC"), lore(List.of("&7Takes it off the selected NPCs"), any("remove it")),
                (GuiModel.Click click) -> chosen().ifPresent((List<String> ids) -> ask(REMOVE, "Takes " + id + " off " + named(ids), ids.size(), false,
                        () -> toolkit.write(viewer, (ToolkitFiles ignored) -> BehaviorScreen.files(toolkit).attach(id, ids, false), changed))));
        back();
        help("Run, trace and check one behavior on the selected NPCs. Remove from NPC takes it off them.", "21-in-game-toolkit", "behavior-controls");
    }

    /** How many selected NPCs list this behavior in their own {@code behaviors}, as stored now. */
    private int listing(List<String> selected) {
        int count = 0;
        for (String id : selected) {
            Optional<NpcDefinition> npc = toolkit.npcs().stored().stream().filter((NpcDefinition definition) -> definition.id().equals(id)).findFirst();
            JsonElement listed = npc.map((NpcDefinition definition) -> definition.json().get("behaviors")).orElse(null);
            if (listed != null && listed.isJsonArray() && listed.getAsJsonArray().asList().stream().anyMatch((JsonElement value) -> value.isJsonPrimitive() && value.getAsString().equals(behavior.id())))
                count++;
        }
        return count;
    }

    private void command(String line) { viewer.host().command(Optional.of(viewer.player()), line); }
}
