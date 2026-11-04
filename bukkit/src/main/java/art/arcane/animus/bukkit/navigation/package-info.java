/**
 * Bounded experimental navigation capture for Bukkit/Paper. Construct the bridge on the main server
 * thread; request planning using a world UUID and primitive capture bounds. The bridge samples at most
 * 256 work units per tick across at most eight total capturing/queued/running jobs, and captures at most
 * 8192 voxels per job. Loaded-chunk checks precede every block read. Unavailable cells remain unknown.
 * The worker receives only an immutable voxel snapshot. Folia is rejected until region-aware capture
 * and ownership transfer exist.
 *
 * <p>Captures span multiple ticks and are not atomic snapshots of the live world. The plan handle exposes
 * capture provenance. Consumers must revalidate the next movement segment on its owning scheduler
 * before execution; this package supplies path previews, not navigation execution. Completion callbacks
 * may run on any thread. Use the handle's cancel method, and close the bridge during plugin shutdown.
 *
 * <p>Air and ordinary water/lava are classified explicitly. Every other material is conservatively a
 * closed full voxel, including plants, partial blocks, open doors, waterlogged blocks, and passable
 * decorations. This avoids unsafe collision-shape queries but can reject otherwise traversable routes
 * and falsely suggest support atop plants or partial blocks. These previews do not prove traversability:
 * both support and clearance require live geometry validation before any movement implementation.
 */
package art.arcane.animus.bukkit.navigation;
