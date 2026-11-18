/// What a schema `pattern` asks for, in words. A field's problem reads
/// "Item: use a namespaced id such as minecraft:bread", never the regular
/// expression. `test/pattern_words_test.dart` fails on a schema pattern
/// this table lacks.
library;

const String _uuidExample = '069a79f4-44e9-4726-a5be-fca90e38aaf5';
const String _uuid = r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';
const String _namespacedId = 'use a namespaced id such as minecraft:bread';
const String _blockState = 'use a block such as minecraft:oak_stairs[facing=north]';
const String _bracketed = 'write it in square brackets, such as [enchantment_glint_override=true]';

const Map<String, String> _words = <String, String>{
  r'\S': "can't be blank",
  r'[^\s/]': "can't be blank",
  r'^(?!.*\.\.)(?!.*[/\\])\S.*$': 'use a name without slashes or .., not starting with a space',
  r'^[a-z0-9_.*:/-]{1,128}(,[a-z0-9_.*:/-]{1,128}){0,7}$': 'use lowercase ids such as minecraft:bread, * for any text, and commas between up to 8',
  r'^([a-z0-9_.-]+:)?[a-z0-9_./-]+$': _namespacedId,
  r'^[a-z0-9_.-]+:[a-z0-9_./-]+$': _namespacedId,
  r'^[a-z0-9._-]+:[a-z0-9/._-]+$': 'use a world key such as minecraft:overworld',
  r'^([a-z0-9_]+:)?[a-z0-9_/]+$': 'use an entity type such as minecraft:villager',
  r'^([a-z0-9_.-]+:)?[a-z0-9_./-]+(\[[a-z0-9_]+=[a-z0-9_]+(,[a-z0-9_]+=[a-z0-9_]+)*\])?$': _blockState,
  r'^([a-z0-9_.-]+:)?[a-z0-9_./-]+(\[[^\]]*\])?$': _blockState,
  r'^([0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}|[a-z0-9_.-]+:[a-z0-9_./-]+)$': 'use an entity UUID, or a type such as minecraft:horse',
  _uuid: 'expected a UUID such as $_uuidExample',
  r'^[0-9a-fA-F-]{36}$': 'expected a UUID such as $_uuidExample',
  r'^[a-f0-9]{64}$': 'expected a skin id: 64 characters of 0 to 9 and a to f',
  r'^sha256:[0-9a-f]{64}$': 'expected sha256: and 64 characters of 0 to 9 and a to f',
  r'^[a-f0-9]{32}$': 'expected 32 characters of 0 to 9 and a to f',
  r'^[a-z0-9][a-z0-9_-]{0,63}$': 'use a lowercase name of up to 64 letters, digits, _ or -, starting with a letter or digit',
  r'^[a-z0-9][a-z0-9_-]{0,31}$': 'use a lowercase name of up to 32 letters, digits, _ or -',
  r'^[a-z0-9][a-z0-9._-]{0,63}$': 'use a lowercase name of up to 64 letters, digits, ., _ or -',
  r'^[a-z0-9][a-z0-9_.:-]{0,63}$': 'use a lowercase name of up to 64 letters, digits, ., :, _ or -',
  r'^[a-z0-9_-]{1,64}$': 'use up to 64 lowercase letters, digits, _ or -',
  r'^[a-z0-9_-]{1,32}$': 'use up to 32 lowercase letters, digits, _ or -',
  r'^[a-z0-9-]{1,64}$': 'use up to 64 lowercase letters, digits or -',
  r'^[a-z][a-z0-9_]*$': 'use lowercase letters, digits and _, starting with a letter',
  r'^[a-z][a-z0-9_.]*$': 'use lowercase letters, digits, _ and ., starting with a letter',
  r'^[a-z0-9_.:/-]+$': 'use lowercase letters, digits and _ . : / -',
  r'^[a-z0-9_-]{1,64}:[a-z0-9_-]{1,64}(/[a-z0-9_-]{1,64}){0,14}$': 'use a lowercase namespace:path such as town:greeter',
  r'^[a-z0-9_-]{1,64}:[a-z0-9_/-]{1,191}$': 'use a lowercase namespace:path such as shop:baker',
  r'^[a-z0-9_-]+:[a-z0-9_/-]+$': 'use a lowercase namespace:path such as town:sign',
  r'^[a-z0-9][a-z0-9_-]{0,63}:[a-z0-9][a-z0-9_-]{0,63}$': 'use a lowercase namespace:name such as mymodule:wave',
  r'^[a-z0-9_-]{1,64}:[a-z0-9][a-z0-9_-]{0,63}$': 'use a lowercase namespace:name such as town:delivery',
  r'^[a-z0-9][a-z0-9_-]{0,63}(/[a-z0-9][a-z0-9_-]{0,63}){0,7}$': 'use a lowercase folder such as town or town/market',
  r'^(?:[a-z0-9][a-z0-9_-]{0,63}(?:/[a-z0-9][a-z0-9_-]{0,63}){0,7})?$': 'use a lowercase folder such as town or town/market, or leave it empty',
  r'^[A-Za-z0-9_-]{1,64}$': 'use up to 64 letters, digits, _ or -',
  r'^[A-Za-z0-9_.-]{1,64}$': 'use up to 64 letters, digits, ., _ or -',
  r'^[A-Za-z0-9_.+-]{1,64}$': 'use up to 64 letters, digits, ., +, _ or -',
  r'^[A-Za-z0-9_]{1,64}$': 'use up to 64 letters, digits or _',
  r'^[A-Za-z0-9_./-]{1,64}$': 'use up to 64 letters, digits, ., /, _ or -',
  r"^[A-Za-z0-9_,'+/-]{1,64}$": "use a region name of up to 64 letters, digits and _ , ' + / -",
  r'^[A-Za-z0-9][A-Za-z0-9._/-]{0,254}$': 'use letters, digits, ., /, _ or -, starting with a letter or digit',
  r'^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$': 'use a server name of up to 64 letters, digits, ., _ or -',
  r'^[a-zA-Z]{1,32}$': 'use up to 32 letters',
  r'^[A-Za-z0-9_-]{8,64}$': 'expected 8 to 64 letters, digits, _ or -',
  r'^[A-Za-z0-9_-]{16,128}$': 'expected 16 to 128 letters, digits, _ or -',
  r'^[A-Za-z0-9_-]{43}$': 'expected 43 letters, digits, _ or -',
  r'^[A-Za-z0-9_-]{86}$': 'expected 86 letters, digits, _ or -',
  r'^[A-Za-z0-9_-]+(\.[A-Za-z0-9_*-]+)*$': 'use a permission node such as animus.use',
  r'^[A-Za-z0-9_.*-]{1,128}$': 'use a permission node such as animus.use, * for any',
  r'^[a-z0-9_-]+(\.[a-z0-9_-]+)*$': 'use a lowercase permission node such as animus.seat',
  r'^[A-Za-z0-9_.-]{1,128}$': 'use a permission node such as animus.fight',
  r'^[a-z0-9_.-]{1,128}$': 'use a lowercase permission node such as shop.hire',
  r'^(factions|factionsally|factionsenemy|nation|nationallies|nationenemies|sbteam|towny):[^\s:]{1,64}$': 'use a team such as towny:Capital or factions:Wolves',
  r'^[^\s/\\]{1,64}$': 'use a world name of up to 64 characters without spaces or slashes',
  r'^[^/]': 'write it without the leading /',
  r'^[^/].*$': 'write the command without the leading /',
  r'^\[[\s\S]*\]$': _bracketed,
  r'^\[.*\]$': _bracketed,
  r'^#[0-9A-Fa-f]{8}$': 'use a colour with alpha such as #FF2A2A2A',
  r'^#[0-9a-fA-F]{8}$': 'use a colour with alpha such as #FF2A2A2A',
  r'^#[0-9A-Fa-f]{6}$': 'use a colour such as #FFAA00',
  r'^[0-9]{1,2}$': 'expected a number of one or two digits',
  r'^(0|[1-9][0-9]?)$': 'expected a whole number from 0 to 99',
  r'^[0-9]+$': 'expected digits only',
  r'^\d+\.\d+(\.\d+)?$': 'expected a version such as 26.3',
  r'^\d{4}-\d{2}-\d{2}T': 'expected a date and time such as 2026-11-17T09:00',
  r'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$': 'expected a time such as 2026-11-17T09:00:00Z',
  r'^bezier\(\s*(0(\.\d+)?|1(\.0+)?)\s*,\s*-?\d+(\.\d+)?\s*,\s*(0(\.\d+)?|1(\.0+)?)\s*,\s*-?\d+(\.\d+)?\s*\)$':
      'use bezier(x1, y1, x2, y2) with x1 and x2 from 0 to 1',
  r'^[A-Za-z0-9+/]*={0,2}$': 'expected base64 data',
  r'^[A-Za-z0-9+/=]{1,40000}$': 'expected a base64 PNG',
  r'^https?://\S{1,248}$': 'expected an http or https address',
  r'^https://textures\.minecraft\.net/texture/[0-9a-f]{1,128}$': 'expected a texture address on textures.minecraft.net',
  r'^(https://|http://127\.0\.0\.1(:\d+)?(/|$)|http://localhost(:\d+)?(/|$))': 'expected an https address, or http on this computer only',
  r'^(wss://|ws://127\.0\.0\.1(:\d+)?(/|$)|ws://localhost(:\d+)?(/|$))': 'expected a wss address, or ws on this computer only',
  r'^anm_[A-Za-z0-9_-]{8,64}\.[A-Za-z0-9_-]{43}$': 'expected an editor token starting with anm_',
  r'^\$': r'expected a field location starting with $',
  r'^/v1/': 'expected an address starting with /v1/',
  r'^animus:(flags|quests)(/|$)': 'expected an id starting with animus:flags or animus:quests',
  r'^animus:': 'expected an id starting with animus:',
};

/// Patterns with words, for tests.
Iterable<String> get knownPatterns => _words.keys;

bool patternHasWords(String pattern) => _words.containsKey(pattern);

/// Fields whose UUID names a player, and those whose UUID names an NPC.
const Set<String> _playerKeys = <String>{'owner', 'owners', 'player', 'players', 'admin', 'recordedBy'};
const Set<String> _npcKeys = <String>{'npc', 'npcs', 'clickRedirect', 'vehicle'};

/// The last name in a JSON path: `uuid` for `$.owner.uuid`, `npc` for
/// `$.guard.npc`, `players` for `$.players[2]`.
List<String> _names(String path) => RegExp(r'[A-Za-z_][A-Za-z0-9_]*').allMatches(path.replaceAll(RegExp(r'\[[^\]]*\]'), '')).map((Match m) => m[0]!).toList();

/// The problem with a text that does not match [pattern] at [path], as it
/// reads after the field's label.
String patternProblem(String pattern, String path) {
  if (pattern == _uuid) {
    final List<String> names = _names(path);
    final String last = names.isEmpty ? '' : names.last;
    final String owner = last == 'uuid' && names.length > 1 ? names[names.length - 2] : last;
    if (_playerKeys.contains(owner)) return 'expected a player UUID';
    if (_npcKeys.contains(owner)) return 'expected an NPC UUID';
  }
  return _words[pattern] ?? 'has characters this field does not take';
}
