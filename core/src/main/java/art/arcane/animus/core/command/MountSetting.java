package art.arcane.animus.core.command;

import art.arcane.animus.core.mount.MountDeclaration;
import art.arcane.animus.core.mount.MountReference;
import art.arcane.animus.core.mount.MountRelations;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code /animus mount}: writes the NPC's {@code animus:mount} module, for another NPC or a real entity. File lane. */
final class MountSetting {
    private MountSetting() {}

    /** Whether {@code vehicle} names a real entity, by type or UUID, rather than another NPC. */
    static boolean real(String vehicle) { return vehicle.contains(":") || vehicle.matches("[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}"); }

    /** Why the NPC cannot ride {@code vehicle} on this server: a real entity where {@code noMobs} says NPCs ride none. */
    static java.util.Optional<String> refused(String vehicle, java.util.Optional<String> noMobs) { return real(vehicle) ? noMobs : java.util.Optional.empty(); }

    /** Seats the NPC on {@code vehicle}: an NPC id, an entity UUID or an entity type, the last within {@code range}. */
    static String set(NpcRepository repository, String id, String vehicle, String range) throws IOException {
        JsonObject module = new JsonObject();
        boolean real = real(vehicle);
        if (real) {
            module.addProperty("entity", vehicle);
            if (!range.isEmpty()) {
                try { module.addProperty("range", Double.parseDouble(range)); }
                catch (NumberFormatException unreadable) { throw new IOException("Range must be a number of blocks, not " + range); }
            }
        } else {
            if (!range.isEmpty()) throw new IOException("Only an entity type is searched within a range");
            refuseLoops(repository, id, vehicle);
            module.addProperty("enabled", true);
            module.addProperty("vehicle", vehicle);
        }
        NpcRepository.Draft draft = repository.draft(id);
        JsonObject document = draft.document();
        JsonObject modules = document.has("modules") && document.get("modules").isJsonObject() ? document.getAsJsonObject("modules") : new JsonObject();
        modules.add(MountDeclaration.MODULE, module);
        document.add("modules", modules);
        String described;
        try {
            described = real ? MountDeclaration.entity(document).map(MountDeclaration.EntityMount::describe)
                    .orElseThrow(() -> new IllegalArgumentException(vehicle + " is not an NPC id, an entity UUID or an entity type")) : vehicle;
            MountDeclaration.parse(document);
        } catch (IllegalArgumentException invalid) {
            String refusal = invalid.getMessage();
            throw new IOException(refusal.contains(".range: ") ? CommandGroup.typed(refusal, "modules." + MountDeclaration.MODULE + ".range", "range", range)
                    : CommandGroup.problem(refusal, "modules." + MountDeclaration.MODULE + ".entity"));
        }
        String transaction = repository.apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        return id + " rides " + described + "; tx " + transaction;
    }

    /** Takes the NPC off whatever it rides. */
    static String off(NpcRepository repository, String id) throws IOException {
        NpcRepository.Draft draft = repository.draft(id);
        JsonObject document = draft.document();
        JsonObject modules = document.has("modules") && document.get("modules").isJsonObject() ? document.getAsJsonObject("modules") : null;
        if (modules == null || !modules.has(MountDeclaration.MODULE)) return id + " rides nothing";
        modules.remove(MountDeclaration.MODULE);
        if (modules.isEmpty()) document.remove("modules");
        String transaction = repository.apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
        return id + " dismounted; tx " + transaction;
    }

    /** An NPC vehicle must exist, and the pair must not form a loop or overload a chain. */
    private static void refuseLoops(NpcRepository repository, String rider, String vehicle) throws IOException {
        if (repository.stored().stream().noneMatch((NpcDefinition definition) -> definition.id().equals(vehicle)))
            throw new IOException("No NPC named " + vehicle + "; name a real entity by UUID or by type, such as minecraft:horse");
        Map<String, UUID> known = new HashMap<>();
        List<MountRelations.Node> nodes = new ArrayList<>();
        for (NpcDefinition definition : repository.stored()) {
            known.put(definition.id(), definition.uuid());
            MountReference reference;
            if (definition.id().equals(rider)) reference = new MountReference("$.modules." + MountDeclaration.MODULE + ".vehicle", vehicle, null, MountReference.Status.DECLARED, vehicle);
            else {
                try { reference = MountDeclaration.parse(definition.json()).orElse(null); }
                catch (IllegalArgumentException invalid) { reference = null; }
            }
            nodes.add(new MountRelations.Node(definition.id(), definition.uuid(), reference));
        }
        for (MountRelations.Diagnostic problem : MountRelations.resolve(nodes, known::get).diagnostics())
            if (problem.id().equals(rider) && problem.code().blocksActivation()) throw new IOException(rider + " cannot ride " + vehicle + ": " + problem.message());
    }
}
