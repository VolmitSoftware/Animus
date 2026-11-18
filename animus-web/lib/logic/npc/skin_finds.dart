/// Whether this editor can ask the paired server for a skin by player name
/// or by MineSkin link, and why not when it cannot.
library;

/// Null when the paired server looks skins up; else one line saying why
/// the Player name and MineSkin rows are off. Capabilities not read yet
/// leave them on: the request itself says what went wrong.
String? skinFindAbsence({required bool paired, Map<String, Object?>? capabilities}) {
  if (!paired) return 'Player names and MineSkin links need a paired server.';
  if (capabilities == null) return null;
  final Object? features = capabilities['features'];
  final Object? find = features is Map<String, Object?> ? features['skins.find'] : null;
  if (find is! Map<String, Object?>) return "This server's Animus is too old to look up skins. Update it, then pair again.";
  if (find['available'] != true) {
    final Object? reason = find['reason'];
    return reason is String && reason.isNotEmpty ? 'The server cannot look up skins: $reason.' : 'The server cannot look up skins.';
  }
  return null;
}
