package art.arcane.animus.api;

import java.util.*;

/** Immutable notice after authority checks. Cancellation is a separate owner-thread integration contract. */
public record InteractionNotice(NpcHandle npc,UUID viewer,Action action,double distance,boolean preview,long sequence) {
    public enum Action { ATTACK, INTERACT }
    public InteractionNotice {Objects.requireNonNull(npc);Objects.requireNonNull(viewer);Objects.requireNonNull(action);if(!Double.isFinite(distance)||distance<0||sequence<0)throw new IllegalArgumentException("Invalid interaction notice");}
}
