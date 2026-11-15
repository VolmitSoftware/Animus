package art.arcane.animus.core.api;

import art.arcane.animus.api.Audience;
import art.arcane.animus.api.Capabilities;
import art.arcane.animus.api.DefinitionService;
import art.arcane.animus.api.DefinitionSnapshot;
import art.arcane.animus.api.Flag;
import art.arcane.animus.api.ItemSpec;
import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.NpcLocation;
import art.arcane.animus.api.NpcModules;
import art.arcane.animus.api.NpcService;
import art.arcane.animus.api.NpcSpec;
import art.arcane.animus.api.NpcState;
import art.arcane.animus.api.Outcome;
import art.arcane.animus.api.SkinSource;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** One owner's {@link NpcService}. After the owner is released every call answers UNAVAILABLE. */
final class ScopedNpcs<P, I> implements NpcService {
    private final NpcApi<P, I> api;
    private final String owner;
    private final DefinitionService definitions;
    private final BooleanSupplier available;
    private volatile boolean released;

    ScopedNpcs(NpcApi<P, I> api, String owner, BooleanSupplier available) {
        this.api = api;
        this.owner = owner;
        this.available = available;
        definitions = api.parts.runtime().definitionGateway().scoped(this::open);
    }

    private boolean open() { return !released && !api.closed() && available.getAsBoolean(); }

    void release() { released = true; }

    private <T> CompletionStage<Outcome<T>> live(Supplier<CompletionStage<Outcome<T>>> call) {
        if (!open()) return api.done(Outcome.rejected(Outcome.Status.UNAVAILABLE, "owner-stopped", owner + " is disabled, suspended or Animus is stopping"));
        return call.get();
    }

    @Override public String owner() { return owner; }
    @Override public DefinitionService definitions() { return definitions; }
    @Override public NpcModules.Registrar modules() {
        return (NpcModules.Descriptor descriptor, NpcModules.Module module) -> !open()
                ? Outcome.<Capabilities.Key>rejected(Outcome.Status.UNAVAILABLE, "owner-stopped", owner + " is disabled, suspended or Animus is stopping")
                : api.modules.register(owner, Objects.requireNonNull(descriptor), Objects.requireNonNull(module));
    }

    @Override public CompletionStage<Outcome<DefinitionSnapshot>> create(NpcSpec spec) { Objects.requireNonNull(spec); return live(() -> api.lifecycle.create(owner, spec, false)); }
    @Override public CompletionStage<Outcome<DefinitionSnapshot>> createTemporary(NpcSpec spec) { Objects.requireNonNull(spec); return live(() -> api.lifecycle.create(owner, spec, true)); }
    @Override public CompletionStage<Outcome<DefinitionSnapshot>> edit(NpcHandle npc, NpcSpec changes) {
        Objects.requireNonNull(npc); Objects.requireNonNull(changes);
        return live(() -> api.lifecycle.edit(owner, npc, changes));
    }
    @Override public CompletionStage<Outcome<DefinitionSnapshot>> updateModule(NpcHandle npc, String key, UnaryOperator<Optional<String>> change) {
        Objects.requireNonNull(npc); Objects.requireNonNull(key); Objects.requireNonNull(change);
        return live(() -> api.lifecycle.updateModule(owner, npc, key, change));
    }
    @Override public CompletionStage<Outcome<NpcHandle>> remove(NpcHandle npc) { Objects.requireNonNull(npc); return live(() -> api.lifecycle.remove(owner, npc)); }
    @Override public CompletionStage<Outcome<NpcState>> state(NpcHandle npc) { Objects.requireNonNull(npc); return live(() -> api.lifecycle.state(npc)); }

    @Override public CompletionStage<Outcome<NpcHandle>> teleport(NpcHandle npc, NpcLocation to) {
        Objects.requireNonNull(npc); Objects.requireNonNull(to);
        return live(() -> api.control.teleport(npc, to));
    }
    @Override public CompletionStage<Outcome<NpcLocation>> navigate(NpcHandle npc, NpcLocation to, double speed) {
        Objects.requireNonNull(npc); Objects.requireNonNull(to);
        return live(() -> api.control.navigate(npc, to, speed));
    }
    @Override public CompletionStage<Outcome<NpcHandle>> follow(NpcHandle npc, UUID entity, double distance) {
        Objects.requireNonNull(npc); Objects.requireNonNull(entity);
        return live(() -> api.follows.start(owner, npc, entity, distance));
    }
    @Override public CompletionStage<Outcome<NpcHandle>> stop(NpcHandle npc) { Objects.requireNonNull(npc); return live(() -> api.control.stop(npc)); }
    @Override public CompletionStage<Outcome<NpcHandle>> lookAt(NpcHandle npc, NpcLocation at) {
        Objects.requireNonNull(npc); Objects.requireNonNull(at);
        return live(() -> api.control.lookAt(npc, at));
    }
    @Override public CompletionStage<Outcome<NpcHandle>> lookAt(NpcHandle npc, UUID entity) {
        Objects.requireNonNull(npc); Objects.requireNonNull(entity);
        return live(() -> api.control.lookAt(npc, entity));
    }
    @Override public CompletionStage<Outcome<Integer>> speak(NpcHandle npc, String text, Audience audience, Speech speech) {
        Objects.requireNonNull(npc); Objects.requireNonNull(text); Objects.requireNonNull(audience); Objects.requireNonNull(speech);
        return live(() -> api.control.speak(npc, text, audience, speech));
    }
    @Override public CompletionStage<Outcome<NpcHandle>> gesture(NpcHandle npc, Gesture gesture) {
        Objects.requireNonNull(npc); Objects.requireNonNull(gesture);
        return live(() -> api.control.gesture(npc, gesture));
    }
    @Override public CompletionStage<Outcome<NpcHandle>> pose(NpcHandle npc, NpcSpec.Pose pose) {
        Objects.requireNonNull(npc); Objects.requireNonNull(pose);
        return live(() -> api.control.pose(npc, pose));
    }
    @Override public CompletionStage<Outcome<NpcHandle>> playAnimation(NpcHandle npc, String animation) {
        Objects.requireNonNull(npc); Objects.requireNonNull(animation);
        return live(() -> api.control.playAnimation(npc, animation));
    }
    @Override public CompletionStage<Outcome<NpcHandle>> equip(NpcHandle npc, NpcSpec.Slot slot, Optional<ItemSpec> item) {
        Objects.requireNonNull(npc); Objects.requireNonNull(slot); Objects.requireNonNull(item);
        return live(() -> api.control.equip(npc, slot, item));
    }
    @Override public CompletionStage<Outcome<NpcHandle>> skin(NpcHandle npc, SkinSource skin) {
        Objects.requireNonNull(npc); Objects.requireNonNull(skin);
        return live(() -> api.control.skin(owner, npc, skin));
    }
    @Override public CompletionStage<Outcome<NpcHandle>> mount(NpcHandle npc, UUID vehicle) {
        Objects.requireNonNull(npc); Objects.requireNonNull(vehicle);
        return live(() -> api.lifecycle.mount(owner, npc, vehicle));
    }
    @Override public CompletionStage<Outcome<NpcHandle>> dismount(NpcHandle npc) { Objects.requireNonNull(npc); return live(() -> api.lifecycle.dismount(owner, npc)); }
    @Override public CompletionStage<Outcome<NpcHandle>> seat(NpcHandle npc, UUID player) {
        Objects.requireNonNull(npc); Objects.requireNonNull(player);
        return live(() -> api.control.seat(npc, player));
    }
    @Override public CompletionStage<Outcome<Boolean>> unseat(UUID player) { Objects.requireNonNull(player); return live(() -> api.control.unseat(player)); }
    @Override public CompletionStage<Outcome<UUID>> startBehavior(NpcHandle npc, String behavior, Optional<UUID> player, Map<String, String> variables) {
        Objects.requireNonNull(npc); Objects.requireNonNull(behavior); Objects.requireNonNull(player); Objects.requireNonNull(variables);
        return live(() -> api.state.start(npc, behavior, player, Map.copyOf(variables)));
    }
    @Override public CompletionStage<Outcome<Boolean>> stopBehavior(UUID run) { Objects.requireNonNull(run); return live(() -> api.state.stop(run)); }
    @Override public CompletionStage<Outcome<Optional<String>>> flag(Flag flag) { Objects.requireNonNull(flag); return live(() -> api.state.flag(flag)); }
    @Override public CompletionStage<Outcome<Flag>> setFlag(Flag flag, Optional<String> json) {
        Objects.requireNonNull(flag); Objects.requireNonNull(json);
        return live(() -> api.state.setFlag(flag, json));
    }
    @Override public CompletionStage<Outcome<NpcHandle>> show(NpcHandle npc, UUID player, boolean shown) {
        Objects.requireNonNull(npc); Objects.requireNonNull(player);
        return live(() -> api.control.show(owner, npc, player, shown));
    }
    @Override public CompletionStage<Outcome<NpcHandle>> run(NpcHandle npc, Runnable work) {
        Objects.requireNonNull(npc); Objects.requireNonNull(work);
        return live(() -> api.control.run(npc, work));
    }
}
