package art.arcane.animus.core.config;

import art.arcane.animus.core.behavior.catalog.BehaviorPath;
import java.util.List;

/** Built-in action budgets and the privileged command allow-list. Empty lists deny every command. Requires restart. */
public record BuiltinActionSettings(int actionsPerTick, int actionsPerNpcPerTick, int queuedActions, int pendingChoices,
                                    int choiceLifetimeSeconds, List<String> commandBehaviors, List<String> commandNamespaces) {
    public static final BuiltinActionSettings DEFAULT = new BuiltinActionSettings(32, 4, 256, 256, 60, List.of(), List.of());
    private static final int MAXIMUM_ALLOW_LIST = 256;

    public BuiltinActionSettings {
        if (actionsPerTick < 1 || actionsPerTick > 256) throw new IllegalArgumentException("actionsPerTick must be within 1..256");
        if (actionsPerNpcPerTick < 1 || actionsPerNpcPerTick > 64 || actionsPerNpcPerTick > actionsPerTick)
            throw new IllegalArgumentException("actionsPerNpcPerTick must be within 1..64 and must not exceed actionsPerTick");
        if (queuedActions < 1 || queuedActions > 1024) throw new IllegalArgumentException("queuedActions must be within 1..1024");
        if (pendingChoices < 1 || pendingChoices > 4096) throw new IllegalArgumentException("pendingChoices must be within 1..4096");
        if (choiceLifetimeSeconds < 1 || choiceLifetimeSeconds > 600) throw new IllegalArgumentException("choiceLifetimeSeconds must be within 1..600");
        commandBehaviors = List.copyOf(commandBehaviors);
        commandNamespaces = List.copyOf(commandNamespaces);
        if (commandBehaviors.size() > MAXIMUM_ALLOW_LIST || commandNamespaces.size() > MAXIMUM_ALLOW_LIST)
            throw new IllegalArgumentException("Command allow-lists accept at most " + MAXIMUM_ALLOW_LIST + " entries");
        for (String behavior : commandBehaviors) BehaviorPath.forId(behavior);
        for (String namespace : commandNamespaces)
            if (!namespace.matches("[a-z0-9_-]{1,64}")) throw new IllegalArgumentException("commandNamespaces expects namespace names");
    }

    public boolean commandAllowed(String behavior) {
        if (behavior == null) return false;
        if (commandBehaviors.contains(behavior)) return true;
        int separator = behavior.indexOf(':');
        return separator > 0 && commandNamespaces.contains(behavior.substring(0, separator));
    }
}
