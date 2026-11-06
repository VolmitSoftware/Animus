package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.NpcHandle;
import art.arcane.animus.core.extension.CapabilityRefusal;
import art.arcane.animus.core.navigation.MovementProfile;
import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.recording.Playback;
import art.arcane.animus.core.virtual.Vec3;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * World and player work for built-in capabilities. {@link #unavailable()}, {@link #ownerTicks(Runnable)},
 * {@link #onNpcOwner}, {@link #onPlayerOwner} and {@link #onGlobalOwner} may be called from any thread. Every other method
 * runs only inside work one of them placed: NPC work on the NPC's owner, player-addressed work of server behaviors and the
 * player-only actions on the player's owner, broadcasts on the global owner. A single-owner port runs that work inline in
 * the owner tick; a regionized port places it on the region, entity scheduler or global scheduler that owns it.
 * Server behaviors run with {@link art.arcane.animus.core.behavior.ServerNpc#HANDLE}, which names no NPC. Implementations reject with
 * {@link art.arcane.animus.core.extension.CapabilityRefusal} to keep an expected failure distinct from a fault.
 */
public interface BehaviorPlatform {
    enum CommandSource { CONSOLE, PLAYER }
    enum CombatOperation { ENABLE, DISABLE, TARGET, RELEASE, GUARD }
    enum ScreenKind { SHOP, GUI, MENU, GLOSS }
    enum BuildOperation { START, PAUSE, RESUME, CANCEL, UNDO }
    record NpcStatus(boolean spawned, double health) {}
    /** A present player addresses only that viewer. An empty player addresses every viewer within the radius. */
    record Audience(Optional<UUID> player, double radius) {
        public Audience { Objects.requireNonNull(player); }
    }
    /** A clickable answer. Colour, hover and style are optional; {@link DialogueFormat} fills sensible defaults. */
    record ChoiceOption(String id, String text, Optional<String> colour, Optional<String> hover, Optional<String> style) {
        public ChoiceOption {
            Objects.requireNonNull(id); Objects.requireNonNull(text);
            Objects.requireNonNull(colour); Objects.requireNonNull(hover); Objects.requireNonNull(style);
        }
        public ChoiceOption(String id, String text) { this(id, text, Optional.empty(), Optional.empty(), Optional.empty()); }
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
    /** Who an {@code animus:message} reaches: one player, everyone online, the players of a world, or the console. */
    enum MessageTarget { PLAYER, ALL, WORLD, CONSOLE }
    /** Ticks are 0..72000. At least one of title and subtitle is present. Texts use the dialogue colour codes. */
    record Title(Optional<String> title, Optional<String> subtitle, int fadeIn, int stay, int fadeOut) {
        public Title { Objects.requireNonNull(title); Objects.requireNonNull(subtitle); }
    }
    /** An item resource key such as minecraft:bread, 1..6400 of it, with an optional display name and lore lines. */
    record ItemGift(String item, int amount, Optional<String> name, List<String> lore) {
        public ItemGift { Objects.requireNonNull(item); Objects.requireNonNull(name); lore = List.copyOf(lore); }
    }
    /** A written book. Pages use the dialogue colour codes. */
    record Book(String title, String author, List<String> pages) {
        public Book { Objects.requireNonNull(title); Objects.requireNonNull(author); pages = List.copyOf(pages); }
    }
    /** Where {@code animus:teleport} sends its target. */
    sealed interface Destination permits Point, ToNpc, ToPlayer {}
    /** An empty world keeps the target's world; empty yaw or pitch keeps its facing. */
    record Point(Optional<String> world, Vec3 position, OptionalDouble yaw, OptionalDouble pitch) implements Destination {
        public Point { Objects.requireNonNull(world); Objects.requireNonNull(position); Objects.requireNonNull(yaw); Objects.requireNonNull(pitch); }
    }
    /** The run's NPC. */
    record ToNpc() implements Destination {}
    record ToPlayer(UUID player) implements Destination {
        public ToPlayer { Objects.requireNonNull(player); }
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
    /** Places work where the player is owned (Folia: the player's entity scheduler). Same contract as {@link #onNpcOwner}. */
    default boolean onPlayerOwner(UUID player, Runnable work, Consumer<String> refused) { work.run(); return true; }
    /** Places work on the global owner (Folia: the global region scheduler). Same contract as {@link #onNpcOwner}. */
    default boolean onGlobalOwner(Runnable work, Consumer<String> refused) { work.run(); return true; }

    boolean hasPermission(UUID player, String node);
    NpcStatus npcStatus(NpcHandle npc);
    /** An empty player asks whether any player is within the radius. */
    boolean playerInRange(NpcHandle npc, Optional<UUID> player, double radius);
    /** Returns the number of viewers reached. The text is raw definition text; format it with {@link DialogueFormat#speech}. */
    int say(NpcHandle npc, Audience audience, String text);
    /** Returns the number of viewers reached. The token identifies the pending prompt in its clickable responses. */
    int presentChoices(NpcHandle npc, UUID player, String prompt, String token, List<ChoiceOption> options);
    /** The player's current name, or empty when the player is unknown here. */
    Optional<String> playerName(UUID player);
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
    /**
     * Replaces the NPC's route with a recording it walks to and plays. {@link #routeFinished} called right after completes
     * when a {@code once} playback ends; a looping playback never finishes.
     */
    void playRecording(NpcHandle npc, String recording, Playback playback);
    /** Restarts the definition's own route or recording. False when the definition declares nothing to resume. */
    boolean resumePatrol(NpcHandle npc);
    void look(NpcHandle npc, Vec3 target);
    void lookAtPlayer(NpcHandle npc, UUID player);
    /** Turns the NPC to one of its definition's named poses; refuses a pose it does not declare. */
    void pose(NpcHandle npc, String pose);
    void setAppearance(NpcHandle npc, AppearanceChange change);
    /** An empty item clears the slot. */
    void setEquipment(NpcHandle npc, EquipmentSlot slot, Optional<String> item);
    int playSound(NpcHandle npc, Audience audience, SoundEffect sound);
    int spawnParticle(NpcHandle npc, Audience audience, ParticleEffect particle);
    void combat(NpcHandle npc, CombatOperation operation, Optional<UUID> target);
    /**
     * Opens a shop or chest GUI for the player, handing the work to the player's owner. Returns false when the player
     * is offline. Platforms without screens refuse as unavailable.
     */
    default boolean openScreen(NpcHandle npc, UUID player, ScreenKind kind, String id) {
        throw new art.arcane.animus.core.extension.CapabilityRefusal(art.arcane.animus.api.Outcome.Status.UNAVAILABLE, "screens-unavailable",
                "Shops and chest GUIs are not available on this platform");
    }

    /**
     * Sends formatted text without a speaker prefix. PLAYER needs {@code player}; WORLD reaches the NPC's world, or the
     * player's world in a server run; ALL and CONSOLE ignore both. Returns the number of receivers.
     */
    default int message(NpcHandle npc, Optional<UUID> player, MessageTarget to, String text) { throw refused("Messages"); }
    /** Shows a title to the player. Returns 1, or 0 when the player is offline. */
    default int title(UUID player, Title title) { throw refused("Titles"); }
    /** Shows action bar text to the player. Returns 1, or 0 when the player is offline. */
    default int actionbar(UUID player, String text) { throw refused("Action bar text"); }
    /** Plays a sound at the player's position for the audience. Returns the number of viewers reached. */
    default int playSoundAtPlayer(UUID player, Audience audience, SoundEffect sound) { throw refused("Sounds at players"); }
    /** Shows particles at the player's position to the audience. Returns the number of viewers reached. */
    default int spawnParticleAtPlayer(UUID player, Audience audience, ParticleEffect particle) { throw refused("Particles at players"); }
    /** Teleports the player. {@code npc} is the run's NPC, used by {@link ToNpc}. Returns false when the player is offline. */
    default boolean teleportPlayer(UUID player, NpcHandle npc, Destination to) { throw refused("Teleporting players"); }
    /** Moves the NPC. {@link ToNpc} is refused. */
    default void teleportNpc(NpcHandle npc, Destination to) { throw refused("Teleporting NPCs"); }
    /** Gives items, dropping what does not fit at the player's feet. Returns the amount given, 0 when the player is offline. */
    default int giveItem(UUID player, ItemGift gift) { throw refused("Giving items"); }
    /** Removes up to amount matching items. Returns the amount taken, never more than the player held. */
    default int takeItem(UUID player, String item, int amount) { throw refused("Taking items"); }
    /** Gives a written book. Returns 1, or 0 when the player is offline. */
    default int giveBook(UUID player, Book book) { throw refused("Giving books"); }

    private static CapabilityRefusal refused(String what) {
        return new CapabilityRefusal(art.arcane.animus.api.Outcome.Status.UNAVAILABLE, "action-unavailable", "Not available on this platform: " + what);
    }

    /** Plays an animation of the NPC's rig. {@code loop} overrides the clip's loop mode. */
    default void playAnimation(NpcHandle npc, String animation, double speed, Optional<String> loop, int layer) {
        throw new CapabilityRefusal(art.arcane.animus.api.Outcome.Status.UNAVAILABLE, "rigs-unavailable", "Rig animation is not available on this platform");
    }
    /** Stops one animation layer, or every played layer, fading over {@code fadeTicks}. */
    default void stopAnimation(NpcHandle npc, java.util.OptionalInt layer, int fadeTicks) {
        throw new CapabilityRefusal(art.arcane.animus.api.Outcome.Status.UNAVAILABLE, "rigs-unavailable", "Rig animation is not available on this platform");
    }

    /**
     * Starts or steers the NPC's build. {@code options} are build option words such as {@code rotation=90}. A start
     * without a schematic uses the NPC's saved builder section. Returns a short JSON result.
     */
    default String build(NpcHandle npc, BuildOperation operation, Optional<String> schematic, Optional<Vec3> origin, java.util.Map<String, String> options) {
        throw new CapabilityRefusal(art.arcane.animus.api.Outcome.Status.UNAVAILABLE, "build-unavailable", "Building is not available on this platform");
    }
}
