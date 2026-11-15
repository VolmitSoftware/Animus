package art.arcane.animus.api;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.UnaryOperator;

/**
 * Creates, edits, removes and drives NPCs for one owner: a Bukkit plugin (from {@code AnimusNpcs.service(plugin)}) or a
 * Java module (from {@link ModuleContext#npcs()}). Temporary NPCs and module registrations belong to that owner and end
 * with it.
 *
 * <h2>Threads</h2>
 * Every method may be called from any thread and returns at once; nothing blocks for the answer. Work that touches an NPC
 * runs on the thread that owns it: the main thread on Paper, Spigot and mod servers, the NPC's region on Folia. File work
 * runs on Animus's file queue, one change at a time, shared with commands and the web editor. Every returned stage
 * completes on an Animus worker thread, never on a server thread, so a callback that touches players, entities or blocks
 * must schedule itself where they are owned (on Bukkit, the scheduler of that entity, region or the main thread), or use
 * {@link #run} to work on the NPC's thread.
 *
 * <h2>Results</h2>
 * Expected refusals come back as an {@link Outcome} with a diagnostic, never as an exception: {@code INVALID} for bad
 * input, {@code NOT_FOUND} for an NPC, player, entity or behavior that does not exist, {@code CONFLICT} for an id or
 * revision clash, {@code UNAVAILABLE} when the server cannot do it now (the NPC is not active, the feature is off or this
 * platform lacks it), {@code BUSY} when Animus's queues are full, {@code CANCELLED} for work stopped before it finished,
 * and {@code FAILED} for a fault. A null argument is a programming error and throws {@link NullPointerException}.
 *
 * <h2>Runtime changes</h2>
 * The runtime methods ({@link #teleport} through {@link #show}) change only the live NPC: a restart, a reload or an edit of
 * its definition shows the definition again. {@link #mount} and {@link #dismount} are the exception: they change the
 * definition's mount, saved for a persistent NPC.
 *
 * @since 1.3
 */
public interface NpcService {
    /** Vanilla animations an NPC plays once. */
    enum Gesture { SWING, SWING_OFFHAND, HURT, CRIT, MAGIC_CRIT, USE_ITEM, STOP_USING_ITEM }

    /** How an NPC says something: in chat, in a speech bubble above its head, or both. */
    enum Speech { CHAT, BUBBLE, BOTH }

    /**
     * The plugin or Java module this service belongs to, as consoles and status lines name it.
     *
     * @return the owner's name
     */
    String owner();

    /**
     * Token-checked reads and full-document replacement of saved and temporary definitions.
     *
     * @return the definition service
     */
    DefinitionService definitions();

    /**
     * Registration of this owner's definition modules.
     *
     * @return the registrar
     */
    NpcModules.Registrar modules();

    // ---- Lifecycle ----

    /**
     * Creates a persistent NPC: saved under {@code npcs/} like one made in game, checked by the same rules as the web
     * editor, and undoable with {@code /animus undo}. The spec needs an id and a location; the type defaults to
     * {@code minecraft:player}, the name to the id and {@code spawned} to true. CONFLICT when the id is taken. A spawned
     * NPC's stage completes once its body is active, so it can be driven at once, or after five seconds when it still
     * waits for its chunk to load; {@link #state} tells which.
     *
     * @param spec the NPC
     * @return the saved definition
     */
    CompletionStage<Outcome<DefinitionSnapshot>> create(NpcSpec spec);

    /**
     * Creates a temporary NPC that is never saved: it vanishes on restart and when this service's owner disables, and
     * {@code /animus} file commands refuse it. It behaves like a saved NPC otherwise: behaviors, modules, clicks, combat and
     * events all apply. The spec needs a location; without an id one is generated. Like {@link #create}, the stage of a
     * spawned NPC completes once its body is active, or after five seconds.
     *
     * @param spec the NPC
     * @return its definition
     */
    CompletionStage<Outcome<DefinitionSnapshot>> createTemporary(NpcSpec spec);

    /**
     * Applies the parts set in {@code changes} to the NPC's current definition and saves it (in memory for a temporary
     * NPC). The id and folder cannot change. A live NPC shows the new definition at once.
     *
     * @param npc the NPC
     * @param changes the parts to change
     * @return the new definition
     */
    CompletionStage<Outcome<DefinitionSnapshot>> edit(NpcHandle npc, NpcSpec changes);

    /**
     * Changes one module's data on the file queue, from what it holds at that moment, so two updates never lose each
     * other. {@code change} receives the current data as a JSON object, or empty when the NPC has none, and returns the
     * new data, or empty to remove it. It runs on the file queue and must be quick. Changing a plugin module this way
     * leaves the live NPC as it is.
     *
     * @param npc the NPC
     * @param key module key, {@code namespace:name}
     * @param change from the current data to the new data
     * @return the new definition
     */
    CompletionStage<Outcome<DefinitionSnapshot>> updateModule(NpcHandle npc, String key, UnaryOperator<Optional<String>> change);

    /**
     * Removes an NPC: deletes its file (undoable with {@code /animus undo}) or forgets a temporary NPC.
     *
     * @param npc the NPC
     * @return the removed NPC
     */
    CompletionStage<Outcome<NpcHandle>> remove(NpcHandle npc);

    /**
     * Where the NPC is and whether it is active, saved or temporary.
     *
     * @param npc the NPC
     * @return its state
     */
    CompletionStage<Outcome<NpcState>> state(NpcHandle npc);

    // ---- Runtime ----

    /**
     * Moves the active NPC onto clear ground at a place and turns it to the location's facing. UNAVAILABLE when the place
     * is not loaded or not clear, or the NPC rides, sits on a seat or carries someone. On Folia a place in another region
     * is checked by that region and the NPC is handed over to it; a place in another world answers UNAVAILABLE there.
     *
     * @param npc the NPC
     * @param to where it goes
     * @return the NPC
     */
    CompletionStage<Outcome<NpcHandle>> teleport(NpcHandle npc, NpcLocation to);

    /**
     * Walks the active NPC to a point in its world along a path. The stage completes when it arrives, with the place it
     * stopped; FAILED when the way is blocked or the path cannot be found, and CANCELLED when {@link #stop}, another route,
     * a follow, a definition change or a despawn ends it.
     *
     * @param npc the NPC
     * @param to the destination, in the NPC's world
     * @param speed blocks per second, 1 to 5, or 0 for 2
     * @return where it arrived
     */
    CompletionStage<Outcome<NpcLocation>> navigate(NpcHandle npc, NpcLocation to, double speed);

    /**
     * Keeps the active NPC walking after a player, a mob or another NPC, stopping {@code distance} blocks short, until
     * something stops it. The stage completes then: CANCELLED for {@link #stop}, {@link #navigate} or another follow,
     * NOT_FOUND once the entity has been gone or out of reach for five seconds (it left, died or changed worlds) or the NPC
     * was removed, and UNAVAILABLE when the NPC stops being active. A route a behavior starts is replaced at the follow's
     * next step. On Folia a mob is followed while it is in the NPC's region.
     *
     * @param npc the NPC
     * @param entity the player, mob or NPC to follow
     * @param distance blocks to keep, 1 to 16
     * @return the NPC
     */
    CompletionStage<Outcome<NpcHandle>> follow(NpcHandle npc, UUID entity, double distance);

    /**
     * Stops the NPC's route, follow or patrol where it stands.
     *
     * @param npc the NPC
     * @return the NPC
     */
    CompletionStage<Outcome<NpcHandle>> stop(NpcHandle npc);

    /**
     * Turns the active NPC's head and body toward a point in its world, within 64 blocks.
     *
     * @param npc the NPC
     * @param at what it looks at
     * @return the NPC
     */
    CompletionStage<Outcome<NpcHandle>> lookAt(NpcHandle npc, NpcLocation at);

    /**
     * Turns the active NPC toward a player, mob or NPC near it.
     *
     * @param npc the NPC
     * @param entity what it looks at
     * @return the NPC
     */
    CompletionStage<Outcome<NpcHandle>> lookAt(NpcHandle npc, UUID entity);

    /**
     * The NPC says {@code text}, formatted like {@code animus:say} with {@code &} colour codes. A bubble shows the first 256
     * characters above its head for five seconds to everyone who sees it, whatever the audience. On Bukkit, speech feeds
     * the {@code hear} triggers of NPCs near it.
     *
     * @param npc the NPC
     * @param text at most 1024 characters
     * @param audience who reads the chat line
     * @param speech chat, bubble or both
     * @return how many players received the chat line, 0 for a bubble alone
     */
    CompletionStage<Outcome<Integer>> speak(NpcHandle npc, String text, Audience audience, Speech speech);

    /**
     * Plays a vanilla animation once, for everyone who sees the NPC.
     *
     * @param npc the NPC
     * @param gesture the animation
     * @return the NPC
     */
    CompletionStage<Outcome<NpcHandle>> gesture(NpcHandle npc, Gesture gesture);

    /**
     * Holds a body pose until the NPC moves or its definition is shown again. INVALID for a pose its body cannot take.
     *
     * @param npc the NPC
     * @param pose the pose
     * @return the NPC
     */
    CompletionStage<Outcome<NpcHandle>> pose(NpcHandle npc, NpcSpec.Pose pose);

    /**
     * Plays one animation of the NPC's rig once at normal speed. UNAVAILABLE for an NPC without a rig, INVALID for an
     * animation its rig does not have.
     *
     * @param npc the NPC
     * @param animation animation name in the rig
     * @return the NPC
     */
    CompletionStage<Outcome<NpcHandle>> playAnimation(NpcHandle npc, String animation);

    /**
     * Puts an item in one of the live NPC's slots, or clears it with an empty item.
     *
     * @param npc the NPC
     * @param slot the slot
     * @param item the item, or empty to clear it
     * @return the NPC
     */
    CompletionStage<Outcome<NpcHandle>> equip(NpcHandle npc, NpcSpec.Slot slot, Optional<ItemSpec> item);

    /**
     * Shows the live NPC in another skin. The skin is saved as a skin file (signed as {@link SkinSource} describes) so
     * other NPCs and the web editor can use it; the NPC's definition keeps its own skin. A texture needs its signature
     * here; an unsigned one answers INVALID, since only skins a definition wears are signed later.
     *
     * @param npc the NPC, with a {@code minecraft:player} body
     * @param skin where the skin comes from
     * @return the NPC
     */
    CompletionStage<Outcome<NpcHandle>> skin(NpcHandle npc, SkinSource skin);

    /**
     * Puts the NPC on a vehicle: another NPC, or a real entity such as a horse or a minecart. This changes the definition's
     * mount ({@code modules["animus:mount"]}), saved for a persistent NPC, so it stays mounted after a restart while the
     * vehicle exists.
     *
     * @param npc the rider
     * @param vehicle the NPC's or entity's UUID
     * @return the rider
     */
    CompletionStage<Outcome<NpcHandle>> mount(NpcHandle npc, UUID vehicle);

    /**
     * Takes the NPC off its vehicle and removes the definition's mount.
     *
     * @param npc the rider
     * @return the rider
     */
    CompletionStage<Outcome<NpcHandle>> dismount(NpcHandle npc);

    /**
     * Seats an online player on the active NPC, as clicking a seat does, but without the seat's permission, empty-hand and
     * sitting-toggle checks: the plugin decides. Refusals that keep the player safe still apply: a dead, sleeping or riding
     * player, a full seat, another world, or region entry denied. The player must be near the NPC; on Folia, in its region.
     *
     * @param npc the NPC
     * @param player the player
     * @return the NPC
     */
    CompletionStage<Outcome<NpcHandle>> seat(NpcHandle npc, UUID player);

    /**
     * Gets the player off any NPC they sit on or ride.
     *
     * @param player the player
     * @return true when they were seated or riding
     */
    CompletionStage<Outcome<Boolean>> unseat(UUID player);

    /**
     * Starts one run of a behavior on the NPC, as {@code /animus behaviors run} does. Expressions read each variable as
     * {@code context.<name>}.
     *
     * @param npc the NPC
     * @param behavior behavior id, such as {@code town:greet}
     * @param player the player the run is about, if any
     * @param variables at most 32 names (letters, digits, {@code _}) with text values of at most 1024 characters
     * @return the run's id
     */
    CompletionStage<Outcome<UUID>> startBehavior(NpcHandle npc, String behavior, Optional<UUID> player, Map<String, String> variables);

    /**
     * Cancels a run started by {@link #startBehavior} or by a trigger.
     *
     * @param run the run's id
     * @return true when the run was still going
     */
    CompletionStage<Outcome<Boolean>> stopBehavior(UUID run);

    /**
     * A flag's value as JSON, such as {@code true}, {@code 3} or {@code "gold"}; empty when it is unset or expired.
     *
     * @param flag the flag
     * @return its value
     */
    CompletionStage<Outcome<Optional<String>>> flag(Flag flag);

    /**
     * Sets a flag to a JSON value, or unsets it with an empty value. The value is saved like a behavior's.
     *
     * @param flag the flag
     * @param json its new value, or empty to unset it
     * @return the flag
     */
    CompletionStage<Outcome<Flag>> setFlag(Flag flag, Optional<String> json);

    /**
     * Shows or hides the NPC for one player until this owner changes it, the player leaves or the owner disables. A hidden
     * NPC is gone for that player alone: no body, speech, sound, particles or triggers.
     *
     * @param npc the NPC
     * @param player the player
     * @param shown false to hide it
     * @return the NPC
     */
    CompletionStage<Outcome<NpcHandle>> show(NpcHandle npc, UUID player, boolean shown);

    /**
     * Runs {@code work} on the thread that owns the active NPC, where the blocks and entities around it may be used. The
     * stage completes after it ran; FAILED when it threw.
     *
     * @param npc the NPC
     * @param work quick work that must not block
     * @return the NPC
     */
    CompletionStage<Outcome<NpcHandle>> run(NpcHandle npc, Runnable work);
}
