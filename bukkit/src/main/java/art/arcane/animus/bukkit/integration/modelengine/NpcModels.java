package art.arcane.animus.bukkit.integration.modelengine;

import art.arcane.animus.core.integration.ModelEngineModule;
import java.util.Objects;
import java.util.UUID;

/**
 * Puts a Model Engine model on an NPC at runtime, over whatever its definition names, for integrations such as a
 * Mythic mob or a disguise that pick the model themselves. The override lasts until it is cleared or the plugin
 * stops; it is never written to the definition. Call on the server thread. Every call answers false and does nothing
 * while Model Engine is not running.
 */
public final class NpcModels {
    private record Hook(DefinitionModels models, ModeledTransport<?, ?> transport) {}
    private static volatile Hook hook;

    private NpcModels() {}

    /** Whether Model Engine is running and models can be shown. */
    public static boolean available() { return hook != null; }

    /** Shows {@code model} on the NPC in place of its definition's model, redrawing it for everyone who sees it. */
    public static boolean show(UUID npc, ModelEngineModule model) {
        Hook current = hook;
        if (current == null) return false;
        current.models().override(npc, model);
        current.transport().refresh(npc);
        return true;
    }

    /** Drops the override; the NPC goes back to its definition's model, or its plain body. */
    public static boolean clear(UUID npc) {
        Hook current = hook;
        if (current == null) return false;
        current.models().clear(npc);
        current.transport().refresh(npc);
        return true;
    }

    static void bind(DefinitionModels models, ModeledTransport<?, ?> transport) { hook = new Hook(Objects.requireNonNull(models), Objects.requireNonNull(transport)); }
    static void unbind(ModeledTransport<?, ?> transport) { Hook current = hook; if (current != null && current.transport() == transport) hook = null; }
}
