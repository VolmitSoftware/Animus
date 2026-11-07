package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.TargetCategory;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.text.ChatLine;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One rule list of the combat module ({@code targets}, {@code ignores} or {@code avoids}) as the kinds of rule it can
 * hold. Clicks change a draft of the list, checked as each entry is added; Save writes it to every selected NPC in one
 * transaction. Nested rule sets, together groups and fight events are kept as they are and edited in the editor.
 */
final class TargetRulesScreen<P, S> extends ToolScreen<P, S> {
    static final int INFO_SLOT = 46, DISCARD = 47, SAVE = 53;
    private static final List<String> NESTED = List.of("all", "together", "events");

    private enum Type { CATEGORY, OWNER, HEALTH, LIST, NESTED }

    /** One row: a category toggle, the owner rule, a health bound, a list of names or patterns, or a kept nested rule. */
    private record Rule(String label, List<String> path, Type type, String icon, String example, String description) {
        Rule { path = List.copyOf(path); }
    }

    private final String list;
    private JsonObject rules;

    TargetRulesScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String list) {
        super(toolkit, viewer, commands);
        this.list = list;
        this.rules = stored();
    }

    @Override String title() { return CombatFields.label(list); }
    @Override int rows() { return 6; }

    /** The rows this list allows: ignores have no chat words, nested scans or fight events. */
    private List<Rule> kinds() {
        List<Rule> kinds = new ArrayList<>();
        for (TargetCategory category : TargetCategory.values())
            kinds.add(new Rule(category == TargetCategory.NPCS ? "NPCs" : CombatFields.label(category.jsonName()), List.of("categories", category.jsonName()), Type.CATEGORY,
                    switch (category) { case PLAYERS -> HEAD; case NPCS -> "minecraft:armor_stand"; case MOBS -> "minecraft:spawner"; case MONSTERS -> "minecraft:zombie_head"; case ANIMALS -> "minecraft:wheat"; },
                    "", "Every " + category.jsonName().replace("npcs", "NPC").replaceAll("s$", "")));
        kinds.add(new Rule("Owner", List.of("owner"), Type.OWNER, "minecraft:golden_helmet", "", "The NPC's owner."));
        kinds.add(new Rule("Health above", List.of("healthAbove"), Type.HEALTH, "minecraft:glistering_melon_slice", "", "Percent of maximum health."));
        kinds.add(new Rule("Health below", List.of("healthBelow"), Type.HEALTH, "minecraft:spider_eye", "", "Percent of maximum health."));
        kinds.add(new Rule("Mob types", List.of("entityTypes"), Type.LIST, "minecraft:zombie_spawn_egg", "minecraft:zombie", "Entity type ids."));
        kinds.add(new Rule("Players by name", List.of("players"), Type.LIST, "minecraft:player_head", "Steve", "Player names or UUIDs."));
        kinds.add(new Rule("NPCs by id", List.of("npcs"), Type.LIST, "minecraft:armor_stand", "guard", "NPC ids or display names. Sneak to click one with the wand."));
        kinds.add(new Rule("Mobs by custom name", List.of("names"), Type.LIST, "minecraft:name_tag", "Bob", "Custom names of mobs."));
        kinds.add(new Rule("Squads", List.of("squads"), Type.LIST, "minecraft:white_banner", "north", "Squads of other NPCs."));
        kinds.add(new Rule("Permissions", List.of("permissions"), Type.LIST, "minecraft:tripwire_hook", "town.outlaw", "Permission nodes a player has."));
        kinds.add(new Rule("Permission groups", List.of("groups"), Type.LIST, "minecraft:chest", "outlaws", "Permission groups a player is in."));
        kinds.add(new Rule("Effects", List.of("effects"), Type.LIST, "minecraft:potion", "minecraft:glowing", "Status effects the target has."));
        kinds.add(new Rule("Player name pattern", List.of("patterns", "players"), Type.LIST, "minecraft:paper", "^Guest", "A regular expression found in the name."));
        kinds.add(new Rule("NPC name pattern", List.of("patterns", "npcs"), Type.LIST, "minecraft:paper", "^bandit", "A regular expression found in the id or name."));
        kinds.add(new Rule("Mob name pattern", List.of("patterns", "names"), Type.LIST, "minecraft:paper", "zombie", "A regular expression found in the mob's name."));
        kinds.add(new Rule("Main-hand item", List.of("items", "mainHand"), Type.LIST, "minecraft:iron_sword", "_sword$", "An item id pattern. Sneak to use the item you hold."));
        kinds.add(new Rule("Off-hand item", List.of("items", "offHand"), Type.LIST, "minecraft:shield", "shield", "An item id pattern. Sneak to use the item you hold."));
        kinds.add(new Rule("Worn item", List.of("items", "worn"), Type.LIST, "minecraft:iron_chestplate", "netherite", "An item id pattern. Sneak to use the item you hold."));
        boolean seeking = !list.equals("ignores");
        if (seeking) kinds.add(new Rule("Chat words", List.of("chat"), Type.LIST, "minecraft:writable_book", "traitor", "Words in chat that make the speaker a target."));
        for (String nested : NESTED)
            if (seeking || nested.equals("all")) kinds.add(new Rule(switch (nested) { case "all" -> "Match-all sets"; case "together" -> "Together groups"; default -> "Fight events"; },
                    List.of(nested), Type.NESTED, "minecraft:bookshelf", "", "Kept as they are; edit them in the web editor."));
        return kinds;
    }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        list(kinds(), "No rules", this::row);
        back();
        icon(INFO_SLOT, "minecraft:name_tag", title(ToolkitFiles.count(selected.size()) + " selected"),
                List.of("&7Shows &f" + (selected.isEmpty() ? "nothing" : selected.getFirst()), "&7Save sets this whole list on all of them"), Optional.empty());
        boolean changed = !rules.equals(stored());
        if (changed) button(DISCARD, "minecraft:bucket", title("Discard"), any("go back to the saved list"), (GuiModel.Click click) -> rules = stored());
        List<String> summary = summary(rules);
        String detail = (summary.isEmpty() ? "Empty; Save removes the list" : String.join("; ", summary)) + (changed ? " (changed)" : "");
        guarded(SAVE, "minecraft:writable_book", "Save", detail, selected.size(), false, this::save);
    }

    private void save() {
        JsonObject saved = rules.deepCopy();
        chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles ignored) -> new CombatFiles(toolkit.npcs()).rules(ids, list, saved)));
    }

    private void row(int slot, Rule rule) {
        List<String> lines = new ArrayList<>(List.of("&7" + rule.description()));
        switch (rule.type()) {
            case CATEGORY -> {
                boolean on = strings(List.of("categories")).contains(rule.path().getLast());
                lines.add(on ? "&aOn" : "&7Off");
                button(slot, rule.icon(), title(rule.label() + ": &f" + (on ? "on" : "off")), lore(lines, any("toggle")), (GuiModel.Click click) -> toggle(rule.path().getLast()));
            }
            case OWNER -> {
                boolean on = CombatFiles.literalTrue(rules, "owner");
                button(slot, rule.icon(), title(rule.label() + ": &f" + (on ? "on" : "off")), lore(lines, any("toggle")), (GuiModel.Click click) -> {
                    if (on) rules.remove("owner"); else rules.addProperty("owner", true);
                });
            }
            case HEALTH -> {
                Optional<JsonElement> value = CombatFiles.at(rules, rule.path());
                button(slot, rule.icon(), title(rule.label() + ": &f" + value.map((JsonElement number) -> SettingsScreen.number(number.getAsDouble()) + "%").orElse("off")),
                        lore(lines, hints(value.isEmpty() ? "turn on at 50%" : "+5%", "-5%", "turn off")), (GuiModel.Click click) -> health(rule, value, click));
            }
            case LIST -> {
                List<String> entries = strings(rule.path());
                lines.add("&f" + (entries.isEmpty() ? "&7None" : names(entries, 6)));
                String sneak = rule.path().getFirst().equals("npcs") ? "click an NPC with the wand" : rule.path().getFirst().equals("items") ? "add the item you hold" : "list and remove";
                button(slot, rule.icon(), title(rule.label() + " &7(" + entries.size() + ")"), lore(lines, hints("add one", "list and remove", sneak)), (GuiModel.Click click) -> entry(rule, click));
            }
            case NESTED -> {
                Optional<JsonElement> value = CombatFiles.at(rules, rule.path());
                int count = value.map((JsonElement element) -> element.isJsonArray() ? element.getAsJsonArray().size() : element.getAsJsonObject().size()).orElse(0);
                lines.add("&f" + count + "&7 kept");
                icon(slot, rule.icon(), title(rule.label()), lines, Optional.empty());
            }
        }
    }

    private void health(Rule rule, Optional<JsonElement> value, GuiModel.Click click) {
        String key = rule.path().getFirst();
        if (sneak(click)) { rules.remove(key); return; }
        if (value.isEmpty()) { if (!right(click)) rules.addProperty(key, 50); return; }
        double next = Math.clamp(value.get().getAsDouble() + (right(click) ? -5 : 5), 0, 100);
        if (next == Math.rint(next)) rules.addProperty(key, (long) next); else rules.addProperty(key, next);
    }

    private void entry(Rule rule, GuiModel.Click click) {
        if (right(click) || click == GuiModel.Click.SHIFT_RIGHT || sneak(click) && rule.path().size() == 1 && !rule.path().getFirst().equals("npcs")) {
            viewer.push(new Entries<>(toolkit, viewer, commands, this, rule));
            return;
        }
        if (sneak(click) && rule.path().getFirst().equals("npcs")) { pick(rule.label(), (String npc) -> add(rule, npc)); return; }
        if (sneak(click)) {
            Optional<NpcItem> held = toolkit.host().worn(viewer.player(), EquipmentSlot.MAIN_HAND);
            if (held.isEmpty()) { toolkit.say(viewer.player(), ChatLine.error("Hold the item in your main hand first")); return; }
            add(rule, "^" + held.get().id() + "$");
            return;
        }
        prompt(rule.label(), rule.example(), (String typed) -> { if (!typed.isBlank()) add(rule, typed.strip()); });
    }

    /** Adds an entry when the list still reads as combat reads it; otherwise says why and leaves it out. */
    private void add(Rule rule, String value) {
        JsonObject next = rules.deepCopy();
        JsonArray entries = CombatFiles.at(next, rule.path()).filter(JsonElement::isJsonArray).map(JsonElement::getAsJsonArray).orElse(new JsonArray());
        if (entries.contains(new JsonPrimitive(value))) return;
        entries.add(value);
        CombatFiles.put(next, rule.path(), Optional.of(entries));
        JsonObject module = new JsonObject();
        module.add(list, next);
        try { CombatSettings.parseModule(module); }
        catch (IllegalArgumentException invalid) { toolkit.say(viewer.player(), ChatLine.error(invalid.getMessage())); return; }
        rules = next;
    }

    private void remove(Rule rule, String value) {
        JsonArray entries = CombatFiles.at(rules, rule.path()).filter(JsonElement::isJsonArray).map((JsonElement array) -> array.getAsJsonArray().deepCopy()).orElse(new JsonArray());
        entries.remove(new JsonPrimitive(value));
        CombatFiles.put(rules, rule.path(), entries.isEmpty() ? Optional.empty() : Optional.of(entries));
    }

    private void toggle(String category) {
        List<String> on = strings(List.of("categories"));
        JsonArray next = new JsonArray();
        for (TargetCategory each : TargetCategory.values())
            if (each.jsonName().equals(category) != on.contains(each.jsonName())) next.add(each.jsonName());
        CombatFiles.put(rules, List.of("categories"), next.isEmpty() ? Optional.empty() : Optional.of(next));
    }

    private List<String> strings(List<String> path) {
        List<String> values = new ArrayList<>();
        CombatFiles.at(rules, path).filter(JsonElement::isJsonArray).ifPresent((JsonElement array) -> {
            for (JsonElement value : array.getAsJsonArray()) if (value.isJsonPrimitive()) values.add(value.getAsString());
        });
        return values;
    }

    /** The first selected NPC's saved list. */
    private JsonObject stored() {
        List<String> selected = toolkit.selected(viewer.id());
        if (selected.isEmpty()) return new JsonObject();
        JsonObject module = toolkit.npcs().find(selected.getFirst()).map((NpcDefinition definition) -> CombatFiles.module(definition.json(), CombatSettings.MODULE)).orElse(new JsonObject());
        return CombatFiles.at(module, List.of(list)).filter(JsonElement::isJsonObject).map((JsonElement object) -> object.getAsJsonObject().deepCopy()).orElse(new JsonObject());
    }

    /** A rule list as lines of {@code key: values}, in file order, as the definition file reads. */
    static List<String> summary(JsonObject rules) {
        List<String> lines = new ArrayList<>();
        summarize("", rules, lines);
        return lines;
    }

    private static void summarize(String prefix, JsonObject object, List<String> lines) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = prefix + entry.getKey();
            JsonElement value = entry.getValue();
            if (NESTED.contains(key)) lines.add(key + ": " + (value.isJsonArray() ? value.getAsJsonArray().size() : value.getAsJsonObject().size()) + " kept");
            else if (value.isJsonObject()) summarize(key + ".", value.getAsJsonObject(), lines);
            else if (value.isJsonArray()) {
                List<String> items = new ArrayList<>();
                for (JsonElement item : value.getAsJsonArray()) items.add(item.isJsonPrimitive() ? item.getAsString() : item.toString());
                lines.add((key.equals("categories") ? "" : key + ": ") + names(items, 6));
            } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) { if (value.getAsBoolean()) lines.add(key); }
            else lines.add(key + ": " + value.getAsString().toLowerCase(Locale.ROOT));
        }
    }

    /** The entries of one list; a click removes one from the draft. */
    static final class Entries<P, S> extends ToolScreen<P, S> {
        private final TargetRulesScreen<P, S> owner;
        private final Rule rule;

        private Entries(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, TargetRulesScreen<P, S> owner, Rule rule) {
            super(toolkit, viewer, commands);
            this.owner = owner; this.rule = rule;
        }

        @Override String title() { return rule.label(); }
        @Override int rows() { return 6; }

        @Override void draw() {
            list(owner.strings(rule.path()), "None yet; left click the row on the rules screen to add one", (Integer slot, String value) ->
                    button(slot, "minecraft:paper", title(value), lore(List.of("&7Back, then Save, writes the change"), any("remove")), (GuiModel.Click click) -> owner.remove(rule, value)));
            back();
        }
    }
}
