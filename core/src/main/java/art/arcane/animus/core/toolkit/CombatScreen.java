package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.npc.NpcDefinition;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Combat for the selection: on and off, the three rule lists, each profile section, and the live
 * {@code /animus combat} actions. File changes go to every selected NPC in one transaction and ask twice above
 * {@link #BULK} NPCs; the live actions run the command once per NPC and its reply comes back as chat.
 */
final class CombatScreen<P, S> extends ToolScreen<P, S> {
    static final int SUMMARY = 4, ENABLE = 10, DISABLE = 11, MOBS = 12, STATUS = 14, ENGAGE = 15, DISENGAGE = 16,
            TARGETS = 19, IGNORES = 20, AVOIDS = 21, RELEASE = 23, GUARD = 24, SECTIONS = 27;
    private static final Map<String, String> ICONS = Map.ofEntries(Map.entry("targeting", "minecraft:spyglass"), Map.entry("avoidance", "minecraft:feather"),
            Map.entry("squad", "minecraft:white_banner"), Map.entry("guard", "minecraft:shield"), Map.entry("pursuit", "minecraft:leather_boots"),
            Map.entry("melee", "minecraft:iron_sword"), Map.entry("ranged", "minecraft:bow"), Map.entry("greetings", "minecraft:oak_sign"));

    CombatScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }

    @Override String title() { return "Combat"; }
    @Override int rows() { return 6; }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        back();
        if (selected.isEmpty()) {
            icon(SUMMARY, "minecraft:name_tag", title("Nothing selected"), List.of("&7Click NPCs with the wand, or use Find"), Optional.empty());
            return;
        }
        int on = 0, waiting = 0;
        for (String id : selected) {
            Optional<NpcDefinition> definition = toolkit.npcs().find(id);
            if (definition.isEmpty()) continue;
            JsonObject module = CombatFiles.module(definition.get().json(), CombatSettings.MODULE);
            if (!CombatFiles.literalTrue(module, "enabled")) continue;
            on++;
            if (definition.get().imported() && !CombatFiles.literalTrue(module, "reviewAccepted")) waiting++;
        }
        JsonObject first = toolkit.npcs().find(selected.getFirst()).map((NpcDefinition definition) -> CombatFiles.module(definition.json(), CombatSettings.MODULE)).orElse(new JsonObject());
        List<String> summary = new ArrayList<>(List.of("&7Combat on &f" + on + "&7 of &f" + selected.size(), "&7Rows show &f" + selected.getFirst()));
        if (waiting > 0) summary.add("&c" + waiting + " with imported combat waiting; Enable turns it on");
        icon(SUMMARY, "minecraft:name_tag", title(ToolkitFiles.count(selected.size()) + " selected"), summary, Optional.empty());

        guarded(ENABLE, "minecraft:lime_concrete", "Enable combat", "Lets them fight; also turns on imported combat", selected.size(), false,
                () -> write((CombatFiles files, List<String> ids) -> files.enable(ids, true)));
        guarded(DISABLE, "minecraft:gray_concrete", "Disable combat", "Stops them fighting; their combat settings stay", selected.size(), false,
                () -> write((CombatFiles files, List<String> ids) -> files.enable(ids, false)));
        boolean mobs = CombatFiles.literalTrue(first, "targetableByMobs");
        guarded(MOBS, mobs ? "minecraft:zombie_head" : "minecraft:skeleton_skull", "Targetable by mobs: " + (mobs ? "on" : "off"),
                "Nearby hostile mobs may attack the NPC; turns it " + (mobs ? "off" : "on"), selected.size(), false,
                () -> write((CombatFiles files, List<String> ids) -> files.settings(CombatSettings.MODULE, ids,
                        new CombatFiles.Changes(Map.of(List.of("targetableByMobs"), mobs ? Optional.<JsonElement>empty() : Optional.of(new JsonPrimitive(true)))))));

        rules(TARGETS, "minecraft:crossbow", "targets", "Who the NPC attacks", first);
        rules(IGNORES, "minecraft:white_wool", "ignores", "Who it never attacks, even when a target rule matches", first);
        rules(AVOIDS, "minecraft:feather", "avoids", "Who it keeps away from", first);

        live(STATUS, "minecraft:spyglass", "Status", hints("status of " + selected.getFirst(), "status of the server", "status of " + selected.getFirst()), (GuiModel.Click click) -> {
            if (right(click)) command("animus combat status"); else command("animus combat status npc=" + selected.getFirst());
        });
        live(ENGAGE, "minecraft:iron_sword", "Engage", any("let each selected NPC fight now"), (GuiModel.Click click) -> each("animus combat engage "));
        live(DISENGAGE, "minecraft:white_banner", "Disengage", any("stop each selected NPC fighting"), (GuiModel.Click click) -> each("animus combat disengage "));
        live(RELEASE, "minecraft:lead", "Release", any("hand each back to its own rules"), (GuiModel.Click click) -> each("animus combat release "));
        live(GUARD, "minecraft:shield", "Guard me", hints("each selected NPC guards you", "each stops guarding", "each guards you"), (GuiModel.Click click) -> {
            for (String id : selected) command("animus combat guard " + id + " " + (right(click) ? "stop" : viewer.id().toString()));
        });

        for (int index = 0; index < CombatFields.SECTIONS.size(); index++) {
            String section = CombatFields.SECTIONS.get(index);
            int slot = SECTIONS + index;
            List<String> lines = new ArrayList<>();
            String description = CombatFields.description(section);
            if (!description.isEmpty()) lines.add("&7" + description);
            lines.add(CombatFiles.at(first, List.of(section)).isPresent() ? "&fSet on " + selected.getFirst() : "&7Defaults on " + selected.getFirst());
            button(slot, ICONS.getOrDefault(section, "minecraft:book"), title(CombatFields.label(section)), lore(lines, any("open")),
                    (GuiModel.Click click) -> viewer.push(new SettingsScreen<>(toolkit, viewer, commands, CombatFields.combat(section))));
        }
    }

    @FunctionalInterface private interface Work { String run(CombatFiles files, List<String> ids) throws java.io.IOException; }

    private void write(Work work) {
        chosen().ifPresent((List<String> ids) -> toolkit.write(viewer, (ToolkitFiles ignored) -> work.run(new CombatFiles(toolkit.npcs()), ids)));
    }

    private void rules(int slot, String icon, String list, String detail, JsonObject first) {
        List<String> lines = new ArrayList<>(List.of("&7" + detail));
        JsonObject rules = CombatFiles.at(first, List.of(list)).filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject).orElse(new JsonObject());
        List<String> summary = TargetRulesScreen.summary(rules);
        if (summary.isEmpty()) lines.add("&7No rules");
        for (String line : summary.subList(0, Math.min(6, summary.size()))) lines.add("&f" + line);
        if (summary.size() > 6) lines.add("&7+" + (summary.size() - 6) + " more");
        button(slot, icon, title(CombatFields.label(list)), lore(lines, any("open")), (GuiModel.Click click) -> viewer.push(new TargetRulesScreen<>(toolkit, viewer, commands, list)));
    }

    private void live(int slot, String icon, String name, List<String> hints, Handler handler) {
        button(slot, icon, title(name), lore(List.of("&7Runs /animus combat; the reply comes as chat"), hints), handler);
    }

    private void each(String command) { for (String id : toolkit.selected(viewer.id())) command(command + id); }

    private void command(String line) { toolkit.host().command(Optional.of(viewer.player()), line); }
}
