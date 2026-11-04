package art.arcane.animus.core.behavior.builtin;

import art.arcane.animus.api.NpcHandle;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A validated click: only the prompted player, on a live prompt, near the NPC that asked. */
public record DialogueChoice(NpcHandle npc, UUID player, Optional<String> behavior, String prompt, String option) {
    public DialogueChoice {
        Objects.requireNonNull(npc);
        Objects.requireNonNull(player);
        Objects.requireNonNull(behavior);
        Objects.requireNonNull(prompt);
        Objects.requireNonNull(option);
    }
}
