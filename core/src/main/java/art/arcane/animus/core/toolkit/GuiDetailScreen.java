package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.gui.GuiBinding;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One GUI file: show it, how the selected NPCs that use it open or place it, unbind and delete. The values shown are
 * the first such NPC's; a change goes on all of them in one transaction. A hologram's offset is from the NPC's feet;
 * without one it floats 0.6 above the head, and the first step starts from {@link #HEAD}.
 */
final class GuiDetailScreen<P, S> extends ToolScreen<P, S> {
    static final int SUMMARY = 4, PREVIEW = 19, OPEN_ON = 20, X = 20, Y = 21, Z = 22, ABOVE = 24, UNBIND = 31, DELETE = 33;
    static final double[] HEAD = {0, 2.4, 0};
    static final List<String> CLICKS = List.of("right", "left", "both");
    private final ShopFiles.Gui gui;
    private final Runnable changed;
    private List<String> users;

    GuiDetailScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, ShopFiles.Gui gui, Runnable changed) {
        super(toolkit, viewer, commands);
        this.gui = gui; this.changed = changed;
        reload();
    }

    private void reload() { toolkit.later(viewer, (ToolkitFiles ignored) -> files().guiUsers(gui.kind(), gui.id()), (List<String> found) -> { users = found; viewer.refresh(); }); }

    private ShopFiles files() { return new ShopFiles(toolkit.npcs(), toolkit.documents()); }

    @Override String title() { return GuiBindScreen.tab(gui.kind()).replaceFirst("s$", "") + " " + gui.id(); }
    @Override int rows() { return 6; }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        String part = gui.kind() == DocumentKind.HOLOGRAM ? "hologram" : "open";
        Set<String> chosen = new HashSet<>(selected);
        Optional<JsonObject> binding = Optional.empty();
        int bound = 0;
        for (NpcDefinition definition : toolkit.npcs().stored()) {
            if (!chosen.contains(definition.id())) continue;
            Optional<JsonObject> found = ShopFiles.module(definition.json(), GuiBinding.MODULE)
                    .filter((JsonObject module) -> module.has(part) && module.get(part).isJsonObject() && new JsonPrimitive(gui.id()).equals(module.getAsJsonObject(part).get("id")))
                    .map((JsonObject module) -> module.getAsJsonObject(part));
            if (found.isEmpty()) continue;
            bound++;
            if (binding.isEmpty()) binding = found;
        }
        List<String> summary = new ArrayList<>();
        gui.title().ifPresent((String shown) -> summary.add("&f" + shown));
        summary.add("&7" + gui.path());
        summary.add("&7Used by &f" + (users == null ? "reading..." : names(users, 4)));
        if (gui.kind() != DocumentKind.TEXT_ANIMATION) summary.add("&7In the selection &f" + bound + "&7 of &f" + selected.size());
        summary.add("&7The layout is painted in the web editor");
        icon(SUMMARY, GuiBindScreen.icon(gui.kind()), title(gui.id()), summary, Optional.empty());
        if (gui.kind() == DocumentKind.INVENTORY || gui.kind() == DocumentKind.MENU) {
            button(PREVIEW, "minecraft:ender_eye", title("Show it to me"), any("open it for you now"), (GuiModel.Click click) -> GuiBindScreen.preview(viewer, gui));
            String openOn = binding.map((JsonObject open) -> open.has("openOn") ? open.get("openOn").getAsString() : "right").orElse("right");
            String next = cycle(GuiModel.Click.LEFT, CLICKS, openOn);
            if (bound > BULK) guarded(OPEN_ON, "minecraft:lever", "Set opens on to " + next, "Opens on " + openOn + " click", bound, false, () -> openOn(next));
            else button(OPEN_ON, "minecraft:lever", title("Opens on: " + openOn + " click"), lore(List.of(binding.isPresent() ? "&7Which click on the NPC opens it" : "&7No selected NPC opens this"),
                    hints("next click", "previous click", "next click")), (GuiModel.Click click) -> openOn(cycle(click, CLICKS, openOn)));
        }
        if (gui.kind() == DocumentKind.HOLOGRAM) offset(binding, bound);
        if (gui.kind() != DocumentKind.TEXT_ANIMATION)
            guarded(UNBIND, "minecraft:shears", "Unbind from selection", "Removes it from " + ToolkitFiles.count(bound), bound, false,
                    () -> chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles ignored) -> files().unbindGui(gui.kind(), gui.id(), ids))));
        boolean used = users != null && !users.isEmpty();
        guarded(DELETE, "minecraft:lava_bucket", "Delete file", used ? "Refused while used; unbind first" : "Rollback restores it", 0, true,
                () -> ShopFiles.save(toolkit, viewer, (ToolkitFiles ignored) -> files().deleteGui(gui.kind(), gui.id()), GuiBindScreen.RELOAD_COMMAND, () -> { changed.run(); viewer.back(); }));
        back();
    }

    /** X, Y and Z buttons that step the offset by 0.1 (sneak 1), and one that puts it back above the head. */
    private void offset(Optional<JsonObject> binding, int bound) {
        Optional<double[]> current = binding.filter((JsonObject hologram) -> hologram.has("offset") && hologram.get("offset").isJsonArray()).map((JsonObject hologram) -> {
            JsonArray vector = hologram.getAsJsonArray("offset");
            return new double[]{vector.get(0).getAsDouble(), vector.get(1).getAsDouble(), vector.get(2).getAsDouble()};
        });
        double[] at = current.orElse(HEAD);
        String[] axes = {"X", "Y", "Z"};
        int[] slots = {X, Y, Z};
        for (int axis = 0; axis < 3; axis++) {
            int index = axis;
            String detail = current.isPresent() ? "&7From the NPC's feet" : "&7Above the head now; starts from 0, 2.4, 0";
            String name = "Offset " + axes[index] + ": " + (current.isPresent() ? String.valueOf(at[index]) : "auto");
            if (bound > BULK) guarded(slots[index], "minecraft:end_rod", "Raise offset " + axes[index] + " by 0.1", name, bound, false, () -> moved(at, index, GuiModel.Click.LEFT));
            else button(slots[index], "minecraft:end_rod", title(name), lore(List.of(binding.isPresent() ? detail : "&7No selected NPC shows this", "&7Sneak steps by 1, within 16"),
                    hints("+0.1", "-0.1", "+1 with left, -1 with right")), (GuiModel.Click click) -> moved(at, index, click));
        }
        guarded(ABOVE, "minecraft:feather", "Above the head", "Removes the offset", bound, false,
                () -> chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles ignored) -> files().offset(gui.id(), ids, Optional.empty()))));
    }

    private void moved(double[] at, int axis, GuiModel.Click click) {
        double[] next = at.clone();
        next[axis] = step(click, at[axis], 0.1, -16, 16);
        chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles ignored) -> files().offset(gui.id(), ids, Optional.of(next))));
    }

    private void openOn(String openOn) {
        chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles ignored) -> files().openOn(gui.id(), ids, openOn)));
    }
}
