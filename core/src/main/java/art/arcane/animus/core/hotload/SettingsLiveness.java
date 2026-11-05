package art.arcane.animus.core.hotload;

import java.util.Set;

/**
 * Which {@code config.toml} keys still need a restart after a hotload. Every other key applies live: each service
 * takes the new value on its own owner thread by its next tick. The one exception is {@code npcs.enabled} on Folia,
 * where NPCs and viewers retire on their own regions in no fixed order, so the runtime cannot safely stop and start
 * again in place. Changing a module jar also needs a restart, but that is a file, not a key.
 */
public final class SettingsLiveness {
    private static final Set<String> RESTART_WITHOUT_LIVE_SWITCH = Set.of("npcs.enabled");

    private SettingsLiveness() {}

    /** @param liveSwitch whether the NPC runtime turns itself on and off in place (false on Folia) */
    public static boolean restartRequired(String key, boolean liveSwitch) {
        return !liveSwitch && RESTART_WITHOUT_LIVE_SWITCH.contains(key);
    }
}
