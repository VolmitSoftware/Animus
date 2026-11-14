/// Whether the paired server's terrain can be read, and if not, why, in
/// words the stage shows over its grass patch.
library;

/// Null when the server shares terrain and the token may read it; else
/// what is missing and what to do about it. [elsewhere]: this tab waits
/// while another one holds the server's connection.
String? terrainAbsence({required bool online, required Map<String, Object?>? capabilities, required List<String> scopes, bool elsewhere = false}) {
  if (elsewhere) return 'Live editing runs in another tab; terrain shows there.';
  if (!online) return 'Terrain shows once the server is connected.';
  final Object? features = capabilities?['features'];
  final Object? terrain = features is Map<String, Object?> ? features['terrain'] : null;
  if (terrain is! Map<String, Object?>) return "This server's Animus does not share terrain yet. Update Animus on the server, then pair again.";
  if (terrain['available'] != true) {
    final Object? reason = terrain['reason'];
    return 'The server does not share terrain${reason is String && reason.isNotEmpty ? ': $reason' : ''}.';
  }
  if (!scopes.contains('terrain')) {
    return 'This pairing has no terrain scope, so the stage cannot show the server\'s blocks. '
        'Run /animus editor pair again and open the new link; its token includes terrain.';
  }
  return null;
}

/// What a stage knows about its terrain right now.
final class TerrainFacts {
  const TerrainFacts({
    this.absence = '',
    this.connection,
    this.failure,
    this.loading = false,
    this.worldUnloaded = false,
    this.chunks = 0,
    this.unloaded = 0,
    this.forbidden = 0,
    this.truncated = false,
    this.fullStates = true,
  });

  /// [terrainAbsence]'s sentence; empty when terrain can be read or no
  /// server is paired.
  final String absence;

  /// The connection's state while the server is not connected, in the
  /// words the top bar's badge uses ("Reconnecting", "Offline").
  final String? connection;

  /// Why the last load failed; the stage does not retry it on its own.
  final String? failure;
  final bool loading;

  /// The server answered `world-unloaded` for the stage's world.
  final bool worldUnloaded;

  /// Chunk slices asked for, and how many came back unloaded (no player
  /// near) or forbidden (outside the token's terrain radius).
  final int chunks;
  final int unloaded;
  final int forbidden;
  final bool truncated;
  final bool fullStates;
}

/// A stage's terrain in one short [line] for under the canvas (null when
/// all is well) and the [detail] sentences for its tip.
typedef TerrainLine = ({String? line, String? detail});

/// [facts] in words; [near] names what to stand near ("this NPC", "this
/// route"). Null when there is nothing to say.
TerrainLine? terrainLine(TerrainFacts facts, {String near = 'this NPC'}) {
  if (facts.absence.isNotEmpty) {
    final String line = facts.absence.contains('terrain scope')
        ? 'Terrain needs a new pairing'
        : facts.absence.contains('another tab')
            ? 'In another tab'
            : facts.absence.contains('connected')
            ? facts.connection ?? 'Not connected'
            : 'Server shares no terrain';
    return (line: line, detail: facts.absence);
  }
  if (facts.failure != null) return (line: 'Terrain did not load', detail: facts.failure);
  if (facts.loading) return (line: 'Loading terrain', detail: null);
  final List<String> details = <String>[
    if (facts.truncated) 'Terrain shows along the first ${facts.chunks} chunks; the rest is too far for one view.',
    if (!facts.fullStates) "This site's model pack has no block states, so stairs, slabs and fences show their default shape.",
  ];
  final String? line = facts.worldUnloaded
      ? 'This world is not loaded on the server.'
      : facts.unloaded > 0
          ? 'Stand near $near in game to load its terrain.'
          : facts.forbidden > 0
              ? 'Part of the terrain is out of reach'
              : null;
  if (facts.unloaded > 0 && !facts.worldUnloaded) details.insert(0, '${facts.unloaded} of ${facts.chunks} chunks are empty: the server holds only chunks a player is near.');
  if (facts.forbidden > 0) details.insert(0, '${facts.forbidden} of ${facts.chunks} chunks are outside the terrain radius this pairing may read.');
  if (line == null && details.isEmpty) return null;
  return (line: line, detail: details.isEmpty ? null : details.join(' '));
}
