/// How the editor names a module: everyday modules by what they do
/// ("Looks at players") with a line for the picker, and a module it has no
/// form for in one closed row each, so an imported NPC's carried-over
/// settings do not fill the tab.
library;

/// Modules by what they do, with the line the module picker shows.
const Map<String, (String, String)> _named = <String, (String, String)>{
  'animus:look-close': ('Looks at players', 'Turns toward the nearest player in range.'),
  'animus:home': ('Returns home', 'Goes back home after standing idle away from it.'),
  'animus:riding': ('Players can ride it', 'A player right-clicks the NPC to ride it.'),
  'animus:mount': ('Rides on', 'Rides another NPC, or a horse, boat or minecart near it.'),
  'animus:seat': ('Seat', 'Players click the NPC to sit on top of it.'),
  'animus:schedule': ('Schedule', 'Walks to a place for each part of the Minecraft day.'),
  'animus:mirror': ('Mirrors the viewer', "Each player sees it with their own skin, name or equipment."),
  'animus:gui': ('Opens a GUI', 'A click opens a chest GUI, menu or shop, and a hologram can follow it.'),
  'animus:hire': ('Can be hired', 'Players hire it as a bodyguard for a price.'),
  'animus:navigation': ('Movement', 'Walks a route or replays a recording.'),
  'animus:combat': ('Combat', 'Who it fights and how.'),
  'animus:health': ('Health', 'Health, death and respawn.'),
  'animus:shop': ('Shop', 'A click opens a shop.'),
  'animus:skin': ('Skin options', "Where a player body's skin comes from."),
  'animus:modelengine': ('Model Engine model', 'Shows a Model Engine model in place of the body.'),
  'mythic:mob': ('MythicMobs mob', "Runs a MythicMobs mob's skills on the NPC."),
};

/// What the editor calls module [key]: its name, else [unknownModuleTitle].
String moduleName(String key) => _named[key]?.$1 ?? unknownModuleTitle(key);

/// One line on what module [key] does, for the picker; null for a module
/// without a name.
String? moduleDescription(String key) => _named[key]?.$2;

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
