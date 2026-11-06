import '../denizen/denizen_converter.dart';

/// A picked Denizen data folder sorted into what the converter reads, and
/// the files it leaves out.
class DenizenFolder {
  const DenizenFolder(this.input, this.skipped);

  final DenizenInput input;

  /// Picked files the converter does not read, by their picked path.
  final List<String> skipped;

  int get scripts => input.scripts.length;

  bool get empty => input.scripts.isEmpty && input.serverFlags == null && input.playerFlags.isEmpty;
}

/// Sorts files picked from a folder (paths as the browser gives them, such
/// as `Denizen/scripts/town.dsc`) the way `DenizenImporter.read` does: every
/// `.dsc` and `.yml` under `scripts/`, `server_flags.dat`, and the
/// `player_flags/<uuid>.dat` files. [citizens] is the Citizens save text the
/// assignments bind to.
DenizenFolder denizenFolderOf(Map<String, String> files, {String? citizens}) {
  final Map<String, String> scripts = <String, String>{};
  final Map<String, String> players = <String, String>{};
  String? serverFlags;
  final List<String> skipped = <String>[];
  for (final MapEntry<String, String> file in files.entries) {
    final List<String> parts = file.key.replaceAll(r'\', '/').split('/');
    final int scriptsAt = parts.indexOf('scripts');
    final String name = parts.last;
    if (scriptsAt >= 0 && scriptsAt < parts.length - 1 && (name.endsWith('.dsc') || name.endsWith('.yml'))) {
      scripts[parts.sublist(scriptsAt + 1).join('/')] = file.value;
    } else if (name == 'server_flags.dat' && !parts.contains('player_flags')) {
      serverFlags = file.value;
    } else if (parts.length >= 2 && parts[parts.length - 2] == 'player_flags' && name.endsWith('.dat')) {
      players[name.substring(0, name.length - 4)] = file.value;
    } else {
      skipped.add(file.key);
    }
  }
  skipped.sort();
  return DenizenFolder(DenizenInput(scripts: scripts, citizens: citizens, serverFlags: serverFlags, playerFlags: players), skipped);
}
