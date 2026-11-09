package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Active NPCs in the player's world, nearest first, as measured when the screen opened. */
final class FindScreen<P, S> extends ToolScreen<P, S> {
    static final int MAXIMUM = LIST * 6;
    private final List<Found> found;
    private Map<String, NpcItem> skins = Map.of();

    record Found(NpcDefinition definition, double distance) {}

    FindScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) {
        super(toolkit, viewer, commands);
        found = nearest(toolkit, viewer.host().pose(viewer.player()));
        List<NpcDefinition> shown = found.stream().map(Found::definition).toList();
        toolkit.later(viewer, (ToolkitFiles ignored) -> new SkinFiles(toolkit.npcs(), toolkit.documents()).worn(shown),
                (Map<String, NpcItem> worn) -> { skins = worn; viewer.refresh(); });
    }

    static List<Found> nearest(Toolkit<?, ?> toolkit, Optional<ScreenHost.Pose> pose) {
        if (pose.isEmpty()) return List.of();
        Vec3 at = new Vec3(pose.get().x(), pose.get().y(), pose.get().z());
        List<Found> near = new ArrayList<>();
        for (NpcDefinition definition : toolkit.npcs().all()) {
            Optional<VirtualNpcState.Snapshot> state = toolkit.living().state(definition.uuid());
            if (state.isEmpty() || !pose.get().in(state.get().position().world())) continue;
            near.add(new Found(definition, state.get().position().point().distance(at)));
        }
        near.sort(Comparator.comparingDouble(Found::distance));
        return near.subList(0, Math.min(MAXIMUM, near.size()));
    }

    @Override String title() { return "Nearest NPCs"; }
    @Override int rows() { return 6; }

    @Override void draw() {
        list(found, "No active NPCs in this world", (Integer slot, Found npc) -> {
            String id = npc.definition().id();
            boolean player = ToolkitFiles.PLAYER.equals(npc.definition().json().get("type").getAsString());
            List<String> lines = List.of("&f" + String.format(Locale.ROOT, "%.1f", npc.distance()) + "&7 blocks away",
                    folder(npc.definition()));
            NpcItem icon = player ? Optional.ofNullable(skins.get(id)).orElse(new NpcItem(HEAD, 1, Optional.empty(), Optional.empty()))
                    : new NpcItem("minecraft:name_tag", 1, Optional.empty(), Optional.empty());
            button(slot, icon, title(id), lore(lines, hints("teleport to it", "select it", "bring it here")), (GuiModel.Click click) -> {
                if (sneak(click)) toolkit.bring(viewer, id);
                else if (right(click)) toolkit.only(viewer.player(), id);
                else toolkit.visit(viewer.player(), id);
            });
        });
        back();
    }
}
