package art.arcane.animus.core.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.combat.CombatEngine;
import art.arcane.animus.core.combat.CombatLoadout;
import art.arcane.animus.core.combat.CombatOperations;
import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.CombatWorld;
import art.arcane.animus.core.combat.ProtectionAdapter;
import art.arcane.animus.core.combat.ProtectionGate;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.extension.CapabilityRefusal;
import art.arcane.animus.core.extension.MutationLane;
import art.arcane.animus.core.npc.NpcDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The definition-driven combat module of a single-owner runtime: finds NPCs whose definitions enable combat, keeps them
 * registered with the engine, and answers the {@code combat <npc-id> ...} commands and behavior combat operations.
 * Owner thread only. The platform supplies the combat world, protection and its own real-entity event hooks.
 */
public final class CombatModule implements AutoCloseable {
    public static final String MODULE_USAGE = "combat status | combat <npc-id> status|engage|disengage|release | combat <npc-id> target <uuid> | combat <npc-id> guard <uuid>|stop";
    private record Candidate(String id, CombatSettings settings, CombatLoadout loadout) {}
    private static final int DEFINITIONS_PER_TICK = 64;
    private static final int ACTIVATIONS_PER_TICK = 16;
    private final AnimusRuntime runtime;
    private final PersistedNpcService<?, ?> living;
    private final CombatEngine engine;
    private final ProtectionGate protection;
    private final String unavailable;
    private final Predicate<UUID> player;
    private final Consumer<String> warnings;
    private final AtomicLong deletedOnDeath = new AtomicLong();
    private Executor files;
    private final Map<UUID, Candidate> candidates = new HashMap<>();
    private Map<UUID, Candidate> nextCandidates = new HashMap<>();
    private Map<String, NpcDefinition> observed;
    private Iterator<NpcDefinition> pass;
    /** Combat NPCs left out because {@link CombatEngine#MAXIMUM_REGISTERED} are registered; logged once per shortage. */
    private int refused;

    /**
     * @param unavailable why the module cannot run here, or null to start the engine when config and runtime allow it; with a
     *                    reason, {@code living} and {@code world} may be null
     * @param player whether a UUID names an online player, so targets are told apart from mobs
     */
    public CombatModule(AnimusRuntime runtime, PersistedNpcService<?, ?> living, CombatWorld world, ProtectionAdapter adapter,
                        String unavailable, Consumer<String> warnings, Predicate<UUID> player) {
        this.runtime = Objects.requireNonNull(runtime); this.living = living; this.player = Objects.requireNonNull(player);
        this.warnings = Objects.requireNonNull(warnings);
        String reason = unavailable;
        if (reason == null) { Objects.requireNonNull(living, "living"); Objects.requireNonNull(world, "world"); }
        if (reason == null && !runtime.combatBudgets().enabled()) reason = "disabled in config.toml";
        else if (reason == null && !living.available()) reason = "unavailable because the NPC runtime is: " + living.status();
        this.unavailable = reason;
        engine = reason == null ? new CombatEngine(runtime.combatBudgets(), world, adapter, new SplittableRandom(), warnings) : null;
        protection = engine != null ? engine.protection() : new ProtectionGate(adapter, warnings);
    }

    public boolean active() { return engine != null; }
    /** The running engine; only call while {@link #active()}. */
    public CombatEngine engine() { return Objects.requireNonNull(engine, "Combat module is not running"); }
    public ProtectionGate protection() { return protection; }

    public String status() {
        return engine == null ? " | module " + unavailable
                : " | module npcs=" + engine.size() + " | " + engine.capacity() + (refused > 0 ? " | not-registered=" + refused : "") + " | helpers=" + engine.helperCount()
                + " | protection-denials=" + protection.denials() + " | protection-failures=" + protection.failures()
                + " | dropped-events=" + engine.droppedEvents() + " | region-unavailable=" + engine.regionUnavailable() + " | deleted-on-death=" + deletedOnDeath.get();
    }

    /** {@code combat <npc-id> ...}: the module's own subcommands. */
    public String execute(String[] args) {
        if (engine == null) throw new IllegalStateException("Combat module " + unavailable);
        if (args.length < 3) throw new IllegalArgumentException(MODULE_USAGE);
        NpcDefinition definition = runtime.npcs().find(args[1]).orElseThrow(() -> new IllegalArgumentException("Unknown NPC: " + args[1]));
        UUID npc = definition.uuid();
        CombatOperations.Result result = switch (args[2]) {
            case "status" -> {
                if (args.length != 3) throw new IllegalArgumentException(MODULE_USAGE);
                yield engine.status(npc).map((CombatEngine.Status state) -> new CombatOperations.Result(CombatOperations.Status.APPLIED,
                        state.state() + " target=" + state.target().map((TargetRef ref) -> ref.kind() + ":" + ref.id()).orElse("none") + (state.disengaged() ? " disengaged" : "")
                                + (state.ammunition() >= 0 ? " ammunition=" + state.ammunition() : "")))
                        .orElse(new CombatOperations.Result(CombatOperations.Status.NOT_COMBATANT, "not a combat NPC (combat disabled, review not accepted, or not active)"));
            }
            case "engage" -> engine.engage(npc);
            case "disengage" -> engine.disengage(npc);
            case "release" -> engine.release(npc);
            case "target" -> {
                if (args.length != 4) throw new IllegalArgumentException(MODULE_USAGE);
                yield engine.target(npc, participant(uuid(args[3])));
            }
            case "guard" -> {
                if (args.length != 4) throw new IllegalArgumentException(MODULE_USAGE);
                yield engine.guard(npc, args[3].equals("stop") ? Optional.empty() : Optional.of(participant(uuid(args[3]))));
            }
            default -> throw new IllegalArgumentException(MODULE_USAGE);
        };
        return "Combat " + args[1] + ": " + result.status() + " " + result.detail();
    }

    /** Whether the NPC guards this player or NPC now. */
    public boolean guards(UUID npc, UUID charge) {
        return engine != null && engine.guarded(npc).map((TargetRef ref) -> ref.id().equals(charge)).orElse(false);
    }

    /** Behavior operations. A refusal carries the reason back to the behavior. */
    public String operate(UUID npc, BehaviorPlatform.CombatOperation operation, Optional<UUID> target) {
        if (engine == null) throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "combat-unavailable", "Combat module " + unavailable);
        CombatOperations.Result result = switch (operation) {
            case ENABLE -> engine.engage(npc);
            case DISABLE -> engine.disengage(npc);
            case RELEASE -> engine.release(npc);
            case TARGET -> engine.target(npc, participant(target.orElseThrow()));
            case GUARD -> engine.guard(npc, target.map(this::participant));
        };
        return switch (result.status()) {
            case APPLIED -> result.detail();
            case INVALID_TARGET -> throw new CapabilityRefusal(Outcome.Status.INVALID, "combat-target", result.detail());
            case NOT_COMBATANT -> throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "combat-not-enabled", result.detail());
            case DEAD -> throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "combat-dead", result.detail());
            case UNAVAILABLE -> throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "combat-unavailable", result.detail());
        };
    }

    /** A combat NPC, an online player or a loaded mob; anything else is refused by the engine as not available. */
    public TargetRef participant(UUID id) {
        if (engine != null && engine.registered(id)) return new TargetRef(TargetKind.NPC, id);
        return new TargetRef(player.test(id) ? TargetKind.PLAYER : TargetKind.MOB, id);
    }
    /**
     * Whether the manual administrator adapter may act for this NPC. A definition without the combat module may; one
     * with it needs literal {@code "enabled": true} and {@code "reviewAccepted": true}.
     */
    public static boolean manualAllowed(NpcDefinition definition) {
        if (definition == null) return false;
        JsonObject document = definition.json();
        if (!document.has("modules") || !document.getAsJsonObject("modules").has(CombatSettings.MODULE)) return true;
        JsonElement value = document.getAsJsonObject("modules").get(CombatSettings.MODULE);
        return value.isJsonObject() && literal(value.getAsJsonObject(), "enabled") && literal(value.getAsJsonObject(), "reviewAccepted");
    }
    private static boolean literal(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }
    public static UUID uuid(String text) {
        try { return UUID.fromString(text); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Expected an entity or NPC UUID: " + text); }
    }

    public void tick() {
        if (engine == null) return;
        reconcile();
        engine.tick();
        for (UUID npc : engine.drainDeletions()) deleteDead(npc);
    }

    /** Deletes the definition on the file lane as one transaction, and names the transaction that undoes it. */
    private void deleteDead(UUID npc) {
        try {
            if (files == null) files = runtime.fileExecutor((Runnable task, MutationLane.EngineTaskEvent event, Throwable failure) -> {
                if (event == MutationLane.EngineTaskEvent.FAILED) warnings.accept("Combat could not delete a dead NPC: " + failure);
            });
            files.execute(() -> {
                try {
                    CombatDeletions.delete(runtime.npcs(), npc).ifPresent((CombatDeletions.Deleted deleted) -> {
                        deletedOnDeath.incrementAndGet();
                        warnings.accept("Combat NPC " + deleted.id() + " died and was deleted (respawn.deleteOnDeath); undo with /animus rollback " + deleted.transaction());
                    });
                } catch (java.io.IOException failure) {
                    warnings.accept("Combat could not delete dead NPC " + npc + ": " + failure.getMessage());
                }
            });
        } catch (IllegalStateException | RejectedExecutionException stopping) {
            warnings.accept("Combat could not delete dead NPC " + npc + " while Animus stops: " + stopping.getMessage());
        }
    }

    /**
     * Bounded discovery of NPCs whose definitions enable combat. A full pass runs only after a definition
     * publication; activation and despawn are checked every tick for the few NPCs involved.
     */
    private void reconcile() {
        Map<String, NpcDefinition> snapshot = runtime.npcs().snapshot();
        if (snapshot != observed) {
            observed = snapshot;
            pass = snapshot.values().iterator();
            nextCandidates = new HashMap<>();
        }
        if (pass != null) {
            for (int examined = 0; examined < DEFINITIONS_PER_TICK && pass.hasNext(); examined++) {
                NpcDefinition definition = pass.next();
                if (!definition.spawned()) continue;
                CombatSettings.active(definition).ifPresent((CombatSettings settings) ->
                        nextCandidates.put(definition.uuid(), new Candidate(definition.id(), settings, CombatLoadout.of(definition))));
            }
            if (!pass.hasNext()) {
                pass = null;
                candidates.clear();
                candidates.putAll(nextCandidates);
                for (UUID npc : engine.npcs()) if (!candidates.containsKey(npc)) engine.unregister(npc);
            }
        }
        List<UUID> retired = new ArrayList<>();
        for (Map.Entry<UUID, Candidate> entry : candidates.entrySet()) {
            if (living.combatant(entry.getKey()).isEmpty()) retired.add(entry.getKey());
        }
        retired.forEach(engine::unregister);
        int activations = 0, left = 0;
        for (Map.Entry<UUID, Candidate> entry : candidates.entrySet()) {
            if (activations >= ACTIVATIONS_PER_TICK) break;
            if (retired.contains(entry.getKey())) continue;
            if (engine.status(entry.getKey()).isEmpty()) activations++;
            if (!engine.register(entry.getKey(), entry.getValue().id(), entry.getValue().settings(), entry.getValue().loadout())) {
                left = candidates.size() - engine.size();
                break;
            }
        }
        if (activations == 0 || left > 0) {
            if (left > 0 && refused == 0) warnings.accept("Combat registers at most " + CombatEngine.MAXIMUM_REGISTERED + " NPCs; " + left + " combat NPCs are not combatants");
            if (left == 0 && refused > 0) warnings.accept("Combat registers every combat NPC again");
            refused = left;
        }
    }

    /** Removes every helper projectile and ends all combat. */
    @Override public void close() {
        if (engine != null) engine.close();
        candidates.clear();
    }
}
