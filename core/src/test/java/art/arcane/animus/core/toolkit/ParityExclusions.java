package art.arcane.animus.core.toolkit;

import java.util.List;

/**
 * NPC fields the property screens do not edit, each with the reason. An entry ending in {@code *} matches every path
 * that starts with what comes before it.
 */
final class ParityExclusions {
    static final List<String> PATHS = List.of(
            // Written by Animus itself: the format version, the save counter and the identity that never changes.
            "schemaVersion",
            "revision",
            "uuid",
            // The Folder screen moves NPCs between folders under npcs/.
            "group",
            // The Skins screen sets it from a player or a saved skin file.
            "skin",
            // Items: the Equipment screen sets worn and held items; stored inventory and hire costs stay in the web editor.
            "equipment.*",
            "inventory.slots.*",
            "modules.animus:hire.items[].*",
            // A hologram line with margins is an object; the screens edit plain lines and the web editor adds margins.
            "hologram.lines[].*",
            // The [min, max] form of a wait; the screens set a fixed number of ticks.
            "modules.animus:navigation.waitTicks[]",
            "modules.animus:navigation.points[].waitTicks[]",
            // Recording steps are written by the recorder, not by hand.
            "modules.animus:navigation.points[].fromTick",
            "modules.animus:navigation.points[].toTick",
            "modules.animus:navigation.points[].recording",
            "modules.animus:navigation.points[].playback",
            // Rule groups that must all match are lists of lists; single rules are edited in game.
            "modules.animus:combat.targets.together*",
            "modules.animus:combat.avoids.together*",
            // Kept from an import and never read, so there is nothing to edit.
            "modules.animus:combat.preservedSettings",
            "modules.animus:combat.historicalStatistics",
            "modules.animus:combat.migrationReport",
            "modules.animus:shop.imported");

    private ParityExclusions() {}

    static boolean excluded(String path) { return PATHS.stream().anyMatch((String entry) -> matches(entry, path)); }

    static boolean matches(String entry, String path) { return entry.endsWith("*") ? path.startsWith(entry.substring(0, entry.length() - 1)) : entry.equals(path); }
}
