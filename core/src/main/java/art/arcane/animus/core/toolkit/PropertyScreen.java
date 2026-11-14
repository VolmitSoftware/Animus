package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.behavior.schema.NpcSchemaGenerator;
import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.storage.NpcPaths;
import art.arcane.animus.core.text.Words;
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
 * "All fields": the NPC definition by section, each opening a {@link FieldScreen} built from the NPC schema, so the lore
 * is the same text the web editor's tips show. Edits apply to every NPC the screen was opened for, except the id and
 * position, which belong to the first. The id and the body are not typed into a row: Id asks for a new id and checks
 * it, Body opens the Body screen. NPCs are followed by uuid, so a rename keeps the screen on the same NPC.
 */
final class PropertyScreen<P, S> extends ToolScreen<P, S> {
    static final JsonObject SCHEMA = NpcSchemaGenerator.schema();
    static final int HEADER = 4, ATTRIBUTES = 15, MODULES = 16, ID = 19, BODY = 20;
    private static final Map<String, String> BODY_FIELDS = bodyFields(SCHEMA);

    /** A row on this screen: root fields by key, or the whole object at {@code path}. */
    record Section(String name, String icon, String detail, int slot, List<String> path, List<String> keys) {}

    static final List<Section> SECTIONS = List.of(
            new Section("Identity", "minecraft:name_tag", "Name, owner, tags, behaviors, trait groups", 10, List.of(),
                    List.of("name", "spawned", "owner", "tags", "behaviors", "groups")),
            new Section("Body settings", "minecraft:armor_stand", "Pose, glow, baby, gravity and the body's own", 11, List.of(),
                    bodyKeys()),
            new Section("Position", "minecraft:compass", "World, x, y, z, yaw and pitch", 12, List.of("position"), List.of()),
            new Section("Appearance", "minecraft:painting", "Hologram, nameplate, rig, disguise, boss bar", 13, List.of(),
                    List.of("hologram", "nameplate", "rig", "disguise", "bossBar", "viewers", "clickRedirect")),
            new Section("Movement", "minecraft:leather_boots", "Speed, doors, water, falls, getting stuck", 14, List.of("movement"), List.of()));

    /** The Body settings section's root fields: pose and flags, then every body's own settings field. */
    private static List<String> bodyKeys() {
        List<String> keys = new ArrayList<>(List.of("pose", "baby", "glowing", "teamColor", "usingItem", "listed", "gravity", "armorStand"));
        keys.addAll(art.arcane.animus.core.npc.BodyVariant.FIELDS.keySet());
        keys.addAll(List.of("inventory", "poses"));
        return List.copyOf(keys);
    }

    private final List<String> opened;
    /** The NPCs edited, by UUID so a rename made here keeps the screen on them; found once the screen shows. */
    private List<UUID> npcs = List.of();

    PropertyScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, List<String> ids) {
        super(toolkit, viewer, commands);
        opened = List.copyOf(ids);
    }

    @Override void begin() {
        List<UUID> found = new ArrayList<>();
        for (String id : opened) stored(id).ifPresent((NpcDefinition definition) -> found.add(definition.uuid()));
        npcs = List.copyOf(found);
    }

    @Override String title() {
        List<String> ids = ids();
        return ValueScreen.clip("All fields" + (ids.isEmpty() ? "" : " of " + ToolkitFiles.named(toolkit.npcs(), ids)), 32);
    }

    @Override String node() { return PermissionNodes.NPC_EDIT; }
    @Override int rows() { return 6; }

    /** The ids of the NPCs this screen edits, as they are named now. */
    private List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (UUID uuid : npcs) toolkit.npcs().stored().stream().filter((NpcDefinition definition) -> definition.uuid().equals(uuid)).findFirst().ifPresent((NpcDefinition definition) -> ids.add(definition.id()));
        return ids;
    }

    private Optional<NpcDefinition> stored(String id) {
        return toolkit.npcs().stored().stream().filter((NpcDefinition definition) -> definition.id().equals(id)).findFirst();
    }

    /** The first NPC's stored definition, the values every field shows. */
    private JsonObject document() {
        List<String> ids = ids();
        return ids.isEmpty() ? new JsonObject() : stored(ids.getFirst()).map(NpcDefinition::json).orElse(new JsonObject());
    }

    private ValueScreen.Scope scope() {
        int count = ids().size();
        return new ValueScreen.Scope(this::document, (JsonObject before, JsonObject after) -> new PropertyFiles(toolkit.npcs(), toolkit.documents()).change(ids(), before, after),
                (List<String> target) -> PropertyFiles.identity(target) ? 1 : count);
    }

    @Override void draw() {
        back();
        help("Every setting of the NPC, as its file has them. Changes save at once and the chat line has Undo.", "21-in-game-toolkit", "all-fields");
        List<String> ids = ids();
        if (ids.isEmpty()) {
            button(HEADER, "minecraft:name_tag", title("Nothing selected"), lore(List.of("&7Pick an NPC to change it"), any("pick one")),
                    (GuiModel.Click click) -> push(new NpcsScreen<>(toolkit, viewer, commands)));
            return;
        }
        JsonObject document = document();
        String first = ids.getFirst(), named = ToolkitFiles.named(toolkit.npcs(), List.of(first));
        boolean several = ids.size() > 1;
        List<String> header = new ArrayList<>(List.of("&7Changes " + (several ? "all " + ToolkitFiles.count(ids.size()) : first)));
        if (several) {
            header.add("&7" + names(ids, 6));
            header.add("&eId and position: " + first + " only");
        }
        icon(HEADER, "minecraft:name_tag", title(several ? ToolkitFiles.count(ids.size()) : first), header, Optional.empty());
        for (Section section : SECTIONS) {
            List<String> lines = new ArrayList<>(List.of("&7" + section.detail()));
            if (several && PropertyFiles.identity(section.path())) lines.add("&eFirst only");
            button(section.slot(), section.icon(), title(section.name()), lore(lines, any("open")),
                    (GuiModel.Click click) -> push(new FieldScreen<>(toolkit, viewer, commands, section.name() + " of " + named, section(SCHEMA, section, Optional.of(type(document()))), scope(), section.path())));
        }
        button(ATTRIBUTES, "minecraft:golden_apple", title("Attributes"), lore(List.of("&7Health, speed and other vanilla values"), any("open")),
                (GuiModel.Click click) -> push(new AttributeScreen<>(toolkit, viewer, commands, "Attributes of " + named, scope())));
        button(MODULES, "minecraft:redstone", title("Modules"), lore(List.of("&7Turn features on or off, change them"), any("open")),
                (GuiModel.Click click) -> push(new ModuleScreen<>(toolkit, viewer, commands, "Modules of " + named, modules(SCHEMA), scope())));
        List<String> id = new ArrayList<>(List.of("&7Names its file: a-z, 0-9, _ and -", FieldScreen.now(first, false)));
        if (several) id.add("&eFirst only");
        button(ID, "minecraft:name_tag", title("Id"), keyed(id, any("type a new id"), "id"), (GuiModel.Click click) -> rename(first));
        String type = type(document);
        button(BODY, "minecraft:armor_stand", title("Body"), keyed(List.of("&7What it looks like", FieldScreen.now(Words.readable(type), false)), any("pick one"), "type"),
                (GuiModel.Click click) -> push(new BodyScreen<>(toolkit, viewer, commands)));
    }

    /** Lore ending in the raw key in dark grey, as the field rows show it. */
    private static List<String> keyed(List<String> lines, List<String> hints, String key) {
        List<String> lore = lore(lines, hints);
        lore.add("&8" + key);
        return lore;
    }

    /** Id: asks for a new id, checks it here, then renames the first NPC's file. */
    private void rename(String first) {
        prompt("New id", first, (String typed) -> {
            String next = typed.strip();
            if (next.isEmpty() || next.equals(first)) return;
            if (!next.matches(NpcPaths.SEGMENT)) { refuse(next + " is not 1-64 lowercase letters, digits, _ or -"); return; }
            if (stored(next).isPresent()) { refuse("An NPC named " + next + " exists"); return; }
            toolkit.write(viewer, (ToolkitFiles files) -> new PropertyFiles(toolkit.npcs(), toolkit.documents()).rename(first, next));
        });
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
     * {@code a[]} for list entries, {@code a.*} for map values. Id and Body reach {@code id} and {@code type}.
     */
    static Set<String> covered(JsonObject schema) {
        Set<String> out = new HashSet<>(List.of("id", "type"));
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
