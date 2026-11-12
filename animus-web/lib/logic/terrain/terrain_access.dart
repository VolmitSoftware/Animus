/// Whether the paired server's terrain can be read, and if not, why, in
/// words the stage shows over its grass patch.
library;

/// Null when the server shares terrain and the token may read it; else
/// what is missing and what to do about it.
String? terrainAbsence({required bool online, required Map<String, Object?>? capabilities, required List<String> scopes}) {
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
