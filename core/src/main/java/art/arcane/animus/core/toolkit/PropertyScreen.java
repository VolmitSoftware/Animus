package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.behavior.schema.NpcSchemaGenerator;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * "Edit &lt;id&gt;": the NPC definition by section, each opening a {@link FieldScreen} built from the NPC schema, so the
 * lore is the same text the web editor's tips show. Edits apply to every NPC the screen was opened for, except the id
 * and position, which belong to the first. NPCs are followed by uuid, so a rename keeps the screen on the same NPC.
 */
final class PropertyScreen<P, S> extends ToolScreen<P, S> {
    static final JsonObject SCHEMA = NpcSchemaGenerator.schema();
    static final int HEADER = 4, HERE = 21, CHOOSE = 23;
    private static final Map<String, String> BODY_FIELDS = bodyFields(SCHEMA);

    /** A row on this screen: root fields by key, or the whole object at {@code path}. */
    record Section(String name, String icon, String detail, int slot, List<String> path, List<String> keys) {}

    static final List<Section> SECTIONS = List.of(
            new Section("Identity", "minecraft:name_tag", "Id, name, type, owner, tags, behaviors, groups", 10, List.of(),
                    List.of("id", "name", "type", "spawned", "owner", "tags", "behaviors", "groups")),
            new Section("Body", "minecraft:armor_stand", "Pose, glow, baby, gravity, look directions", 11, List.of(),
                    List.of("pose", "baby", "glowing", "teamColor", "usingItem", "listed", "gravity", "armorStand", "horse", "llama", "frog", "armadillo", "dragon", "inventory", "poses")),
            new Section("Position", "minecraft:compass", "World, x, y, z, yaw and pitch", 12, List.of("position"), List.of()),
            new Section("Appearance", "minecraft:painting", "Hologram, nameplate, rig", 13, List.of(), List.of("hologram", "nameplate", "rig")),
            new Section("Movement", "minecraft:leather_boots", "Style, speed, doors, water, falls, getting stuck", 14, List.of("movement"), List.of()));
    static final int ATTRIBUTES = 15, MODULES = 16;

    private final List<UUID> npcs;

    PropertyScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) {
        this(toolkit, viewer, commands, toolkit.selected(viewer.id()));
    }

    PropertyScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, List<String> ids) {
        super(toolkit, viewer, commands);
        List<UUID> found = new ArrayList<>();
        for (String id : ids) toolkit.npcs().stored().stream().filter((NpcDefinition definition) -> definition.id().equals(id)).findFirst().ifPresent((NpcDefinition definition) -> found.add(definition.uuid()));
        this.npcs = List.copyOf(found);
    }

    @Override String title() { return ValueScreen.clip("Edit " + ids().stream().findFirst().orElse("an NPC"), 32); }
    @Override String node() { return PermissionNodes.NPC_EDIT; }
    @Override int rows() { return 6; }

    /** The ids of the NPCs this screen edits, as they are named now. */
    private List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (UUID uuid : npcs) stored(uuid).ifPresent((NpcDefinition definition) -> ids.add(definition.id()));
        return ids;
    }

    private Optional<NpcDefinition> stored(UUID uuid) {
        return toolkit.npcs().stored().stream().filter((NpcDefinition definition) -> definition.uuid().equals(uuid)).findFirst();
    }

    /** The first NPC's stored definition, the values every field shows. */
    private JsonObject document() { return npcs.isEmpty() ? new JsonObject() : stored(npcs.getFirst()).map(NpcDefinition::json).orElse(new JsonObject()); }

    private ValueScreen.Scope scope() {
        int count = ids().size();
        return new ValueScreen.Scope(this::document, (JsonObject before, JsonObject after) -> new PropertyFiles(toolkit.npcs(), toolkit.documents()).change(ids(), before, after),
                (List<String> target) -> PropertyFiles.identity(target) ? 1 : count);
    }

    @Override void draw() {
        List<String> ids = ids();
        back();
        button(CHOOSE, "minecraft:spyglass", title("Choose an NPC"), lore(List.of("&7Edit one NPC by clicking it"), any("click an NPC with the wand")),
                (GuiModel.Click click) -> pick("Edit", (String id) -> viewer.replace(new PropertyScreen<>(toolkit, viewer, commands, List.of(id)))));
        if (ids.isEmpty()) {
            icon(HEADER, "minecraft:name_tag", title("Nothing to edit"), List.of("&7Select NPCs with the wand,", "&7or choose one below"), Optional.empty());
            return;
        }
        JsonObject document = document();
        String first = ids.getFirst();
        icon(HEADER, "minecraft:name_tag", title("Edit " + first), ids.size() == 1 ? List.of("&7Body &f" + type(document))
                : List.of("&7Changes apply to all " + ToolkitFiles.count(ids.size()), "&7Id and position change on " + first + " only", "&7" + names(ids, 6)), Optional.empty());
        String applies = ids.size() == 1 ? first : ToolkitFiles.count(ids.size());
        for (Section section : SECTIONS) {
            List<String> lines = new ArrayList<>(List.of("&7" + section.detail(), "&7Changes " + (PropertyFiles.identity(section.path()) ? first : applies)));
            if (section.name().equals("Appearance")) {
                JsonElement skin = document.get("skin");
                lines.add("&7Skin &f" + (skin == null ? "none" : ValueScreen.clip(skin.getAsString(), 15)) + "&7; the Skins screen changes it");
            }
            button(section.slot(), section.icon(), title(section.name()), lore(lines, any("open")),
                    (GuiModel.Click click) -> viewer.push(new FieldScreen<>(toolkit, viewer, commands, section.name() + " of " + first, section(SCHEMA, section, Optional.of(type(document()))), scope(), section.path())));
        }
        button(ATTRIBUTES, "minecraft:iron_chestplate", title("Attributes"), lore(List.of("&7Vanilla attributes this body has", "&7Changes " + applies), any("open")),
                (GuiModel.Click click) -> viewer.push(new AttributeScreen<>(toolkit, viewer, commands, "Attributes of " + first, scope())));
        button(MODULES, "minecraft:redstone", title("Modules"), lore(List.of("&7Turn modules on or off, edit them", "&7Changes " + applies), any("open")),
                (GuiModel.Click click) -> viewer.push(new ModuleScreen<>(toolkit, viewer, commands, "Modules of " + first, modules(SCHEMA), scope())));
        button(HERE, "minecraft:ender_pearl", title("Use where I stand"), lore(List.of("&7Moves " + first + " to you and saves it"), any("move it here")),
                (GuiModel.Click click) -> toolkit.bring(viewer, first));
    }

    /** The schema object a section edits; the body settings of other types are left out when {@code type} is given. */
    static JsonObject section(JsonObject schema, Section section, Optional<String> type) {
        JsonObject properties = schema.getAsJsonObject("properties");
        if (section.keys().isEmpty()) return ValueScreen.shape(properties.getAsJsonObject(section.path().getFirst()));
        JsonObject subset = new JsonObject(), shown = new JsonObject();
        for (String key : section.keys()) {
            String owner = BODY_FIELDS.get(key);
            if (properties.has(key) && (owner == null || type.isEmpty() || owner.equals(type.get()))) shown.add(key, properties.get(key));
        }
        subset.addProperty("type", "object");
        subset.add("properties", shown);
        return subset;
    }

    static JsonObject modules(JsonObject schema) { return schema.getAsJsonObject("properties").getAsJsonObject("modules"); }

    /**
     * Every leaf field path of {@code schema} these screens reach, in the parity test's notation: {@code a.b} for fields,
     * {@code a[]} for list entries, {@code a.*} for map values.
     */
    static Set<String> covered(JsonObject schema) {
        Set<String> out = new HashSet<>();
        for (Section section : SECTIONS) ValueScreen.covered(section(schema, section, Optional.empty()), section.path().isEmpty() ? "" : String.join(".", section.path()) + ".", out);
        out.addAll(AttributeScreen.covered(schema));
        out.addAll(ModuleScreen.covered(modules(schema)));
        return out;
    }

    /** A definition under the NPC schema's {@code $defs}, from a {@code #/$defs/<name>} reference. */
    static Optional<JsonObject> definition(String reference) {
        String name = reference.substring(reference.lastIndexOf('/') + 1);
        JsonObject defs = SCHEMA.getAsJsonObject("$defs");
        return defs != null && defs.has(name) ? Optional.of(defs.getAsJsonObject(name)) : Optional.empty();
    }

    /** The body settings only one type may carry, field to type, read from the schema's type rules. */
    static Map<String, String> bodyFields() { return BODY_FIELDS; }

    private static Map<String, String> bodyFields(JsonObject schema) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (!schema.has("allOf")) return fields;
        for (JsonElement rule : schema.getAsJsonArray("allOf")) {
            JsonElement type = ValueScreen.at(rule, List.of("if", "properties", "type", "const")), required = ValueScreen.at(rule, List.of("else", "not", "required"));
            if (type == null || required == null || !required.isJsonArray()) continue;
            for (JsonElement field : required.getAsJsonArray()) fields.put(field.getAsString(), type.getAsString());
        }
        return fields;
    }

    /** The document's body type with its namespace, so {@code player} reads as {@code minecraft:player}. */
    static String type(JsonObject document) {
        JsonElement type = document.get("type");
        String text = type == null || !type.isJsonPrimitive() ? "" : type.getAsString();
        return text.isEmpty() || text.contains(":") ? text : "minecraft:" + text;
    }
}
