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
 * Mounts of the first selected NPC, picked by clicking NPCs with the wand, and how players ride the selection. A mount
 * that would loop, nest too deep or overload the NPC carrying it is refused before anything is written.
 */
final class MountScreen<P, S> extends ToolScreen<P, S> {
    static final int SUMMARY = 4, RIDE = 10, CARRY = 11, PICK = 12, MOB = 13, RIDING = 14, STATUS = 15, SEAT = 16, DISMOUNT = 17;
    /** Real entities an NPC commonly rides, by type and the name the menu shows. */
    static final List<ChoiceScreen.Option> VEHICLES = List.of(vehicle("minecraft:horse", "Horse"), vehicle("minecraft:donkey", "Donkey"), vehicle("minecraft:mule", "Mule"),
            vehicle("minecraft:camel", "Camel"), vehicle("minecraft:llama", "Llama"), vehicle("minecraft:pig", "Pig"), vehicle("minecraft:strider", "Strider"),
            vehicle("minecraft:happy_ghast", "Happy ghast"), vehicle("minecraft:oak_boat", "Oak boat"), vehicle("minecraft:minecart", "Minecart"));
    private static ChoiceScreen.Option vehicle(String type, String name) {
        return new ChoiceScreen.Option(new com.google.gson.JsonPrimitive(type), name, List.of("&7Rides the nearest one within " + (int) MountDeclaration.DEFAULT_RANGE + " blocks"), type);
    }

    MountScreen(Toolkit<P, S> toolkit, ScreenManager<P, S>.Viewer viewer, Toolkit.Commands commands) { super(toolkit, viewer, commands); }

    @Override String title() { return "Mounts"; }
    @Override String node() { return PermissionNodes.NPC_EDIT; }
    @Override int rows() { return 6; }

    @Override void draw() {
        List<String> selected = toolkit.selected(viewer.id());
        back();
        help("A mount is an NPC riding another NPC or a real mob, a player riding an NPC, or a player sitting on one. Pick NPCs with the wand.", MovementScreen.PAGE, "mounted-npcs");
        button(STATUS, "minecraft:saddle", title("Ride status"), lore(List.of("&7Who rides what right now", "&7Shows in chat"), any("show it")), (GuiModel.Click click) -> {
            viewer.close();
            toolkit.host().command(Optional.of(viewer.player()), "animus ride status");
        });
        button(PICK, "minecraft:lead", title("Pick a rider"), lore(List.of("&7Click the rider, then the NPC it rides"), any("pick both with the wand")),
                (GuiModel.Click click) -> pickAndClose("Rider", (String rider) -> pickAndClose("NPC for " + rider + " to ride", (String mount) -> mount(rider, mount))));
        if (selected.isEmpty()) {
            icon(SUMMARY, "minecraft:name_tag", title("Nothing selected"), List.of("&7Pick an NPC first; Pick a rider works without one"), Optional.empty());
            return;
        }
        String first = selected.getFirst();
        Optional<String> rides = Optional.empty();
        List<String> rideEntity = new ArrayList<>(1);
        List<String> riders = new ArrayList<>(), problems = new ArrayList<>();
        Map<String, UUID> known = new HashMap<>();
        List<MountRelations.Node> nodes = new ArrayList<>();
        for (NpcDefinition definition : toolkit.npcs().stored()) {
            known.put(definition.id(), definition.uuid());
            MountReference reference;
            try { reference = MountDeclaration.parse(definition.json()).orElse(null); }
            catch (IllegalArgumentException invalid) { reference = null; if (definition.id().equals(first)) problems.add(invalid.getMessage()); }
            nodes.add(new MountRelations.Node(definition.id(), definition.uuid(), reference));
            if (definition.id().equals(first)) {
                try { MountDeclaration.entity(definition.json()).ifPresent((MountDeclaration.EntityMount entity) -> rideEntity.add(entity.describe())); }
                catch (IllegalArgumentException invalid) { /* Reported above with the module's other problems. */ }
            }
            if (reference == null || reference.vehicleId() == null) continue;
            if (definition.id().equals(first)) rides = Optional.of(reference.vehicleId());
            if (reference.vehicleId().equals(first)) riders.add(definition.id());
        }
        for (MountRelations.Diagnostic problem : MountRelations.resolve(nodes, known::get).diagnostics())
            if (problem.id().equals(first)) problems.add(problem.message());
        String ridden = rides.orElse(rideEntity.isEmpty() ? "nothing" : rideEntity.getFirst());
        List<String> lines = new ArrayList<>(List.of("&7Rides &f" + ridden, "&7Carries &f" + names(riders.stream().sorted().toList(), 6)));
        for (String problem : problems) lines.add("&c" + problem);
        if (selected.size() > 1) lines.add("&7First of " + ToolkitFiles.count(selected.size()) + "; Riding changes all");
        icon(SUMMARY, "minecraft:name_tag", title(first), lines, Optional.empty());

        button(RIDE, "minecraft:saddle", title("Ride another NPC"), lore(List.of("&7" + first + " rides the NPC you click"), any("pick it with the wand")),
                (GuiModel.Click click) -> pickAndClose("NPC for " + first + " to ride", (String picked) -> mount(first, picked)));
        button(CARRY, "minecraft:chest_minecart", title("Carry another NPC"), lore(List.of("&7The NPC you click rides " + first), any("pick it with the wand")),
                (GuiModel.Click click) -> pickAndClose("Rider for " + first, (String picked) -> mount(picked, first)));
        button(MOB, "minecraft:lead", title("Ride a mob"), lore(List.of("&7" + first + " rides the nearest horse, boat or other mob"), any("pick a kind")),
                (GuiModel.Click click) -> push(new ChoiceScreen<>(toolkit, viewer, commands, "Mob for " + first, VEHICLES, null,
                        (com.google.gson.JsonElement type) -> toolkit.write(viewer, (ToolkitFiles ignored) -> new CombatFiles(toolkit.npcs()).mountEntity(first, type.getAsString())))));
        button(SEAT, "minecraft:oak_stairs", title("Seat"), lore(List.of("&7Players sit on top with a click, as GSit seats players"), any("open")),
                (GuiModel.Click click) -> push(new SettingsScreen<>(toolkit, viewer, commands, CombatFields.seat())));
        button(RIDING, "minecraft:carrot_on_a_stick", title("Riding"), lore(List.of("&7How players ride it: permission, speed, leash, lifetime"), any("open")),
                (GuiModel.Click click) -> push(new SettingsScreen<>(toolkit, viewer, commands, CombatFields.riding())));
        confirm(DISMOUNT, "minecraft:barrier", "Dismount", first + " stops riding; Undo in chat brings it back", 0, true,
                () -> toolkit.write(viewer, (ToolkitFiles ignored) -> new CombatFiles(toolkit.npcs()).dismount(first)));
    }

    private void mount(String rider, String mount) {
        toolkit.write(viewer, (ToolkitFiles ignored) -> new CombatFiles(toolkit.npcs()).mount(rider, mount));
    }
}
