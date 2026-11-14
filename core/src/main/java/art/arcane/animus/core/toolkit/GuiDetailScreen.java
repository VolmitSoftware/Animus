package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.gui.GuiBinding;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One chest GUI, menu or text animation (Advanced): show it, how the selected NPCs that use it open it, take it off them
 * and delete it. Opens on shows their shared click, or Mixed; a change goes on all of them in one transaction. Saved
 * holograms have their own screens, {@link SavedHologramScreen} and the NPC's {@link HologramScreen}.
 */
final class GuiDetailScreen<P, S> extends ToolScreen<P, S> {
    static final int HEADER = 4, SHOW = 10, OPEN_ON = 19, REMOVE = 33, DELETE = 35;
    static final List<String> CLICKS = List.of("right", "left", "both");
    static final Map<String, String> CLICK_NAMES = Map.of("right", "Right-click", "left", "Left-click", "both", "Both clicks");
    private final ShopFiles.Gui gui;
    private final Runnable changed;
    private List<String> users;

    GuiDetailScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, ShopFiles.Gui gui, Runnable changed) {
        super(toolkit, viewer, commands);
        this.gui = gui; this.changed = changed;
    }

    @Override void begin() { reload(); }

    private void reload() { toolkit.later(viewer, (ToolkitFiles ignored) -> files().guiUsers(gui.kind(), gui.id()), (List<String> found) -> { users = found; viewer.refresh(); }); }

    private ShopFiles files() { return new ShopFiles(toolkit.npcs(), toolkit.documents()); }

    @Override String title() { return GuiBindScreen.tab(gui.kind()).replaceFirst("s$", "") + " " + gui.id(); }
    @Override String node() { return PermissionNodes.GUI; }
    @Override int rows() { return 6; }

    @Override void draw() {
        List<String> clicks = new ArrayList<>();
        for (String id : toolkit.selected(viewer.id()))
            ShopFiles.module(NpcScreen.stored(toolkit, id), GuiBinding.MODULE)
                    .filter((JsonObject module) -> module.has("open") && module.get("open").isJsonObject() && new JsonPrimitive(gui.id()).equals(module.getAsJsonObject("open").get("id")))
                    .map((JsonObject module) -> module.getAsJsonObject("open"))
                    .ifPresent((JsonObject open) -> clicks.add(open.has("openOn") ? open.get("openOn").getAsString() : "right"));
        int bound = clicks.size();
        icon(HEADER, GuiBindScreen.icon(gui.kind()), title(gui.id()), List.of("&7" + gui.title().orElse(GuiBindScreen.what(gui.kind())),
                "&fNow: " + (users == null ? "reading" : users.isEmpty() ? "not used" : "used by " + npcNames(users, 3)), "&8" + gui.path()), Optional.empty());
        if (gui.kind() == DocumentKind.INVENTORY || gui.kind() == DocumentKind.MENU) {
            button(SHOW, "minecraft:ender_eye", title("Show to me"), lore(List.of("&7Opens it for you now"), any("show it")), (GuiModel.Click click) -> GuiBindScreen.preview(viewer, gui));
            if (bound == 0) icon(OPEN_ON, "minecraft:gray_dye", "&7Opens on", List.of(ShopDetailScreen.FIRST), Optional.empty());
            else if (clicks.stream().distinct().count() > 1)
                button(OPEN_ON, "minecraft:lever", title("Opens on"), lore(List.of("&7Which click on the NPC opens it", "&fNow: Mixed"),
                        hints("set all to " + CLICK_NAMES.get(CLICKS.getFirst()), "set all to " + CLICK_NAMES.get(CLICKS.getLast()), "")), (GuiModel.Click click) -> {
                    String next = click.right() ? CLICKS.getLast() : CLICKS.getFirst();
                    ask(OPEN_ON, "Sets opens on to " + CLICK_NAMES.get(next) + " on all " + ToolkitFiles.count(bound) + "; they differ now", bound, true, () -> openOn(next));
                });
            else {
                String openOn = clicks.getFirst();
                button(OPEN_ON, "minecraft:lever", title("Opens on"), lore(List.of("&7Which click on the NPC opens it", "&fNow: " + CLICK_NAMES.getOrDefault(openOn, openOn)), hints("next", "previous", "")),
                        (GuiModel.Click click) -> {
                            String next = cycle(click, CLICKS, openOn);
                            ask(OPEN_ON, "Sets opens on for " + ToolkitFiles.count(bound), bound, false, () -> openOn(next));
                        });
            }
        }
        if (gui.kind() != DocumentKind.TEXT_ANIMATION) {
            if (bound == 0) icon(REMOVE, "minecraft:gray_dye", "&7Remove from NPC", List.of("&7Not on the selected NPCs"), Optional.empty());
            else button(REMOVE, "minecraft:shears", title("Remove from NPC"), lore(List.of("&7Takes it off the selected NPCs"), any("remove it")), (GuiModel.Click click) ->
                    chosen().ifPresent((List<String> ids) -> ask(REMOVE, "Takes " + gui.id() + " off " + named(ids), ids.size(), false,
                            () -> {
                                toolkit.write(viewer, (ToolkitFiles ignored) -> files().unbindGui(gui.kind(), gui.id(), ids));
                                reload();
                            })));
        }
        if (users == null || !users.isEmpty())
            icon(DELETE, "minecraft:gray_dye", "&7Delete", List.of(users == null ? "&7Reading what uses it" : "&7Used by " + npcNames(users, 3) + "; remove it first"), Optional.empty());
        else confirm(DELETE, "minecraft:lava_bucket", "Delete", "Deletes the file; Undo in chat brings it back", 0, true,
                () -> ShopFiles.save(toolkit, viewer, (ToolkitFiles ignored) -> files().deleteGui(gui.kind(), gui.id()), GuiBindScreen.RELOAD_COMMAND, () -> { changed.run(); viewer.back(); }));
        back();
        help("Settings change the selected NPCs that use this file; its layout is drawn in the web editor.", "21-in-game-toolkit", "guis-and-holograms");
    }

    private void openOn(String openOn) {
        chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles ignored) -> files().openOn(gui.id(), ids, openOn)));
    }
}
