package art.arcane.animus.core.runtime;

import art.arcane.animus.core.command.CombatControl;
import art.arcane.animus.core.metrics.AnimusMetrics;
import art.arcane.animus.core.metrics.Subsystem;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.combat.CombatEngine;
import art.arcane.animus.core.combat.CombatLoadout;
import art.arcane.animus.core.combat.CombatOperations;
import art.arcane.animus.core.combat.CombatSettings;
import art.arcane.animus.core.combat.HealthSettings;
import art.arcane.animus.core.combat.CombatWorld;
import art.arcane.animus.core.combat.ProtectionAdapter;
import art.arcane.animus.core.combat.ProtectionGate;
import art.arcane.animus.core.combat.TargetKind;
import art.arcane.animus.core.combat.TargetRef;
import art.arcane.animus.core.config.CombatBudgets;
import art.arcane.animus.core.config.PluginSettings;
import art.arcane.animus.core.extension.CapabilityRefusal;
import art.arcane.animus.core.extension.MutationLane;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.text.ChatLine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The definition-driven combat module of a single-owner runtime: finds NPCs whose definitions enable combat, keeps them
 * registered with the engine, and answers the {@code combat <npc-id> ...} commands and behavior combat operations.
 * Owner thread only. The platform supplies the combat world, protection and its own real-entity event hooks.
 * <p>
 * The {@code [combat]} settings apply live: a hotload leaves them pending and the next {@link #tick} swaps them in.
 * Turning combat off winds the engine down ({@link CombatEngine#stop}) and closes it once its helpers are gone; turning
 * it on starts an engine as startup does.
 */
public final class CombatModule implements AutoCloseable {
    private record Candidate(String id, CombatSettings settings, CombatLoadout loadout) {}
    private static final int DEFINITIONS_PER_TICK = 64;
    private static final int ACTIVATIONS_PER_TICK = 16;
    private final AnimusRuntime runtime;
    private static final String DISABLED = "disabled in config.toml";
    private final PersistedNpcService<?, ?> living;
    private final CombatWorld world;
    private final ProtectionAdapter adapter;
    private CombatEngine engine;
    private ProtectionGate protection;
    /** Why this platform cannot run the module at all, or null; config cannot change it. */
    private final String unavailable;
    private boolean enabled;
    /** Budgets a hotload delivered, taken by the next tick; written on the file lane. */
    private final AtomicReference<CombatBudgets> pending = new AtomicReference<>();
    private final AutoCloseable settingsListener;
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
        this.world = world; this.adapter = Objects.requireNonNull(adapter, "adapter");
        String reason = unavailable;
        if (reason == null) { Objects.requireNonNull(living, "living"); Objects.requireNonNull(world, "world"); }
        if (reason == null && !living.available()) reason = "unavailable because the NPC runtime is: " + living.status();
        this.unavailable = reason;
        protection = new ProtectionGate(adapter, warnings);
        CombatBudgets budgets = runtime.combatBudgets();
        enabled = budgets.enabled();
        if (reason == null && enabled) start(budgets);
        settingsListener = reason == null ? runtime.onSettings((PluginSettings next) -> pending.set(next.combat())) : null;
    }

    private void start(CombatBudgets budgets) {
        engine = new CombatEngine(budgets, world, adapter, new SplittableRandom(), warnings);
        living.onLanding((UUID npc, Double damage) -> { if (engine != null) engine.environment(npc, HealthSettings.DamageSource.FALL, damage); });
        protection = engine.protection();
        observed = null; pass = null; refused = 0;
        candidates.clear(); nextCandidates = new HashMap<>();
    }

    /** Whether this platform can run the module here; config may still turn combat off and on. */
    public boolean supported() { return unavailable == null; }
    /** Whether combat runs: the platform supports it and config enables it. False while a disabled engine still drains. */
    public boolean active() { return engine != null && enabled; }
    /** Whether an engine exists, running or draining its helpers after combat was turned off; platform hooks feed it then. */
    public boolean running() { return engine != null; }
    /** The engine; only call while {@link #running()}. */
    public CombatEngine engine() { return Objects.requireNonNull(engine, "Combat module is not running"); }
    public ProtectionGate protection() { return protection; }
    private String reason() { return unavailable != null ? unavailable : DISABLED; }

    public String status() {
        return !active() ? " | module " + reason() + (engine != null ? " (stopping, helpers=" + engine.helperCount() + ")" : "")
                : " | module npcs=" + engine.size() + " | " + engine.capacity() + (refused > 0 ? " | not-registered=" + refused : "") + " | helpers=" + engine.helperCount()
                + " | protection-denials=" + protection.denials() + " | protection-failures=" + protection.failures()
                + " | dropped-events=" + engine.droppedEvents() + " | region-unavailable=" + engine.regionUnavailable() + " | deleted-on-death=" + deletedOnDeath.get();
    }

    /** {@code combat <operation> <npc>}: the module's own operations on one NPC, answered as a sentence naming it. */
    public String command(String id, CombatControl.Operation operation, Optional<UUID> target) {
        if (!active()) throw new IllegalStateException("Combat module " + reason());
        NpcDefinition definition = runtime.npcs().find(id).orElseThrow(() -> new IllegalArgumentException("Unknown NPC: " + id));
        UUID npc = definition.uuid();
        if (operation == CombatControl.Operation.STATUS)
            return engine.status(npc).map((CombatEngine.Status state) -> status(id, state, this::who))
                    .orElse(id + " is not a combat NPC (combat disabled, review not accepted, or not active)");
        CombatOperations.Result result = switch (operation) {
            case STATUS -> throw new IllegalStateException("answered above");
            case ENGAGE -> engine.engage(npc);
            case DISENGAGE -> engine.disengage(npc);
            case RELEASE -> engine.release(npc);
            case TARGET -> engine.target(npc, participant(target.orElseThrow(() -> new IllegalArgumentException("Name a target UUID"))));
            case GUARD -> engine.guard(npc, target.map(this::participant));
            case REVIVE -> engine.revive(npc);
        };
        return sentence(id, operation, target.map(this::who), result);
    }

    /** {@code guard is idle, no target}, then whether it is disengaged and its ammunition. */
    static String status(String id, CombatEngine.Status state, Function<UUID, String> who) {
        return id + " is " + state.state().name().toLowerCase(Locale.ROOT).replace('_', ' ') + ", "
                + state.target().map((TargetRef ref) -> "targeting " + who.apply(ref.id())).orElse("no target")
                + (state.disengaged() ? ", disengaged" : "") + (state.ammunition() >= 0 ? ", " + state.ammunition() + " ammunition" : "");
    }

    /** What an operation did, naming the NPC and the one it targets or guards; a refusal gives the engine's reason. */
    static String sentence(String id, CombatControl.Operation operation, Optional<String> other, CombatOperations.Result result) {
        if (result.status() != CombatOperations.Status.APPLIED) return id + ": " + result.detail();
        return switch (operation) {
            case STATUS -> id + " " + result.detail();
            case ENGAGE -> id + " can fight";
            case DISENGAGE -> id + " stopped fighting";
            case RELEASE -> id + " released its target";
            case TARGET -> id + " targets " + other.orElse("nothing");
            case GUARD -> other.map((String charge) -> id + " is guarding " + charge).orElse(id + " stopped guarding");
            case REVIVE -> "Revived " + id;
        };
    }

    /** An NPC by its id, anything else by the first characters of its UUID. */
    private String who(UUID participant) {
        return runtime.npcs().all().stream().filter((NpcDefinition npc) -> npc.uuid().equals(participant)).map(NpcDefinition::id).findFirst().orElseGet(() -> shortWho(participant));
    }

    static String shortWho(UUID participant) { return ChatLine.shortId(participant.toString()); }

    /** Whether the NPC guards this player or NPC now. */
    public boolean guards(UUID npc, UUID charge) {
        return active() && engine.guarded(npc).map((TargetRef ref) -> ref.id().equals(charge)).orElse(false);
    }

    /** Behavior operations. A refusal carries the reason back to the behavior. */
    public String operate(UUID npc, BehaviorPlatform.CombatOperation operation, Optional<UUID> target) {
        if (!active()) throw new CapabilityRefusal(Outcome.Status.UNAVAILABLE, "combat-unavailable", "Combat module " + reason());
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
    /** Whether the NPC's running combat lets hostile mobs target it. */
    public static boolean targetableByMobs(NpcDefinition definition) {
        try { return definition != null && CombatSettings.active(definition).map(CombatSettings::targetableByMobs).orElse(false); }
        catch (IllegalArgumentException invalid) { return false; }
    }
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
        CombatBudgets next = pending.getAndSet(null);
        if (next != null) apply(next);
        if (engine == null) return;
        AnimusMetrics metrics = AnimusMetrics.current();
        long started = metrics.start();
        try {
            if (!enabled) {
                engine.tick();
                for (UUID npc : engine.drainDeletions()) deleteDead(npc);
                for (UUID npc : engine.drainPermadeaths()) markDead(npc);
                if (engine.drained()) {
                    engine.close();
                    engine = null;
                    candidates.clear();
                    warnings.accept("Combat stopped: disabled in config.toml");
                }
                return;
            }
            reconcile();
            engine.tick();
            for (UUID npc : engine.drainDeletions()) deleteDead(npc);
            for (UUID npc : engine.drainPermadeaths()) markDead(npc);
        } finally { metrics.stop(Subsystem.COMBAT, started); }
    }
    /** Combat NPCs waiting for a slot; owner thread. */
    public int waiting() { return engine == null ? 0 : engine.waitingCount(); }
    /** 0-100: combat slots in use; 100 while any NPC waits for one. Owner thread. */
    public int load() {
        if (engine == null) return 0;
        if (engine.waitingCount() > 0) return 100;
        return engine.slotLimit() <= 0 ? 0 : Math.min(100, engine.activeCount() * 100 / engine.slotLimit());
    }

    /** Owner thread: budgets swap in, and combat starts, resumes or winds down when {@code enabled} changed. */
    private void apply(CombatBudgets next) {
        boolean was = enabled;
        enabled = next.enabled();
        if (engine != null) engine.reconfigure(next);
        if (enabled && !was) {
            if (engine == null) start(next);
            else engine.resume();
            warnings.accept("Combat started: enabled in config.toml");
        } else if (!enabled && was && engine != null) {
            engine.stop();
            warnings.accept("Combat stopping: disabled in config.toml; helpers in flight land or expire first");
        }
    }

    private Executor files() {
        if (files == null) files = runtime.fileExecutor((Runnable task, MutationLane.EngineTaskEvent event, Throwable failure) -> {
            if (event == MutationLane.EngineTaskEvent.FAILED) warnings.accept("Combat could not record a death: " + failure);
        });
        return files;
    }

    /** Marks a permadeath NPC dead in its definition on the file lane, so it stays dead across restarts until revived. */
    private void markDead(UUID npc) {
        try {
            files().execute(() -> {
                try {
                    HealthRecords.markDead(runtime.npcs(), npc).ifPresent((HealthRecords.Recorded recorded) ->
                            warnings.accept("NPC " + recorded.id() + " died for good; /animus npc revive " + recorded.id() + " brings it back; tx " + recorded.transaction()));
                } catch (java.io.IOException failure) {
                    warnings.accept("Combat could not record the permadeath of " + npc + ": " + failure.getMessage());
                }
            });
        } catch (IllegalStateException | RejectedExecutionException stopping) {
            warnings.accept("Combat could not record the permadeath of " + npc + " while Animus stops: " + stopping.getMessage());
        }
    }

    /** Deletes the definition on the file lane as one transaction, and names the transaction that undoes it. */
    private void deleteDead(UUID npc) {
        try {
            files().execute(() -> {
                try {
                    CombatDeletions.delete(runtime.npcs(), npc).ifPresent((CombatDeletions.Deleted deleted) -> {
                        deletedOnDeath.incrementAndGet();
                        warnings.accept("Combat NPC " + deleted.id() + " died and was deleted (animus:health afterDeath delete); tx " + deleted.transaction());
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
        if (settingsListener != null) {
            try { settingsListener.close(); } catch (Exception ignored) { }
        }
        if (engine != null) {
            engine.close();
            for (UUID npc : engine.drainPermadeaths()) markDead(npc);
        }
        candidates.clear();
    }
}
