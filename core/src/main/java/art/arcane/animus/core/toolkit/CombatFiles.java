package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.HealthSettings;
import art.arcane.animus.core.control.RideSettings;
import art.arcane.animus.core.mount.MountDeclaration;
import art.arcane.animus.core.mount.MountReference;
import art.arcane.animus.core.mount.MountRelations;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.presentation.BodyRules;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The combat lane's file work: the combat, health and riding modules, mount pairs and equipment. Every change to several NPCs
 * is one transaction, checked the way the runtime reads it and refused whole when any NPC would be invalid. The Combat
 * screen's replies name the NPCs, then the setting as it now is: {@code guard: can be hurt}.
 */
final class CombatFiles {
    static final String COMBAT = CombatSettings.MODULE, HEALTH = HealthSettings.MODULE, MOUNT = MountDeclaration.MODULE, RIDING = RideSettings.MODULE;
    private final NpcRepository npcs;

    CombatFiles(NpcRepository npcs) { this.npcs = npcs; }

    @FunctionalInterface private interface Edit { boolean change(JsonObject document); }

    /** Changes inside one module by path; an empty value removes the key. */
    record Changes(Map<List<String>, Optional<JsonElement>> values) {
        Changes { values = Map.copyOf(values); }
    }

    /** Turns combat on with literal true, or off by removing {@code enabled}; the rest of the module stays. */
    String enable(List<String> ids, boolean on) throws IOException {
        List<NpcRepository.Draft> drafts = drafts(ids, (JsonObject document) -> {
            JsonObject module = module(document, COMBAT);
            if (literalTrue(module, "enabled") == on) return false;
            if (on) module.addProperty("enabled", true); else module.remove("enabled");
            store(document, COMBAT, module);
            return true;
        });
        if (drafts.isEmpty()) return ToolkitFiles.named(npcs, ids) + ": combat already " + (on ? "on" : "off");
        return ToolkitFiles.named(drafts) + ": combat " + (on ? "on" : "off") + "; tx " + npcs.applyAll(drafts);
    }

    /** Sets fields of the combat or riding module on every listed NPC; {@code title} names them in the reply, such as {@code Melee}. */
    String settings(String key, String title, List<String> ids, Changes changes) throws IOException {
        int count = changes.values().size();
        return change(key, ids, changes, (String who) -> title + ": " + count + (count == 1 ? " change" : " changes") + " on " + who);
    }

    /** Replaces one rule list ({@code targets}, {@code ignores} or {@code avoids}) on every listed NPC. */
    String rules(List<String> ids, String list, JsonObject rules) throws IOException {
        Optional<JsonElement> value = rules.isEmpty() ? Optional.empty() : Optional.of(rules);
        List<String> summary = TargetRulesScreen.summary(rules);
        return change(COMBAT, ids, new Changes(Map.of(List.of(list), value)),
                (String who) -> CombatFields.label(list) + " on " + who + ": " + (summary.isEmpty() ? "none" : String.join("; ", summary)));
    }

    /** Sets the targets list to one Attacks choice, replacing whatever rules it held. */
    String attacks(List<String> ids, CombatFields.Attacks attacks) throws IOException {
        JsonObject targets = CombatFields.targets(attacks);
        return change(COMBAT, ids, new Changes(Map.of(List.of("targets"), targets.isEmpty() ? Optional.empty() : Optional.of(targets))),
                (String who) -> who + ": attacks " + attacks.label().toLowerCase(java.util.Locale.ROOT));
    }

    /** Lets nearby hostile mobs attack the NPCs, or stops it by removing {@code targetableByMobs}. */
    String mobs(List<String> ids, boolean on) throws IOException {
        return change(COMBAT, ids, new Changes(Map.of(List.of("targetableByMobs"), on ? Optional.of(new JsonPrimitive(true)) : Optional.empty())),
                (String who) -> who + (on ? ": mobs can attack " : ": mobs no longer attack ") + (ids.size() == 1 ? "it" : "them"));
    }

    /** Makes the NPCs guard {@code player}, dropping any NPC they guarded, or with empty stops them guarding a player. */
    String guard(List<String> ids, Optional<UUID> player) throws IOException {
        Map<List<String>, Optional<JsonElement>> values = new HashMap<>();
        values.put(List.of("guard", "player"), player.map((UUID id) -> new JsonPrimitive(id.toString())));
        if (player.isPresent()) values.put(List.of("guard", "npc"), Optional.empty());
        return change(COMBAT, ids, new Changes(values), (String who) -> who + ": guard me " + (player.isPresent() ? "on" : "off"));
    }

    /** Lets the NPCs take damage with {@code killable} in {@code animus:health}, or removes it so they cannot be hurt again. */
    String hurt(List<String> ids, boolean on) throws IOException {
        return change(HEALTH, ids, new Changes(Map.of(List.of("killable"), on ? Optional.of(new JsonPrimitive(true)) : Optional.empty())),
                (String who) -> who + (on ? ": can be hurt" : ": cannot be hurt"));
    }

    /** Sets {@code maximumHealth} in {@code animus:health}, or with empty removes it so the attribute or 20 applies. */
    String health(List<String> ids, Optional<Double> maximum) throws IOException {
        Optional<JsonElement> value = maximum.map((Double health) -> health == Math.rint(health) ? new JsonPrimitive(health.longValue()) : new JsonPrimitive(health));
        return change(HEALTH, ids, new Changes(Map.of(List.of("maximumHealth"), value)),
                (String who) -> who + ": health " + maximum.map(SettingsScreen::number).orElse("back to default"));
    }

    /** Applies the changes to the module of every listed NPC in one transaction; {@code line} words the reply around the NPCs it changed. */
    private String change(String key, List<String> ids, Changes changes, Function<String, String> line) throws IOException {
        if (changes.values().isEmpty()) return "Nothing to save";
        List<NpcRepository.Draft> drafts = drafts(ids, (JsonObject document) -> {
            JsonObject module = module(document, key);
            JsonObject before = module.deepCopy();
            for (Map.Entry<List<String>, Optional<JsonElement>> change : changes.values().entrySet()) put(module, change.getKey(), change.getValue());
            if (module.equals(before)) return false;
            store(document, key, module);
            return true;
        });
        if (drafts.isEmpty()) return "Nothing changed on " + ToolkitFiles.named(npcs, ids);
        return line.apply(ToolkitFiles.named(drafts)) + "; tx " + npcs.applyAll(drafts);
    }

    /** Puts {@code rider} on {@code vehicle}, refused when the vehicle is unknown or the pair would loop or overload. */
    String mount(String rider, String vehicle) throws IOException {
        if (npcs.stored().stream().noneMatch((NpcDefinition definition) -> definition.id().equals(vehicle))) throw new IOException("No NPC named " + vehicle);
        Map<String, UUID> known = new HashMap<>();
        List<MountRelations.Node> nodes = new ArrayList<>();
        for (NpcDefinition definition : npcs.stored()) {
            known.put(definition.id(), definition.uuid());
            MountReference reference = definition.id().equals(rider) ? new MountReference("$.modules." + MOUNT + ".vehicle", vehicle, null, MountReference.Status.DECLARED, vehicle)
                    : declared(definition.json());
            nodes.add(new MountRelations.Node(definition.id(), definition.uuid(), reference));
        }
        for (MountRelations.Diagnostic problem : MountRelations.resolve(nodes, known::get).diagnostics())
            if (problem.id().equals(rider) && problem.code().blocksActivation()) throw new IOException(rider + " cannot ride " + vehicle + ": " + problem.message());
        List<NpcRepository.Draft> drafts = drafts(List.of(rider), (JsonObject document) -> {
            JsonObject module = new JsonObject();
            module.addProperty("enabled", true);
            module.addProperty("vehicle", vehicle);
            if (module.equals(module(document, MOUNT))) return false;
            store(document, MOUNT, module);
            return true;
        });
        if (drafts.isEmpty()) return rider + " already rides " + vehicle;
        return rider + " rides " + vehicle + "; tx " + npcs.applyAll(drafts);
    }

    /** Removes the rider's mount module. */
    String dismount(String rider) throws IOException {
        List<NpcRepository.Draft> drafts = drafts(List.of(rider), (JsonObject document) -> {
            if (!document.has("modules") || !document.getAsJsonObject("modules").has(MOUNT)) return false;
            store(document, MOUNT, new JsonObject());
            return true;
        });
        if (drafts.isEmpty()) return rider + " rides nothing";
        return rider + " dismounted; tx " + npcs.applyAll(drafts);
    }

    /**
     * Sets each listed slot, an empty item clearing it, on every listed NPC. An item for a slot the NPC's body cannot
     * show is skipped on that NPC and counted; clearing such a slot is no skip, since there is nothing to show.
     */
    String equip(List<String> ids, Map<EquipmentSlot, Optional<NpcItem>> items) throws IOException {
        int[] skipped = {0};
        List<NpcRepository.Draft> drafts = drafts(ids, (JsonObject document) -> {
            String type = document.get("type").getAsString();
            JsonObject equipment = document.has("equipment") && document.get("equipment").isJsonObject() ? document.getAsJsonObject("equipment") : new JsonObject();
            JsonObject before = equipment.deepCopy();
            boolean skip = false;
            for (Map.Entry<EquipmentSlot, Optional<NpcItem>> item : items.entrySet()) {
                if (!BodyRules.equipmentSlots(type).contains(item.getKey())) { skip |= item.getValue().isPresent(); continue; }
                if (item.getValue().isPresent()) equipment.add(item.getKey().key(), item.getValue().get().toJson());
                else equipment.remove(item.getKey().key());
            }
            if (skip) skipped[0]++;
            if (equipment.equals(before)) return false;
            if (equipment.isEmpty()) document.remove("equipment"); else document.add("equipment", equipment);
            return true;
        });
        String slots = items.size() == 1 ? items.keySet().iterator().next().key().replace('_', ' ') : "equipment";
        String skip = skipped[0] == 0 ? "" : ", " + skipped[0] + " skipped (body cannot show it)";
        if (drafts.isEmpty()) return "No change to " + slots + skip;
        boolean clearing = items.values().stream().allMatch(Optional::isEmpty);
        return (clearing ? "Cleared " : "Set ") + slots + " on " + ToolkitFiles.named(drafts) + skip + "; tx " + npcs.applyAll(drafts);
    }

    private static MountReference declared(JsonObject document) {
        try { return MountDeclaration.parse(document).orElse(null); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    /** The module object, a fresh one when absent. */
    static JsonObject module(JsonObject document, String key) {
        JsonElement modules = document.get("modules");
        if (modules == null || !modules.isJsonObject()) return new JsonObject();
        JsonElement module = modules.getAsJsonObject().get(key);
        return module != null && module.isJsonObject() ? module.getAsJsonObject().deepCopy() : new JsonObject();
    }

    static boolean literalTrue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    /** The value at a path inside an object, or empty. */
    static Optional<JsonElement> at(JsonObject object, List<String> path) {
        JsonElement current = object;
        for (String key : path) {
            if (current == null || !current.isJsonObject()) return Optional.empty();
            current = current.getAsJsonObject().get(key);
        }
        return Optional.ofNullable(current);
    }

    /** Sets or removes the value at a path, creating objects on the way and dropping the ones a removal empties. */
    static void put(JsonObject object, List<String> path, Optional<JsonElement> value) {
        String key = path.getFirst();
        if (path.size() == 1) {
            if (value.isPresent()) object.add(key, value.get().deepCopy()); else object.remove(key);
            return;
        }
        JsonObject child = object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : new JsonObject();
        put(child, path.subList(1, path.size()), value);
        if (child.isEmpty()) object.remove(key); else object.add(key, child);
    }

    /** Writes the module back; an empty one is removed, and so is {@code modules} when nothing is left. */
    private static void store(JsonObject document, String key, JsonObject module) {
        JsonObject modules = document.has("modules") && document.get("modules").isJsonObject() ? document.getAsJsonObject("modules") : new JsonObject();
        if (module.isEmpty()) modules.remove(key); else modules.add(key, module);
        if (modules.isEmpty()) document.remove("modules"); else document.add("modules", modules);
    }

    /** Drafts for the NPCs the edit changes, each checked as the combat, riding and mount readers check it. */
    private List<NpcRepository.Draft> drafts(List<String> ids, Edit edit) throws IOException {
        List<NpcRepository.Draft> drafts = new ArrayList<>();
        for (String id : ids) {
            NpcRepository.Draft draft = npcs.draft(id);
            JsonObject document = draft.document();
            if (!edit.change(document)) continue;
            try {
                CombatSettings.parse(document);
                RideSettings.parse(document);
                MountDeclaration.parse(document);
            } catch (IllegalArgumentException invalid) { throw new IOException(id + ": " + invalid.getMessage()); }
            drafts.add(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        }
        return drafts;
    }
}
