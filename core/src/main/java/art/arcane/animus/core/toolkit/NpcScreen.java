package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.text.Words;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * One screen of named jobs for the selected NPC, or for every selected NPC at once: how it looks, what it does, and
 * managing it. Advanced adds every field, behaviors, GUIs, folders, trait groups, attributes, modules and the admin row.
 * A route being made or a recording running shows its finish button beside the header, as Home shows it.
 */
final class NpcScreen<P, S> extends ToolScreen<P, S> {
    static final int HEADER = 4, RUNNING = 5,
            NAME = 10, BODY = 11, SKIN = 12, EQUIPMENT = 13, HOLOGRAM = 14, FIELDS = 16,
            TALK = 19, SHOP = 20, MOVEMENT = 21, COMBAT = 22, BEHAVIORS = 23, GUIS = 24,
            BRING = 28, VISIT = 29, SPAWNED = 30, COPY = 31, FOLDER = 32, GROUPS = 33, DELETE = 35,
            ATTRIBUTES = 37, MODULES = 38, INSPECT = 39, REVIVE = 40, EDITOR = 41;

    NpcScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }

    @Override String title() {
        List<String> ids = toolkit.selected(viewer.id());
        if (ids.size() > 1) return ToolkitFiles.count(ids.size());
        return ids.isEmpty() ? "NPC" : toolkit.npcs().find(ids.getFirst()).map(NpcsScreen::name).orElse(ids.getFirst());
    }

    @Override int rows() { return 6; }
    @Override boolean forSelection() { return true; }

    @Override void draw() {
        back();
        modeLever();
        help("Each button changes the selected NPC or, with several selected, all of them. Advanced shows every setting.", "21-in-game-toolkit", "manage");
        List<String> ids = toolkit.selected(viewer.id());
        Optional<NpcDefinition> found = ids.isEmpty() ? Optional.empty() : toolkit.npcs().find(ids.getFirst());
        if (found.isEmpty()) {
            button(HEADER, "minecraft:name_tag", title("Nothing selected"), lore(List.of("&7Pick an NPC to change it"), any("pick one")), (GuiModel.Click click) -> push(new NpcsScreen<>(toolkit, viewer, commands)));
            return;
        }
        NpcDefinition first = found.get();
        boolean several = ids.size() > 1, advanced = advanced();
        String type = PropertyScreen.type(first.json());
        header(ids, first, type, advanced);
        HomeScreen.running(this, RUNNING);
        look(ids, first, type, several, advanced);
        does(ids, advanced);
        manage(ids, first, several, advanced);
        if (advanced) admin(ids, first);
    }

    private void header(List<String> ids, NpcDefinition first, String type, boolean advanced) {
        List<String> lines = new ArrayList<>();
        if (ids.size() > 1) {
            lines.add("&7Every job changes all of them");
            lines.add("&7" + names(ids, 6));
        }
        lines.add("&7Body &f" + Words.readable(type));
        lines.add(first.spawned() ? "&aSpawned" : "&7Despawned");
        if (advanced) {
            lines.add(folder(first));
            if (!first.groups().isEmpty()) lines.add("&7Trait groups &f" + names(first.groups(), 4));
        }
        button(HEADER, "minecraft:name_tag", title(title()), lore(lines, any("pick another")), (GuiModel.Click click) -> push(new NpcsScreen<>(toolkit, viewer, commands)));
    }

    private void look(List<String> ids, NpcDefinition first, String type, boolean several, boolean advanced) {
        gated(NAME, PermissionNodes.NPC_EDIT, "minecraft:name_tag", "Name", lore(lines("What players see above it", "Now: " + NpcsScreen.name(first), several), any("type a name")),
                (GuiModel.Click click) -> rename(first));
        open(BODY, "minecraft:armor_stand", "Body", "Now: " + Words.readable(type), () -> new BodyScreen<>(toolkit, viewer, commands));
        boolean players = ids.stream().anyMatch((String id) -> toolkit.npcs().find(id).map((NpcDefinition npc) -> ToolkitFiles.PLAYER.equals(PropertyScreen.type(npc.json()))).orElse(false));
        if (!players) icon(SKIN, "minecraft:gray_dye", "&7Skin", List.of("&7Player bodies only"), Optional.empty());
        else open(SKIN, HEAD, "Skin", "The look a player body wears", () -> new SkinScreen<>(toolkit, viewer, commands));
        open(EQUIPMENT, "minecraft:iron_chestplate", "Equipment", "What it holds and wears", () -> new EquipmentScreen<>(toolkit, viewer, commands));
        open(HOLOGRAM, "minecraft:oak_sign", "Hologram", "Floating text above it", () -> new HologramScreen<>(toolkit, viewer, commands));
        if (advanced) open(FIELDS, "minecraft:writable_book", "All fields", "Every setting, as the file has it", () -> new PropertyScreen<>(toolkit, viewer, commands, ids));
    }

    private void does(List<String> ids, boolean advanced) {
        open(TALK, "minecraft:bell", "Talk", "Lines and replies", () -> new TalkScreen<>(toolkit, viewer, commands));
        open(SHOP, "minecraft:emerald", "Shop", "A trade window it opens", () -> new ShopBindScreen<>(toolkit, viewer, commands));
        open(MOVEMENT, "minecraft:leather_boots", "Movement", MovementScreen.now(toolkit, ids.getFirst()), () -> new MovementScreen<>(toolkit, viewer, commands));
        open(COMBAT, "minecraft:iron_sword", "Combat", "Whether it fights and who it targets", () -> new CombatScreen<>(toolkit, viewer, commands));
        if (!advanced) return;
        open(BEHAVIORS, "minecraft:lectern", "Behaviors", "What it does when something happens", () -> new BehaviorScreen<>(toolkit, viewer, commands));
        open(GUIS, "minecraft:item_frame", "GUIs", "Chest windows it opens", () -> new GuiBindScreen<>(toolkit, viewer, commands));
    }

    private void manage(List<String> ids, NpcDefinition first, boolean several, boolean advanced) {
        int count = ids.size();
        gated(BRING, PermissionNodes.NPC_MOVE, "minecraft:lead", "Bring here", lore(List.of("&7Moves it to where you stand"), any("bring it")),
                (GuiModel.Click click) -> ask(BRING, "Moves " + named(ids) + " to you", count, false, () -> toolkit.bring(viewer, ids)));
        button(VISIT, "minecraft:ender_pearl", title("Go to"), lore(lines("Takes you to it", "", several), any("go there")),
                (GuiModel.Click click) -> toolkit.visit(viewer.player(), first.id()));
        boolean spawned = ids.stream().allMatch((String id) -> toolkit.npcs().find(id).map(NpcDefinition::spawned).orElse(false));
        gated(SPAWNED, PermissionNodes.NPC_EDIT, spawned ? "minecraft:lime_dye" : "minecraft:gray_dye", "Spawned: " + (spawned ? "on" : "off"),
                lore(List.of("&7Despawned NPCs stay saved"), any(spawned ? "despawn" : "spawn")),
                (GuiModel.Click click) -> ask(SPAWNED, (spawned ? "Despawns " : "Spawns ") + named(ids), count, false, () -> toolkit.spawned(viewer, ids, !spawned)));
        gated(COPY, PermissionNodes.NPC_CREATE, "minecraft:paper", "Copy", lore(List.of("&7Saves a despawned copy"), any("copy")), (GuiModel.Click click) -> ask(COPY, "Copies " + named(ids), count, false, () -> {
            if (allows(PermissionNodes.NPC_CREATE)) toolkit.write(viewer, (ToolkitFiles files) -> files.copies(ids));
        }));
        if (!holds(PermissionNodes.NPC_DELETE)) denied(DELETE, "Delete", PermissionNodes.NPC_DELETE);
        else confirm(DELETE, "minecraft:lava_bucket", "Delete", "Deletes " + named(ids) + "; Undo in chat brings it back", count, true, () -> {
            if (allows(PermissionNodes.NPC_DELETE)) toolkit.write(viewer, (ToolkitFiles files) -> files.delete(ids), this::leave);
        });
        if (!advanced) return;
        open(FOLDER, "minecraft:chest", "Folder", "Where its file is kept", () -> new FolderScreen<>(toolkit, viewer, commands));
        open(GROUPS, "minecraft:book", "Trait groups", "Settings shared by many NPCs", () -> new GroupScreen<>(toolkit, viewer, commands));
    }

    private void admin(List<String> ids, NpcDefinition first) {
        open(ATTRIBUTES, "minecraft:golden_apple", "Attributes", "Health, speed and other vanilla values", () -> new AttributeScreen<>(toolkit, viewer, commands, "Attributes of " + NpcsScreen.name(first), scope(toolkit, ids)));
        open(MODULES, "minecraft:redstone", "Modules", "Turn features on or off", () -> new ModuleScreen<>(toolkit, viewer, commands, "Modules of " + NpcsScreen.name(first), PropertyScreen.modules(PropertyScreen.SCHEMA), scope(toolkit, ids)));
        gated(INSPECT, PermissionNodes.NPC_VIEW, "minecraft:spyglass", "Inspect", lore(lines("Its file and live state", "Shows in chat", ids.size() > 1), any("inspect")), (GuiModel.Click click) -> {
            if (!allows(PermissionNodes.NPC_VIEW)) return;
            viewer.close();
            commands.inspect().accept(toolkit.actor(viewer.player()), first.id());
        });
        gated(REVIVE, PermissionNodes.NPC_EDIT, "minecraft:totem_of_undying", "Revive", lore(List.of("&7Brings a dead NPC back at full health"), any("revive")),
                (GuiModel.Click click) -> ask(REVIVE, "Revives " + named(ids), ids.size(), false, () -> {
                    if (allows(PermissionNodes.NPC_EDIT)) for (String id : ids) commands.revive().accept(toolkit.actor(viewer.player()), id);
                }));
        gated(EDITOR, PermissionNodes.EDITOR_PAIR, "minecraft:ender_eye", "Open in editor", lore(List.of("&7Pairs your browser", "&7Shows in chat"), any("get a link")), (GuiModel.Click click) -> {
            if (!allows(PermissionNodes.EDITOR_PAIR)) return;
            viewer.close();
            commands.pair().accept(toolkit.actor(viewer.player()));
        });
    }

    /** A grey line of what it is, an optional white state line, and "First only" for a job that changes one NPC. */
    private static List<String> lines(String what, String now, boolean firstOnly) {
        List<String> lines = new ArrayList<>(List.of("&7" + what));
        if (!now.isEmpty()) lines.add(now.startsWith("Now") ? "&f" + now : "&7" + now);
        if (firstOnly) lines.add("&eFirst only");
        return lines;
    }

    /** A job that opens its own screen, greyed out when the player lacks that screen's node. */
    private void open(int slot, String icon, String name, String what, Supplier<ToolScreen<P, S>> next) {
        opens(slot, icon, name, lore(what.startsWith("Now") ? List.of("&f" + what) : List.of("&7" + what), any("open")), next);
    }

    /** Name: a prompt that writes the first NPC's display name. */
    private void rename(NpcDefinition first) {
        if (!allows(PermissionNodes.NPC_EDIT)) return;
        prompt("Name", first.name(), (String typed) -> {
            String name = typed.strip();
            if (name.isEmpty()) return;
            JsonObject before = stored(toolkit, first.id()), after = before.deepCopy();
            after.addProperty("name", name);
            toolkit.write(viewer, (ToolkitFiles files) -> new PropertyFiles(toolkit.npcs(), toolkit.documents()).change(List.of(first.id()), before, after));
        });
    }

    /** Edits read the first NPC's stored file and write the same change to every listed NPC; its id and place change on the first only. */
    static ValueScreen.Scope scope(Toolkit<?, ?> toolkit, List<String> ids) {
        return new ValueScreen.Scope(() -> stored(toolkit, ids.getFirst()), (JsonObject before, JsonObject after) -> new PropertyFiles(toolkit.npcs(), toolkit.documents()).change(ids, before, after),
                (List<String> target) -> PropertyFiles.identity(target) ? 1 : ids.size());
    }

    /** The NPC's definition as its file stores it, without trait group values. */
    static JsonObject stored(Toolkit<?, ?> toolkit, String id) {
        return toolkit.npcs().stored().stream().filter((NpcDefinition definition) -> definition.id().equals(id)).findFirst().map(NpcDefinition::json).orElse(new JsonObject());
    }
}
