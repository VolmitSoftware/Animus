package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.HealthSettings;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Combat for the selection. Simple shows six controls that save at once: Fights, Attacks, Can be hurt, Health, Guard
 * me and Mobs can attack it. Advanced adds three labelled rows: the rule lists, the live {@code /animus combat} actions
 * and the eight setting sections. File changes go to every selected NPC in one transaction and ask once above {@link #BULK}.
 * A control whose NPCs differ reads Mixed, and a change from there sets all of them to one stated value after asking.
 */
final class CombatScreen<P, S> extends ToolScreen<P, S> {
    static final int HEADER = 4, FIGHTS = 10, ATTACKS = 11, HURT = 12, HEALTH = 13, GUARD = 14, MOBS = 15,
            RULES = 18, TARGETS = 19, IGNORES = 20, AVOIDS = 21,
            LIVE = 27, STATUS = 28, ENGAGE = 29, DISENGAGE = 30, RELEASE = 31, GUARD_NOW = 32, SERVER = 33,
            SETTINGS = 36, SECTIONS = 37;
    /** Maximum health moves a heart per click, within the bounds {@link HealthSettings} reads. */
    private static final double HEALTH_STEP = 2, HEALTH_MINIMUM = 1, HEALTH_MAXIMUM = 2048;
    private static final String LABEL = "minecraft:light_gray_stained_glass_pane";
    private static final Map<String, String> ICONS = Map.ofEntries(Map.entry("targeting", "minecraft:ender_eye"), Map.entry("avoidance", "minecraft:feather"),
            Map.entry("squad", "minecraft:bell"), Map.entry("guard", "minecraft:shield"), Map.entry("pursuit", "minecraft:leather_boots"),
            Map.entry("melee", "minecraft:iron_sword"), Map.entry("ranged", "minecraft:bow"), Map.entry("greetings", "minecraft:oak_sign"));

    CombatScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }

    @Override String title() { return "Combat"; }
    @Override String node() { return PermissionNodes.COMBAT; }
    @Override int rows() { return 6; }
    @Override boolean forSelection() { return true; }

    @Override void draw() {
        back();
        help("Sets whether the selected NPC fights, who it attacks and its health; each change saves at once with Undo in chat. "
                + "Advanced adds the rule lists, live controls and every setting.", "21-in-game-toolkit", "combat");
        List<String> ids = toolkit.selected(viewer.id());
        List<NpcDefinition> npcs = ids.stream().map(toolkit.npcs()::find).flatMap(Optional::stream).toList();
        if (npcs.isEmpty()) { nothingSelected(this); return; }
        NpcDefinition first = npcs.getFirst();
        List<String> header = new ArrayList<>(List.of("&7" + (npcs.size() > 1 ? names(ids, 4) : "Whether it fights, who it attacks, its health")));
        if (npcs.size() > 1 && advanced()) header.add("&7Rules and settings show " + first.id());
        icon(HEADER, "minecraft:iron_sword", title(npcs.size() > 1 ? ToolkitFiles.count(npcs.size()) : NpcsScreen.name(first)), header, Optional.empty());
        long fighting = npcs.stream().filter((NpcDefinition npc) -> CombatFiles.literalTrue(combat(npc), "enabled")).count();
        toggle(FIGHTS, "Fights", "Fights back and attacks its targets", fighting, npcs.size(), (Boolean on) -> (CombatFiles files, List<String> chosen) -> files.enable(chosen, on));
        attacks(npcs);
        long hurt = npcs.stream().filter(CombatScreen::hurt).count();
        toggle(HURT, "Can be hurt", "Takes damage and can die", hurt, npcs.size(), (Boolean on) -> (CombatFiles files, List<String> chosen) -> files.hurt(chosen, on));
        health(npcs);
        guard(npcs);
        long mobs = npcs.stream().filter((NpcDefinition npc) -> CombatFiles.literalTrue(combat(npc), "targetableByMobs")).count();
        toggle(MOBS, "Mobs can attack it", "Hostile mobs nearby may attack it", mobs, npcs.size(), (Boolean on) -> (CombatFiles files, List<String> chosen) -> files.mobs(chosen, on));
        if (!advanced()) return;
        label(RULES, "Rules", "Who it attacks, ignores and avoids");
        rules(TARGETS, "minecraft:target", "targets", "Who it attacks", first);
        rules(IGNORES, "minecraft:white_wool", "ignores", "Who it never attacks", first);
        rules(AVOIDS, "minecraft:feather", "avoids", "Who it keeps away from", first);
        live(ids);
        label(SETTINGS, "Settings", "Every combat setting, by section");
        for (int index = 0; index < CombatFields.SECTIONS.size(); index++) {
            String section = CombatFields.SECTIONS.get(index);
            boolean set = CombatFiles.at(combat(first), List.of(section)).isPresent();
            button(SECTIONS + index, ICONS.getOrDefault(section, "minecraft:book"), title(CombatFields.label(section)),
                    lore(List.of("&7" + CombatFields.about(section), "&fNow: " + (set ? "set" : "defaults")), any("open")),
                    (GuiModel.Click click) -> push(new SettingsScreen<>(toolkit, viewer, commands, CombatFields.combat(section))));
        }
    }

    /** What a combat screen shows with nothing selected: the next step, instead of controls with nothing to change. */
    static <P, S> void nothingSelected(ToolScreen<P, S> screen) {
        screen.button(HEADER, "minecraft:name_tag", title("Nothing selected"), lore(List.of("&7Pick an NPC first"), any("pick one")),
                (GuiModel.Click click) -> screen.push(new NpcsScreen<>(screen.toolkit, screen.viewer, screen.commands)));
    }

    @FunctionalInterface private interface Work { String run(CombatFiles files, List<String> ids) throws IOException; }

    /** Runs the file work on the selection as it is at the click; the reply carries Undo. */
    private void write(Work work) {
        chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles ignored) -> work.run(new CombatFiles(toolkit.npcs()), ids)));
    }

    /** Asks once above {@link #BULK} NPCs, or when {@code destructive}, then writes. */
    private void change(int slot, String what, boolean destructive, Work work) {
        ask(slot, what, toolkit.selected(viewer.id()).size(), destructive, () -> write(work));
    }

    /** An on/off control over the selection: on for all turns it off, anything less turns it on for all. */
    private void toggle(int slot, String name, String what, long on, int of, Function<Boolean, Work> work) {
        boolean all = on == of;
        String now = on == 0 ? "off" : all ? "on" : "on for " + on + " of " + of;
        button(slot, all ? "minecraft:lime_dye" : "minecraft:gray_dye", title(name), lore(List.of("&7" + what, "&fNow: " + now), any(all ? "turn off" : "turn on")),
                (GuiModel.Click click) -> change(slot, name + (all ? ": off" : ": on") + " for " + named(toolkit.selected(viewer.id())), false, work.apply(!all)));
    }

    /**
     * Attacks: the four target choices in a cycle. Rules no choice describes read Custom and stay until a choice is
     * picked, which asks first because it replaces them.
     */
    private void attacks(List<NpcDefinition> npcs) {
        List<Optional<CombatFields.Attacks>> current = npcs.stream().map((NpcDefinition npc) -> CombatFiles.at(combat(npc), List.of("targets"))
                .map((JsonElement targets) -> targets.isJsonObject() ? CombatFields.attacks(targets.getAsJsonObject()) : Optional.<CombatFields.Attacks>empty())
                .orElse(Optional.of(CombatFields.ATTACKS.getLast()))).toList();
        boolean custom = current.stream().anyMatch(Optional::isEmpty);
        boolean same = current.stream().distinct().count() == 1;
        Optional<CombatFields.Attacks> shared = custom || !same ? Optional.empty() : current.getFirst();
        String now = custom ? "Custom" : shared.map(CombatFields.Attacks::label).orElse("Mixed");
        CombatFields.Attacks next = shared.map((CombatFields.Attacks choice) -> cycle(GuiModel.Click.LEFT, CombatFields.ATTACKS, choice)).orElse(CombatFields.ATTACKS.getFirst());
        CombatFields.Attacks previous = shared.map((CombatFields.Attacks choice) -> cycle(GuiModel.Click.RIGHT, CombatFields.ATTACKS, choice)).orElse(CombatFields.ATTACKS.getLast());
        button(ATTACKS, "minecraft:crossbow", title("Attacks"), lore(List.of("&fNow: " + now), hints("switch to " + next.label(), "switch to " + previous.label(), "")),
                (GuiModel.Click click) -> {
                    CombatFields.Attacks chosen = click.right() ? previous : next;
                    String what = custom ? "Replaces the custom target rules with " + chosen.label() : "Attacks: " + chosen.label();
                    change(ATTACKS, what, custom, (CombatFiles files, List<String> ids) -> files.attacks(ids, chosen));
                });
    }

    /**
     * Health: the selected NPCs' maximum health. When they share it a click adds a heart, a right-click takes one off and a
     * sneak-click types it; when they differ it reads Mixed and any click types one value for all of them, asking first.
     */
    private void health(List<NpcDefinition> npcs) {
        List<Double> values = npcs.stream().map((NpcDefinition npc) -> HealthSettings.maximum(npc.json())).distinct().sorted().toList();
        if (values.size() > 1) {
            String now = "Mixed, " + SettingsScreen.number(values.getFirst()) + " to " + SettingsScreen.number(values.getLast());
            button(HEALTH, "minecraft:glistering_melon_slice", title("Health"), lore(numberRow("Its maximum health", now), any("type one value for all")),
                    (GuiModel.Click click) -> typeHealth("", true));
            return;
        }
        double maximum = values.getFirst();
        String step = SettingsScreen.number(HEALTH_STEP);
        button(HEALTH, "minecraft:glistering_melon_slice", title("Health"), lore(numberRow("Its maximum health", SettingsScreen.number(maximum)), hints("+" + step, "-" + step, "type a value")),
                (GuiModel.Click click) -> {
                    if (sneak(click)) typeHealth(SettingsScreen.number(maximum), false);
                    else health(Optional.of(Math.clamp(maximum + (click.right() ? -HEALTH_STEP : HEALTH_STEP), HEALTH_MINIMUM, HEALTH_MAXIMUM)), false);
                });
    }

    /** Asks for a health; blank goes back to the default. {@code mixed} asks once more, since the NPCs differ now. */
    private void typeHealth(String initial, boolean mixed) {
        prompt("Health", initial, (String typed) -> {
            if (typed.isBlank()) { health(Optional.empty(), mixed); return; }
            Optional<Double> value = SettingsScreen.parse(typed);
            if (value.isEmpty() || value.get() < HEALTH_MINIMUM || value.get() > HEALTH_MAXIMUM)
                refuse("Health is a number from " + SettingsScreen.number(HEALTH_MINIMUM) + " to " + SettingsScreen.number(HEALTH_MAXIMUM));
            else health(value, mixed);
        });
    }

    private void health(Optional<Double> maximum, boolean mixed) {
        String value = maximum.map(SettingsScreen::number).orElse("default");
        String what = mixed ? "Sets health to " + value + " on all " + ToolkitFiles.count(toolkit.selected(viewer.id()).size()) + "; they differ now" : "Health: " + value;
        change(HEALTH, what, mixed, (CombatFiles files, List<String> ids) -> files.health(ids, maximum));
    }

    /**
     * Guard me: saves the viewer as the charge of every selected NPC, or stops them guarding when all of them already do.
     * Taking NPCs off another player or NPC they guard asks first and says how many.
     */
    private void guard(List<NpcDefinition> npcs) {
        String me = viewer.id().toString();
        long on = npcs.stream().filter((NpcDefinition npc) -> charge(npc, "player").filter(me::equals).isPresent()).count();
        List<String> others = npcs.stream().map(this::guarded).flatMap(Optional::stream).toList();
        boolean all = on == npcs.size();
        String now = all ? "on" : others.isEmpty() ? (on > 0 ? "on for " + on + " of " + npcs.size() : "off")
                : npcs.size() == 1 ? "guards " + others.getFirst() : "Mixed, " + on + " guard you, " + others.size() + " guard others";
        String what = all ? "Guard me: off" : others.isEmpty() ? "Guard me: on" : "Replaces who " + ToolkitFiles.count(others.size()) + " guard now with you";
        button(GUARD, "minecraft:shield", title("Guard me"), lore(List.of("&7Follows you and fights what hurts you", "&fNow: " + now), any(all ? "stop guarding you" : "guard you")),
                (GuiModel.Click click) -> change(GUARD, what, !all && !others.isEmpty(),
                        (CombatFiles files, List<String> ids) -> files.guard(ids, all ? Optional.empty() : Optional.of(viewer.id()))));
    }

    /** Who else the NPC guards: an NPC by its id, or another player; empty when it guards the viewer or nobody. */
    private Optional<String> guarded(NpcDefinition npc) {
        Optional<String> other = charge(npc, "npc").map((String uuid) -> toolkit.npcs().stored().stream()
                .filter((NpcDefinition each) -> each.uuid().toString().equals(uuid)).findFirst().map(NpcDefinition::id).orElse("an NPC"));
        return other.isPresent() ? other : charge(npc, "player").filter((String player) -> !player.equals(viewer.id().toString())).map((String player) -> "another player");
    }

    private static Optional<String> charge(NpcDefinition npc, String key) {
        return CombatFiles.at(combat(npc), List.of("guard", key)).filter(JsonElement::isJsonPrimitive).map(JsonElement::getAsString);
    }

    /** Whether the NPC takes damage: {@code killable} in its health module, off until set. */
    private static boolean hurt(NpcDefinition npc) { return CombatFiles.literalTrue(CombatFiles.module(npc.json(), HealthSettings.MODULE), "killable"); }

    private static JsonObject combat(NpcDefinition npc) { return CombatFiles.module(npc.json(), CombatSettings.MODULE); }

    /** A row's label at its left end: not a button. */
    private void label(int slot, String name, String what) { icon(slot, LABEL, "&f" + name, List.of("&7" + what), Optional.empty()); }

    /** A rule list with at most three lines of what it holds. */
    private void rules(int slot, String icon, String list, String what, NpcDefinition first) {
        JsonObject rules = CombatFiles.at(combat(first), List.of(list)).filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject).orElse(new JsonObject());
        List<String> summary = TargetRulesScreen.summary(rules);
        List<String> lines = new ArrayList<>(List.of("&7" + what));
        List<String> shown = summary.size() > 3 ? summary.subList(0, 2) : summary;
        if (shown.isEmpty()) lines.add("&fNow: none");
        for (int index = 0; index < shown.size(); index++) lines.add("&f" + (index == 0 ? "Now: " : "") + shown.get(index));
        if (summary.size() > 3) lines.add("&7+" + (summary.size() - 2) + " more");
        button(slot, icon, title(CombatFields.label(list)), lore(lines, any("open")), (GuiModel.Click click) -> push(new TargetRulesScreen<>(toolkit, viewer, commands, list)));
    }

    /** The live row: runs {@code /animus combat} as the viewer, once per selected NPC; nothing is saved. */
    private void live(List<String> ids) {
        label(LIVE, "Live", "Runs now; nothing is saved");
        button(STATUS, "minecraft:spyglass", title("Status"), lore(List.of("&7What each one is doing now", "&7Shows in chat"), any("show it")), (GuiModel.Click click) -> {
            viewer.close();
            each(ids, "animus combat status npc=");
        });
        live(ENGAGE, "minecraft:iron_sword", "Engage", "Lets it fight now", "animus combat engage ", ids);
        live(DISENGAGE, "minecraft:poppy", "Disengage", "Stops it fighting until engaged", "animus combat disengage ", ids);
        live(RELEASE, "minecraft:lead", "Release", "Back to its own rules", "animus combat release ", ids);
        button(GUARD_NOW, "minecraft:shield", title("Guard me now"), lore(List.of("&7Until released; not saved"), hints("guard you", "stop guarding", "")),
                (GuiModel.Click click) -> ask(GUARD_NOW, (click.right() ? "Stops " : "Guards you with ") + named(ids), ids.size(), false,
                        () -> { for (String id : ids) command("animus combat guard " + id + " " + (click.right() ? "stop" : viewer.id().toString())); }));
        button(SERVER, "minecraft:map", title("Server status"), lore(List.of("&7Combat on the whole server", "&7Shows in chat"), any("show it")), (GuiModel.Click click) -> {
            viewer.close();
            command("animus combat status");
        });
    }

    private void live(int slot, String icon, String name, String what, String command, List<String> ids) {
        button(slot, icon, title(name), lore(List.of("&7" + what), any(name.toLowerCase(Locale.ROOT))),
                (GuiModel.Click click) -> ask(slot, name + " " + named(ids), ids.size(), false, () -> each(ids, command)));
    }

    private void each(List<String> ids, String command) { for (String id : ids) command(command + id); }

    private void command(String line) { toolkit.host().command(Optional.of(viewer.player()), line); }
}
