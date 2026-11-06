package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.recording.Playback;
import art.arcane.animus.core.virtual.Vec3;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Platform double: records owner-side calls and asserts they are made from the owner tick. */
final class RecordingPlatform implements BehaviorPlatform {
    final List<String> calls = new CopyOnWriteArrayList<>();
    final List<String> targets = new CopyOnWriteArrayList<>();
    /** Where each piece of owner work was placed: npc, player:<uuid> or global. */
    final List<String> routes = new CopyOnWriteArrayList<>();
    volatile int taken = 3;
    final AtomicInteger closedTicks = new AtomicInteger();
    volatile Optional<String> unavailable = Optional.empty();
    volatile NpcStatus status = new NpcStatus(true, 20);
    volatile boolean inRange = true;
    volatile boolean permission = true;
    volatile boolean resumable = true;
    volatile int delivered = 1;
    volatile RuntimeException failure;
    volatile List<ChoiceOption> presentedOptions = List.of();
    volatile String presentedToken;
    volatile Optional<String> playerName = Optional.of("Steve");
    private volatile Runnable tick;
    private volatile CompletableFuture<Void> route;
    private volatile boolean onOwner;

    void pump() {
        Runnable current = tick;
        if (current == null) return;
        onOwner = true;
        try { current.run(); }
        finally { onOwner = false; }
    }

    /** Null reports arrival; a refusal reports how the route ended. */
    void finishRoute(RuntimeException failure) {
        if (failure == null) route.complete(null); else route.completeExceptionally(failure);
    }

    <T> T onOwner(Supplier<T> work) {
        onOwner = true;
        try { return work.get(); }
        finally { onOwner = false; }
    }

    private void record(String call) {
        if (!onOwner) throw new AssertionError("Platform call off the owner tick: " + call);
        if (failure != null) throw failure;
        calls.add(call);
    }

    boolean tickBound() { return tick != null; }

    @Override public Optional<String> unavailable() { return unavailable; }
    @Override public AutoCloseable ownerTicks(Runnable next) {
        tick = next;
        return () -> { tick = null; closedTicks.incrementAndGet(); };
    }
    @Override public boolean onNpcOwner(NpcHandle npc, Runnable work, java.util.function.Consumer<String> refused) { routes.add("npc"); work.run(); return true; }
    @Override public boolean onPlayerOwner(UUID player, Runnable work, java.util.function.Consumer<String> refused) { routes.add("player:" + player); work.run(); return true; }
    @Override public boolean onGlobalOwner(Runnable work, java.util.function.Consumer<String> refused) { routes.add("global"); work.run(); return true; }
    @Override public int message(NpcHandle npc, Optional<UUID> player, MessageTarget to, String text) { record("message:" + to + ":" + player.isPresent() + ":" + text); return delivered; }
    @Override public int title(UUID player, Title title) { record("title:" + title.title().orElse("") + ":" + title.subtitle().orElse("") + ":" + title.fadeIn() + ":" + title.stay() + ":" + title.fadeOut()); return delivered; }
    @Override public int actionbar(UUID player, String text) { record("actionbar:" + text); return delivered; }
    @Override public int playSoundAtPlayer(UUID player, Audience audience, SoundEffect sound) { record("sound-at-player:" + sound.sound()); return delivered; }
    @Override public int spawnParticleAtPlayer(UUID player, Audience audience, ParticleEffect particle) { record("particle-at-player:" + particle.particle()); return delivered; }
    @Override public boolean teleportPlayer(UUID player, NpcHandle npc, Destination to) { record("teleport-player:" + to.getClass().getSimpleName()); return true; }
    @Override public void teleportNpc(NpcHandle npc, Destination to) { record("teleport-npc:" + to.getClass().getSimpleName()); }
    @Override public int giveItem(UUID player, ItemGift gift) { record("give:" + gift.item() + ":" + gift.amount() + ":" + gift.name().orElse("") + ":" + gift.lore().size()); return gift.amount(); }
    @Override public int takeItem(UUID player, String item, int amount) { record("take:" + item + ":" + amount); return Math.min(amount, taken); }
    @Override public int giveBook(UUID player, Book book) { record("book:" + book.title() + ":" + book.pages().size()); return 1; }
    @Override public boolean hasPermission(UUID player, String node) { record("permission:" + node); return permission; }
    @Override public NpcStatus npcStatus(NpcHandle npc) { record("status"); return status; }
    @Override public boolean playerInRange(NpcHandle npc, Optional<UUID> player, double radius) {
        record("range:" + player.isPresent() + ":" + radius);
        return inRange;
    }
    @Override public int say(NpcHandle npc, Audience audience, String text) {
        record("say:" + audience.player().isPresent() + ":" + audience.radius() + ":" + text);
        return delivered;
    }
    @Override public int presentChoices(NpcHandle npc, UUID player, String prompt, String token, List<ChoiceOption> options) {
        record("choices:" + prompt);
        presentedToken = token;
        presentedOptions = options;
        return delivered;
    }
    @Override public Optional<String> playerName(UUID player) {
        if (!onOwner) throw new AssertionError("Platform call off the owner tick: name");
        return playerName;
    }
    @Override public void runCommand(CommandSource source, Optional<UUID> player, String command) { record("command:" + source + ":" + command); }
    @Override public String navigate(NpcHandle npc, Vec3 destination, MovementProfile.Mode mode, double speed) {
        record("navigate:" + mode + ":" + speed);
        return "route-id";
    }
    @Override public void followRoute(NpcHandle npc, String route) { record("route:" + route); }
    @Override public CompletionStage<Void> routeFinished(NpcHandle npc) {
        record("await");
        route = new CompletableFuture<>();
        return route;
    }
    @Override public String navigationStatus(NpcHandle npc) { return "suspended somewhere"; }
    @Override public boolean stopNavigation(NpcHandle npc) { record("stop"); return true; }
    @Override public void playRecording(NpcHandle npc, String recording, Playback playback) { record("recording:" + recording + ":" + playback.key()); }
    @Override public boolean resumePatrol(NpcHandle npc) { record("resume"); return resumable; }
    @Override public void look(NpcHandle npc, Vec3 target) { record("look-point"); }
    @Override public void lookAtPlayer(NpcHandle npc, UUID player) { record("look-player"); }
    @Override public void pose(NpcHandle npc, String pose) { record("pose:" + pose); }
    @Override public void setAppearance(NpcHandle npc, AppearanceChange change) { record("appearance:" + change.name().orElse("") + ":" + change.reset()); }
    @Override public void setEquipment(NpcHandle npc, EquipmentSlot slot, Optional<String> item) { record("equipment:" + slot + ":" + item.orElse("none")); }
    @Override public void setAttribute(NpcHandle npc, String attribute, java.util.OptionalDouble value) { record("attribute:" + attribute + ":" + (value.isPresent() ? String.valueOf(value.getAsDouble()) : "reset")); }
    @Override public void playAnimation(NpcHandle npc, String animation, double speed, Optional<String> loop, int layer) { record("animation:" + animation + ":" + speed + ":" + loop.orElse("clip") + ":" + layer); }
    @Override public void stopAnimation(NpcHandle npc, java.util.OptionalInt layer, int fadeTicks) { record("stop-animation:" + (layer.isPresent() ? layer.getAsInt() : "all") + ":" + fadeTicks); }
    @Override public int playSound(NpcHandle npc, Audience audience, SoundEffect sound) { record("sound:" + sound.sound()); return delivered; }
    @Override public int spawnParticle(NpcHandle npc, Audience audience, ParticleEffect particle) { record("particle:" + particle.particle() + ":" + particle.count()); return delivered; }
    @Override public boolean openScreen(NpcHandle npc, UUID player, ScreenKind kind, String id) { record("screen:" + kind + ":" + id); return true; }
    @Override public void combat(NpcHandle npc, CombatOperation operation, Optional<UUID> target) {
        record("combat:" + operation + ":" + target.isPresent());
        target.ifPresent(id -> targets.add("combat:" + operation + ":" + id));
    }
}
