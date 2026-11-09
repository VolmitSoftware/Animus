package art.arcane.animus.core.runtime;

import art.arcane.animus.core.combat.HealthSettings;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Permadeath in the definition: a death marks {@code modules["animus:health"].dead}, which keeps the NPC unpresented
 * across restarts, and a revival clears it. Each is one ordinary transaction with a receipt. Runs on the file lane.
 */
public final class HealthRecords {
    public record Recorded(String id, String transaction) {
        /** The log line for a permadeath, a normal game event, so platforms log it at INFO. */
        public String deathNotice() { return "NPC " + id + " died for good; /animus npc revive " + id + " brings it back; tx " + transaction; }
    }

    private HealthRecords() {}

    /** Marks the NPC with this UUID dead; empty when no definition has it or it is already marked. */
    public static Optional<Recorded> markDead(NpcRepository repository, UUID npc) throws IOException {
        for (NpcDefinition definition : repository.snapshot().values()) {
            if (!definition.uuid().equals(npc)) continue;
            if (definition.dead()) return Optional.empty();
            return Optional.of(new Recorded(definition.id(), write(repository, definition.id(), true)));
        }
        return Optional.empty();
    }

    /** Clears the mark and spawns the definition; empty when it was not marked dead. */
    public static Optional<Recorded> revive(NpcRepository repository, String id) throws IOException {
        NpcDefinition definition = repository.find(id).orElseThrow(() -> new IllegalArgumentException("Unknown NPC: " + id));
        if (!definition.dead()) return Optional.empty();
        return Optional.of(new Recorded(definition.id(), write(repository, definition.id(), false)));
    }

    private static String write(NpcRepository repository, String id, boolean dead) throws IOException {
        NpcRepository.Draft draft = repository.draft(id);
        JsonObject document = draft.document();
        JsonElement present = document.get("modules");
        if (present != null && !present.isJsonObject()) throw new IOException(id + ": modules is not an object");
        JsonObject modules = present == null ? new JsonObject() : present.getAsJsonObject();
        JsonElement existing = modules.get(HealthSettings.MODULE);
        if (existing != null && !existing.isJsonObject()) throw new IOException(id + ": " + HealthSettings.MODULE + " is not an object");
        // A trait group may hold the module; the NPC's own file carries only the mark, which merges into it.
        JsonObject health = existing == null ? new JsonObject() : existing.getAsJsonObject();
        if (dead) health.addProperty(HealthSettings.DEAD, true);
        else {
            health.remove(HealthSettings.DEAD);
            document.addProperty("spawned", true);
        }
        if (health.isEmpty()) modules.remove(HealthSettings.MODULE);
        else modules.add(HealthSettings.MODULE, health);
        if (modules.isEmpty()) document.remove("modules");
        else document.add("modules", modules);
        return repository.apply(new NpcRepository.Draft(draft.path(), draft.expectedHash(), draft.expectedRevision(), document));
    }
}
