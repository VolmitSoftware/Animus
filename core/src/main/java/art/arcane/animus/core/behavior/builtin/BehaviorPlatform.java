package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.virtual.Vec3;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * World and player work for built-in capabilities. {@link #unavailable()}, {@link #ownerTicks(Runnable)} and
 * {@link #onNpcOwner} may be called from any thread. Every other method runs only inside work that
 * {@link #onNpcOwner} placed on the NPC's owner. A single-owner port runs that work inline in the owner tick;
 * a regionized port places it on the region that owns the NPC. Implementations reject with
 * {@link art.arcane.animus.core.extension.CapabilityRefusal} to keep an expected failure distinct from a fault.
 */
public interface BehaviorPlatform {
    enum CommandSource { CONSOLE, PLAYER }
    enum CombatOperation { ENABLE, DISABLE, TARGET, RELEASE, GUARD }
    enum EquipmentSlot { MAIN_HAND, OFF_HAND, HEAD, CHEST, LEGS, FEET, BODY }

    record NpcStatus(boolean spawned, double health) {}
    /** A present player addresses only that viewer. An empty player addresses every viewer within the radius. */
    record Audience(Optional<UUID> player, double radius) {
        public Audience { Objects.requireNonNull(player); }
    }
    record ChoiceOption(String id, String text) {
        public ChoiceOption { Objects.requireNonNull(id); Objects.requireNonNull(text); }
    }
    record AppearanceChange(Optional<String> name, Optional<String> skin, boolean reset) {
        public AppearanceChange { Objects.requireNonNull(name); Objects.requireNonNull(skin); }
    }
    record SoundEffect(String sound, float volume, float pitch) {
        public SoundEffect { Objects.requireNonNull(sound); }
    }
    record ParticleEffect(String particle, int count, double spread) {
        public ParticleEffect { Objects.requireNonNull(particle); }
    }

    /** Present when world and player work cannot run here; capabilities stay registered and report this reason. */
    Optional<String> unavailable();
    /** Runs the tick once per owner tick until the returned handle closes. Called only while available. */
    AutoCloseable ownerTicks(Runnable tick);
    /**
     * Places work where the NPC is owned. Returns false when it cannot be accepted now. Accepted work runs exactly
     * once, or {@code refused} receives a reason when its owner later drops it. The default is a single owner.
     */
    default boolean onNpcOwner(NpcHandle npc, Runnable work, Consumer<String> refused) { work.run(); return true; }

    boolean hasPermission(UUID player, String node);
    NpcStatus npcStatus(NpcHandle npc);
    /** An empty player asks whether any player is within the radius. */
    boolean playerInRange(NpcHandle npc, Optional<UUID> player, double radius);
    /** Returns the number of viewers reached. */
    int say(NpcHandle npc, Audience audience, String text);
    /** Returns the number of viewers reached. The token identifies the pending prompt in its clickable responses. */
    int presentChoices(NpcHandle npc, UUID player, String prompt, String token, List<ChoiceOption> options);
    void runCommand(CommandSource source, Optional<UUID> player, String command);
    /** Returns the identity of the requested route. Completion does not wait for arrival; see {@link #routeFinished}. */
    String navigate(NpcHandle npc, Vec3 destination, MovementProfile.Mode mode, double speed);
    void followRoute(NpcHandle npc, String route);
    /**
     * Completes on arrival of the route just started, at the last point of a reusable route. Fails with a
     * {@link art.arcane.animus.core.extension.CapabilityRefusal} when it is blocked, unavailable, replaced or stopped.
     */
    CompletionStage<Void> routeFinished(NpcHandle npc);
    String navigationStatus(NpcHandle npc);
    boolean stopNavigation(NpcHandle npc);
    void look(NpcHandle npc, Vec3 target);
    void lookAtPlayer(NpcHandle npc, UUID player);
    void setAppearance(NpcHandle npc, AppearanceChange change);
    /** An empty item clears the slot. */
    void setEquipment(NpcHandle npc, EquipmentSlot slot, Optional<String> item);
    int playSound(NpcHandle npc, Audience audience, SoundEffect sound);
    int spawnParticle(NpcHandle npc, Audience audience, ParticleEffect particle);
    void combat(NpcHandle npc, CombatOperation operation, Optional<UUID> target);
}
