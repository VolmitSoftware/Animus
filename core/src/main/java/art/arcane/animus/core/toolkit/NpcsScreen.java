package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.text.ChatLine;
import art.arcane.animus.core.text.RichText;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * "NPCs": every NPC, the spawned ones in the player's world nearest first, then the rest by name. A click edits one,
 * a right-click goes to it. Advanced multi-selects with a sneak-click and builds the selection by folder, tag or world.
 * Distances are measured when the screen opens.
 */
final class NpcsScreen<P, S> extends ToolScreen<P, S> {
    static final int FOLDER = 36, TAG = 37, WORLD = 38, INVERT = 39, CLEAR = 42, EDIT = 44;
    private List<Entry> entries = List.of();
    private Map<String, NpcItem> skins = Map.of();

    /** One NPC as listed: its definition, the name shown and how far away it stands when it is spawned in the player's world. */
    record Entry(NpcDefinition definition, String name, OptionalDouble distance, Optional<String> world) {
        String id() { return definition.id(); }
    }

    NpcsScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) {
        super(toolkit, viewer, commands);
    }

    @Override void begin() {
        entries = entries(toolkit, viewer.host().pose(viewer.player()));
        List<NpcDefinition> shown = entries.stream().map(Entry::definition).toList();
        toolkit.later(viewer, (ToolkitFiles ignored) -> new SkinFiles(toolkit.npcs(), toolkit.documents()).worn(shown),
                (Map<String, NpcItem> worn) -> { skins = worn; viewer.refresh(); });
    }

    static List<Entry> entries(Toolkit<?, ?> toolkit, Optional<ScreenHost.Pose> pose) {
        List<Entry> near = new ArrayList<>(), rest = new ArrayList<>();
        for (NpcDefinition definition : toolkit.npcs().all()) {
            Optional<VirtualNpcState.Snapshot> state = toolkit.living().state(definition.uuid());
            String name = name(definition);
            if (state.isPresent() && pose.isPresent() && pose.get().in(state.get().position().world())) {
                double distance = state.get().position().point().distance(new Vec3(pose.get().x(), pose.get().y(), pose.get().z()));
                near.add(new Entry(definition, name, OptionalDouble.of(distance), Optional.empty()));
            } else rest.add(new Entry(definition, name, OptionalDouble.empty(), state.map((VirtualNpcState.Snapshot snapshot) -> snapshot.position().world())));
        }
        near.sort(Comparator.comparingDouble((Entry entry) -> entry.distance().orElseThrow()));
        rest.sort(Comparator.comparing((Entry entry) -> entry.name().toLowerCase(Locale.ROOT)).thenComparing(Entry::id));
        near.addAll(rest);
        return near;
    }

    /** The name players see, without colours; the id when the NPC has none. */
    static String name(NpcDefinition definition) {
        String shown = RichText.plain(RichText.colorize(definition.name())).strip();
        return shown.isEmpty() ? definition.id() : shown;
    }

    @Override String title() { return "NPCs"; }
    @Override int rows() { return 6; }
    @Override boolean searchable() { return true; }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        Set<String> picked = new HashSet<>(selected);
        boolean advanced = advanced();
        list(entries, (Entry entry) -> entry.name() + " " + entry.id(), "No NPCs yet; make one with New NPC", (Integer slot, Entry entry) -> {
            String id = entry.id();
            List<String> lines = new ArrayList<>(List.of("&7" + where(entry)));
            if (!entry.name().equals(id)) lines.add("&8" + id);
            if (picked.contains(id)) lines.add("&aSelected");
            boolean player = ToolkitFiles.PLAYER.equals(PropertyScreen.type(entry.definition().json()));
            NpcItem icon = player ? Optional.ofNullable(skins.get(id)).orElse(new NpcItem(HEAD, 1, Optional.empty(), Optional.empty()))
                    : new NpcItem("minecraft:name_tag", 1, Optional.empty(), Optional.empty());
            button(slot, icon, title(entry.name()), lore(lines, hints("edit it", "go to it", advanced ? "add or remove" : "")), (GuiModel.Click click) -> {
                if (advanced && sneak(click)) toggle(id);
                else if (right(click)) toolkit.visit(viewer.player(), id);
                else {
                    toolkit.only(viewer.player(), id);
                    push(new NpcScreen<>(toolkit, viewer, commands));
                }
            });
        });
        back();
        help("Click an NPC to edit it, right-click to go to it." + (advanced ? " Sneak-click adds it to the selection or takes it out." : ""), "21-in-game-toolkit", "selection");
        if (!advanced) return;
        button(FOLDER, "minecraft:chest", title("By folder"), lore(List.of("&7Selects the NPCs in one folder"), any("pick the folder")),
                (GuiModel.Click click) -> push(PickScreen.folders(this, "By folder", (String folder) -> replace(in(folder), "in " + where(folder)))));
        button(TAG, "minecraft:name_tag", title("By tag"), lore(List.of("&7Selects every NPC with the tag"), any("type a tag")),
                (GuiModel.Click click) -> prompt("Tag", "", (String typed) -> {
                    String tag = typed.strip();
                    List<String> tagged = new ArrayList<>();
                    for (NpcDefinition definition : toolkit.npcs().all()) if (definition.tags().contains(tag)) tagged.add(definition.id());
                    tagged.sort(String::compareTo);
                    replace(tagged, "tagged " + tag);
                }));
        List<String> world = inWorld();
        button(WORLD, "minecraft:grass_block", title("All in world"), lore(List.of("&7Selects the " + ToolkitFiles.count(world.size()) + " spawned in your world"), any("select them")),
                (GuiModel.Click click) -> ask(WORLD, "Selects " + ToolkitFiles.count(world.size()), world.size(), false, () -> replace(world, "spawned in this world")));
        button(INVERT, "minecraft:hopper", title("Invert in folder"), lore(List.of("&7In one folder, selected NPCs leave", "&7the selection and the rest join it"), any("pick the folder")),
                (GuiModel.Click click) -> push(PickScreen.folders(this, "Invert in folder", this::invert)));
        button(CLEAR, "minecraft:bucket", title("Clear selection"), lore(List.of("&7" + ToolkitFiles.count(selected.size()) + " selected now"), any("clear it")),
                (GuiModel.Click click) -> toolkit.clear(viewer.player()));
        if (!selected.isEmpty())
            button(EDIT, "minecraft:writable_book", title("Edit selection (" + selected.size() + ")"), lore(List.of("&7" + names(selected, 6)), any("edit them")),
                    (GuiModel.Click click) -> push(new NpcScreen<>(toolkit, viewer, commands)));
    }

    /** {@code 12 blocks away}, the world it is spawned in, or whether it is spawned at all. */
    private static String where(Entry entry) {
        if (entry.distance().isPresent()) {
            long blocks = Math.round(entry.distance().getAsDouble());
            return blocks + (blocks == 1 ? " block away" : " blocks away");
        }
        if (entry.world().isPresent()) return "In " + entry.world().get();
        return entry.definition().spawned() ? "Spawned" : "Despawned";
    }

    private void toggle(String id) {
        try { toolkit.selection().toggle(viewer.id(), id); }
        catch (IllegalStateException full) { refuse(String.valueOf(full.getMessage())); }
    }

    /** A button that lists the selection, for screens that act on it. */
    static <P, S> void summary(ToolScreen<P, S> screen, int slot, List<String> selected) {
        screen.button(slot, "minecraft:name_tag", title(ToolkitFiles.count(selected.size()) + " selected"), lore(List.of("&7" + names(selected, 6)), any("list them")),
                (GuiModel.Click click) -> screen.push(new NpcsScreen<>(screen.toolkit, screen.viewer, screen.commands)));
    }

    /** The tail of a chat line when the selection could not hold everything. */
    static String capped(int kept, int wanted) { return kept < wanted ? "; the selection holds " + ToolkitSelection.MAXIMUM + ", " + (wanted - kept) + " left out" : ""; }

    static String where(String folder) { return FolderFiles.shown(folder); }

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

    /** The spawned NPCs in the player's world, by id. */
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
