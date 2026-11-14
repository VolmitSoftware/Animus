/// How the Modules tab names a module it has no form for: one closed row
/// each, so an imported NPC's carried-over settings do not fill the tab.
library;

/// Importers whose namespace marks the settings they carried over.
const Map<String, String> _importers = <String, String>{
  'citizens': 'Citizens',
  'shopkeepers': 'Shopkeepers',
  'denizen': 'Denizen',
};

/// The row title of a module without a form: where it came from when an
/// importer wrote it, else its key.
String unknownModuleTitle(String key) {
  final int colon = key.indexOf(':');
  final String? importer = colon < 0 ? null : _importers[key.substring(0, colon)];
  return importer == null ? key : 'Imported from $importer';
}

/// Whether module [key] is an importer's record of what it carried over
/// (`citizens:review`): bookkeeping nothing runs, never an advanced setting.
bool importedModule(String key) {
  final int colon = key.indexOf(':');
  return colon > 0 && _importers.containsKey(key.substring(0, colon));
}

/// How many settings a module holds: an object's keys, one for any other
/// value, none for null.
int moduleSettingCount(Object? value) => switch (value) {
      null => 0,
      final Map<String, Object?> map => map.length,
      _ => 1,
    };
