package art.arcane.animus.core.toolkit;

import art.arcane.animus.core.combat.CombatSettings;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The combat lane's file work: the combat and riding modules, mount pairs and equipment. Every change to several NPCs
 * is one transaction, checked the way the runtime reads it and refused whole when any NPC would be invalid.
 */
final class CombatFiles {
    static final String COMBAT = CombatSettings.MODULE, MOUNT = MountDeclaration.MODULE, RIDING = RideSettings.MODULE;
    private final NpcRepository npcs;

    CombatFiles(NpcRepository npcs) { this.npcs = npcs; }

    @FunctionalInterface private interface Edit { boolean change(JsonObject document); }

    /** Changes inside one module by path; an empty value removes the key. */
    record Changes(Map<List<String>, Optional<JsonElement>> values) {
        Changes { values = Map.copyOf(values); }
    }

    /** Turns combat on with literal true, or off by removing {@code enabled}. */
    String enable(List<String> ids, boolean on) throws IOException {
        List<NpcRepository.Draft> drafts = drafts(ids, (JsonObject document) -> {
            JsonObject module = module(document, COMBAT);
            if (literalTrue(module, "enabled") == on) return false;
            if (on) module.addProperty("enabled", true); else module.remove("enabled");
            store(document, COMBAT, module);
            return true;
        });
        if (drafts.isEmpty()) return "Combat already " + (on ? "on" : "off") + " for " + ToolkitFiles.count(ids.size());
        return "Combat " + (on ? "on" : "off") + " for " + ToolkitFiles.named(drafts) + "; tx " + npcs.applyAll(drafts);
    }

    /** Sets fields of the combat or riding module on every listed NPC. */
    String settings(String key, List<String> ids, Changes changes) throws IOException {
        int count = changes.values().size();
        return change(key, ids, changes, (key.equals(COMBAT) ? "Combat: " : "Riding: ") + count + (count == 1 ? " change" : " changes"));
    }

    /** Replaces one rule list ({@code targets}, {@code ignores} or {@code avoids}) on every listed NPC. */
    String rules(List<String> ids, String list, JsonObject rules) throws IOException {
        Optional<JsonElement> value = rules.isEmpty() ? Optional.empty() : Optional.of(rules);
        List<String> summary = TargetRulesScreen.summary(rules);
        return change(COMBAT, ids, new Changes(Map.of(List.of(list), value)), "Combat " + list, summary.isEmpty() ? ": none" : ": " + String.join("; ", summary));
    }

    private String change(String key, List<String> ids, Changes changes, String what) throws IOException { return change(key, ids, changes, what, ""); }

    /** {@code detail} follows the NPCs, such as the rules a list now holds. */
    private String change(String key, List<String> ids, Changes changes, String what, String detail) throws IOException {
        if (changes.values().isEmpty()) return "Nothing to save";
        List<NpcRepository.Draft> drafts = drafts(ids, (JsonObject document) -> {
            JsonObject module = module(document, key);
            JsonObject before = module.deepCopy();
            for (Map.Entry<List<String>, Optional<JsonElement>> change : changes.values().entrySet()) put(module, change.getKey(), change.getValue());
            if (module.equals(before)) return false;
            store(document, key, module);
            return true;
        });
        if (drafts.isEmpty()) return what + " already set on " + ToolkitFiles.count(ids.size());
        return what + " on " + ToolkitFiles.named(drafts) + detail + "; tx " + npcs.applyAll(drafts);
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
     * Sets each listed slot, an empty item clearing it, on every listed NPC. A slot the NPC's body cannot show is
     * skipped on that NPC and counted.
     */
    String equip(List<String> ids, Map<EquipmentSlot, Optional<NpcItem>> items) throws IOException {
        int[] skipped = {0};
        List<NpcRepository.Draft> drafts = drafts(ids, (JsonObject document) -> {
            String type = document.get("type").getAsString();
            JsonObject equipment = document.has("equipment") && document.get("equipment").isJsonObject() ? document.getAsJsonObject("equipment") : new JsonObject();
            JsonObject before = equipment.deepCopy();
            boolean skip = false;
            for (Map.Entry<EquipmentSlot, Optional<NpcItem>> item : items.entrySet()) {
                if (!BodyRules.equipmentSlots(type).contains(item.getKey())) { skip = true; continue; }
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
        return "Set " + slots + " on " + ToolkitFiles.named(drafts) + skip + "; tx " + npcs.applyAll(drafts);
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
