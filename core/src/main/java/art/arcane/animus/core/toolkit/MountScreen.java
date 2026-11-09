package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.command.PermissionNodes;
import art.arcane.animus.core.gui.GuiModel;
import art.arcane.animus.core.gui.ScreenManager;
import art.arcane.animus.core.mount.MountDeclaration;
import art.arcane.animus.core.mount.MountReference;
import art.arcane.animus.core.mount.MountRelations;
import art.arcane.animus.core.npc.NpcDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Mount pairs for the first selected NPC, chosen by clicking NPCs with the wand, and the riding settings of the
 * selection. A pair that would loop, nest too deep or overload its vehicle is refused before anything is written.
 */
final class MountScreen<P, S> extends ToolScreen<P, S> {
    static final int SUMMARY = 4, RIDE = 10, CARRY = 11, PAIR = 12, DISMOUNT = 13, RIDING = 15, RIDES = 16;

    MountScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }

    @Override String title() { return "Mounts"; }
    @Override String node() { return PermissionNodes.NPC_EDIT; }
    @Override int rows() { return 6; }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        back();
        button(RIDES, "minecraft:saddle", title("Ride status"), lore(List.of("&7Who rides which NPC right now"), any("run /animus ride status")),
                (GuiModel.Click click) -> toolkit.host().command(Optional.of(viewer.player()), "animus ride status"));
        button(PAIR, "minecraft:lead", title("Pair two NPCs"), lore(List.of("&7Click the rider, then the vehicle"), any("pick both with the wand")),
                (GuiModel.Click click) -> pickAndClose("Rider", (String rider) -> pickAndClose("Vehicle for " + rider, (String vehicle) -> mount(rider, vehicle))));
        if (selected.isEmpty()) {
            icon(SUMMARY, "minecraft:name_tag", title("Nothing selected"), List.of("&7Click NPCs with the wand, or use Find; Pair works without one"), Optional.empty());
            return;
        }
        String first = selected.getFirst();
        Optional<String> vehicle = Optional.empty();
        List<String> riders = new ArrayList<>(), problems = new ArrayList<>();
        Map<String, UUID> known = new HashMap<>();
        List<MountRelations.Node> nodes = new ArrayList<>();
        for (NpcDefinition definition : toolkit.npcs().stored()) {
            known.put(definition.id(), definition.uuid());
            MountReference reference;
            try { reference = MountDeclaration.parse(definition.json()).orElse(null); }
            catch (IllegalArgumentException invalid) { reference = null; if (definition.id().equals(first)) problems.add(invalid.getMessage()); }
            nodes.add(new MountRelations.Node(definition.id(), definition.uuid(), reference));
            if (reference == null || reference.vehicleId() == null) continue;
            if (definition.id().equals(first)) vehicle = Optional.of(reference.vehicleId());
            if (reference.vehicleId().equals(first)) riders.add(definition.id());
        }
        for (MountRelations.Diagnostic problem : MountRelations.resolve(nodes, known::get).diagnostics())
            if (problem.id().equals(first)) problems.add(problem.message());
        List<String> lines = new ArrayList<>(List.of("&7Rides &f" + vehicle.orElse("nothing"), "&7Carries &f" + names(riders.stream().sorted().toList(), 6)));
        for (String problem : problems) lines.add("&c" + problem);
        if (selected.size() > 1) lines.add("&7First of " + ToolkitFiles.count(selected.size()) + "; Riding applies to all");
        icon(SUMMARY, "minecraft:name_tag", title(first), lines, Optional.empty());

        button(RIDE, "minecraft:saddle", title("Ride another NPC"), lore(List.of("&7" + first + " sits on the NPC you click"), any("pick the vehicle with the wand")),
                (GuiModel.Click click) -> pickAndClose("Vehicle for " + first, (String picked) -> mount(first, picked)));
        button(CARRY, "minecraft:chest_minecart", title("Carry another NPC"), lore(List.of("&7The NPC you click sits on " + first), any("pick the rider with the wand")),
                (GuiModel.Click click) -> pickAndClose("Rider for " + first, (String picked) -> mount(picked, first)));
        button(DISMOUNT, "minecraft:barrier", title("Dismount"), lore(List.of("&7Removes " + first + "'s mount module"), any("dismount " + first)),
                (GuiModel.Click click) -> toolkit.write(viewer, (ToolkitFiles ignored) -> new CombatFiles(toolkit.npcs()).dismount(first)));
        button(RIDING, "minecraft:carrot_on_a_stick", title("Riding"), lore(List.of("&7Lets players ride the selection: permission, speed, leash, lifetime"), any("open")),
                (GuiModel.Click click) -> viewer.push(new SettingsScreen<>(toolkit, viewer, commands, CombatFields.riding())));
    }

    private void mount(String rider, String vehicle) {
        toolkit.write(viewer, (ToolkitFiles ignored) -> new CombatFiles(toolkit.npcs()).mount(rider, vehicle));
    }
}
