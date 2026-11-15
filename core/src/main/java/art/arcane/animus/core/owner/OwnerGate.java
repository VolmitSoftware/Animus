package art.arcane.animus.core.owner;

import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.core.editor.DocumentKind;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The same rule as {@link OwnerWrites}, kept while behaviors run: each action or condition of a run on an NPC owned by a
 * player is refused unless its owner holds what that call needs now. A behavior an admin changed after it was bound, or
 * a node the owner lost, therefore never lends the NPC more than its owner has. Never blocks: an offline owner's answer
 * is the last one seen, and none yet refuses. The shops and menus a call opens were checked when the NPC was saved; they
 * are not read here, so a call never waits on the store.
 */
public final class OwnerGate {
    public static final String REFUSED = "owner-lacks-permission";
    private final Supplier<Map<String, NpcDefinition>> definitions;
    private final Supplier<OwnerPermissions> permissions;
    private volatile Map<String, NpcDefinition> indexed = Map.of();
    private volatile Map<UUID, NpcDefinition> byUuid = Map.of();

    public OwnerGate(NpcRepository npcs, Supplier<OwnerPermissions> permissions) { this(npcs::snapshot, permissions); }

    OwnerGate(Supplier<Map<String, NpcDefinition>> definitions, Supplier<OwnerPermissions> permissions) {
        this.definitions = Objects.requireNonNull(definitions); this.permissions = Objects.requireNonNull(permissions);
    }

    /** Why the call may not run, or empty. {@code privileged} is the registry's mark for the capability. Any thread. */
    public Optional<String> refused(NpcHandle npc, String capability, String argumentsJson, boolean privileged) {
        Optional<NpcDefinition> definition = definition(npc.uuid());
        if (definition.isEmpty() || definition.get().owner().isEmpty()) return Optional.empty();
        UUID owner = definition.get().owner().get();
        JsonObject arguments;
        try { arguments = JsonParser.parseString(argumentsJson) instanceof JsonObject object ? object : new JsonObject(); }
        catch (JsonParseException unreadable) { arguments = new JsonObject(); }
        List<OwnerNeed> needs = OwnerNeeds.call(capability, arguments, privileged, "action " + capability, definition.get().owner(), new Sources());
        for (OwnerNeed need : needs)
            if (permissions.get().cached(owner, need.node()) != OwnerPermissions.Answer.YES) return Optional.of(need.refusal(definition.get().id()));
        return Optional.empty();
    }

    private Optional<NpcDefinition> definition(UUID uuid) {
        Map<String, NpcDefinition> current = definitions.get();
        if (current != indexed) {
            Map<UUID, NpcDefinition> next = new HashMap<>();
            for (NpcDefinition definition : current.values()) next.put(definition.uuid(), definition);
            byUuid = Map.copyOf(next);
            indexed = current;
        }
        return Optional.ofNullable(byUuid.get(uuid));
    }

    /** Only other NPCs, from memory: a single call reads no behaviors, shops or menus. */
    private final class Sources implements OwnerNeeds.Sources {
        @Override public Optional<JsonElement> behavior(String id) { return Optional.empty(); }
        @Override public Optional<JsonElement> document(DocumentKind kind, String id) { return Optional.empty(); }
        @Override public Optional<NpcDefinition> npc(UUID uuid) { return definition(uuid); }
        @Override public Optional<NpcDefinition> npc(String id) { return Optional.ofNullable(definitions.get().get(id)); }
        @Override public Set<String> privileged() { return Set.of(); }
    }
}
