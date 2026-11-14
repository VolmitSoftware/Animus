package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.HealthSettings;
import art.arcane.animus.core.combat.TargetCategory;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One rule list of the combat module ({@code targets}, {@code ignores} or {@code avoids}) as the kinds of rule it can
 * hold. Clicks change a draft of the list, checked as each entry is added; Save writes it to every selected NPC in one
 * transaction. Nested rule sets, together groups and fight events are kept as they are and edited in the editor.
 */
final class TargetRulesScreen<P, S> extends ToolScreen<P, S> {
    static final int INFO_SLOT = ACTIONS + 8;
    private static final List<String> NESTED = List.of("all", "together", "events");
    private static final String EXPLAINED = "A rule list matches anyone who fits one of its rules, and an ignore always beats a target. "
            + "Clicks change a draft that Save writes to every selected NPC.";

    private enum Type { CATEGORY, OWNER, HEALTH, LIST, NESTED }

    /** How a list row adds an entry besides typing one: a wand pick, the held item, or neither. */
    private enum Adds { TYPED, NPC, HELD }

    /** One row: a category toggle, the owner rule, a health bound, a list of names or patterns, or a kept nested rule. */
    private record Rule(String label, List<String> path, Type type, String icon, String example, String description, Adds adds) {
        Rule { path = List.copyOf(path); }

        String key() { return String.join(".", path); }
    }

    private final String list;
    private JsonObject rules;

    TargetRulesScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, String list) {
        super(toolkit, viewer, commands);
        this.list = list;
        this.rules = stored();
    }

    @Override String title() { return CombatFields.label(list); }
    @Override String node() { return PermissionNodes.COMBAT; }
    @Override int rows() { return 6; }

    /** The rows a list allows: ignores have no chat words, nested scans or fight events. */
    private static List<Rule> kinds(String list) {
        List<Rule> kinds = new ArrayList<>();
        for (TargetCategory category : TargetCategory.values())
            kinds.add(new Rule(category(category), List.of("categories", category.jsonName()), Type.CATEGORY,
                    switch (category) { case PLAYERS -> HEAD; case NPCS -> "minecraft:armor_stand"; case MOBS -> "minecraft:spawner"; case MONSTERS -> "minecraft:zombie_head"; case ANIMALS -> "minecraft:wheat"; },
                    "", "Every " + category.jsonName().replace("npcs", "NPC").replaceAll("s$", ""), Adds.TYPED));
        kinds.add(new Rule("Owner", List.of("owner"), Type.OWNER, "minecraft:golden_helmet", "", "The NPC's owner", Adds.TYPED));
        kinds.add(new Rule("Health above", List.of("healthAbove"), Type.HEALTH, "minecraft:glistering_melon_slice", "", "Percent of its maximum health", Adds.TYPED));
        kinds.add(new Rule("Health below", List.of("healthBelow"), Type.HEALTH, "minecraft:spider_eye", "", "Percent of its maximum health", Adds.TYPED));
        kinds.add(new Rule("Mob types", List.of("entityTypes"), Type.LIST, "minecraft:zombie_spawn_egg", "minecraft:zombie", "Entity type ids", Adds.TYPED));
        kinds.add(new Rule("Players by name", List.of("players"), Type.LIST, "minecraft:player_head", "Steve", "Player names or UUIDs", Adds.TYPED));
        kinds.add(new Rule("NPCs by id", List.of("npcs"), Type.LIST, "minecraft:armor_stand", "guard", "NPC ids or display names", Adds.NPC));
        kinds.add(new Rule("Mobs by custom name", List.of("names"), Type.LIST, "minecraft:name_tag", "Bob", "Custom names of mobs", Adds.TYPED));
        kinds.add(new Rule("Squads", List.of("squads"), Type.LIST, "minecraft:bell", "north", "Squads of other NPCs", Adds.TYPED));
        kinds.add(new Rule("Permissions", List.of("permissions"), Type.LIST, "minecraft:tripwire_hook", "town.outlaw", "Permission nodes a player has", Adds.TYPED));
        kinds.add(new Rule("Permission groups", List.of("groups"), Type.LIST, "minecraft:chest", "outlaws", "Permission groups a player is in", Adds.TYPED));
        kinds.add(new Rule("Effects", List.of("effects"), Type.LIST, "minecraft:potion", "minecraft:glowing", "Status effects the target has", Adds.TYPED));
        kinds.add(new Rule("Player name pattern", List.of("patterns", "players"), Type.LIST, "minecraft:paper", "^Guest", "A regular expression found in the name", Adds.TYPED));
        kinds.add(new Rule("NPC name pattern", List.of("patterns", "npcs"), Type.LIST, "minecraft:paper", "^bandit", "A regular expression found in the id or name", Adds.TYPED));
        kinds.add(new Rule("Mob name pattern", List.of("patterns", "names"), Type.LIST, "minecraft:paper", "zombie", "A regular expression found in the mob's name", Adds.TYPED));
        kinds.add(new Rule("Main-hand item", List.of("items", "mainHand"), Type.LIST, "minecraft:iron_sword", "_sword$", "An item id pattern", Adds.HELD));
        kinds.add(new Rule("Off-hand item", List.of("items", "offHand"), Type.LIST, "minecraft:shield", "shield", "An item id pattern", Adds.HELD));
        kinds.add(new Rule("Worn item", List.of("items", "worn"), Type.LIST, "minecraft:iron_chestplate", "netherite", "An item id pattern", Adds.HELD));
        boolean seeking = !list.equals("ignores");
        if (seeking) kinds.add(new Rule("Chat words", List.of("chat"), Type.LIST, "minecraft:writable_book", "traitor", "Words in chat that make the speaker a target", Adds.TYPED));
        for (String nested : NESTED)
            if (seeking || nested.equals("all")) kinds.add(new Rule(switch (nested) { case "all" -> "Match-all sets"; case "together" -> "Together sets"; default -> "Fight events"; },
                    List.of(nested), Type.NESTED, "minecraft:bookshelf", "", "Edit them in the web editor", Adds.TYPED));
        return kinds;
    }

    /** {@code npcs} reads NPCs, the rest as their capitalised name. */
    private static String category(TargetCategory category) { return category == TargetCategory.NPCS ? "NPCs" : CombatFields.label(category.jsonName()); }

    @Override void draw() {
        back();
        help(EXPLAINED, "21-in-game-toolkit", "rule-lists");
        List<String> selected = toolkit.selected(viewer.id());
        if (selected.isEmpty()) { CombatScreen.nothingSelected(this); return; }
        list(kinds(list), Rule::label, "No rules", this::row);
        List<String> lines = new ArrayList<>();
        if (selected.size() > 1) lines.add("&7Rows show " + selected.getFirst() + "; Save sets all of them");
        List<String> summary = summary(rules);
        if (summary.isEmpty()) lines.add("&7Empty; Save removes the list");
        for (String line : summary) lines.add("&f" + line);
        icon(INFO_SLOT, "minecraft:name_tag", title(named(selected)), lines, Optional.empty());
        draft(changes(), selected.size(), true, this::save, this::discard);
    }

    /** How many rule kinds the draft changes from the saved list. */
    private int changes() {
        JsonObject saved = stored();
        Set<String> keys = new HashSet<>(saved.keySet());
        keys.addAll(rules.keySet());
        int changed = 0;
        for (String key : keys) if (!Objects.equals(saved.get(key), rules.get(key))) changed++;
        return changed;
    }

    private void save() {
        JsonObject saved = rules.deepCopy();
        chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles ignored) -> new CombatFiles(toolkit.npcs()).rules(ids, list, saved)));
    }

    private void discard() { rules = stored(); }

    private void row(int slot, Rule rule) {
        List<String> lines = new ArrayList<>(List.of("&7" + rule.description()));
        switch (rule.type()) {
            case CATEGORY -> {
                boolean on = strings(List.of("categories")).contains(rule.path().getLast());
                lines.add("&fNow: " + (on ? "on" : "off"));
                lines.add("&8categories: " + rule.path().getLast());
                button(slot, rule.icon(), title(rule.label()), lore(lines, any(on ? "turn off" : "turn on")), (GuiModel.Click click) -> toggle(rule.path().getLast()));
            }
            case OWNER -> {
                boolean on = CombatFiles.literalTrue(rules, "owner");
                lines.add("&fNow: " + (on ? "on" : "off"));
                lines.add("&8owner");
                button(slot, rule.icon(), title(rule.label()), lore(lines, any(on ? "turn off" : "turn on")), (GuiModel.Click click) -> {
                    if (on) rules.remove("owner"); else rules.addProperty("owner", true);
                });
            }
            case HEALTH -> {
                Optional<JsonElement> value = CombatFiles.at(rules, rule.path());
                lines.add("&fNow: " + value.map((JsonElement number) -> SettingsScreen.number(number.getAsDouble()) + "%").orElse("off"));
                lines.add("&8" + rule.key());
                List<String> hints = value.isEmpty() ? hints("turn on at 50%", "", "type a value") : hints("+5%", "-5%", "type a value, blank turns off");
                button(slot, rule.icon(), title(rule.label()), lore(lines, hints), (GuiModel.Click click) -> health(rule, value, click));
            }
            case LIST -> {
                List<String> entries = strings(rule.path());
                lines.add("&fNow: " + (entries.isEmpty() ? "none" : names(entries, 4)));
                lines.add("&8" + rule.key());
                button(slot, rule.icon(), title(rule.label()), lore(lines, hints("add one", "list and remove", "")), (GuiModel.Click click) -> {
                    if (click.right()) push(new Entries<>(toolkit, viewer, commands, this, rule));
                    else type(rule);
                });
            }
            case NESTED -> {
                Optional<JsonElement> value = CombatFiles.at(rules, rule.path());
                int count = value.map((JsonElement element) -> element.isJsonArray() ? element.getAsJsonArray().size() : element.getAsJsonObject().size()).orElse(0);
                lines.add("&fNow: " + count + " kept");
                lines.add("&8" + rule.key());
                icon(slot, rule.icon(), title(rule.label()), lines, Optional.empty());
            }
        }
    }

    /** Click turns a bound on at 50% or adds 5%, right-click takes 5% off, sneak-click types a percent; blank turns it off. */
    private void health(Rule rule, Optional<JsonElement> value, GuiModel.Click click) {
        String key = rule.path().getFirst();
        if (sneak(click)) {
            prompt(rule.label(), value.map((JsonElement number) -> SettingsScreen.number(number.getAsDouble())).orElse(""), (String typed) -> {
                if (typed.isBlank()) { rules.remove(key); return; }
                Optional<Double> percent = SettingsScreen.parse(typed);
                if (percent.isEmpty() || percent.get() < 0 || percent.get() > 100) { refuse(rule.label() + " is a percent from 0 to 100"); return; }
                percent(key, percent.get());
            });
            return;
        }
        if (value.isEmpty()) { if (!click.right()) rules.addProperty(key, 50); return; }
        percent(key, Math.clamp(value.get().getAsDouble() + (click.right() ? -5 : 5), 0, 100));
    }

    private void percent(String key, double value) {
        if (value == Math.rint(value)) rules.addProperty(key, (long) value); else rules.addProperty(key, value);
    }

    private void type(Rule rule) {
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
        try { CombatSettings.parseModule(module, HealthSettings.UNKILLABLE); }
        catch (IllegalArgumentException invalid) { refuse(invalid.getMessage()); return; }
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

    /** A rule list as lines people read, one per kind of rule in screen order: {@code Monsters, Players}, {@code Players by name: Steve}. */
    static List<String> summary(JsonObject rules) {
        List<String> lines = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        Set<String> shown = new HashSet<>();
        for (Rule rule : kinds("targets")) {
            Optional<JsonElement> value = CombatFiles.at(rules, rule.path().subList(0, rule.type() == Type.CATEGORY ? 1 : rule.path().size()));
            shown.add(rule.path().getFirst());
            if (value.isEmpty()) continue;
            switch (rule.type()) {
                case CATEGORY -> { if (value.get().isJsonArray() && value.get().getAsJsonArray().contains(new JsonPrimitive(rule.path().getLast()))) categories.add(rule.label()); }
                case OWNER -> { if (CombatFiles.literalTrue(rules, "owner")) lines.add(rule.label()); }
                case HEALTH -> lines.add(rule.label() + " " + SettingsScreen.number(value.get().getAsDouble()) + "%");
                case LIST -> lines.add(rule.label() + ": " + names(texts(value.get()), 4));
                case NESTED -> lines.add(rule.label() + ": " + (value.get().isJsonArray() ? value.get().getAsJsonArray().size() : value.get().getAsJsonObject().size()) + " kept");
            }
        }
        if (!categories.isEmpty()) lines.addFirst(String.join(", ", categories));
        for (Map.Entry<String, JsonElement> other : rules.entrySet()) if (!shown.contains(other.getKey())) lines.add(other.getKey() + ": " + other.getValue());
        return lines;
    }

    private static List<String> texts(JsonElement array) {
        List<String> texts = new ArrayList<>();
        if (array.isJsonArray()) for (JsonElement item : array.getAsJsonArray()) texts.add(item.isJsonPrimitive() ? item.getAsString() : item.toString());
        return texts;
    }

    /** The entries of one list: a click removes one from the draft, row 4 adds one, and Save writes the shared draft. */
    static final class Entries<P, S> extends ToolScreen<P, S> {
        static final int ADD = ACTIONS, OTHER = ACTIONS + 1;
        private final TargetRulesScreen<P, S> owner;
        private final Rule rule;

        private Entries(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands, TargetRulesScreen<P, S> owner, Rule rule) {
            super(toolkit, viewer, commands);
            this.owner = owner; this.rule = rule;
        }

        @Override String title() { return rule.label(); }
        @Override String node() { return PermissionNodes.COMBAT; }
        @Override int rows() { return 6; }

        @Override void draw() {
            list(owner.strings(rule.path()), ToolScreen::itself, "None yet; add one below", (Integer slot, String value) ->
                    button(slot, "minecraft:paper", title(value), any("remove"), (GuiModel.Click click) -> owner.remove(rule, value)));
            button(ADD, "minecraft:writable_book", title("Add one"), lore(List.of("&7" + rule.description()), any("type one")), (GuiModel.Click click) -> type());
            switch (rule.adds()) {
                case NPC -> button(OTHER, "minecraft:armor_stand", title("Pick an NPC"), lore(List.of("&7Click one with the wand"), any("pick one")),
                        (GuiModel.Click click) -> pick(rule.label(), (String npc) -> { owner.add(rule, npc); viewer.refresh(); }));
                case HELD -> button(OTHER, "minecraft:item_frame", title("Add held item"), lore(List.of("&7The item in your main hand"), any("add it")), (GuiModel.Click click) -> held());
                case TYPED -> { }
            }
            draft(owner.changes(), toolkit.selected(viewer.id()).size(), false, owner::save, owner::discard);
            help(EXPLAINED, "21-in-game-toolkit", "rule-lists");
        }

        private void type() {
            prompt(rule.label(), rule.example(), (String typed) -> { if (!typed.isBlank()) owner.add(rule, typed.strip()); });
        }

        private void held() {
            Optional<NpcItem> item = toolkit.host().worn(viewer.player(), EquipmentSlot.MAIN_HAND);
            if (item.isEmpty()) { refuse("Hold the item in your main hand first"); return; }
            owner.add(rule, "^" + item.get().id() + "$");
        }
    }
}
