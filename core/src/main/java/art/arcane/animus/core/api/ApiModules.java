package art.arcane.animus.core.api;

import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.Diagnostic;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.NpcModules;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcRepository;
import art.arcane.animus.core.storage.JsonDocuments;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Plugin definition modules: who registered each key, the save rule that runs their validators, and the hooks. Thread-safe;
 * each hook runs on the thread its caller is on.
 */
final class ApiModules implements NpcRepository.ModuleRules {
    /** One registered module and the plugin or Java module that owns it. */
    record Registered(String owner, NpcModules.Descriptor descriptor, NpcModules.Module module) {}

    /** Index of the NPCs carrying each ticking module, rebuilt when the definition snapshot changes. */
    private record Carriers(Map<String, NpcDefinition> source, Map<String, List<NpcDefinition>> byKey) {}

    private static final long REPORT_EVERY_NANOS = 60_000_000_000L;
    private final Map<String, Registered> byKey = new ConcurrentHashMap<>();
    private final Map<String, Long> reported = new ConcurrentHashMap<>();
    private final Predicate<String> temporary;
    private final Consumer<String> log;
    private volatile Carriers carriers = new Carriers(null, Map.of());

    /** @param temporary whether the NPC with this id is temporary */
    ApiModules(Predicate<String> temporary, Consumer<String> log) {
        this.temporary = Objects.requireNonNull(temporary);
        this.log = Objects.requireNonNull(log);
    }

    Outcome<Capabilities.Key> register(String owner, NpcModules.Descriptor descriptor, NpcModules.Module module) {
        Objects.requireNonNull(descriptor); Objects.requireNonNull(module);
        Capabilities.Key key = descriptor.key();
        if (NpcDefinition.ENGINE_NAMESPACES.contains(key.namespace()))
            return Outcome.rejected(Outcome.Status.INVALID, "module-namespace-reserved", "The namespace " + key.namespace() + " belongs to Animus; use your plugin's own");
        String description = descriptor.description();
        if (description.isBlank() || description.getBytes(StandardCharsets.UTF_8).length > 2048)
            return Outcome.rejected(Outcome.Status.INVALID, "module-description", "A module needs a description of 1 to 2048 bytes");
        if (descriptor.tickInterval() < 0 || descriptor.tickInterval() > NpcModules.MAXIMUM_TICK_INTERVAL)
            return Outcome.rejected(Outcome.Status.INVALID, "module-tick-interval", "The tick interval is 0 for none, or 1 to " + NpcModules.MAXIMUM_TICK_INTERVAL);
        Registered registered = new Registered(owner, descriptor, module);
        Registered existing = byKey.putIfAbsent(key.toString(), registered);
        if (existing != null) return Outcome.rejected(Outcome.Status.CONFLICT, "module-key-taken", key + " is registered by " + existing.owner());
        carriers = new Carriers(null, Map.of());
        return Outcome.ok(key);
    }

    /** Ends every registration of {@code owner}; returns the keys that ended. */
    List<String> release(String owner) {
        List<String> ended = new ArrayList<>();
        byKey.entrySet().removeIf((Map.Entry<String, Registered> entry) -> {
            if (!entry.getValue().owner().equals(owner)) return false;
            ended.add(entry.getKey());
            return true;
        });
        if (!ended.isEmpty()) carriers = new Carriers(null, Map.of());
        return ended;
    }


    /** The registered module validators, for every key whose data changed. */
    @Override public Set<String> keys() { return Set.copyOf(byKey.keySet()); }

    @Override public List<String> problems(Optional<JsonObject> previous, JsonObject next) {
        if (byKey.isEmpty()) return List.of();
        JsonObject after = modules(next), before = previous.map(ApiModules::modules).orElse(new JsonObject());
        List<String> problems = new ArrayList<>();
        for (Registered registered : byKey.values()) {
            String key = registered.descriptor().key().toString();
            JsonElement data = after.get(key);
            if (data == null || data.equals(before.get(key))) continue;
            if (!data.isJsonObject()) { problems.add("$.modules." + key + ": expected object"); continue; }
            try {
                for (Diagnostic diagnostic : registered.module().validate(JsonDocuments.write(data))) {
                    if (diagnostic.severity() != Diagnostic.Severity.ERROR) continue;
                    String field = diagnostic.field().equals("$") ? "" : diagnostic.field().startsWith("$.") ? diagnostic.field().substring(1) : "." + diagnostic.field();
                    problems.add("$.modules." + key + field + ": " + diagnostic.message());
                }
            } catch (RuntimeException | LinkageError failure) {
                problems.add("$.modules." + key + ": " + registered.owner() + " could not check this data; the server console says why");
                log.accept("The data check of " + key + " from " + registered.owner() + " failed: " + failure);
            }
        }
        return problems;
    }

    /** On the player's thread, after the click was accepted. */
    void clicked(NpcDefinition definition, UUID player, boolean attack) {
        for (Registered registered : byKey.values()) {
            Optional<JsonObject> data = definition.module(registered.descriptor().key().toString());
            if (data.isEmpty()) continue;
            NpcModules.Context context = context(definition, data.get());
            hook(registered, "click", () -> registered.module().clicked(context, player, attack));
        }
    }

    /** On the NPC's thread, once it became active. */
    void spawned(NpcDefinition definition) {
        for (Registered registered : byKey.values()) {
            Optional<JsonObject> data = definition.module(registered.descriptor().key().toString());
            if (data.isEmpty()) continue;
            NpcModules.Context context = context(definition, data.get());
            hook(registered, "spawn", () -> registered.module().spawned(context));
        }
    }

    /**
     * Once per server tick: for each ticking module whose interval is due, hands one tick of every active NPC carrying it
     * to {@code onOwner}, which runs it on that NPC's thread and answers false when it could not.
     */
    void tick(long tick, Map<String, NpcDefinition> snapshot, Predicate<UUID> active, BiPredicate<UUID, Runnable> onOwner) {
        if (byKey.isEmpty()) return;
        Map<String, List<NpcDefinition>> carrying = carriers(snapshot);
        for (Registered registered : byKey.values()) {
            int interval = registered.descriptor().tickInterval();
            if (interval == 0 || tick % interval != 0) continue;
            String key = registered.descriptor().key().toString();
            for (NpcDefinition definition : carrying.getOrDefault(key, List.of())) {
                if (!active.test(definition.uuid())) continue;
                Optional<JsonObject> data = definition.module(key);
                if (data.isEmpty()) continue;
                NpcModules.Context context = context(definition, data.get());
                onOwner.test(definition.uuid(), () -> hook(registered, "tick", () -> registered.module().ticked(context)));
            }
        }
    }

    private Map<String, List<NpcDefinition>> carriers(Map<String, NpcDefinition> snapshot) {
        Carriers known = carriers;
        if (known.source() == snapshot) return known.byKey();
        Map<String, List<NpcDefinition>> next = new java.util.HashMap<>();
        for (Registered registered : byKey.values()) if (registered.descriptor().tickInterval() > 0) next.put(registered.descriptor().key().toString(), new ArrayList<>());
        for (NpcDefinition definition : snapshot.values())
            for (Map.Entry<String, List<NpcDefinition>> key : next.entrySet())
                if (definition.module(key.getKey()).isPresent()) key.getValue().add(definition);
        carriers = new Carriers(snapshot, next);
        return next;
    }

    private NpcModules.Context context(NpcDefinition definition, JsonElement data) {
        return new NpcModules.Context(new NpcHandle(definition.uuid()), definition.id(), temporary.test(definition.id()), data.toString());
    }

    @FunctionalInterface private interface Hook { void run() throws Exception; }

    private void hook(Registered registered, String name, Hook hook) {
        try { hook.run(); }
        catch (Exception | LinkageError | AssertionError failure) {
            String key = registered.descriptor().key() + "/" + name;
            long now = System.nanoTime();
            Long last = reported.get(key);
            if (last != null && now - last < REPORT_EVERY_NANOS) return;
            reported.put(key, now);
            log.accept("The " + name + " hook of " + registered.descriptor().key() + " from " + registered.owner() + " failed: " + failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : ": " + failure.getMessage()));
        }
    }

    private static JsonObject modules(JsonObject definition) {
        JsonElement modules = definition.get("modules");
        return modules != null && modules.isJsonObject() ? modules.getAsJsonObject() : new JsonObject();
    }
}
