package art.arcane.animus.api;

import java.util.*;

/**
 * Immutable notice after authority checks. {@code preview} marks a viewer-private proof body that has no definition.
 * {@code sequence} increases per engine start; notices can arrive out of order on the bounded workers.
 *
 * @param npc NPC that was used
 * @param viewer player who used it
 * @param action kind of interaction
 * @param distance distance from the viewer to the NPC
 * @param preview true for a viewer-private proof body
 * @param sequence engine start sequence number
 */
public record InteractionNotice(NpcHandle npc,UUID viewer,Action action,double distance,boolean preview,long sequence) {
    public enum Action { ATTACK, INTERACT }
    public InteractionNotice {Objects.requireNonNull(npc);Objects.requireNonNull(viewer);Objects.requireNonNull(action);if(!Double.isFinite(distance)||distance<0||sequence<0)throw new IllegalArgumentException("Invalid interaction notice");}
    /**
     * Runs on the bounded module worker with the module invocation deadline, never on a platform owner. It cannot
     * cancel the interaction. A notice is dropped when no invocation slot is free.
     * @since 1.1
     */
    @FunctionalInterface public interface Listener {void interacted(InteractionNotice notice,Capabilities.Cancellation cancellation) throws Exception;}
}
