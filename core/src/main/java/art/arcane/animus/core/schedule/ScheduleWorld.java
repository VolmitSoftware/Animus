package art.arcane.animus.core.schedule;

import art.arcane.animus.core.npc.EquipmentSlot;
import art.arcane.animus.core.npc.NpcItem;
import art.arcane.animus.core.npc.NpcPose;
import art.arcane.animus.core.virtual.WorldPosition;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * What schedules need from a server. {@link #onOwner} may be called from any thread; every other method runs inside
 * work it placed, on the NPC's owner.
 */
public interface ScheduleWorld {
    /** The world's time of day, 0-23999, and whether it rains or storms there. */
    record Clock(long time, boolean storm) {}

    /**
     * Runs work on the NPC's owner, now or later. False when the NPC is not active anywhere; {@code refused} runs instead
     * of accepted work its owner drops later.
     */
    boolean onOwner(UUID npc, Runnable work, Runnable refused);
    /** Where the active NPC stands; empty when it is not active. */
    Optional<WorldPosition> position(UUID npc);
    /** Empty when the world is not loaded here. */
    Optional<Clock> clock(String world);
    /** Starts a walking leg; {@link #arrival} called right after completes when it arrives and fails when it does not. */
    void walk(String id, WorldPosition goal, double speed);
    CompletableFuture<Void> arrival(String id);
    void look(String id, WorldPosition target);
    /** A runtime pose until the NPC moves again or its definition is applied again. */
    void pose(String id, NpcPose pose);
    /** An empty item clears the slot. */
    void equip(String id, EquipmentSlot slot, Optional<NpcItem> item);
    /** Shows a skin file from {@code skins/} on a player body. */
    void skin(String id, String skin);
    /** Runs a console command. */
    void command(String command);
}
