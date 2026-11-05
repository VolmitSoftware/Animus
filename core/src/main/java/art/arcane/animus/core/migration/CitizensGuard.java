package art.arcane.animus.core.migration;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Holds imported Citizens NPCs while the Citizens plugin itself is enabled, so the same NPC never runs in both plugins.
 * The Bukkit platform installs its check at startup and removes it when it stops; mod servers never have Citizens.
 */
public final class CitizensGuard {
    private static volatile BooleanSupplier citizensEnabled = () -> false;

    private CitizensGuard() {}

    public static void install(BooleanSupplier check) { citizensEnabled = Objects.requireNonNull(check, "check"); }
    public static void clear() { citizensEnabled = () -> false; }

    /** True when an imported definition must stay despawned because Citizens is running. */
    public static boolean holds(boolean imported) { return imported && citizensEnabled.getAsBoolean(); }
}
