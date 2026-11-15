package art.arcane.animus.core.api;

import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.api.NpcService;
import art.arcane.animus.core.AnimusRuntime;
import art.arcane.animus.core.RuntimeEnvironment;
import art.arcane.animus.core.behavior.builtin.BehaviorPlatform;
import art.arcane.animus.core.behavior.trigger.TriggerDispatcher;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcAnimation;
import art.arcane.animus.core.npc.NpcDefinition;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.runtime.NpcRuntime;
import art.arcane.animus.core.runtime.ViewerPreviews;
import art.arcane.animus.core.virtual.Hitbox;
import art.arcane.animus.core.virtual.Vec3;
import art.arcane.animus.core.virtual.VirtualNpcState;
import art.arcane.animus.core.virtual.WorldPosition;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A real Animus runtime over a temporary folder with a fake NPC runtime: every spawned definition counts as active, NPC
 * work runs on one {@code owner} thread, and runtime calls are recorded as lines.
 */
final class ApiHarness implements AutoCloseable {
    static final String WORLD = "world", WORLD_KEY = "11111111-1111-1111-1111-111111111111";
    final AnimusRuntime runtime;
    final Fake living;
    final List<String> calls = new CopyOnWriteArrayList<>(), logged = new CopyOnWriteArrayList<>();
    final Map<UUID, WorldPosition> entities = new ConcurrentHashMap<>();
    final Set<UUID> online = ConcurrentHashMap.newKeySet();
    final List<Runnable> ticks = new CopyOnWriteArrayList<>();
    final NpcApi<Object, String> api;
    final ExecutorService owner = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("fake-owner").factory());

    ApiHarness(Path root) throws Exception {
        runtime = new AnimusRuntime(RuntimeEnvironment.bukkit("26.3", root));
        runtime.start();
        living = new Fake(runtime, new ViewerPreviews(Runnable::run, runtime.documents()));
        BehaviorPlatform platform = (BehaviorPlatform) java.lang.reflect.Proxy.newProxyInstance(BehaviorPlatform.class.getClassLoader(), new Class<?>[] {BehaviorPlatform.class},
                (Object proxy, java.lang.reflect.Method method, Object[] arguments) -> switch (method.getName()) {
                    case "say" -> { calls.add("say " + arguments[2] + " radius=" + ((BehaviorPlatform.Audience) arguments[1]).radius()); yield 3; }
                    case "playerName" -> Optional.of("Alex");
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        api = new NpcApi<>(new NpcApi.Parts<>(runtime, living, platform, new Ports(), () -> null,
                (Runnable tick) -> { ticks.add(tick); return () -> ticks.remove(tick); },
                (NpcHandle npc, String plain) -> calls.add("heard " + plain), logged::add));
    }

    NpcService service(String owner) { return api.open(owner, () -> true); }

    void tick(int count) { for (int i = 0; i < count; i++) for (Runnable tick : ticks) tick.run(); }

    /** Waits for every queued owner turn. */
    void settle() throws Exception { owner.submit(() -> {}).get(5, TimeUnit.SECONDS); }

    @Override public void close() {
        api.close();
        owner.shutdownNow();
        runtime.close();
    }

    private final class Ports implements ApiPlatform<Object, String> {
        @Override public String item(NpcItem item, String path, List<String> errors) {
            if (item.id().equals("minecraft:barrier")) { errors.add(path + ".id: unknown item " + item.id() + " on this server"); return null; }
            return item.id();
        }
        @Override public String emptyItem() { return "air"; }
        @Override public java.util.concurrent.CompletionStage<Boolean> teleport(String id, WorldPosition to, float yaw, float pitch) {
            if (!Thread.currentThread().getName().equals("fake-owner")) throw new IllegalStateException("teleports start on the NPC's thread");
            calls.add("teleport " + id + " " + to.point().brief() + " facing " + yaw);
            return CompletableFuture.supplyAsync(() -> to.point().y() >= 0, owner);
        }
        @Override public Optional<WorldPosition> entity(UUID entity) { return Optional.ofNullable(entities.get(entity)); }
        @Override public WorldPosition position(Object player) { return new WorldPosition(WORLD_KEY, Vec3.ZERO); }
        @Override public Optional<String> world(String reference) { return reference.equals(WORLD) || reference.equals(WORLD_KEY) ? Optional.of(WORLD_KEY) : Optional.empty(); }
        @Override public String worldName(String key) { return key.equals(WORLD_KEY) ? WORLD : key; }
    }

    /** The NPC runtime: spawned definitions are active where they stand, until {@link #retire}. */
    final class Fake implements NpcRuntime<Object, String> {
        final Set<UUID> retired = ConcurrentHashMap.newKeySet();
        /** While set, no NPC is active, as when every chunk is still loading. */
        volatile boolean dormant;
        final Map<String, CompletableFuture<Void>> routes = new ConcurrentHashMap<>();
        final List<String> activation = new ArrayList<>();
        private final AnimusRuntime runtime;
        private final ViewerPreviews previews;
        Fake(AnimusRuntime runtime, ViewerPreviews previews) { this.runtime = runtime; this.previews = previews; }

        void retire(UUID npc) { retired.add(npc); }
        private Optional<NpcDefinition> definition(UUID npc) { return runtime.npcs().all().stream().filter((NpcDefinition definition) -> definition.uuid().equals(npc)).findFirst(); }
        private void record(String line) {
            if (!Thread.currentThread().getName().equals("fake-owner")) throw new IllegalStateException("NPC runtime requires the owner thread");
            calls.add(line);
        }

        /** Like the main-thread runtime, only the owner may ask; {@link #state} answers from any thread. */
        @Override public Optional<ActiveNpc> active(UUID npc) {
            if (!Thread.currentThread().getName().equals("fake-owner")) throw new IllegalStateException("NPC runtime requires the owner thread");
            return published(npc);
        }
        private Optional<ActiveNpc> published(UUID npc) {
            return definition(npc).filter((NpcDefinition definition) -> !dormant && definition.spawned() && !retired.contains(npc)).map((NpcDefinition definition) -> {
                var position = definition.json().getAsJsonObject("position");
                WorldPosition at = new WorldPosition(WORLD_KEY, new Vec3(position.get("x").getAsDouble(), position.get("y").getAsDouble(), position.get("z").getAsDouble()));
                return new ActiveNpc(definition.id(), definition.name(), new VirtualNpcState.Snapshot(npc, at, new Hitbox(at.point(), at.point().add(new Vec3(0.6, 1.8, 0.6))), 20, true, 1));
            });
        }
        @Override public boolean onOwner(UUID npc, Runnable work, Consumer<String> refused) { owner.execute(work); return true; }
        @Override public boolean onOwner(String id, Runnable work, Consumer<String> refused) { owner.execute(work); return true; }
        @Override public UUID navigate(String id, WorldPosition destination, MovementProfile.Mode mode, double blocksPerSecond) {
            record("navigate " + id + " " + destination.point().brief() + " at " + blocksPerSecond);
            routes.put(id, new CompletableFuture<>());
            return UUID.randomUUID();
        }
        @Override public CompletableFuture<Void> awaitRoute(String id) { return routes.computeIfAbsent(id, (String key) -> new CompletableFuture<>()); }
        @Override public boolean stopNavigation(String id) {
            record("stop " + id);
            CompletableFuture<Void> route = routes.remove(id);
            if (route != null) route.completeExceptionally(art.arcane.animus.core.runtime.NavigationRefusals.of("cancelled: navigation stopped"));
            return true;
        }
        @Override public void lookAt(String id, WorldPosition target) { record("look " + id + " " + target.point().brief()); }
        @Override public void animate(String id, NpcAnimation animation) { record("animate " + id + " " + animation); }
        @Override public void rest(String id, NpcPose pose) { record("rest " + id + " " + pose); }
        @Override public void bubble(String id, String text, int ticks) { record("bubble " + id + " " + text + " " + ticks); }
        @Override public void setEquipment(String id, EquipmentSlot slot, String item) { record("equip " + id + " " + slot + " " + item); }
        @Override public void setAppearance(String id, Optional<String> name, Optional<String> skin, boolean reset) { record("skin " + id + " " + skin.orElse("none")); }
        @Override public java.util.concurrent.CompletionStage<Optional<String>> seat(UUID player, UUID npc) {
            return CompletableFuture.supplyAsync(() -> { record("seat " + player); return Optional.empty(); }, owner);
        }
        @Override public boolean stopRider(String playerName) { record("unseat " + playerName); return true; }
        @Override public List<String> validateActivation(NpcDefinition definition) { return List.copyOf(activation); }
        @Override public ViewerPreviews previews() { return previews; }
        @Override public art.arcane.animus.core.behavior.trigger.HiddenNpcs hidden() { return previews; }
        @Override public Optional<VirtualNpcState.Snapshot> state(UUID npc) { return published(npc).map(ActiveNpc::state); }
        @Override public PlayerAccess<Object> players() {
            return new PlayerAccess<>() {
                @Override public boolean online(UUID player) { return online.contains(player); }
                @Override public Optional<Object> owned(UUID player) { return Optional.of(player); }
                @Override public List<Object> ownedNear(WorldPosition at, double radius, int limit) { return List.of(); }
                @Override public boolean deliver(UUID player, Consumer<Object> action) { owner.execute(() -> action.accept(player)); return true; }
                @Override public boolean console(Runnable action) { owner.execute(action); return true; }
            };
        }
        @Override public boolean available() { return true; }
        @Override public void interactions(InteractionListener<Object> listener) {}
        @Override public void observeInteractions(InteractionObserver observer) {}
        @Override public boolean pressed(Object player, art.arcane.animus.core.behavior.trigger.TriggerEvent.Action key) { return false; }
        @Override public String status() { return "fake"; }
        @Override public boolean cosmeticAllowed() { return true; }
        @Override public void bindTriggers(TriggerDispatcher dispatcher) {}
        @Override public void refresh() {}
        @Override public String inspect(String id) { return ""; }
        @Override public String rideStatus() { return ""; }
        @Override public Optional<String> selection(UUID viewer) { return Optional.empty(); }
        @Override public void select(UUID viewer, String id) {}
        @Override public String navigationStatus(String id) { return ""; }
        @Override public WorldPosition navigationOrigin(String id) { throw new UnsupportedOperationException(); }
        @Override public void pose(String id, String pose) {}
        @Override public void followRoute(String id, String route) {}
        @Override public void playRecording(String id, String recording, Optional<art.arcane.animus.core.recording.Playback> playback) {}
        @Override public boolean resumePatrol(String id) { return false; }
        @Override public void setAttribute(String id, String attribute, java.util.OptionalDouble value) {}
        @Override public void close() {}
    }
}
