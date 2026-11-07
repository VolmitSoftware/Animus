package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.virtual.VirtualNpcState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The selection, one entry per NPC in the order picked, and ways to build it in bulk: everything active in the
 * player's world, by tag, by folder, or inverted within a folder.
 */
final class SelectionScreen<P, S> extends ToolScreen<P, S> {
    static final int WORLD = 46, TAG = 47, FOLDER = 51, INVERT = 52, CLEAR = 53;

    SelectionScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }

    @Override String title() { return "Selection"; }
    @Override int rows() { return 6; }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        list(selected, "Nothing selected; click NPCs with the wand", (Integer slot, String id) -> {
            Optional<NpcDefinition> found = toolkit.npcs().find(id);
            if (found.isEmpty()) return;
            NpcDefinition definition = found.get();
            String type = definition.json().get("type").getAsString();
            boolean active = toolkit.living().state(definition.uuid()).isPresent();
            List<String> lines = List.of("&7Folder &f" + (definition.group().isEmpty() ? "npcs/" : "npcs/" + definition.group() + "/"), "&7Type &f" + type,
                    "&7Groups &f" + names(definition.groups(), 4), active ? "&aActive" : "&7Not active");
            button(slot, ToolkitFiles.PLAYER.equals(type) ? HEAD : "minecraft:name_tag", title(id), lore(lines, hints("teleport to it", "remove it from the selection", "select only it")),
                    (GuiModel.Click click) -> {
                        if (sneak(click)) toolkit.only(viewer.player(), id);
                        else if (right(click)) toolkit.selection().toggle(viewer.id(), id);
                        else toolkit.visit(viewer.player(), id);
                    });
        });
        back();
        List<String> world = inWorld();
        guarded(WORLD, "minecraft:grass_block", "Select all in my world", "Replaces the selection with " + ToolkitFiles.count(world.size()), world.size(), false,
                () -> replace(world, "active in this world"));
        button(TAG, "minecraft:name_tag", title("Select by tag"), lore(List.of("&7Replaces the selection with", "&7every NPC that has the tag"), any("type a tag")),
                (GuiModel.Click click) -> prompt("Tag to select", "", (String typed) -> {
                    String tag = typed.trim();
                    List<String> tagged = new ArrayList<>();
                    for (NpcDefinition definition : toolkit.npcs().all()) if (definition.tags().contains(tag)) tagged.add(definition.id());
                    tagged.sort(String::compareTo);
                    replace(tagged, "tagged " + tag);
                }));
        button(FOLDER, "minecraft:chest", title("Select by folder"), lore(List.of("&7Replaces the selection with", "&7the NPCs in one folder"), any("pick the folder")),
                (GuiModel.Click click) -> viewer.push(new FolderPickScreen<>(toolkit, viewer, commands, "Select a folder", (String folder) -> replace(in(folder), "in " + where(folder)))));
        button(INVERT, "minecraft:hopper", title("Invert in folder"), lore(List.of("&7In one folder, selected NPCs leave", "&7the selection and the rest join it"), any("pick the folder")),
                (GuiModel.Click click) -> viewer.push(new FolderPickScreen<>(toolkit, viewer, commands, "Invert in a folder", this::invert)));
        button(CLEAR, "minecraft:bucket", title("Clear"), lore(List.of("&7Empties the selection"), any("clear it")), (GuiModel.Click click) -> toolkit.clear(viewer.player()));
    }

    /** The selection button other screens show in their bottom row. */
    static <P, S> void summary(ToolScreen<P, S> screen, int slot, List<String> selected) {
        screen.button(slot, "minecraft:spyglass", title(ToolkitFiles.count(selected.size()) + " selected"), lore(List.of("&7" + names(selected, 6)), any("list the selection")),
                (GuiModel.Click click) -> screen.viewer.push(new SelectionScreen<>(screen.toolkit, screen.viewer, screen.commands)));
    }

    /** The tail of a chat line when the selection could not hold everything. */
    static String capped(int kept, int wanted) { return kept < wanted ? "; the selection holds " + ToolkitSelection.MAXIMUM + ", " + (wanted - kept) + " left out" : ""; }

    static String where(String folder) { return "npcs/" + (folder.isEmpty() ? "" : folder + "/"); }

    private void replace(List<String> ids, String what) {
        int kept = toolkit.selection().set(viewer.id(), ids);
        toolkit.say(viewer.player(), ChatLine.reply("Selected " + ToolkitFiles.count(kept) + " " + what + capped(kept, ids.size())));
    }

    private void invert(String folder) {
        Set<String> inside = new HashSet<>(in(folder));
        List<String> next = new ArrayList<>(), current = toolkit.selected(viewer.id());
        for (String id : current) if (!inside.contains(id)) next.add(id);
        for (String id : in(folder)) if (!current.contains(id)) next.add(id);
        int kept = toolkit.selection().set(viewer.id(), next);
        toolkit.say(viewer.player(), ChatLine.reply("Inverted " + where(folder) + ", " + ToolkitFiles.count(kept) + " selected" + capped(kept, next.size())));
    }

    /** The stored NPCs directly in the folder. */
    private List<String> in(String folder) {
        List<String> ids = new ArrayList<>();
        for (NpcDefinition definition : toolkit.npcs().stored()) if (definition.group().equals(folder)) ids.add(definition.id());
        ids.sort(String::compareTo);
        return ids;
    }

    /** The active NPCs in the player's world, by id. */
    private List<String> inWorld() {
        Optional<ScreenHost.Pose> pose = viewer.host().pose(viewer.player());
        List<String> ids = new ArrayList<>();
        if (pose.isEmpty()) return ids;
        for (NpcDefinition definition : toolkit.npcs().all()) {
            Optional<VirtualNpcState.Snapshot> state = toolkit.living().state(definition.uuid());
            if (state.isPresent() && pose.get().in(state.get().position().world())) ids.add(definition.id());
        }
        ids.sort(String::compareTo);
        return ids;
    }
}
