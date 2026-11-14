package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One module section as rows of settings. Clicks change a draft; Save writes the draft to every selected NPC in one
 * transaction, and Back asks once before dropping it. Rows show the first selected NPC, a value it leaves out as the
 * default it runs with. A nested object opens the same screen on its rows, sharing the draft.
 */
final class SettingsScreen<P, S> extends ToolScreen<P, S> {
    static final int INFO_SLOT = ACTIONS + 8;
    /** The longest prompt label an anvil shows. */
    private static final int LABEL = 20;
    private final CombatFields.Settings settings;
    private final String title;
    private final List<CombatFields.Field> fields;
    private final Map<List<String>, Optional<JsonElement>> draft;
    private final boolean nested;

    SettingsScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, CombatFields.Settings settings) {
        this(toolkit, viewer, commands, settings, settings.title(), settings.fields(), Collections.synchronizedMap(new LinkedHashMap<>()), false);
    }

    private SettingsScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, CombatFields.Settings settings, String title,
                           List<CombatFields.Field> fields, Map<List<String>, Optional<JsonElement>> draft, boolean nested) {
        super(toolkit, viewer, commands);
        this.settings = settings; this.title = title; this.fields = fields; this.draft = draft; this.nested = nested;
    }

    @Override String title() { return title; }
    /** Riding settings are an NPC edit, as Mounts is; combat settings need the combat node. */
    @Override String node() { return settings.module().equals(CombatFiles.RIDING) ? PermissionNodes.NPC_EDIT : PermissionNodes.COMBAT; }
    @Override int rows() { return 6; }

    @Override void draw() {
        back();
        help(settings.help(), settings.page(), settings.anchor());
        List<String> selected = toolkit.selected(viewer.id());
        if (selected.isEmpty()) { CombatScreen.nothingSelected(this); return; }
        list(fields, CombatFields.Field::label, "Nothing to edit here", this::row);
        icon(INFO_SLOT, "minecraft:name_tag", title(named(selected)),
                selected.size() > 1 ? List.of("&7Rows show " + selected.getFirst() + "; Save sets all of them") : List.of(), Optional.empty());
        draft(draft.size(), selected.size(), !nested, this::save, draft::clear);
    }

    private void save() {
        Map<List<String>, Optional<JsonElement>> saved;
        synchronized (draft) { saved = new LinkedHashMap<>(draft); }
        CombatFiles.Changes changes = new CombatFiles.Changes(saved);
        chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles ignored) -> {
            String line = new CombatFiles(toolkit.npcs()).settings(settings.module(), settings.title(), ids, changes);
            synchronized (draft) { for (Map.Entry<List<String>, Optional<JsonElement>> change : saved.entrySet()) draft.remove(change.getKey(), change.getValue()); }
            return line;
        }));
    }

    /** The first selected NPC's definition, empty when nothing is selected. */
    private JsonObject document() {
        List<String> selected = toolkit.selected(viewer.id());
        return selected.isEmpty() ? new JsonObject() : toolkit.npcs().find(selected.getFirst()).map(NpcDefinition::json).orElse(new JsonObject());
    }

    /** What a row shows: the draft's value, else the first NPC's, else what it runs with. */
    private Optional<JsonElement> value(CombatFields.Field field) {
        synchronized (draft) { if (draft.containsKey(field.path())) return draft.get(field.path()); }
        JsonObject document = document();
        Optional<JsonElement> stored = CombatFiles.at(CombatFiles.module(document, settings.module()), field.path());
        return stored.isPresent() ? stored : settings.fallback().value(document, field.path());
    }

    private void row(int slot, CombatFields.Field field) {
        boolean changed;
        synchronized (draft) { changed = field.kind() == CombatFields.Kind.OBJECT ? draft.keySet().stream().anyMatch((List<String> path) -> inside(field, path)) : draft.containsKey(field.path()); }
        boolean stored = CombatFiles.at(CombatFiles.module(document(), settings.module()), field.path()).isPresent();
        Optional<JsonElement> value = value(field);
        List<String> lines = new ArrayList<>();
        boolean number = field.kind() == CombatFields.Kind.NUMBER || field.kind() == CombatFields.Kind.INTEGER;
        if (!field.description().isEmpty() && (advanced() || !number)) lines.add("&7" + field.description());
        String state = field.kind() == CombatFields.Kind.OBJECT ? (stored || changed ? "set" : "none") : shown(field, value);
        String now = "Now: " + state + (changed ? " (not saved)" : !stored && value.isPresent() ? " (default)" : "");
        lines.add((changed ? "&e" : "&f") + now);
        lines.add("&8" + field.key());
        button(slot, icon(field, value), title(field.label()), lore(lines, hints(field, value)), (GuiModel.Click click) -> click(field, value, click));
    }

    private static boolean inside(CombatFields.Field field, List<String> path) {
        return path.size() > field.path().size() && path.subList(0, field.path().size()).equals(field.path());
    }

    private void click(CombatFields.Field field, Optional<JsonElement> value, GuiModel.Click click) {
        boolean back = click.right();
        switch (field.kind()) {
            case BOOLEAN -> stage(field, new JsonPrimitive(!value.map(JsonElement::getAsBoolean).orElse(false)));
            case NUMBER, INTEGER -> {
                if (sneak(click)) { type(field, value); return; }
                double next = step(click, value.map(JsonElement::getAsDouble).orElse(field.minimum()), field.step(), field.minimum(), field.maximum());
                stage(field, field.kind() == CombatFields.Kind.INTEGER ? new JsonPrimitive(Math.round(next)) : new JsonPrimitive(next));
            }
            case CHOICE -> stage(field, new JsonPrimitive(cycle(click, field.options(), value.map(JsonElement::getAsString).orElse(field.options().getFirst()))));
            case TEXT -> prompt(label(field), value.map(JsonElement::getAsString).orElse(""), (String typed) -> {
                if (typed.isBlank()) clear(field); else stage(field, new JsonPrimitive(typed.strip()));
            });
            case YOU -> { if (back) clear(field); else charge(field, new JsonPrimitive(viewer.id().toString())); }
            case NPC -> {
                if (back) { clear(field); return; }
                pick(field.label(), (String npc) -> {
                    toolkit.npcs().find(npc).ifPresent((NpcDefinition definition) -> charge(field, new JsonPrimitive(definition.uuid().toString())));
                    viewer.refresh();
                });
            }
            case HERE -> { if (back) clear(field); else here().ifPresent((JsonObject spot) -> stage(field, spot)); }
            case OBJECT -> push(new SettingsScreen<>(toolkit, viewer, commands, settings, title + " " + field.label().toLowerCase(Locale.ROOT), field.children(), draft, true));
            case WEAPONS -> { if (back) push(new Weapons<>(toolkit, viewer, commands, this, field)); else weapon(field); }
        }
    }

    /** A number typed as people read it, seconds for a tick count; blank goes back to the default. */
    private void type(CombatFields.Field field, Optional<JsonElement> value) {
        prompt(label(field), value.map((JsonElement number) -> number(number.getAsDouble() / field.scale())).orElse(""), (String typed) -> {
            if (typed.isBlank()) { clear(field); return; }
            double minimum = field.minimum() / field.scale(), maximum = field.maximum() / field.scale();
            Optional<Double> shown = parse(typed);
            if (shown.isEmpty() || shown.get() < minimum || shown.get() > maximum) {
                refuse(field.label() + " is from " + number(minimum) + " to " + number(maximum) + (field.unit().isEmpty() ? "" : " " + field.unit()));
                return;
            }
            double stored = shown.get() * field.scale();
            stage(field, field.kind() == CombatFields.Kind.INTEGER ? new JsonPrimitive(Math.round(stored)) : new JsonPrimitive(stored));
        });
    }

    /** Asks the damage for the item in the viewer's main hand. */
    private void weapon(CombatFields.Field field) {
        Optional<NpcItem> held = toolkit.host().worn(viewer.player(), EquipmentSlot.MAIN_HAND);
        if (held.isEmpty()) { refuse("Hold the weapon in your main hand first"); return; }
        JsonObject map = weapons(field);
        String item = held.get().id();
        prompt("Weapon damage", map.has(item) ? number(map.get(item).getAsDouble()) : "", (String typed) -> {
            Optional<Double> damage = parse(typed);
            if (damage.isEmpty() || damage.get() < 0 || damage.get() > 100) { refuse("Damage is a number from 0 to 100"); return; }
            JsonObject next = weapons(field);
            next.addProperty(item, damage.get());
            stage(field, next);
        });
    }

    private void unweapon(CombatFields.Field field, String item) {
        JsonObject map = weapons(field);
        if (map.remove(item) == null) return;
        if (map.isEmpty()) clear(field); else stage(field, map);
    }

    /** A copy of the weapon damage map the row shows. */
    private JsonObject weapons(CombatFields.Field field) {
        return value(field).filter(JsonElement::isJsonObject).map((JsonElement object) -> object.getAsJsonObject().deepCopy()).orElse(new JsonObject());
    }

    /** Guard either a player or an NPC: setting one clears the other. */
    private void charge(CombatFields.Field field, JsonElement value) {
        for (CombatFields.Field other : fields)
            if (other != field && (other.kind() == CombatFields.Kind.YOU || other.kind() == CombatFields.Kind.NPC)) clear(other);
        stage(field, value);
    }

    private void stage(CombatFields.Field field, JsonElement value) { draft.put(field.path(), Optional.of(value)); }
    private void clear(CombatFields.Field field) { draft.put(field.path(), Optional.empty()); }

    /** Where the viewer stands, in the first NPC's world when the viewer is in it. */
    private Optional<JsonObject> here() {
        Optional<ScreenHost.Pose> pose = toolkit.host().pose(viewer.player());
        if (pose.isEmpty()) { refuse("Your position cannot be read here"); return Optional.empty(); }
        List<String> selected = toolkit.selected(viewer.id());
        Optional<String> world = selected.isEmpty() ? Optional.empty() : toolkit.npcs().find(selected.getFirst())
                .map((NpcDefinition definition) -> definition.json().getAsJsonObject("position").get("world").getAsString()).filter(pose.get()::in);
        JsonObject spot = new JsonObject();
        spot.addProperty("world", world.orElseGet(() -> pose.get().worlds().stream().sorted().findFirst().orElse("world")));
        spot.addProperty("x", Math.round(pose.get().x() * 100) / 100d);
        spot.addProperty("y", Math.round(pose.get().y() * 100) / 100d);
        spot.addProperty("z", Math.round(pose.get().z() * 100) / 100d);
        return Optional.of(spot);
    }

    private static String label(CombatFields.Field field) { return field.label().length() > LABEL ? field.label().substring(0, LABEL) : field.label(); }

    private static List<String> hints(CombatFields.Field field, Optional<JsonElement> value) {
        return switch (field.kind()) {
            case BOOLEAN -> any(value.map(JsonElement::getAsBoolean).orElse(false) ? "turn off" : "turn on");
            case NUMBER, INTEGER -> hints("+" + field.shown(field.step()), "-" + field.shown(field.step()), "type a value, blank resets");
            case CHOICE -> hints("next", "previous", "");
            case TEXT -> any("type a value, blank clears");
            case YOU -> hints("set to you", "clear", "");
            case NPC -> hints("click an NPC with the wand", "clear", "");
            case HERE -> hints("set to where you stand", "clear", "");
            case OBJECT -> any("open");
            case WEAPONS -> hints("add the held item", "list and remove", "");
        };
    }

    private static String icon(CombatFields.Field field, Optional<JsonElement> value) {
        return switch (field.kind()) {
            case BOOLEAN -> value.map(JsonElement::getAsBoolean).orElse(false) ? "minecraft:lime_dye" : "minecraft:gray_dye";
            case NUMBER -> "minecraft:clock";
            case INTEGER -> "minecraft:repeater";
            case CHOICE -> "minecraft:compass";
            case TEXT -> "minecraft:name_tag";
            case YOU -> HEAD;
            case NPC -> "minecraft:armor_stand";
            case HERE -> "minecraft:red_bed";
            case OBJECT -> "minecraft:chest";
            case WEAPONS -> "minecraft:iron_sword";
        };
    }

    /** A value as the row's Now line reads it: on or off, a number in its unit, you, an NPC's id, a spot, or how many entries. */
    private String shown(CombatFields.Field field, Optional<JsonElement> value) {
        if (value.isEmpty() || value.get().isJsonNull()) return "none";
        JsonElement element = value.get();
        return switch (field.kind()) {
            case BOOLEAN -> element.getAsBoolean() ? "on" : "off";
            case NUMBER, INTEGER -> field.shown(element.getAsDouble());
            case YOU -> element.getAsString().equals(viewer.id().toString()) ? "you" : "another player";
            case NPC -> npc(element.getAsString());
            case HERE -> element.isJsonObject() ? spot(element.getAsJsonObject()) : "set";
            case OBJECT -> "set";
            case WEAPONS -> element.isJsonObject() ? element.getAsJsonObject().size() + (element.getAsJsonObject().size() == 1 ? " item" : " items") : "none";
            case CHOICE, TEXT -> element.isJsonPrimitive() ? element.getAsString() : "set";
        };
    }

    private String npc(String uuid) {
        return toolkit.npcs().stored().stream().filter((NpcDefinition definition) -> definition.uuid().toString().equals(uuid)).findFirst().map(NpcDefinition::id).orElse("an NPC");
    }

    private static String spot(JsonObject spot) {
        return Math.round(spot.get("x").getAsDouble()) + " " + Math.round(spot.get("y").getAsDouble()) + " " + Math.round(spot.get("z").getAsDouble());
    }

    /** A typed number, or empty when it is not one. */
    static Optional<Double> parse(String typed) {
        try {
            double value = Double.parseDouble(typed.strip());
            return Double.isFinite(value) ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException invalid) { return Optional.empty(); }
    }

    /** 2.0 reads as 2, 0.25 as 0.25. */
    static String number(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) return Long.toString((long) value);
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** The weapon damage entries: a click removes one from the shared draft, row 4 adds the held item. */
    static final class Weapons<P, S> extends ToolScreen<P, S> {
        static final int ADD = ACTIONS;
        private final SettingsScreen<P, S> owner;
        private final CombatFields.Field field;

        private Weapons(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, SettingsScreen<P, S> owner, CombatFields.Field field) {
            super(toolkit, viewer, commands);
            this.owner = owner; this.field = field;
        }

        @Override String title() { return field.label(); }
        @Override String node() { return owner.node(); }
        @Override int rows() { return 6; }

        @Override void draw() {
            List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(owner.weapons(field).entrySet());
            list(entries, Map.Entry::getKey, "None yet; add one below", (Integer slot, Map.Entry<String, JsonElement> entry) ->
                    button(slot, "minecraft:paper", title(itemName(new NpcItem(entry.getKey(), 1, Optional.empty(), Optional.empty()))),
                            lore(List.of("&fNow: " + number(entry.getValue().getAsDouble()) + " damage", "&8" + entry.getKey()), any("remove")),
                            (GuiModel.Click click) -> owner.unweapon(field, entry.getKey())));
            button(ADD, "minecraft:item_frame", title("Add held item"), lore(List.of("&7The item in your main hand"), any("type its damage")), (GuiModel.Click click) -> owner.weapon(field));
            draft(owner.draft.size(), toolkit.selected(viewer.id()).size(), false, owner::save, owner.draft::clear);
            help(owner.settings.help(), owner.settings.page(), owner.settings.anchor());
        }
    }
}
