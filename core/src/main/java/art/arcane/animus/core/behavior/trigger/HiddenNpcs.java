package art.arcane.animus.core.behavior.trigger;

import java.util.UUID;

/**
 * Live NPCs a viewer must neither see nor be affected by, such as those an editor preview stands in for. While an NPC
 * is hidden from a viewer, that viewer fires none of its triggers and receives none of its speech, sounds or particles;
 * everyone else is unaffected. Implementations are thread-safe and cheap enough for every per-player check.
 */
@FunctionalInterface
public interface HiddenNpcs {
    HiddenNpcs NONE = (UUID viewer, String id, UUID npc) -> false;

    /** The live NPC with this definition id or UUID is hidden from the viewer. */
    boolean hides(UUID viewer, String id, UUID npc);
}
