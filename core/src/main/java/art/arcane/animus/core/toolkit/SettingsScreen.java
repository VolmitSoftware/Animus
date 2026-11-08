package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenHost;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.text.ChatLine;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One module section as rows of settings. Clicks change a draft; Save writes the draft to every selected NPC in one
 * transaction and Back drops it. Rows show the first selected NPC, a value it leaves out as the default it runs with.
 * A nested object opens the same screen on its rows, sharing the draft.
 */
final class SettingsScreen<P, S> extends ToolScreen<P, S> {
    static final int INFO_SLOT = 46, DISCARD = 47, SAVE = 53;
    private final CombatFields.Settings settings;
    private final String title;
    private final List<CombatFields.Field> fields;
    private final Map<List<String>, Optional<JsonElement>> draft;

    SettingsScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, CombatFields.Settings settings) {
        this(toolkit, viewer, commands, settings, settings.title(), settings.fields(), Collections.synchronizedMap(new LinkedHashMap<>()));
    }

    private SettingsScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, CombatFields.Settings settings, String title,
                           List<CombatFields.Field> fields, Map<List<String>, Optional<JsonElement>> draft) {
        super(toolkit, viewer, commands);
        this.settings = settings; this.title = title; this.fields = fields; this.draft = draft;
    }

    @Override String title() { return title; }
    @Override int rows() { return 6; }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        JsonObject document = selected.isEmpty() ? new JsonObject() : toolkit.npcs().find(selected.getFirst()).map(NpcDefinition::json).orElse(new JsonObject());
        JsonObject module = CombatFiles.module(document, settings.module());
        list(fields, "Nothing to edit here", (Integer slot, CombatFields.Field field) -> row(slot, field, document, module));
        back();
        int changes = draft.size();
        icon(INFO_SLOT, "minecraft:name_tag", title(ToolkitFiles.count(selected.size()) + " selected"),
                List.of("&7Shows &f" + (selected.isEmpty() ? "nothing" : selected.getFirst()), "&7Save sets the changed rows on all of them"), Optional.empty());
        if (changes > 0) button(DISCARD, "minecraft:bucket", title("Discard"), any("drop " + changes + (changes == 1 ? " change" : " changes")), (GuiModel.Click click) -> draft.clear());
        guarded(SAVE, "minecraft:writable_book", "Save", changes + (changes == 1 ? " change" : " changes") + " for " + ToolkitFiles.count(selected.size()), selected.size(), false, this::save);
    }

    private void save() {
        Map<List<String>, Optional<JsonElement>> saved;
        synchronized (draft) { saved = new LinkedHashMap<>(draft); }
        if (saved.isEmpty()) { toolkit.say(viewer.player(), ChatLine.reply("Nothing changed")); return; }
        CombatFiles.Changes changes = new CombatFiles.Changes(saved);
        chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles ignored) -> {
            String line = new CombatFiles(toolkit.npcs()).settings(settings.module(), ids, changes);
            synchronized (draft) { for (Map.Entry<List<String>, Optional<JsonElement>> change : saved.entrySet()) draft.remove(change.getKey(), change.getValue()); }
            return line;
        }));
    }

    private void row(int slot, CombatFields.Field field, JsonObject document, JsonObject module) {
        boolean changed = draft.containsKey(field.path());
        Optional<JsonElement> stored = CombatFiles.at(module, field.path());
        Optional<JsonElement> value = changed ? draft.get(field.path()) : stored.isPresent() ? stored : settings.fallback().value(document, field.path());
        boolean fallback = !changed && stored.isEmpty();
        List<String> lines = new ArrayList<>();
        if (!field.description().isEmpty()) lines.add("&7" + field.description());
        if (field.kind() == CombatFields.Kind.NUMBER || field.kind() == CombatFields.Kind.INTEGER)
            lines.add("&7From &f" + number(field.minimum()) + "&7 to &f" + number(field.maximum()) + "&7, step &f" + number(field.step()));
        if (field.kind() == CombatFields.Kind.CHOICE) lines.add("&7One of &f" + String.join(", ", field.options()));
        lines.addAll(entries(field, value));
        if (fallback && value.isPresent()) lines.add("&7Not set; runs with this");
        if (changed) lines.add("&eChanged; Save writes it");
        String name = title(field.label() + (field.kind() == CombatFields.Kind.OBJECT || isList(field) ? "" : ": &f" + shown(value)));
        button(slot, icon(field, value), name, lore(lines, hints(field)), (GuiModel.Click click) -> click(field, value, click));
    }

    private void click(CombatFields.Field field, Optional<JsonElement> value, GuiModel.Click click) {
        boolean clear = right(click) || click == GuiModel.Click.SHIFT_RIGHT;
        switch (field.kind()) {
            case BOOLEAN -> stage(field, new JsonPrimitive(!value.map(JsonElement::getAsBoolean).orElse(false)));
            case NUMBER -> stage(field, new JsonPrimitive(step(click, value.map(JsonElement::getAsDouble).orElse(field.minimum()), field.step(), field.minimum(), field.maximum())));
            case INTEGER -> stage(field, new JsonPrimitive(Math.round(step(click, value.map(JsonElement::getAsDouble).orElse(field.minimum()), field.step(), field.minimum(), field.maximum()))));
            case CHOICE -> stage(field, new JsonPrimitive(cycle(click, field.options(), value.map(JsonElement::getAsString).orElse(field.options().getFirst()))));
            case TEXT -> {
                if (clear || sneak(click)) { clear(field); return; }
                prompt(field.label(), value.map(JsonElement::getAsString).orElse(""), (String typed) -> {
                    if (typed.isBlank()) clear(field); else stage(field, new JsonPrimitive(typed.strip()));
                });
            }
            case YOU -> { if (clear) clear(field); else charge(field, new JsonPrimitive(viewer.id().toString())); }
            case NPC -> {
                if (clear) { clear(field); return; }
                pick(field.label(), (String npc) -> toolkit.npcs().find(npc).ifPresent((NpcDefinition definition) -> charge(field, new JsonPrimitive(definition.uuid().toString()))));
            }
            case HERE -> { if (clear) clear(field); else here().ifPresent((JsonObject spot) -> stage(field, spot)); }
            case OBJECT -> viewer.push(new SettingsScreen<>(toolkit, viewer, commands, settings, title + "." + field.path().getLast(), field.children(), draft));
            case WEAPONS -> weapons(field, value, clear);
        }
    }

    /** Left sets the held item's damage through a prompt; right removes the held item's entry. */
    private void weapons(CombatFields.Field field, Optional<JsonElement> value, boolean clear) {
        Optional<NpcItem> held = toolkit.host().worn(viewer.player(), EquipmentSlot.MAIN_HAND);
        if (held.isEmpty()) { toolkit.say(viewer.player(), ChatLine.error("Hold the weapon in your main hand first")); return; }
        JsonObject map = value.filter(JsonElement::isJsonObject).map((JsonElement object) -> object.getAsJsonObject().deepCopy()).orElse(new JsonObject());
        String item = held.get().id();
        if (clear) {
            if (map.remove(item) == null) return;
            if (map.isEmpty()) clear(field); else stage(field, map);
            return;
        }
        prompt("Damage for " + item, map.has(item) ? number(map.get(item).getAsDouble()) : "", (String typed) -> {
            double damage;
            try { damage = Double.parseDouble(typed.strip()); }
            catch (NumberFormatException invalid) { toolkit.say(viewer.player(), ChatLine.error("Damage is a number from 0 to 100")); return; }
            if (!(damage >= 0 && damage <= 100)) { toolkit.say(viewer.player(), ChatLine.error("Damage is a number from 0 to 100")); return; }
            map.addProperty(item, damage);
            stage(field, map);
        });
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
        if (pose.isEmpty()) { toolkit.say(viewer.player(), ChatLine.error("Your position cannot be read here")); return Optional.empty(); }
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

    private static boolean isList(CombatFields.Field field) {
        return field.kind() == CombatFields.Kind.WEAPONS;
    }

    /** The lines that list a list's entries. */
    private static List<String> entries(CombatFields.Field field, Optional<JsonElement> value) {
        if (!isList(field) && field.kind() != CombatFields.Kind.OBJECT) return List.of();
        List<String> lines = new ArrayList<>();
        if (value.isEmpty()) { lines.add("&7None"); return lines; }
        if (value.get().isJsonObject())
            for (Map.Entry<String, JsonElement> entry : value.get().getAsJsonObject().entrySet()) lines.add("&f" + entry.getKey() + " " + shown(Optional.of(entry.getValue())));
        return lines;
    }

    private static List<String> hints(CombatFields.Field field) {
        return switch (field.kind()) {
            case BOOLEAN -> any("toggle");
            case NUMBER, INTEGER -> hints("+" + number(field.step()), "-" + number(field.step()), "ten steps");
            case CHOICE -> hints("next", "previous", "next");
            case TEXT -> hints("type a value", "clear", "clear");
            case YOU -> hints("set to you", "clear", "set to you");
            case NPC -> hints("click an NPC with the wand", "clear", "click an NPC with the wand");
            case HERE -> hints("set to where you stand", "clear", "set to where you stand");
            case OBJECT -> any("open");
            case WEAPONS -> hints("type a damage for the held item", "remove the held item", "type a damage for the held item");
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

    private static String shown(Optional<JsonElement> value) {
        if (value.isEmpty() || value.get().isJsonNull()) return "none";
        JsonElement element = value.get();
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) return element.getAsBoolean() ? "on" : "off";
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) return number(element.getAsDouble());
        if (element.isJsonPrimitive()) return element.getAsString();
        return "set";
    }

    /** 2.0 reads as 2, 0.25 as 0.25. */
    static String number(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) return Long.toString((long) value);
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
