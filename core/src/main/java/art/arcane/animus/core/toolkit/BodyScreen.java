package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.presentation.BodyRules;
import art.arcane.animus.core.text.Words;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Body: what the NPC looks like. Fourteen common bodies, Search, and All bodies, every body Animus shows. In the guided
 * create a pick moves on; from the NPC screen it changes the selection's body, asking first when that drops settings only
 * the old body has. Advanced types any body id and, in the create, saves the NPC despawned.
 */
final class BodyScreen<P, S> extends ToolScreen<P, S> {
    static final int HEADER = 4, ALL = ACTIONS, TYPED = ACTIONS + 1, SPAWNED = ACTIONS + 8;
    /** The common bodies in the order they show, the player first. */
    static final List<String> COMMON = List.of(ToolkitFiles.PLAYER, "minecraft:villager", "minecraft:zombie", "minecraft:skeleton", "minecraft:iron_golem",
            "minecraft:armor_stand", "minecraft:allay", "minecraft:cat", "minecraft:wolf", "minecraft:horse", "minecraft:pig", "minecraft:cow", "minecraft:sheep",
            "minecraft:chicken");
    /**
     * Two rows around the window's centre column, where the pointer starts, so a double-click never lands on a body.
     * Player, the usual pick, stands apart: a gap follows it.
     */
    static final int[] SLOTS = {9, 11, 12, 14, 15, 16, 17, 18, 19, 20, 21, 23, 24, 25};
    /** A body id as the NPC schema's type pattern has it. */
    static final String ID = "([a-z0-9_]+:)?[a-z0-9_/]+";
    /** Every body Animus shows, by name. */
    static final List<String> EVERY = BodyRules.TYPES.stream().sorted(Comparator.comparing((String type) -> Words.readable(type)).thenComparing((String type) -> type)).toList();
    /** Bodies without a spawn egg, and the item each shows as. */
    private static final Map<String, String> ICONS = Map.ofEntries(Map.entry(ToolkitFiles.PLAYER, HEAD), Map.entry("minecraft:zombie", "minecraft:zombie_head"),
            Map.entry("minecraft:skeleton", "minecraft:skeleton_skull"), Map.entry("minecraft:armor_stand", "minecraft:armor_stand"),
            Map.entry("minecraft:mannequin", "minecraft:armor_stand"), Map.entry("minecraft:giant", "minecraft:zombie_head"), Map.entry("minecraft:illusioner", "minecraft:bow"),
            Map.entry("minecraft:painting", "minecraft:painting"), Map.entry("minecraft:item_frame", "minecraft:item_frame"),
            Map.entry("minecraft:glow_item_frame", "minecraft:glow_item_frame"), Map.entry("minecraft:end_crystal", "minecraft:end_crystal"),
            Map.entry("minecraft:falling_block", "minecraft:sand"), Map.entry("minecraft:block_display", "minecraft:grass_block"),
            Map.entry("minecraft:item_display", "minecraft:diamond"), Map.entry("minecraft:text_display", "minecraft:oak_sign"), Map.entry("minecraft:item", "minecraft:apple"));
    private final Optional<CreateFlow> creating;
    private final boolean every;

    BodyScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { this(toolkit, viewer, commands, Optional.empty(), false); }

    BodyScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, CreateFlow flow) { this(toolkit, viewer, commands, Optional.of(flow), false); }

    private BodyScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, Optional<CreateFlow> creating, boolean every) {
        super(toolkit, viewer, commands);
        this.creating = creating;
        this.every = every;
    }

    @Override String title() { return every ? "All bodies" : "Body"; }
    @Override String node() { return creating.isPresent() ? PermissionNodes.NPC_CREATE : PermissionNodes.NPC_EDIT; }
    @Override int rows() { return 6; }
    @Override boolean searchable() { return every; }
    @Override boolean forSelection() { return creating.isEmpty(); }

    @Override void draw() {
        back();
        if (creating.isPresent()) help("Pick what your new NPC looks like. A player body wears a skin, which you pick next.", "21-in-game-toolkit", "new-npc");
        else help("Pick what the NPC looks like. A new body drops settings only the old one had; Undo in chat brings them back.", "04-npc-definitions", "body-settings");
        creating.ifPresent((CreateFlow flow) -> flow.showing(this));
        List<String> ids = toolkit.selected(viewer.id());
        Optional<String> now = creating.isPresent() || ids.isEmpty() ? Optional.empty() : toolkit.npcs().find(ids.getFirst()).map((NpcDefinition npc) -> PropertyScreen.type(npc.json()));
        if (creating.isEmpty() && now.isEmpty()) {
            button(HEADER, "minecraft:armor_stand", title("Nothing selected"), lore(List.of("&7Pick an NPC first"), any("pick one")), (GuiModel.Click click) -> push(new NpcsScreen<>(toolkit, viewer, commands)));
            return;
        }
        if (every) {
            list(EVERY, (String type) -> Words.readable(type) + " " + type, "No bodies", (Integer slot, String type) -> body(slot, type, now));
            return;
        }
        header(ids, now);
        for (int index = 0; index < COMMON.size(); index++) body(SLOTS[index], COMMON.get(index), now);
        button(ALL, "minecraft:bookshelf", title("All bodies"), lore(List.of("&7Every body this server shows"), any("open")), (GuiModel.Click click) -> push(new BodyScreen<>(toolkit, viewer, commands, creating, true)));
        button(bottom(SEARCH), "minecraft:spyglass", title("Search"), any("type a name"), (GuiModel.Click click) -> prompt("Search", "", (String typed) -> {
            BodyScreen<P, S> found = new BodyScreen<>(toolkit, viewer, commands, creating, true);
            found.search(typed);
            push(found);
        }));
        if (!advanced()) return;
        button(TYPED, "minecraft:name_tag", title("Type an id"), lore(List.of("&7For bodies from mods or newer versions"), any("type it")),
                (GuiModel.Click click) -> prompt("Body id", now.orElse(""), this::typed));
        creating.ifPresent((CreateFlow flow) -> button(SPAWNED, flow.spawn ? "minecraft:lime_dye" : "minecraft:gray_dye", title("Spawned: " + (flow.spawn ? "on" : "off")),
                lore(List.of("&7Off saves it despawned"), any(flow.spawn ? "save it despawned" : "spawn it")), (GuiModel.Click click) -> flow.spawn = !flow.spawn));
    }

    private void header(List<String> ids, Optional<String> now) {
        if (creating.isPresent()) {
            CreateFlow flow = creating.get();
            icon(HEADER, "minecraft:name_tag", title(flow.name), advanced() ? List.of("&7New NPC", "&8" + flow.id) : List.of("&7New NPC"), Optional.empty());
            return;
        }
        String name = ids.size() > 1 ? ToolkitFiles.count(ids.size()) : toolkit.npcs().find(ids.getFirst()).map(NpcsScreen::name).orElse(ids.getFirst());
        List<String> lines = new ArrayList<>(List.of("&fNow: " + Words.readable(now.orElseThrow())));
        if (ids.size() > 1) lines.add("&7Changes all of them");
        icon(HEADER, "minecraft:armor_stand", title(name), lines, Optional.empty());
    }

    private void body(int slot, String type, Optional<String> now) {
        boolean current = now.filter(type::equals).isPresent();
        NpcItem item = new NpcItem(icon(type), 1, current ? Optional.of("[enchantment_glint_override=true]") : Optional.empty(), Optional.empty());
        List<String> lines = new ArrayList<>();
        if (type.equals(ToolkitFiles.PLAYER) && !every) lines.add("&7Most common");
        if (current) lines.add("&fNow: this body");
        if (advanced()) lines.add("&8" + type);
        button(slot, item, title(Words.readable(type)), lore(lines, any(creating.isPresent() ? "pick it" : "change to it")), (GuiModel.Click click) -> choose(slot, type));
    }

    /** The item a body shows as: its spawn egg, or its own item for bodies without one. */
    static String icon(String type) {
        String own = ICONS.get(type);
        if (own != null) return own;
        return BodyRules.LIVING.contains(type) ? type + "_spawn_egg" : "minecraft:armor_stand";
    }

    private void typed(String typed) {
        String id = typed.strip().toLowerCase(Locale.ROOT);
        if (!id.isEmpty() && !id.contains(":")) id = "minecraft:" + id;
        if (!id.matches(ID)) { refuse("'" + typed.strip() + "' is not a body id such as minecraft:fox"); return; }
        choose(TYPED, id);
    }

    private void choose(int slot, String type) {
        if (creating.isPresent()) { creating.get().body(this, type); return; }
        chosen().ifPresent((List<String> ids) -> {
            List<String> drops = drops(ids, type);
            String what = drops.isEmpty() ? "Changes " + named(ids) + " to " + Words.readable(type)
                    : "Drops " + and(drops) + "; Undo in chat brings " + (drops.size() == 1 ? "it" : "them") + " back";
            ask(slot, what, ids.size(), !drops.isEmpty(), () -> {
                toolkit.write(viewer, (ToolkitFiles ignored) -> change(toolkit.npcs(), ids, type));
                if (every) viewer.back();
            });
        });
    }

    /** What the new body would drop from the NPCs: the old body's own settings, a player skin, a pose. */
    private List<String> drops(List<String> ids, String type) {
        List<String> dropped = new ArrayList<>();
        for (String id : ids) {
            JsonObject document = NpcScreen.stored(toolkit, id), next = document.deepCopy();
            if (PropertyScreen.type(document).equals(type)) continue;
            next.addProperty("type", type);
            PropertyFiles.body(next);
            for (String key : document.keySet()) {
                String what = key.equals("skin") ? "skin" : key.equals("pose") ? "pose" : Words.readable(key).toLowerCase(Locale.ROOT) + " settings";
                if (!next.has(key) && !dropped.contains(what)) dropped.add(what);
            }
        }
        return dropped;
    }

    private static String and(List<String> words) {
        if (words.size() == 1) return words.getFirst();
        return String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.getLast();
    }

    /** Gives every listed NPC the body, dropping the settings only another body has, in one transaction. Runs on the file lane. */
    static String change(NpcRepository npcs, List<String> ids, String type) throws IOException {
        List<NpcRepository.Draft> drafts = new ArrayList<>();
        for (String id : ids) {
            NpcRepository.Draft draft = npcs.draft(id);
            JsonObject document = draft.document();
            if (PropertyScreen.type(document).equals(type)) continue;
            document.addProperty("type", type);
            PropertyFiles.body(document);
            drafts.add(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        }
        String body = Words.readable(type);
        if (drafts.isEmpty()) return "Body already " + body + " on " + ToolkitFiles.named(npcs, ids);
        return "Body " + body + " on " + ToolkitFiles.named(drafts) + "; tx " + npcs.applyAll(drafts);
    }
}
