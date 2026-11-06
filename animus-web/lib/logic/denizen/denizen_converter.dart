/// A line-for-line copy of core's `DenizenConverter`: Denizen scripts and flags become Animus behaviors in the
/// `denizen` namespace and behavior state, with the same staged bytes as the server. Everything that does not convert
/// is listed in the report with its reason. See wiki animus/13-migrating-from-citizens.
library;

import 'dart:collection';
import 'dart:typed_data';

import 'package:archive/archive.dart';

import '../citizens/citizens_converter.dart' show importOutputHash;
import '../citizens/java_json.dart';
import '../citizens/java_lang.dart';
import '../citizens/snake_yaml.dart';
import 'behavior_limits.dart';
import 'denizen_args.dart';
import 'denizen_flags.dart';
import 'denizen_scripts.dart';

const int denizenConverterVersion = 1;
const String _namespace = 'denizen';
const int denizenMaximumFiles = 512;
const int denizenMaximumPlayers = 10000;
const int _maximumStateKeys = 128;
const int _maximumText = 1024;
const int _defaultRadius = 5;
const String _stateRoot = 'state/behavior-state/';

final RegExp _uuid = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');
final RegExp _name = RegExp(r'^[a-z0-9][a-z0-9_-]{0,63}$');
final RegExp _javaSpaces = RegExp('[ \t\n\x0B\f\r]+');

class DenizenImportException implements Exception {
  const DenizenImportException(this.message);

  final String message;

  @override
  String toString() => message;
}

/// What a Denizen data folder holds: scripts by path under `scripts/`, the Citizens save it binds to, and the flags.
class DenizenInput {
  DenizenInput({Map<String, String>? scripts, this.citizens, this.serverFlags, Map<String, String>? playerFlags})
      : scripts = SplayTreeMap<String, String>.of(scripts ?? <String, String>{}),
        playerFlags = SplayTreeMap<String, String>.of(playerFlags ?? <String, String>{});

  final SplayTreeMap<String, String> scripts;
  final String? citizens;
  final String? serverFlags;
  final SplayTreeMap<String, String> playerFlags;
}

/// A converted folder: [files] maps each path under `imports/denizen/<id>/` to its text.
class DenizenPreview {
  const DenizenPreview(this.id, this.files, this.report, this.input);

  final String id;
  final SplayTreeMap<String, String> files;
  final JObject report;
  final DenizenInput input;

  int _count(String key) => (report.get(key)! as JInt).value.toInt();
  int get behaviors => _count('behaviors');
  int get bindings => report.object('bindings')!.length;
  int get preserved => (report.get('preserved')! as JArray).length;
  int get flagsPreserved => (report.object('flags')!.get('preserved')! as JInt).value.toInt();
  int get flagsImported {
    int total = 0;
    for (final JElement count in report.object('flags')!.object('imported')!.members.values) {
      total += (count as JInt).value.toInt();
    }
    return total;
  }

  /// The files to unzip into the data folder: the Denizen folder under `imports/denizen/` and the staged tree.
  Map<String, Uint8List> bundle() => <String, Uint8List>{
        for (final MapEntry<String, String> script in input.scripts.entries) 'imports/denizen/scripts/${script.key}': javaUtf8(script.value),
        if (input.serverFlags != null) 'imports/denizen/server_flags.dat': javaUtf8(input.serverFlags!),
        for (final MapEntry<String, String> player in input.playerFlags.entries) 'imports/denizen/player_flags/${player.key}.dat': javaUtf8(player.value),
        for (final MapEntry<String, String> file in files.entries) 'imports/denizen/$id/${file.key}': javaUtf8(file.value),
      };

  /// `ImportDigest.outputHash` over the staged tree.
  String outputHash() => importOutputHash(<String, Uint8List>{
        for (final MapEntry<String, String> file in files.entries) file.key: javaUtf8(file.value),
      });
}

/// The data-folder bundle as a reproducible zip: entries in path order, stored, one fixed timestamp.
Uint8List denizenBundleZip(DenizenPreview preview) {
  final Map<String, Uint8List> files = preview.bundle();
  final Archive archive = Archive();
  for (final String path in files.keys.toList()..sort()) {
    archive.add(ArchiveFile.bytes(path, files[path]!)
      ..lastModTime = 315532800
      ..compression = CompressionType.none);
  }
  return Uint8List.fromList(ZipEncoder().encodeBytes(archive));
}

DenizenPreview convertDenizen(DenizenInput input) => _Converter().run(input);

enum _Kind { neutral, boolean, integer, other }

class _Npc {
  const _Npc(this.key, this.id, this.uuid, this.name, this.assignments, this.triggers, this.flags, this.problems);

  final String key;
  final String id;
  final String uuid;
  final String name;
  final List<String> assignments;
  final JObject triggers;
  final SplayTreeMap<String, String> flags;
  final List<String> problems;
}

class _Outcome {
  const _Outcome(this.file, this.line, this.container, this.kind, this.name, this.text, this.converted, this.reason);

  final String file;
  final int line;
  final String container;
  final String kind;
  final String name;
  final String text;
  final bool converted;
  final String reason;
}

class _Block {
  const _Block(this.steps, this.stops);

  final List<JObject> steps;
  final bool stops;
}

class _Chain {
  const _Chain(this.node, this.tail, this.stops);

  final JObject node;
  final bool tail;
  final bool stops;
}

typedef _Condition = JObject Function(JObject then, JObject? otherwise);

class _Interact {
  const _Interact(this.container, this.slug, this.quest, this.steps, this.order, this.defaultIndex);

  final DenizenContainer container;
  final String slug;
  final String quest;
  final List<String> steps;
  final List<String> order;
  final int defaultIndex;

  int? stage(String name) {
    final String wanted = _stepName(name);
    for (int index = 0; index < steps.length; index++) {
      if (_stepName(steps[index]) == wanted) return index;
    }
    return null;
  }
}

String _stepName(String name) => javaTrim(name.toLowerCase().replaceAll('*', ''));

class _Scope {
  _Scope(this.routines, this.calling, this.npcName, this.record, this.container, this.interact, this.step);

  final LinkedHashMap<String, JObject> routines;
  final Set<String> calling;
  final String? npcName;
  final bool record;
  final DenizenContainer container;
  final _Interact? interact;
  final int step;

  _Scope inContainer(DenizenContainer other) => _Scope(routines, calling, npcName, false, other, null, -1);
}

class _Converted {
  const _Converted(this.steps, this.reason);

  _Converted.of(List<JObject> steps) : this(steps, '');
  const _Converted.kept(String reason) : this(const <JObject>[], reason);

  final List<JObject> steps;
  final String reason;
}

class _Mapped {
  const _Mapped(this.value, this.reason);

  final String? value;
  final String? reason;
}

class _Result {
  const _Result(this.value, this.reason);

  final _Condition? value;
  final String? reason;
}

const Map<String, String> _particleNames = <String, String>{
  'villager_happy': 'happy_villager', 'villager_angry': 'angry_villager', 'spell_witch': 'witch',
  'smoke_normal': 'smoke', 'smoke_large': 'large_smoke', 'explosion_normal': 'poof',
  'explosion_large': 'explosion', 'explosion_huge': 'explosion_emitter', 'water_splash': 'splash',
  'water_bubble': 'bubble', 'water_wake': 'fishing', 'suspended': 'underwater',
  'suspended_depth': 'underwater', 'crit_magic': 'enchanted_hit', 'spell': 'effect',
  'spell_instant': 'instant_effect', 'drip_water': 'dripping_water', 'drip_lava': 'dripping_lava',
  'enchantment_table': 'enchant', 'town_aura': 'mycelium', 'snowball': 'item_snowball',
  'snow_shovel': 'item_snowball', 'slime': 'item_slime', 'fireworks_spark': 'firework',
  'totem': 'totem_of_undying', 'mob_appearance': 'elder_guardian', 'water_drop': 'rain',
};
const Set<String> _dataParticles = <String>{
  'dust', 'redstone', 'block', 'block_crack', 'block_dust', 'item', 'item_crack', 'falling_dust', 'dust_color_transition',
  'vibration', 'entity_effect', 'spell_mob', 'spell_mob_ambient', 'block_marker', 'sculk_charge', 'shriek', 'dust_pillar', 'trail', 'legacy_block_marker',
};
const Set<String> _scriptTypes = <String>{'task', 'procedure', 'command', 'assignment', 'interact', 'world'};
const Map<String, String> _escapes =<String, String>{
  '&nl': '\n', 'n': '\n', '&sp': ' ', '&co': ':', '&sc': ';', '&pipe': '|',
  '&lt': '<', '&gt': '>', '&amp': '&', '&lb': '[', '&rb': ']', '&dq': '"',
  '&quo': '"', '&sq': "'", '&pc': '%', '&ns': '#', '&hash': '#', '&at': '@',
  '&dot': '.', '&cm': ',', '&exc': '!', '&ss': '§', '&bs': r'\', '&fs': '/',
  '&lc': '{', '&rc': '}', '&eq': '=', '&nbsp': ' ', '&hrt': '❤',
};
const Map<String, String> _colors = <String, String>{
  'black': '0', 'dark_blue': '1', 'dark_green': '2', 'dark_aqua': '3', 'dark_red': '4',
  'dark_purple': '5', 'gold': '6', 'gray': '7', 'dark_gray': '8', 'blue': '9',
  'green': 'a', 'aqua': 'b', 'red': 'c', 'light_purple': 'd', 'yellow': 'e',
  'white': 'f', 'magic': 'k', 'bold': 'l', 'strikethrough': 'm', 'underline': 'n',
  'italic': 'o', 'reset': 'r',
};

bool _full(String pattern, String text) => RegExp('^(?:$pattern)\$').hasMatch(text);

JObject _action(String capability, JObject arguments) => JObject()
  ..addString('type', 'action')
  ..addString('capability', capability)
  ..add('arguments', arguments);

JObject _plain(List<JObject> steps) {
  if (steps.length == 1) return steps.first;
  return JObject()
    ..addString('type', 'sequence')
    ..add('steps', JArray(<JElement>[...steps]));
}

/// One step, or a sequence; more than 128 steps nest in sequences of at most 128.
JObject _sequence(List<JObject> steps) {
  if (steps.length == 1) return steps.first;
  List<JObject> current = steps;
  while (current.length > 128) {
    final List<JObject> grouped = <JObject>[];
    for (int index = 0; index < current.length; index += 128) {
      grouped.add(_plain(current.sublist(index, index + 128 < current.length ? index + 128 : current.length)));
    }
    current = grouped;
  }
  return _plain(current);
}

JObject _conditionNode(String capability, JObject arguments, JObject then, JObject? otherwise) {
  final JObject node = JObject()
    ..addString('type', 'condition')
    ..addString('capability', capability)
    ..add('arguments', arguments)
    ..add('then', then);
  if (otherwise != null) node.add('else', otherwise);
  return node;
}

_Condition _atom(String capability, JObject arguments, bool negated) => (JObject then, JObject? otherwise) => negated
    ? _conditionNode(capability, arguments.deepCopy(), otherwise ?? _sequence(<JObject>[]), then)
    : _conditionNode(capability, arguments.deepCopy(), then, otherwise);

String _scopeOf(String target) => switch (target) { 'npc' => 'npc', 'server' => 'global', _ => 'player' };

String _raw(DenizenArg arg) => arg.prefix.isEmpty ? arg.value : '${arg.prefix}:${arg.value}';

String? _flagName(String name) {
  final String lowered = name.toLowerCase();
  if (!_full('[a-z0-9_.]+', lowered)) return null;
  final String mapped = lowered.replaceAll('.', '-');
  return _name.hasMatch(mapped) ? mapped : null;
}

/// `{target, name, attribute}` of a flag tag such as `<player.has_flag[name]>`.
List<String>? _flagTag(String token) {
  if (!token.startsWith('<') || !token.endsWith('>') || denizenTagEnd(token, 0) != token.length - 1) return null;
  final DenizenTag tag = denizenTag(token.substring(1, token.length - 1));
  final List<DenizenPart> parts = List<DenizenPart>.of(tag.parts);
  if (parts.length == 3 && parts[2].name == 'if_null') parts.removeLast();
  if (parts.length != 2 || parts.first.parameter != null) return null;
  final String target = parts.first.name.toLowerCase();
  final String attribute = parts[1].name.toLowerCase();
  if (!<String>['player', 'npc', 'server'].contains(target) || !<String>['flag', 'has_flag'].contains(attribute) || parts[1].parameter == null) return null;
  final String name = parts[1].parameter!;
  if (name.contains('<')) return null;
  return <String>[target, name.toLowerCase(), attribute];
}

_Kind _valueKind(String value) {
  if (value == '!') return _Kind.neutral;
  if (value.toLowerCase() == 'true' || value.toLowerCase() == 'false') return _Kind.boolean;
  if (value == '++' || value == '--' || value.startsWith('+:') || value.startsWith('-:') || value.startsWith('*:') || value.startsWith('/:') || _full('-?[0-9]+', value)) {
    return _Kind.integer;
  }
  return _Kind.other;
}

bool _playerTarget(String value) {
  final String lowered = value.toLowerCase();
  return lowered == '<player>' || lowered == 'player';
}

bool _npcTarget(String value) {
  final String lowered = value.toLowerCase();
  return lowered == '<npc>' || lowered == 'npc';
}

List<double>? _location(String value) {
  String text = value;
  if (text.toLowerCase().startsWith('l@')) text = text.substring(2);
  if (text.toLowerCase().startsWith('<location[') && text.endsWith(']>')) text = text.substring(10, text.length - 2);
  final List<String> parts = text.split(',');
  if (parts.length < 3 || parts.length > 6) return null;
  final List<double> coordinates = <double>[];
  for (int index = 0; index < 3; index++) {
    if (!_full(r'-?[0-9]{1,8}(?:\.[0-9]{1,6})?', parts[index])) return null;
    coordinates.add(javaParseDouble(parts[index]));
  }
  return coordinates;
}

double? _number(String value, double minimum, double maximum) {
  if (!_full(r'[0-9]{1,4}(?:\.[0-9]{1,6})?', value)) return null;
  final double number = javaParseDouble(value);
  return number < minimum || number > maximum ? null : number;
}

/// A Bukkit sound name such as `ENTITY_VILLAGER_YES` becomes `minecraft:entity.villager.yes`.
String? denizenSoundKey(String name, bool custom) {
  final String lowered = name.toLowerCase();
  final String key;
  if (lowered.contains(':')) {
    key = lowered;
  } else if (lowered.contains('.') || custom) {
    key = 'minecraft:$lowered';
  } else {
    final int first = lowered.indexOf('_');
    final int last = lowered.lastIndexOf('_');
    if (first < 0 || first == last) return null;
    key = 'minecraft:${lowered.substring(0, first)}.${lowered.substring(first + 1, last)}.${lowered.substring(last + 1)}';
  }
  return _full(r'[a-z0-9_.-]+:[a-z0-9_./-]+', key) ? key : null;
}

JObject _commandAction(String line) => _action('animus:command', JObject()..addString('command', line));

String _component(String text) => (JObject()..addString('text', text)).toString();

List<JObject> _copies(List<JObject> steps) => <JObject>[for (final JObject step in steps) step.deepCopy()];

List<JObject> _joined(_Block block, _Block rest) => <JObject>[...block.steps, if (!block.stops) ..._copies(rest.steps)];

bool _controlsLoop(DenizenSequence sequence) {
  for (final DenizenItem item in sequence.items) {
    final DenizenCommand command = denizenCommand(item.text);
    if (command.name == 'repeat' && item.children == null) return true;
    if (item.children != null && _controlsLoop(item.children!)) return true;
  }
  return false;
}

DenizenSequence? _scriptOf(DenizenNode node) {
  if (node is DenizenSequence) return node;
  if (node is DenizenMapping) {
    final DenizenNode? script = node.get('script');
    if (script is DenizenSequence) return script;
  }
  return null;
}

bool _reserve(LinkedHashMap<String, JObject> routines, String routine) {
  if (routines.containsKey(routine)) return true;
  if (routines.length >= behaviorMaximumRoutines) return false;
  routines[routine] = JObject();
  return true;
}

String _worldReason(String event) {
  if (event.contains('delta time') || event.contains('system time') || event.contains('server start')) {
    return 'Animus timers run per NPC; there is no server-wide timer or startup trigger';
  }
  if (event.contains('npc')) return 'this event covers every NPC; Animus binds a behavior to each NPC instead';
  return 'Animus has no trigger for this event; its triggers belong to NPCs';
}

String _statePath(String scope, String? npc, String? player, String kind) {
  final StringBuffer path = StringBuffer(_stateRoot)
    ..write(scope)
    ..write('/');
  if (npc != null) path.write('$npc/');
  if (player != null) path.write('$player/');
  path.write('animus/$kind/$_namespace.json');
  return path.toString();
}

String _stateDocument(String path, SplayTreeMap<String, JObject> values) {
  final List<String> parts = path.substring(_stateRoot.length).split('/');
  final String scope = parts[0];
  final JObject root = JObject()
    ..addInt('schemaVersion', 1)
    ..addString('behavior', 'animus:${parts[parts.length - 2]}/$_namespace')
    ..addString('scope', scope);
  if (scope == 'npc' || scope == 'relationship') root.addString('npc', parts[1]);
  if (scope == 'player') root.addString('player', parts[1]);
  if (scope == 'relationship') root.addString('player', parts[2]);
  final JObject stored = JObject();
  for (final MapEntry<String, JObject> value in values.entries) {
    stored.add(value.key, value.value);
  }
  root.add('values', stored);
  return writeJson(root);
}

class _Converter {
  final SplayTreeMap<String, DenizenContainer> containers = SplayTreeMap<String, DenizenContainer>();
  final SplayTreeMap<String, _Interact> interacts = SplayTreeMap<String, _Interact>();
  final SplayTreeMap<String, _Kind> kinds = SplayTreeMap<String, _Kind>();
  final SplayTreeMap<String, _Outcome> outcomes = SplayTreeMap<String, _Outcome>();
  final List<String> issues = <String>[];
  final SplayTreeMap<String, String> sounds = SplayTreeMap<String, String>();
  final JArray preserved = JArray();
  final SplayTreeMap<String, String> slugs = SplayTreeMap<String, String>();
  final Set<String> usedSlugs = <String>{};
  final List<_Npc> npcs = <_Npc>[];

  DenizenPreview run(DenizenInput input) {
    if (input.scripts.length > denizenMaximumFiles) throw const DenizenImportException('More than $denizenMaximumFiles Denizen script files');
    if (input.playerFlags.length > denizenMaximumPlayers) throw const DenizenImportException('More than $denizenMaximumPlayers Denizen player flag files');
    final JObject sources = JObject();
    for (final MapEntry<String, String> file in input.scripts.entries) {
      sources.addString('scripts/${file.key}', sha256Hex(file.value));
    }
    if (input.citizens != null) sources.addString('citizens', sha256Hex(input.citizens!));
    if (input.serverFlags != null) sources.addString('server_flags.dat', sha256Hex(input.serverFlags!));
    for (final MapEntry<String, String> file in input.playerFlags.entries) {
      sources.addString('player_flags/${file.key}.dat', sha256Hex(file.value));
    }
    final String id = sha256Hex('animus-denizen-v$denizenConverterVersion\n$sources');

    for (final MapEntry<String, String> file in input.scripts.entries) {
      final DenizenParsed parsed = parseDenizenScripts(file.key, file.value);
      issues.addAll(parsed.issues);
      for (final DenizenContainer container in parsed.containers) {
        final String key = container.name.toLowerCase();
        if (containers.containsKey(key)) {
          issues.add('${container.file}:${container.line}: script ${container.name} is defined again; the first definition in ${containers[key]!.file} is used');
          preserve(container.file, container.line, container.name, 'container', container.type, '', 'script name defined twice; Denizen also refuses duplicates');
          continue;
        }
        containers[key] = container;
      }
    }
    for (final DenizenContainer container in containers.values) {
      slug(container.name);
    }
    for (final DenizenContainer container in containers.values) {
      if (container.type == 'interact') interacts[container.name.toLowerCase()] = interact(container);
    }
    if (input.citizens != null) readNpcs(input.citizens!);

    final SplayTreeMap<String, DenizenFlagValues> playerValues = SplayTreeMap<String, DenizenFlagValues>();
    for (final MapEntry<String, String> file in input.playerFlags.entries) {
      playerValues[file.key] = denizenFlagFile(file.value);
    }
    final DenizenFlagValues? serverValues = input.serverFlags == null ? null : denizenFlagFile(input.serverFlags!);
    for (final DenizenContainer container in containers.values) {
      for (final DenizenEntry entry in container.body.entries) {
        scanKinds(entry.value);
      }
    }
    for (final DenizenFlagValues values in playerValues.values) {
      dataKinds(values);
    }
    if (serverValues != null) dataKinds(serverValues);
    for (final _Npc npc in npcs) {
      dataKinds(denizenFlagMapping(npc.flags));
    }

    final SplayTreeMap<String, String> files = SplayTreeMap<String, String>();
    final SplayTreeMap<String, List<String>> bindings = SplayTreeMap<String, List<String>>();
    final JObject behaviorStatus = JObject();
    final SplayTreeMap<String, String> documents = SplayTreeMap<String, String>();
    for (final DenizenContainer container in containers.values) {
      switch (container.type) {
        case 'task':
          task(container, documents);
        case 'assignment':
          assignment(container, documents);
        case 'interact':
          interactDocument(interacts[container.name.toLowerCase()]!, documents);
        case 'world':
          world(container);
        default:
          preserveContainer(
              container,
              switch (container.type) {
                'procedure' => 'procedure scripts return values to tags; behaviors have no expressions',
                'command' => 'behaviors do not register commands',
                'data' => 'data scripts hold values; literal values that converted text reads were copied into it',
                _ => 'no Animus equivalent for ${container.type} scripts',
              });
      }
    }
    for (final _Npc npc in npcs) {
      final List<String> bound = <String>[];
      for (final String assignment in npc.assignments) {
        final DenizenContainer? container = containers[assignment];
        if (container == null || container.type != 'assignment') {
          issues.add('${npc.id}: assignment $assignment is not an assignment script here');
          continue;
        }
        final String own = behaviorId('assignment', container);
        if (documents.containsKey(own) && !bound.contains(own)) bound.add(own);
        for (final DenizenContainer listed in interactList(container)) {
          final String other = behaviorId('interact', listed);
          if (documents.containsKey(other) && !bound.contains(other)) bound.add(other);
        }
      }
      for (final String problem in npc.problems) {
        issues.add('${npc.id}: $problem');
      }
      if (bound.isNotEmpty) bindings[npc.id] = bound;
    }
    for (final MapEntry<String, String> document in documents.entries) {
      files['behaviors/${document.key.replaceAll(':', '/')}.json'] = document.value;
    }

    final SplayTreeMap<String, SplayTreeMap<String, JObject>> state = SplayTreeMap<String, SplayTreeMap<String, JObject>>();
    final SplayTreeMap<String, int> imported = SplayTreeMap<String, int>();
    for (final String scope in <String>['global', 'npc', 'player', 'relationship']) {
      imported[scope] = 0;
    }
    int preservedFlags = 0;
    for (final MapEntry<String, DenizenFlagValues> player in playerValues.entries) {
      final String source = 'player_flags/${player.key}.dat';
      if (!_uuid.hasMatch(player.key)) {
        issues.add('$source: the file name is not a lowercase player UUID; its flags are kept');
        preservedFlags += player.value.flags.length;
        for (final DenizenFlag flag in player.value.flags.values) {
          preserve(source, 0, '', 'flag', flag.name, '', 'the file name is not a player UUID');
        }
        continue;
      }
      preservedFlags += importFlags(source, player.value, 'player', null, player.key, state, imported);
    }
    if (serverValues != null) preservedFlags += importFlags('server_flags.dat', serverValues, 'global', null, null, state, imported);
    for (final _Npc npc in npcs) {
      if (npc.flags.isEmpty) continue;
      preservedFlags += importFlags('citizens:${npc.id}', denizenFlagMapping(npc.flags), 'npc', npc.uuid, null, state, imported);
    }
    for (final MapEntry<String, SplayTreeMap<String, JObject>> document in state.entries) {
      files[document.key] = _stateDocument(document.key, document.value);
    }

    final JObject report = JObject()
      ..addInt('schemaVersion', 1)
      ..addInt('converterVersion', denizenConverterVersion)
      ..addString('importId', id)
      ..addString('notice', 'Data conversion only. Generated behaviors are listed on no NPC until the denizen review is accepted, and commands stay denied until allow-listed.')
      ..add('sources', sources)
      ..addInt('behaviors', documents.length)
      ..add('containers', containerCounts())
      ..add('commands', counts('command', 'converted', 'preserved'))
      ..add('events', counts('event', 'converted', 'preserved'))
      ..add('tags', tagCounts());
    final JObject importedCounts = JObject();
    for (final MapEntry<String, int> count in imported.entries) {
      importedCounts.addInt(count.key, count.value);
    }
    report.add('flags', JObject()
      ..add('imported', importedCounts)
      ..addInt('preserved', preservedFlags));
    final JObject bound = JObject();
    for (final MapEntry<String, List<String>> binding in bindings.entries) {
      bound.add(binding.key, JArray(<JElement>[for (final String behavior in binding.value) JString(behavior)]));
    }
    report.add('bindings', bound);
    final JObject npcUuids = JObject();
    for (final _Npc npc in npcs) {
      if (bindings.containsKey(npc.id)) npcUuids.addString(npc.id, npc.uuid);
    }
    report.add('npcUuids', npcUuids);
    for (final String document in documents.keys) {
      behaviorStatus.addString(document, partial(document) ? 'partial' : 'complete');
    }
    report.add('behaviorStatus', behaviorStatus);
    report.add('commandBehaviors', JArray(<JElement>[
      for (final MapEntry<String, String> document in documents.entries)
        if (document.value.contains('"animus:command"')) JString(document.key),
    ]));
    final JObject soundMap = JObject();
    for (final MapEntry<String, String> sound in sounds.entries) {
      soundMap.addString(sound.key, sound.value);
    }
    report
      ..add('sounds', soundMap)
      ..add('issues', JArray(<JElement>[for (final String issue in issues) JString(issue)]))
      ..add('preserved', preserved);
    final JObject manifest = JObject();
    for (final MapEntry<String, String> file in files.entries) {
      manifest.addString(file.key, sha256Hex(file.value));
    }
    report.add('documents', manifest);
    files['report.json'] = writeJson(report);
    return DenizenPreview(id, files, report, input);
  }

  // ---- Citizens NPCs ----

  void readNpcs(String text) {
    final JObject root;
    try {
      root = parseCitizensYaml(text);
    } on CitizensImportException catch (invalid) {
      throw DenizenImportException(invalid.message);
    }
    final JElement? npcValue = root.get('npc');
    if (npcValue is! JObject) throw const DenizenImportException(r'$.npc: expected Citizens NPC mapping');
    final SplayTreeMap<int, MapEntry<String, JElement>> ordered = SplayTreeMap<int, MapEntry<String, JElement>>();
    for (final MapEntry<String, JElement> entry in npcValue.entries) {
      if (!_full('0|[1-9][0-9]{0,9}', entry.key) || entry.value is! JObject) continue;
      ordered[int.parse(entry.key)] = entry;
    }
    for (final MapEntry<String, JElement> entry in ordered.values) {
      final String key = entry.key;
      final JObject record = entry.value as JObject;
      final JObject traits = record.object('traits') ?? JObject();
      final String uuid = _scalar(record, 'uuid') ?? nameUuid('animus:citizens:$key');
      final List<String> assignments = <String>[];
      final JElement? assignment = traits.get('assignment');
      if (assignment is JPrimitive) {
        assignments.add(javaTrim(assignment.asString).toLowerCase());
      } else if (assignment is JObject) {
        final JElement? list = assignment.get('assignment_list');
        if (list is JArray) {
          for (final JElement name in list.elements) {
            if (name is JPrimitive) assignments.add(javaTrim(name.asString).toLowerCase());
          }
        }
        final JElement? single = assignment.get('assignment');
        if (single is JPrimitive) assignments.add(javaTrim(single.asString).toLowerCase());
      }
      final JObject triggers = traits.object('triggers') ?? JObject();
      final SplayTreeMap<String, String> flags = SplayTreeMap<String, String>();
      final List<String> problems = <String>[];
      final JElement? denizen = traits.get('denizen_flags');
      if (denizen is JObject && denizen.has('full_flag_data')) {
        final JElement data = denizen.get('full_flag_data')!;
        if (data is JObject) {
          for (final MapEntry<String, JElement> flag in data.entries) {
            if (flag.value is JPrimitive) {
              flags[flag.key] = (flag.value as JPrimitive).asString;
            } else {
              problems.add('NPC flag ${flag.key} is not a single value; kept in citizens:review');
            }
          }
        }
      }
      if (traits.has('denizenflags')) problems.add('old denizenflags data is kept in citizens:review');
      npcs.add(_Npc(key, 'citizens-$key', uuid, _scalar(record, 'name') ?? 'Citizen $key', assignments, triggers, flags, problems));
    }
  }

  static String? _scalar(JObject object, String key) {
    final JElement? value = object.get(key);
    return value is JPrimitive ? value.asString : null;
  }

  List<_Npc> boundNpcs(DenizenContainer container) {
    final String name = container.name.toLowerCase();
    final List<_Npc> bound = <_Npc>[];
    for (final _Npc npc in npcs) {
      for (final String assignment in npc.assignments) {
        final DenizenContainer? script = containers[assignment];
        if (script == null || script.type != 'assignment') continue;
        bool reaches = assignment == name;
        if (!reaches) {
          for (final DenizenContainer listed in interactList(script)) {
            if (listed.name.toLowerCase() == name) reaches = true;
          }
        }
        if (reaches) {
          bound.add(npc);
          break;
        }
      }
    }
    return bound;
  }

  String? npcName(DenizenContainer container) {
    final SplayTreeSet<String> names = SplayTreeSet<String>();
    for (final _Npc npc in boundNpcs(container)) {
      names.add(npc.name);
    }
    return names.length == 1 ? names.first : null;
  }

  bool enabled(DenizenContainer container, String trigger, DenizenSequence? onAssignment) {
    bool known = false;
    for (final _Npc npc in boundNpcs(container)) {
      final JElement? enabled = npc.triggers.get('enabled');
      if (enabled is! JObject) continue;
      known = true;
      final JElement? value = _ignoringCase(enabled, trigger);
      if (value is JPrimitive && value.asString.toLowerCase() == 'true') return true;
    }
    if (known) return false;
    if (onAssignment != null) {
      for (final DenizenItem item in onAssignment.items) {
        final DenizenCommand command = denizenCommand(item.text);
        if (command.name != 'trigger') continue;
        final DenizenArg? name = command.prefixed('name');
        final DenizenArg? state = command.prefixed('state');
        if (name != null && name.value.toLowerCase() == trigger.toLowerCase()) return state == null || state.value.toLowerCase() == 'true';
      }
    }
    return trigger == 'click' || trigger == 'chat';
  }

  double? triggerSetting(DenizenContainer container, String field, String trigger) {
    for (final _Npc npc in boundNpcs(container)) {
      final JElement? section = npc.triggers.get(field);
      if (section is! JObject) continue;
      final JElement? value = _ignoringCase(section, trigger);
      if (value is! JPrimitive) continue;
      try {
        return javaParseDouble(value.asString);
      } on FormatException {
        // not a number: look further
      }
    }
    return null;
  }

  /// Denizen saves trigger names in upper case (`CLICK`); older saves used lower case.
  static JElement? _ignoringCase(JObject object, String key) {
    for (final MapEntry<String, JElement> entry in object.entries) {
      if (entry.key.toLowerCase() == key.toLowerCase()) return entry.value;
    }
    return null;
  }

  int radius(DenizenContainer container, DenizenSequence? onAssignment) {
    final double? stored = triggerSetting(container, 'radius', 'proximity');
    if (stored != null) return _clampRadius(stored);
    if (onAssignment != null) {
      for (final DenizenItem item in onAssignment.items) {
        final DenizenCommand command = denizenCommand(item.text);
        final DenizenArg? name = command.prefixed('name');
        final DenizenArg? radius = command.prefixed('radius');
        if (command.name == 'trigger' && name != null && name.value.toLowerCase() == 'proximity' && radius != null && _full('[0-9]{1,3}', radius.value)) {
          return _clampRadius(javaParseDouble(radius.value));
        }
      }
    }
    return _defaultRadius;
  }

  static int _clampRadius(double radius) {
    final BigInt rounded = javaRound(radius);
    if (rounded < BigInt.one) return 1;
    if (rounded > BigInt.from(64)) return 64;
    return rounded.toInt();
  }

  void cooldown(JObject trigger, DenizenContainer container, String kind) {
    final double? seconds = triggerSetting(container, 'duration', kind);
    if (seconds != null && seconds > 0) {
      final BigInt rounded = javaRound(seconds * 20);
      trigger.addInt('cooldownTicks', rounded > BigInt.from(72000) ? 72000 : rounded.toInt());
    }
  }

  // ---- Names ----

  String slug(String name) {
    final String key = name.toLowerCase();
    final String? known = slugs[key];
    if (known != null) return known;
    String base = key.replaceAll(RegExp('[^a-z0-9_-]+'), '-').replaceAll(RegExp('^[^a-z0-9]+'), '');
    if (base.length > 60) base = base.substring(0, 60);
    if (base.isEmpty) base = 'script';
    String candidate = base;
    for (int suffix = 2; usedSlugs.contains(candidate); suffix++) {
      candidate = '$base-$suffix';
    }
    usedSlugs.add(candidate);
    slugs[key] = candidate;
    return candidate;
  }

  String behaviorId(String kind, DenizenContainer container) => '$_namespace:$kind/${slug(container.name)}';

  // ---- Flag kinds ----

  void kind(String flag, _Kind kind) {
    final String name = flag.toLowerCase();
    final _Kind current = kinds[name] ?? _Kind.neutral;
    final _Kind merged;
    if (current == _Kind.other || kind == _Kind.other) {
      merged = _Kind.other;
    } else if (current == _Kind.neutral) {
      merged = kind;
    } else if (kind == _Kind.neutral || kind == current) {
      merged = current;
    } else {
      merged = _Kind.other;
    }
    kinds[name] = merged;
  }

  _Kind kindOf(String flag) {
    final _Kind kind = kinds[flag.toLowerCase()] ?? _Kind.neutral;
    return kind == _Kind.neutral ? _Kind.boolean : kind;
  }

  void scanKinds(DenizenNode node) {
    if (node is DenizenMapping) {
      for (final DenizenEntry entry in node.entries) {
        scanKinds(entry.value);
      }
    }
    if (node is! DenizenSequence) return;
    for (final DenizenItem item in node.items) {
      final DenizenCommand command = denizenCommand(item.text);
      if (command.name == 'flag' && command.args.length >= 2) {
        final String raw = _raw(command.args[1]);
        final int colon = raw.indexOf(':');
        final String name = colon < 0 ? raw : raw.substring(0, colon);
        if (!name.contains('<')) kind(name, colon < 0 ? _Kind.boolean : _valueKind(raw.substring(colon + 1)));
      }
      if (command.name == 'if' || command.name == 'else' || command.name == 'choose') {
        final List<String> tokens = <String>[];
        denizenSplit(item.text, tokens, <bool>[]);
        for (int index = 1; index < tokens.length; index++) {
          final String token = tokens[index].startsWith('!') ? tokens[index].substring(1) : tokens[index];
          final List<String>? flag = _flagTag(token);
          if (flag == null) continue;
          final String operator = index + 1 < tokens.length ? tokens[index + 1] : '';
          final String operand = index + 2 < tokens.length ? tokens[index + 2] : '';
          if (command.name == 'choose') {
            kind(flag[1], _Kind.integer);
          } else if (<String>['==', '!='].contains(operator) && (operand.toLowerCase() == 'true' || operand.toLowerCase() == 'false')) {
            kind(flag[1], _Kind.boolean);
          } else if (<String>['==', '!=', '>=', '<=', '>', '<'].contains(operator) && _full('-?[0-9]+', operand)) {
            kind(flag[1], _Kind.integer);
          } else {
            kind(flag[1], _Kind.neutral);
          }
        }
      }
      if (item.children != null) scanKinds(item.children!);
    }
  }

  void dataKinds(DenizenFlagValues values) {
    for (final DenizenFlag flag in values.flags.values) {
      if (flag.name.startsWith('__') || flag.expires) continue;
      if (flag.value == 'true' || flag.value == 'false') {
        kind(flag.name, _Kind.boolean);
      } else if (denizenStage(flag.value) != null) {
        kind(flag.name, _Kind.integer);
      } else {
        kind(flag.name, _Kind.other);
      }
    }
  }

  // ---- Outcomes ----

  void outcome(_Scope scope, DenizenItem item, String kind, String name, bool converted, String reason) {
    if (!scope.record) return;
    record(scope.container, item.line, kind, name, item.text, converted, reason);
  }

  void record(DenizenContainer container, int line, String kind, String name, String text, bool converted, String reason) {
    final String key = '${container.file}:${line.toString().padLeft(7, '0')}';
    if (outcomes.containsKey(key)) return;
    outcomes[key] = _Outcome(container.file, line, container.name, kind, name, text, converted, reason);
    if (!converted) preserve(container.file, line, container.name, kind, name, text, reason);
  }

  void preserve(String file, int line, String container, String kind, String name, String text, String reason) {
    final JObject entry = JObject()..addString('source', line > 0 ? '$file:$line' : file);
    if (container.isNotEmpty) entry.addString('container', container);
    entry
      ..addString('kind', kind)
      ..addString('item', name)
      ..addString('reason', reason);
    preserved.add(entry);
  }

  void preserveTree(_Scope scope, DenizenItem item, String reason) {
    outcome(scope, item, 'command', denizenCommand(item.text).name, false, reason);
    if (item.children != null) preserveItems(scope, item.children!.items, 0, reason.startsWith('inside ') ? reason : 'inside a block that is kept: $reason');
  }

  void preserveItems(_Scope scope, List<DenizenItem> items, int from, String reason) {
    for (int index = from; index < items.length; index++) {
      preserveTree(scope, items[index], reason);
    }
  }

  void preserveContainer(DenizenContainer container, String reason) {
    record(container, container.line, 'container', container.type, container.name, false, reason);
    // Only script-running containers hold command lines; lists in data, book or inventory scripts are values.
    if (!_scriptTypes.contains(container.type)) return;
    final _Scope scope = _Scope(LinkedHashMap<String, JObject>(), <String>{}, null, true, container, null, -1);
    preserveNode(scope, container.body, reason);
  }

  void preserveNode(_Scope scope, DenizenNode node, String reason) {
    if (node is DenizenMapping) {
      for (final DenizenEntry entry in node.entries) {
        preserveNode(scope, entry.value, reason);
      }
    } else if (node is DenizenSequence) {
      for (final DenizenItem item in node.items) {
        preserveTree(scope, item, reason);
      }
    }
  }

  bool partial(String document) {
    for (final _Outcome outcome in outcomes.values) {
      if (!outcome.converted && document == documentOf(outcome)) return true;
    }
    return false;
  }

  String documentOf(_Outcome outcome) {
    final DenizenContainer? container = containers[outcome.container.toLowerCase()];
    if (container == null) return '';
    return switch (container.type) {
      'task' => behaviorId('task', container),
      'assignment' => behaviorId('assignment', container),
      'interact' => behaviorId('interact', container),
      _ => '',
    };
  }

  // ---- Containers ----

  void task(DenizenContainer container, SplayTreeMap<String, String> documents) {
    final DenizenNode? script = container.body.get('script');
    if (script is! DenizenSequence) {
      preserveContainer(container, 'task script without a script list');
      return;
    }
    final LinkedHashMap<String, JObject> routines = LinkedHashMap<String, JObject>();
    final Set<String> calling = <String>{routineFor(container, null)};
    routines['main'] = JObject();
    final _Scope scope = _Scope(routines, calling, null, true, container, null, -1);
    for (final DenizenEntry entry in container.body.entries) {
      final String key = entry.key.toLowerCase();
      final DenizenNode path = entry.value;
      if (key == 'script' || path is! DenizenSequence) continue;
      final String routine = routineFor(container, key);
      if (!_reserve(routines, routine)) {
        record(container, entry.line, 'event', 'task path', entry.key, false, 'more than 32 routines in one behavior');
        preserveItems(scope, path.items, 0, 'inside a task path past the routine limit');
        continue;
      }
      calling.add(routine);
      routines[routine] = _sequence(block(path.items, 0, scope).steps);
      calling.remove(routine);
    }
    final _Block body = block(script.items, 0, scope);
    routines['main'] = _sequence(body.steps);
    final String empty = _sequence(<JObject>[]).toString();
    if (routines.values.every((JObject routine) => routine.toString() == empty)) return;
    document(behaviorId('task', container), container, routines, JArray(), documents);
  }

  void assignment(DenizenContainer container, SplayTreeMap<String, String> documents) {
    final LinkedHashMap<String, JObject> routines = LinkedHashMap<String, JObject>();
    final JArray triggers = JArray();
    final DenizenNode? actions = container.body.get('actions');
    DenizenSequence? onAssignment;
    if (actions is DenizenMapping) {
      final DenizenNode? assigned = actions.get('on assignment');
      if (assigned is DenizenSequence) onAssignment = assigned;
    }
    final String? name = npcName(container);
    final int range = radius(container, onAssignment);
    if (actions is DenizenMapping) {
      for (final DenizenEntry entry in actions.entries) {
        final String event = javaTrim(entry.key.toLowerCase().replaceAll(_javaSpaces, ' '));
        final DenizenNode sequence = entry.value;
        if (sequence is! DenizenSequence) {
          record(container, entry.line, 'event', event, entry.key, false, 'action without a script list');
          continue;
        }
        final _Scope scope = _Scope(routines, <String>{}, name, true, container, null, -1);
        if (event == 'on assignment') {
          record(container, entry.line, 'event', event, entry.key, true, '');
          for (final DenizenItem item in sequence.items) {
            if (denizenCommand(item.text).name == 'trigger' && item.children == null) {
              outcome(scope, item, 'command', 'trigger', true, '');
            } else {
              preserveTree(scope, item, "on assignment runs once, when Denizen assigns the script; its trigger settings are read from the NPC's saved triggers");
            }
          }
          continue;
        }
        final List<String>? mapped = switch (event) {
          'on click' => <String>['interaction', 'interact', 'click'],
          'on damage' => <String>['interaction', 'attack', 'damage'],
          'on enter proximity' => <String>['proximity', 'enter', 'proximity'],
          'on exit proximity' => <String>['proximity', 'leave', 'proximity'],
          'on spawn' => <String>['spawn', '', ''],
          'on despawn' => <String>['despawn', '', ''],
          'on death' => <String>['death', '', ''],
          _ => null,
        };
        if (mapped == null) {
          record(container, entry.line, 'event', event, entry.key, false, 'no Animus trigger for this action');
          preserveItems(scope, sequence.items, 0, 'inside an action that is kept: no Animus trigger for this action');
          continue;
        }
        if (mapped[2].isNotEmpty && !enabled(container, mapped[2], onAssignment)) {
          record(container, entry.line, 'event', event, entry.key, false, 'the ${mapped[2]} trigger is off on every NPC with this assignment');
          preserveItems(scope, sequence.items, 0, 'inside an action whose trigger is off');
          continue;
        }
        final String routine = event.replaceAll(' ', '-');
        if (routines.length >= behaviorMaximumRoutines) {
          record(container, entry.line, 'event', event, entry.key, false, 'more than 32 routines in one behavior');
          preserveItems(scope, sequence.items, 0, 'inside an action past the routine limit');
          continue;
        }
        routines[routine] = JObject();
        final _Block body = block(sequence.items, 0, scope);
        routines[routine] = _sequence(body.steps);
        record(container, entry.line, 'event', event, entry.key, true, '');
        final JObject trigger = JObject()
          ..addString('id', routine)
          ..addString('event', mapped[0]);
        if (mapped[0] == 'interaction') trigger.addString('action', mapped[1]);
        if (mapped[0] == 'proximity') {
          trigger
            ..addString('phase', mapped[1])
            ..addInt('radius', range);
        }
        trigger.addString('routine', routine);
        if (mapped[2].isNotEmpty) cooldown(trigger, container, mapped[2]);
        triggers.add(trigger);
      }
    } else if (actions != null) {
      record(container, actions.line, 'event', 'actions', 'actions', false, 'actions is not a list of actions');
    }
    final DenizenNode? list = container.body.get('interact scripts');
    if (list is DenizenSequence) {
      for (final DenizenItem item in list.items) {
        final bool found = interactFor(item.text) != null;
        record(container, item.line, 'event', 'interact script', item.text, found, found ? '' : 'no interact script with this name');
      }
    }
    for (final DenizenEntry entry in container.body.entries) {
      final String key = entry.key.toLowerCase();
      if (<String>['type', 'actions', 'interact scripts', 'debug'].contains(key)) continue;
      record(container, entry.line, 'event', key, entry.key, false, 'assignment setting $key has no Animus equivalent');
    }
    if (routines.isEmpty) {
      if (outcomesOf(container).isEmpty) record(container, container.line, 'container', 'assignment', container.name, true, '');
      return;
    }
    document(behaviorId('assignment', container), container, routines, triggers, documents);
  }

  List<_Outcome> outcomesOf(DenizenContainer container) => <_Outcome>[
        for (final _Outcome outcome in outcomes.values)
          if (outcome.container.toLowerCase() == container.name.toLowerCase() && outcome.file == container.file) outcome,
      ];

  List<DenizenContainer> interactList(DenizenContainer assignment) {
    final List<DenizenContainer> found = <DenizenContainer>[];
    final DenizenNode? list = assignment.body.get('interact scripts');
    if (list is! DenizenSequence) return found;
    for (final DenizenItem item in list.items) {
      final DenizenContainer? container = interactFor(item.text);
      if (container != null) found.add(container);
    }
    return found;
  }

  DenizenContainer? interactFor(String text) {
    String name = javaTrim(text);
    if (_full('[0-9]+ .+', name)) name = javaTrim(name.substring(name.indexOf(' ') + 1));
    final DenizenContainer? container = containers[name.toLowerCase()];
    return container != null && container.type == 'interact' ? container : null;
  }

  _Interact interact(DenizenContainer container) {
    final List<String> order = <String>[];
    final DenizenNode? steps = container.body.get('steps');
    if (steps is DenizenMapping) {
      for (final DenizenEntry entry in steps.entries) {
        order.add(entry.key);
      }
    }
    int defaultIndex = 0;
    for (int index = 0; index < order.length; index++) {
      if (order[index].contains('*')) {
        defaultIndex = index;
        break;
      }
    }
    final List<String> staged = <String>[];
    if (order.isNotEmpty) staged.add(order[defaultIndex]);
    for (int index = 0; index < order.length; index++) {
      if (index != defaultIndex) staged.add(order[index]);
    }
    final String slugged = slug(container.name);
    final String quest = 'interact-$slugged'.length > 64 ? 'interact-$slugged'.substring(0, 64) : 'interact-$slugged';
    return _Interact(container, slugged, quest, staged, order, defaultIndex);
  }

  void interactDocument(_Interact interact, SplayTreeMap<String, String> documents) {
    final DenizenContainer container = interact.container;
    final DenizenNode? steps = container.body.get('steps');
    if (steps is! DenizenMapping || steps.entries.isEmpty) {
      preserveContainer(container, 'interact script without steps');
      return;
    }
    for (final String flag in kinds.keys) {
      if (_flagName(flag) != null && _flagName(flag) == interact.quest) {
        issues.add('${container.name}: the step quest ${interact.quest} shares its name with the flag $flag');
      }
    }
    final LinkedHashMap<String, JObject> routines = LinkedHashMap<String, JObject>();
    final JArray triggers = JArray();
    final String? name = npcName(container);
    final DenizenSequence? assigned = onAssignmentFor(container);
    final int range = radius(container, assigned);
    final SplayTreeMap<String, List<int>> dispatch = SplayTreeMap<String, List<int>>();
    for (final String phase in <String>['click', 'enter', 'exit']) {
      dispatch[phase] = <int>[];
    }
    final List<JObject> choiceTriggers = <JObject>[];
    final String prefix = interact.slug.length > 50 ? interact.slug.substring(0, 50) : interact.slug;
    for (final DenizenEntry step in steps.entries) {
      final int stage = interact.stage(step.key)!;
      final int position = interact.order.indexOf(step.key);
      final DenizenNode body = step.value;
      if (body is! DenizenMapping) {
        record(container, step.line, 'event', 'step', step.key, false, 'step is not a list of triggers');
        continue;
      }
      final _Scope scope = _Scope(routines, <String>{}, name, true, container, interact, position);
      final List<JObject> clickSteps = <JObject>[];
      bool click = false;
      final JArray options = JArray();
      for (final DenizenEntry trigger in body.entries) {
        final String triggerName = javaTrim(trigger.key.toLowerCase().replaceAll(_javaSpaces, ' '));
        switch (triggerName) {
          case 'click trigger':
            final DenizenSequence? script = _scriptOf(trigger.value);
            if (script == null) {
              record(container, trigger.line, 'event', triggerName, trigger.key, false, 'trigger without a script list');
              continue;
            }
            if (!enabled(container, 'click', assigned)) {
              record(container, trigger.line, 'event', triggerName, trigger.key, false, 'the click trigger is off on every NPC with this script');
              preserveItems(scope, script.items, 0, 'inside a trigger that is off');
              continue;
            }
            clickSteps.addAll(block(script.items, 0, scope).steps);
            click = true;
            record(container, trigger.line, 'event', triggerName, trigger.key, true, '');
          case 'proximity trigger':
            final DenizenNode phases = trigger.value;
            if (phases is! DenizenMapping) {
              record(container, trigger.line, 'event', triggerName, trigger.key, false, 'trigger without entry or exit');
              continue;
            }
            final bool on = enabled(container, 'proximity', assigned);
            for (final DenizenEntry phase in phases.entries) {
              final String which = phase.key.toLowerCase();
              final DenizenSequence? script = _scriptOf(phase.value);
              final String label = '$triggerName $which';
              if (which != 'entry' && which != 'exit' || script == null) {
                record(container, phase.line, 'event', label, phase.key, false,
                    which == 'move' ? 'no Animus trigger for movement inside the radius' : 'no Animus trigger for this proximity phase');
                if (script != null) preserveItems(scope, script.items, 0, 'inside a proximity phase that is kept');
                continue;
              }
              if (!on) {
                record(container, phase.line, 'event', label, phase.key, false, 'the proximity trigger is off on every NPC with this script');
                preserveItems(scope, script.items, 0, 'inside a trigger that is off');
                continue;
              }
              final String routine = 's$stage-${which == 'entry' ? 'enter' : 'exit'}';
              if (!_reserve(routines, routine)) {
                record(container, phase.line, 'event', label, phase.key, false, 'more than 32 routines in one behavior');
                preserveItems(scope, script.items, 0, 'inside a trigger past the routine limit');
                continue;
              }
              routines[routine] = _sequence(block(script.items, 0, scope).steps);
              dispatch[which == 'entry' ? 'enter' : 'exit']!.add(stage);
              record(container, phase.line, 'event', label, phase.key, true, '');
            }
          case 'chat trigger':
            final DenizenNode chats = trigger.value;
            if (chats is! DenizenMapping) {
              record(container, trigger.line, 'event', triggerName, trigger.key, false, 'trigger without chat entries');
              continue;
            }
            final bool on = enabled(container, 'chat', assigned);
            int index = 0;
            for (final DenizenEntry chat in chats.entries) {
              index++;
              final DenizenSequence? script = _scriptOf(chat.value);
              final DenizenNode chatBody = chat.value;
              final String? phrase = chatBody is DenizenMapping ? chatBody.text('trigger') : null;
              String? reason;
              if (script == null || phrase == null) {
                reason = 'chat entry without trigger and script';
              } else if (!on) {
                reason = 'the chat trigger is off on every NPC with this script';
              } else if (phrase.contains('<') || phrase.toLowerCase().contains('regex:') || phrase.contains('*')) {
                reason = 'a chat pattern or tag cannot become an answer button';
              } else if (options.length >= 8) {
                reason = 'more than 8 chat answers in one step';
              }
              final String text = javaTrim((phrase ?? '').replaceAll('/', '')).replaceAll(_javaSpaces, ' ');
              if (reason == null && (text.isEmpty || text.length > 128)) reason = 'chat answer text is empty or longer than 128 characters';
              final String routine = 's$stage-chat-$index';
              if (reason == null && !_reserve(routines, routine)) reason = 'more than 32 routines in one behavior';
              if (reason == null && triggers.length + choiceTriggers.length + 3 >= behaviorMaximumTriggers) reason = 'more than 32 triggers in one behavior';
              if (reason != null) {
                routines.remove(routine);
                record(container, chat.line, 'event', 'chat trigger', chat.key, false, reason);
                if (script != null) preserveItems(scope, script.items, 0, 'inside a chat answer that is kept');
                continue;
              }
              routines[routine] = _sequence(block(script!.items, 0, scope).steps);
              final String choice = 'c$index';
              options.add(JObject()
                ..addString('id', choice)
                ..addString('text', text));
              choiceTriggers.add(JObject()
                ..addString('id', 's$stage-$choice')
                ..addString('event', 'dialogue_choice')
                ..addString('dialogue', '$prefix-s$stage')
                ..addString('choice', choice)
                ..addString('routine', routine));
              record(container, chat.line, 'event', 'chat trigger', chat.key, true, '');
            }
          case 'damage trigger':
            record(container, trigger.line, 'event', triggerName, trigger.key, false, 'Animus damage triggers carry no player, so player steps cannot follow them');
            final DenizenSequence? script = _scriptOf(trigger.value);
            if (script != null) preserveItems(scope, script.items, 0, 'inside a damage trigger that is kept');
          default:
            record(container, trigger.line, 'event', triggerName, trigger.key, false, 'no Animus trigger for $triggerName');
            final DenizenSequence? script = _scriptOf(trigger.value);
            if (script != null) preserveItems(scope, script.items, 0, 'inside a trigger that is kept');
        }
      }
      if (options.isNotEmpty) {
        clickSteps.add(_action(
            'animus:choices',
            JObject()
              ..addString('id', '$prefix-s$stage')
              ..addString('prompt', 'Choose a reply')
              ..add('options', options)));
        click = true;
      }
      if (click) {
        final String routine = 's$stage-click';
        if (_reserve(routines, routine)) {
          routines[routine] = _sequence(clickSteps);
          dispatch['click']!.add(stage);
        } else {
          record(container, step.line, 'event', 'click trigger', step.key, false, 'more than 32 routines in one behavior');
        }
      }
    }
    final bool single = interact.steps.length == 1;
    for (final String phase in <String>['click', 'enter', 'exit']) {
      final List<int> stages = List<int>.of(dispatch[phase]!);
      if (stages.isEmpty) continue;
      final String routine = single ? 's0-$phase' : phase;
      if (!single) {
        if (!_reserve(routines, routine)) {
          issues.add('${container.name}: no room for the $routine step dispatcher; that trigger is not generated');
          continue;
        }
        stages.sort();
        JObject? chain;
        for (int index = stages.length - 1; index >= 0; index--) {
          final JObject arguments = JObject()
            ..addString('name', interact.quest)
            ..addString('scope', 'relationship')
            ..addInt('equals', stages[index]);
          final JObject call = JObject()
            ..addString('type', 'call')
            ..addString('routine', 's${stages[index]}-$phase');
          chain = _conditionNode('animus:quest-state', arguments, call, chain);
        }
        routines[routine] = chain!;
      }
      final JObject trigger = JObject()..addString('id', phase);
      if (phase == 'click') {
        trigger
          ..addString('event', 'interaction')
          ..addString('routine', routine);
        cooldown(trigger, container, 'click');
      } else {
        trigger
          ..addString('event', 'proximity')
          ..addString('phase', phase == 'enter' ? 'enter' : 'leave')
          ..addInt('radius', range)
          ..addString('routine', routine);
        cooldown(trigger, container, 'proximity');
      }
      triggers.add(trigger);
    }
    for (final JObject choice in choiceTriggers) {
      triggers.add(choice);
    }
    for (final DenizenEntry entry in container.body.entries) {
      final String key = entry.key.toLowerCase();
      if (<String>['type', 'steps', 'debug'].contains(key)) continue;
      record(container, entry.line, 'event', key, entry.key, false, 'interact setting $key has no Animus equivalent');
    }
    if (routines.isEmpty) {
      record(container, container.line, 'container', 'interact', container.name, false, 'no step trigger converts');
      return;
    }
    final LinkedHashMap<String, JObject> sorted = LinkedHashMap<String, JObject>();
    for (final String first in <String>['click', 'enter', 'exit']) {
      if (routines.containsKey(first)) sorted[first] = routines[first]!;
    }
    for (final MapEntry<String, JObject> routine in routines.entries) {
      if (!sorted.containsKey(routine.key)) sorted[routine.key] = routine.value;
    }
    document(behaviorId('interact', container), container, sorted, triggers, documents);
  }

  DenizenSequence? onAssignmentFor(DenizenContainer container) {
    final String name = container.name.toLowerCase();
    for (final DenizenContainer assignment in containers.values) {
      if (assignment.type != 'assignment') continue;
      bool reaches = assignment.name.toLowerCase() == name;
      for (final DenizenContainer listed in interactList(assignment)) {
        if (listed.name.toLowerCase() == name) reaches = true;
      }
      if (!reaches) continue;
      final DenizenNode? actions = assignment.body.get('actions');
      if (actions is! DenizenMapping) continue;
      final DenizenNode? assigned = actions.get('on assignment');
      if (assigned is DenizenSequence) return assigned;
    }
    return null;
  }

  void world(DenizenContainer container) {
    record(container, container.line, 'container', 'world', container.name, false, 'world scripts react to server-wide events; Animus triggers belong to NPCs');
    final DenizenNode? events = container.body.get('events');
    final _Scope scope = _Scope(LinkedHashMap<String, JObject>(), <String>{}, null, true, container, null, -1);
    if (events is DenizenMapping) {
      for (final DenizenEntry entry in events.entries) {
        final String event = 'world ${javaTrim(entry.key.toLowerCase().replaceAll(_javaSpaces, ' '))}';
        record(container, entry.line, 'event', event, entry.key, false, _worldReason(entry.key.toLowerCase()));
        preserveNode(scope, entry.value, 'inside a world event that is kept');
      }
    }
    for (final DenizenEntry entry in container.body.entries) {
      if (entry.key.toLowerCase() != 'events') preserveNode(scope, entry.value, 'inside a world script that is kept');
    }
  }

  void document(String id, DenizenContainer container, LinkedHashMap<String, JObject> routines, JArray triggers, SplayTreeMap<String, String> documents) {
    final JObject document = JObject()
      ..addInt('schemaVersion', 1)
      ..addInt('revision', 1)
      ..addString('id', id)
      ..addString('entry', routines.keys.first);
    final JObject body = JObject();
    for (final MapEntry<String, JObject> routine in routines.entries) {
      body.add(routine.key, routine.value);
    }
    document.add('routines', body);
    if (triggers.isNotEmpty) document.add('triggers', triggers);
    final String text = writeJson(document);
    final String? problem = behaviorLimitProblem(document, text);
    if (problem != null) {
      issues.add('${container.name}: the converted behavior $problem; it is not generated');
      record(container, container.line, 'container', container.type, container.name, false, 'the converted behavior $problem');
      return;
    }
    documents[id] = text;
  }

  // ---- Commands ----

  _Block block(List<DenizenItem> items, int from, _Scope scope) {
    final List<JObject> steps = <JObject>[];
    for (int index = from; index < items.length; index++) {
      final DenizenItem item = items[index];
      final DenizenCommand command = denizenCommand(item.text);
      switch (command.name) {
        case 'stop':
          outcome(scope, item, 'command', 'stop', true, '');
          preserveItems(scope, items, index + 1, 'never runs: after stop');
          return _Block(steps, true);
        case 'determine':
          bool passive = false;
          for (final DenizenArg arg in command.args) {
            if (arg.isWord('passively')) passive = true;
          }
          outcome(scope, item, 'command', 'determine', false, 'determine returns a value to Denizen; behaviors return none');
          if (!passive) {
            preserveItems(scope, items, index + 1, 'never runs: after determine');
            return _Block(steps, true);
          }
        case 'if':
        case 'choose':
          int end = index + 1;
          if (command.name == 'if') {
            while (end < items.length && denizenCommand(items[end].text).name == 'else') {
              end++;
            }
          }
          final _Chain? result = chain(items, index, end, scope);
          index = end - 1;
          if (result == null) continue;
          steps.add(result.node);
          if (result.tail) return _Block(steps, result.stops);
        case 'else':
          preserveTree(scope, item, 'else without a matching if');
        case 'repeat':
          final JObject? node = repeat(item, command, scope);
          if (node != null) steps.add(node);
        default:
          if (item.children != null) {
            preserveTree(scope, item,
                command.name == 'foreach' || command.name == 'while' ? 'loops over computed values have no Animus equivalent' : 'no Animus equivalent for the ${command.name} block');
            continue;
          }
          final _Converted converted = simple(command, scope);
          outcome(scope, item, 'command', command.name, converted.reason.isEmpty, converted.reason);
          steps.addAll(converted.steps);
      }
    }
    return _Block(steps, false);
  }

  _Chain? chain(List<DenizenItem> items, int start, int end, _Scope scope) {
    final DenizenItem head = items[start];
    final DenizenCommand command = denizenCommand(head.text);
    final List<_Condition> conditions = <_Condition>[];
    final List<DenizenItem> bodies = <DenizenItem>[];
    DenizenItem? otherwise;
    String? reason;
    if (command.name == 'choose') {
      final List<String>? flag = command.args.length == 1 ? _flagTag(command.args.first.value) : null;
      if (flag == null || flag[2] != 'flag' || kindOf(flag[1]) != _Kind.integer || _flagName(flag[1]) == null) {
        reason = 'choose works only on a whole-number flag';
      } else if (head.children == null) {
        reason = 'choose without cases';
      } else {
        for (final DenizenItem option in head.children!.items) {
          final DenizenCommand when = denizenCommand(option.text);
          if (option.children == null) {
            reason = 'case without commands';
            break;
          }
          if (when.name == 'default' && when.args.isEmpty) {
            otherwise = option;
            continue;
          }
          if (when.name != 'case' || when.args.length != 1 || denizenStage(when.args.first.value) == null) {
            reason = 'case values must be whole numbers';
            break;
          }
          conditions.add(_atom(
              'animus:quest-state',
              JObject()
                ..addString('name', _flagName(flag[1])!)
                ..addString('scope', _scopeOf(flag[0]))
                ..addInt('equals', denizenStage(when.args.first.value)!),
              false));
          bodies.add(option);
        }
      }
    } else {
      for (int index = start; index < end; index++) {
        final DenizenItem branch = items[index];
        final List<String> tokens = <String>[];
        denizenSplit(branch.text, tokens, <bool>[]);
        final bool isElse = index > start;
        if (branch.children == null) {
          reason = 'if without a block';
          break;
        }
        if (isElse && tokens.length == 1) {
          if (index != end - 1) {
            reason = 'else before else if';
            break;
          }
          otherwise = branch;
          continue;
        }
        final List<String> expression = tokens.sublist(isElse ? 2 : 1);
        if (isElse && tokens[1].toLowerCase() != 'if') {
          reason = 'unknown else form';
          break;
        }
        final _Result condition = parseCondition(expression);
        if (condition.reason != null) {
          reason = condition.reason;
          break;
        }
        conditions.add(condition.value!);
        bodies.add(branch);
      }
    }
    if (reason != null || conditions.isEmpty) {
      final String why = reason ?? 'nothing to choose between';
      for (int index = start; index < end; index++) {
        preserveTree(scope, items[index], why);
      }
      return null;
    }
    for (int index = start; index < end; index++) {
      outcome(scope, items[index], 'command', denizenCommand(items[index].text).name, true, '');
    }
    if (command.name == 'choose') {
      for (final DenizenItem option in head.children!.items) {
        outcome(scope, option, 'command', denizenCommand(option.text).name, true, '');
      }
    }
    final List<_Block> blocks = <_Block>[];
    bool anyStop = false;
    for (final DenizenItem body in bodies) {
      final _Block result = block(body.children!.items, 0, scope);
      blocks.add(result);
      anyStop |= result.stops;
    }
    final _Block? elseBlock = otherwise == null ? null : block(otherwise.children!.items, 0, scope);
    if (elseBlock != null) anyStop |= elseBlock.stops;
    if (!anyStop) {
      JObject? tail = elseBlock == null ? null : _sequence(elseBlock.steps);
      for (int index = conditions.length - 1; index >= 0; index--) {
        tail = conditions[index](_sequence(blocks[index].steps), tail);
      }
      return _Chain(tail!, false, false);
    }
    final _Block rest = block(items, end, scope);
    bool allStop;
    JObject? tail;
    if (elseBlock != null) {
      tail = _sequence(_joined(elseBlock, rest));
      allStop = elseBlock.stops || rest.stops;
    } else {
      tail = rest.steps.isEmpty ? null : _sequence(_copies(rest.steps));
      allStop = rest.stops;
    }
    for (int index = conditions.length - 1; index >= 0; index--) {
      allStop &= blocks[index].stops || rest.stops;
      tail = conditions[index](_sequence(_joined(blocks[index], rest)), tail);
    }
    return _Chain(tail!, true, allStop);
  }

  JObject? repeat(DenizenItem item, DenizenCommand command, _Scope scope) {
    if (item.children == null) {
      preserveTree(scope, item, 'repeat without a block');
      return null;
    }
    if (command.args.length != 1 || !_full('[0-9]{1,3}', command.args.first.value) || int.parse(command.args.first.value) > 128) {
      preserveTree(scope, item, 'repeat needs a literal count from 0 to 128');
      return null;
    }
    if (_controlsLoop(item.children!)) {
      preserveTree(scope, item, 'repeat stop and repeat next have no Animus equivalent');
      return null;
    }
    outcome(scope, item, 'command', 'repeat', true, '');
    final _Block body = block(item.children!.items, 0, scope);
    if (body.stops) issues.add('${scope.container.file}:${item.line}: stop inside repeat only ends the current pass in the converted behavior');
    return JObject()
      ..addString('type', 'repeat')
      ..addInt('count', int.parse(command.args.first.value))
      ..add('body', _sequence(body.steps));
  }

  _Result parseCondition(List<String> tokens) {
    if (tokens.isEmpty) return const _Result(null, 'if without a condition');
    bool and = false;
    bool or = false;
    final List<List<String>> groups = <List<String>>[];
    List<String> current = <String>[];
    for (final String token in tokens) {
      if (token == '&&' || token == '||') {
        if (token == '&&') {
          and = true;
        } else {
          or = true;
        }
        groups.add(current);
        current = <String>[];
      } else if (token.startsWith('(') || token.endsWith(')')) {
        return const _Result(null, 'grouped conditions have no Animus equivalent');
      } else {
        current.add(token);
      }
    }
    groups.add(current);
    if (and && or) return const _Result(null, 'mixed && and || conditions have no Animus equivalent');
    final List<_Condition> atoms = <_Condition>[];
    for (final List<String> group in groups) {
      final _Result atom = comparison(group);
      if (atom.reason != null) return atom;
      atoms.add(atom.value!);
    }
    _Condition combined = atoms.last;
    for (int index = atoms.length - 2; index >= 0; index--) {
      final _Condition left = atoms[index];
      final _Condition right = combined;
      combined = and
          ? (JObject then, JObject? otherwise) => left(right(then, otherwise?.deepCopy()), otherwise)
          : (JObject then, JObject? otherwise) => left(then, right(then.deepCopy(), otherwise));
    }
    return _Result(combined, null);
  }

  _Result comparison(List<String> group) {
    if (group.length != 1 && group.length != 3) return const _Result(null, 'condition form has no Animus equivalent');
    String left = group.first;
    final bool negated = left.startsWith('!');
    if (negated) left = left.substring(1);
    if (!left.startsWith('<') || !left.endsWith('>') || denizenTagEnd(left, 0) != left.length - 1) {
      return _Result(null, 'condition ${group.first} has no Animus equivalent');
    }
    final DenizenTag tag = denizenTag(left.substring(1, left.length - 1));
    if (group.length == 1) {
      if (tag.parts.length == 2 &&
          tag.base.toLowerCase() == 'player' &&
          tag.parts[1].name.toLowerCase() == 'has_permission' &&
          tag.parts[1].parameter != null &&
          !tag.parts[1].parameter!.contains('<')) {
        return _Result(_atom('animus:has-permission', JObject()..addString('permission', tag.parts[1].parameter!), negated), null);
      }
      if (tag.parts.length == 2 && tag.base.toLowerCase() == 'npc' && tag.parts[1].name.toLowerCase() == 'is_spawned' && tag.parts[1].parameter == null) {
        return _Result(_atom('animus:npc-state', JObject()..addBool('spawned', true), negated), null);
      }
    }
    final List<String>? flag = _flagTag(left);
    if (flag == null) return _Result(null, 'condition tag ${tag.label} has no Animus equivalent');
    final String? name = _flagName(flag[1]);
    if (name == null) return _Result(null, 'flag name ${flag[1]} cannot be an Animus flag name');
    final _Kind kind = kindOf(flag[1]);
    final JObject arguments = JObject()
      ..addString('name', name)
      ..addString('scope', _scopeOf(flag[0]));
    if (group.length == 1) {
      if (kind == _Kind.boolean) return _Result(_atom('animus:flag-state', arguments, negated), null);
      if (kind == _Kind.integer) {
        arguments.addInt('atLeast', 1);
        return _Result(_atom('animus:quest-state', arguments, negated), null);
      }
      return _Result(null, 'flag ${flag[1]} holds values Animus flags cannot');
    }
    if (negated) return const _Result(null, 'negated comparison has no Animus equivalent');
    final String operator = group[1];
    final String operand = group[2];
    if (kind == _Kind.boolean && (operator == '==' || operator == '!=') && (operand.toLowerCase() == 'true' || operand.toLowerCase() == 'false')) {
      final bool wanted = (operand.toLowerCase() == 'true') == (operator == '==');
      if (!wanted) arguments.addBool('value', false);
      return _Result(_atom('animus:flag-state', arguments, false), null);
    }
    if (kind != _Kind.integer || !_full('[0-9]{1,13}', operand) || int.parse(operand) > 1000000000000) {
      return _Result(null, 'comparison with $operand has no Animus equivalent');
    }
    final int number = int.parse(operand);
    switch (operator) {
      case '==':
        arguments.addInt('equals', number);
      case '!=':
        arguments.addInt('equals', number);
        return _Result(_atom('animus:quest-state', arguments, true), null);
      case '>=':
        arguments.addInt('atLeast', number);
      case '<=':
        arguments.addInt('atMost', number);
      case '>':
        arguments.addInt('atLeast', number + 1);
      case '<':
        if (number == 0) return const _Result(null, 'a quest stage is never below 0');
        arguments.addInt('atMost', number - 1);
      default:
        return _Result(null, 'operator $operator has no Animus equivalent');
    }
    return _Result(_atom('animus:quest-state', arguments, false), null);
  }

  _Converted simple(DenizenCommand command, _Scope scope) => switch (command.name) {
        'narrate' => say(command, scope, 'trigger'),
        'chat' => say(command, scope, 'nearby'),
        'wait' => waitFor(command),
        'flag' => flag(command),
        'zap' => zap(command, scope),
        'run' || 'inject' => call(command, scope),
        'execute' => execute(command, scope),
        'give' => give(command),
        'take' => take(command),
        'look' => look(command),
        'walk' => walk(command),
        'equip' => equip(command),
        'playsound' => sound(command),
        'playeffect' => particle(command),
        'actionbar' => actionbar(command, scope),
        'title' => title(command, scope),
        'debug' => const _Converted(<JObject>[], ''),
        'define' => const _Converted.kept('definitions hold computed values; behaviors have no expressions'),
        'trigger' => const _Converted.kept("trigger settings change only in on assignment; the NPC's saved triggers are used"),
        'teleport' => const _Converted.kept('no Animus teleport action; an allow-listed animus:command with tp can replace it'),
        '' => const _Converted.kept('empty command line'),
        _ => _Converted.kept('no Animus equivalent for ${command.name}'),
      };

  _Converted say(DenizenCommand command, _Scope scope, String audience) {
    final List<String> words = <String>[];
    String chosen = audience;
    for (final DenizenArg arg in command.args) {
      if (arg.prefix.isEmpty) {
        if (!arg.isWord('per_player')) words.add(arg.value);
        continue;
      }
      if (arg.prefix == 'targets' && _playerTarget(arg.value)) {
        chosen = 'trigger';
        continue;
      }
      return _Converted.kept('argument ${arg.prefix}: has no Animus equivalent');
    }
    if (words.isEmpty) return const _Converted.kept('nothing to say');
    final _Mapped text = mapText(words.join(' '), scope, false, 0);
    if (text.reason != null) return _Converted.kept(text.reason!);
    if (javaIsBlank(text.value!)) return const _Converted.kept('nothing to say');
    if (text.value!.length > _maximumText) return const _Converted.kept('text longer than 1024 characters');
    final JObject arguments = JObject()..addString('text', text.value!);
    if (chosen == 'nearby') arguments.addString('audience', 'nearby');
    return _Converted.of(<JObject>[_action('animus:say', arguments)]);
  }

  _Converted waitFor(DenizenCommand command) {
    if (command.args.length > 1 || command.args.length == 1 && command.args.first.prefix.isNotEmpty) return const _Converted.kept('wait argument has no Animus equivalent');
    final int? ticks = command.args.isEmpty ? 60 : denizenTicks(command.args.first.value);
    if (ticks == null) return const _Converted.kept('wait needs a literal duration');
    if (ticks == 0) return const _Converted(<JObject>[], '');
    if (ticks > 72000) return const _Converted.kept('wait longer than an hour');
    return _Converted.of(<JObject>[
      JObject()
        ..addString('type', 'wait')
        ..addInt('ticks', ticks),
    ]);
  }

  _Converted flag(DenizenCommand command) {
    if (command.args.length < 2) return const _Converted.kept('flag needs a target and a name');
    if (command.args.length > 2) {
      return _Converted.kept(command.args[2].prefix == 'expire' ? 'expiring flags have no Animus equivalent' : 'flag argument has no Animus equivalent');
    }
    final String target = command.args.first.value.toLowerCase().replaceAll('<', '').replaceAll('>', '');
    if (command.args.first.prefix.isNotEmpty || !<String>['player', 'npc', 'server'].contains(target)) {
      return const _Converted.kept('flags on other objects have no Animus equivalent');
    }
    final String raw = _raw(command.args[1]);
    final int colon = raw.indexOf(':');
    final String denizen = (colon < 0 ? raw : raw.substring(0, colon)).toLowerCase();
    final String value = colon < 0 ? '' : raw.substring(colon + 1);
    final String? name = _flagName(denizen);
    if (denizen.contains('<') || name == null) return _Converted.kept('flag name $denizen cannot be an Animus flag name');
    final JObject arguments = JObject()
      ..addString('name', name)
      ..addString('scope', _scopeOf(target));
    final _Kind kind = kindOf(denizen);
    if (kind == _Kind.boolean) {
      if (colon < 0 || value.toLowerCase() == 'true') return _Converted.of(<JObject>[_action('animus:set-flag', arguments)]);
      if (value == '!' || value.toLowerCase() == 'false') {
        arguments.addBool('value', false);
        return _Converted.of(<JObject>[_action('animus:set-flag', arguments)]);
      }
    }
    if (kind == _Kind.integer) {
      if (value == '!') {
        arguments.addInt('value', 0);
        return _Converted.of(<JObject>[_action('animus:set-quest-state', arguments)]);
      }
      final int? stage = denizenStage(value);
      if (stage != null) {
        arguments.addInt('value', stage);
        return _Converted.of(<JObject>[_action('animus:set-quest-state', arguments)]);
      }
      if (value == '++') return _Converted.of(<JObject>[_action('animus:advance-quest-state', arguments)]);
      if (value.startsWith('+:') && _full('[0-9]{1,7}', value.substring(2)) && int.parse(value.substring(2)) >= 1 && int.parse(value.substring(2)) <= 1000000) {
        arguments.addInt('by', int.parse(value.substring(2)));
        return _Converted.of(<JObject>[_action('animus:advance-quest-state', arguments)]);
      }
      return const _Converted.kept('quest stages only rise or are set; this flag change has no Animus equivalent');
    }
    return _Converted.kept('flag $denizen holds values Animus flags cannot');
  }

  _Converted zap(DenizenCommand command, _Scope scope) {
    _Interact? target = scope.interact;
    String? step;
    for (final DenizenArg arg in command.args) {
      if (arg.prefix == 'script') {
        target = interacts[arg.value.toLowerCase()];
        continue;
      }
      if (arg.prefix == 'step') {
        step = arg.value;
        continue;
      }
      if (arg.prefix.isNotEmpty) return _Converted.kept('zap argument ${arg.prefix}: has no Animus equivalent');
      if (interacts.containsKey(arg.value.toLowerCase()) && step == null && identical(target, scope.interact)) {
        target = interacts[arg.value.toLowerCase()];
        continue;
      }
      if (step != null) return const _Converted.kept('zap with a duration has no Animus equivalent');
      step = arg.value;
    }
    if (target == null) return const _Converted.kept("zap outside an interact script needs the script's name");
    final _Interact interact = target;
    final int stage;
    if (step == null) {
      if (scope.interact == null || !identical(scope.interact, interact) || scope.step < 0 || scope.step + 1 >= interact.order.length) {
        return const _Converted.kept('zap to the next step needs a following step');
      }
      stage = interact.stage(interact.order[scope.step + 1])!;
    } else {
      if (step.contains('<')) return const _Converted.kept('zap to a computed step has no Animus equivalent');
      final int? found = interact.stage(step);
      if (found == null) return _Converted.kept('no step $step in ${interact.container.name}');
      stage = found;
    }
    return _Converted.of(<JObject>[
      _action(
          'animus:set-quest-state',
          JObject()
            ..addString('name', interact.quest)
            ..addString('scope', 'relationship')
            ..addInt('value', stage)),
    ]);
  }

  _Converted call(DenizenCommand command, _Scope scope) {
    String? name;
    String? path;
    for (final DenizenArg arg in command.args) {
      if (arg.prefix.isEmpty) {
        if (arg.isWord('instantly') || arg.isWord('local')) continue;
        if (name != null) return _Converted.kept('argument ${arg.value} has no Animus equivalent');
        name = arg.value;
        continue;
      }
      switch (arg.prefix) {
        case 'path':
          path = arg.value;
        case 'npc':
          if (arg.value.toLowerCase() != '<npc>') return const _Converted.kept('running for another NPC has no Animus equivalent');
        case 'player':
          if (arg.value.toLowerCase() != '<player>') return const _Converted.kept('running for another player has no Animus equivalent');
        case 'def':
        case 'defmap':
        case 'definitions':
          return const _Converted.kept('definitions passed to a task have no Animus equivalent');
        default:
          return _Converted.kept('argument ${arg.prefix}: has no Animus equivalent');
      }
    }
    if (name == null || name.contains('<')) return const _Converted.kept('running a computed script has no Animus equivalent');
    final DenizenContainer? task = containers[name.toLowerCase()];
    if (task == null) return _Converted.kept('no script named $name');
    if (task.type != 'task') return const _Converted.kept('only task scripts can become routines');
    final String key = path?.toLowerCase() ?? 'script';
    final DenizenNode? body = task.body.get(key);
    if (body is! DenizenSequence) return _Converted.kept('task ${task.name} has no $key list');
    final String routine = routineFor(task, path);
    if (scope.calling.contains(routine)) return const _Converted.kept('recursive task calls have no Animus equivalent');
    final JObject call = JObject()
      ..addString('type', 'call')
      ..addString('routine', routine);
    if (scope.routines.containsKey(routine)) return _Converted.of(<JObject>[call]);
    if (scope.routines.length >= behaviorMaximumRoutines) return const _Converted.kept('more than 32 routines in one behavior');
    scope.routines[routine] = JObject();
    scope.calling.add(routine);
    final _Block result = block(body.items, 0, scope.inContainer(task));
    scope.calling.remove(routine);
    scope.routines[routine] = _sequence(result.steps);
    return _Converted.of(<JObject>[call]);
  }

  String routineFor(DenizenContainer task, String? path) {
    final String routine = 'task-${slug(task.name)}${path == null ? '' : '-${path.toLowerCase().replaceAll(RegExp('[^a-z0-9_-]+'), '-')}'}';
    return routine.length > 64 ? routine.substring(0, 64) : routine;
  }

  _Converted execute(DenizenCommand command, _Scope scope) {
    String? as;
    final List<String> words = <String>[];
    for (final DenizenArg arg in command.args) {
      if (arg.isWord('as_server') || arg.isWord('as_op')) {
        as = 'console';
        continue;
      }
      if (arg.isWord('as_player')) {
        as = 'player';
        continue;
      }
      if (arg.isWord('as_npc')) return const _Converted.kept('commands run as the NPC have no Animus equivalent');
      if (arg.isWord('silent')) continue;
      words.add(_raw(arg));
    }
    if (as == null) return const _Converted.kept('execute needs as_server, as_op or as_player');
    final _Mapped text = mapText(words.join(' '), scope, true, 0);
    if (text.reason != null) return _Converted.kept(text.reason!);
    String line = javaTrim(text.value!);
    if (line.startsWith('/')) line = line.substring(1);
    if (line.isEmpty || line.length > 1024) return const _Converted.kept('command is empty or longer than 1024 characters');
    final JObject arguments = JObject()..addString('command', line);
    if (as == 'player') arguments.addString('as', 'player');
    return _Converted.of(<JObject>[_action('animus:command', arguments)]);
  }

  String? material(String value) {
    String lowered = value.toLowerCase();
    if (lowered.startsWith('minecraft:')) lowered = lowered.substring(10);
    if (!_full('[a-z0-9_]{1,64}', lowered) || containers.containsKey(lowered)) return null;
    return 'minecraft:$lowered';
  }

  _Converted give(DenizenCommand command) {
    String? item;
    int quantity = 1;
    for (final DenizenArg arg in command.args) {
      if (arg.prefix == 'quantity') {
        if (!_full('[0-9]{1,4}', arg.value) || int.parse(arg.value) < 1) return const _Converted.kept('quantity needs a literal number');
        quantity = int.parse(arg.value);
      } else if (arg.prefix.isEmpty && item == null) {
        if (arg.value.toLowerCase() == 'xp' || arg.value.toLowerCase() == 'money') return _Converted.kept('giving ${arg.value} has no Animus equivalent');
        item = material(arg.value);
        if (item == null) return const _Converted.kept('only plain materials can be given; item scripts and tags have no Animus equivalent');
      } else {
        return const _Converted.kept('give argument has no Animus equivalent');
      }
    }
    if (item == null) return const _Converted.kept('nothing to give');
    return _Converted.of(<JObject>[_commandAction('give {player} $item $quantity')]);
  }

  _Converted take(DenizenCommand command) {
    String? item;
    int quantity = 1;
    for (final DenizenArg arg in command.args) {
      if (arg.prefix == 'quantity') {
        if (!_full('[0-9]{1,4}', arg.value) || int.parse(arg.value) < 1) return const _Converted.kept('quantity needs a literal number');
        quantity = int.parse(arg.value);
      } else if ((arg.prefix == 'item' || arg.prefix.isEmpty) && item == null) {
        item = material(arg.value);
        if (item == null) return const _Converted.kept('only plain materials can be taken; item scripts, flags and tags have no Animus equivalent');
      } else {
        return const _Converted.kept('take argument has no Animus equivalent');
      }
    }
    if (item == null) return const _Converted.kept('nothing to take');
    return _Converted.of(<JObject>[_commandAction('clear {player} $item $quantity')]);
  }

  _Converted look(DenizenCommand command) {
    List<DenizenArg> plain = command.plain();
    if (plain.length != command.args.length) return const _Converted.kept('look argument has no Animus equivalent');
    if (plain.length == 2 && _npcTarget(plain.first.value)) plain = plain.sublist(1, 2);
    if (plain.length != 1) return const _Converted.kept('look at more than one target has no Animus equivalent');
    final String target = plain.first.value.toLowerCase();
    final JObject arguments = JObject();
    if (target == '<player>' || target == '<player.location>' || target == '<player.eye_location>') {
      arguments.addString('target', 'player');
    } else {
      final List<double>? at = _location(plain.first.value);
      if (at == null) return const _Converted.kept('look target must be the player or a literal location');
      arguments
        ..addDouble('x', at[0])
        ..addDouble('y', at[1])
        ..addDouble('z', at[2]);
    }
    return _Converted.of(<JObject>[_action('animus:look', arguments)]);
  }

  _Converted walk(DenizenCommand command) {
    List<DenizenArg> plain = command.plain();
    if (plain.length != command.args.length) return const _Converted.kept('walk argument has no Animus equivalent');
    if (plain.length == 2 && _npcTarget(plain.first.value)) plain = plain.sublist(1, 2);
    if (plain.length != 1) return const _Converted.kept('walk needs one literal location');
    final List<double>? at = _location(plain.first.value);
    if (at == null) return const _Converted.kept('walk needs a literal location');
    return _Converted.of(<JObject>[
      _action(
          'animus:navigate',
          JObject()
            ..addDouble('x', at[0])
            ..addDouble('y', at[1])
            ..addDouble('z', at[2])),
    ]);
  }

  _Converted equip(DenizenCommand command) {
    final SplayTreeMap<int, JObject> slots = SplayTreeMap<int, JObject>();
    for (final DenizenArg arg in command.args) {
      if (arg.prefix.isEmpty) {
        if (_npcTarget(arg.value)) continue;
        return const _Converted.kept('equipping another entity has no Animus equivalent');
      }
      final (int, String)? slot = switch (arg.prefix) {
        'hand' => (0, 'main_hand'),
        'offhand' => (1, 'off_hand'),
        'head' => (2, 'head'),
        'chest' => (3, 'chest'),
        'legs' => (4, 'legs'),
        'boots' => (5, 'feet'),
        _ => null,
      };
      if (slot == null) return _Converted.kept('equipment slot ${arg.prefix} has no Animus equivalent');
      final JObject arguments = JObject()..addString('slot', slot.$2);
      if (arg.value.toLowerCase() != 'air') {
        final String? item = material(arg.value);
        if (item == null) return const _Converted.kept('only plain materials can be equipped');
        arguments.addString('item', item);
      }
      slots[slot.$1] = _action('animus:set-equipment', arguments);
    }
    if (slots.isEmpty) return const _Converted.kept('nothing to equip');
    return _Converted(slots.values.toList(), '');
  }

  _Converted sound(DenizenCommand command) {
    String? sound;
    String? audience;
    bool custom = false;
    final JObject arguments = JObject();
    double? volume;
    double? pitch;
    for (final DenizenArg arg in command.args) {
      if (arg.isWord('custom')) {
        custom = true;
        continue;
      }
      switch (arg.prefix) {
        case 'sound':
          sound = arg.value;
        case 'volume':
          volume = _number(arg.value, 0, 4);
          if (volume == null) return const _Converted.kept('volume must be a number from 0 to 4');
        case 'pitch':
          pitch = _number(arg.value, 0.5, 2);
          if (pitch == null) return const _Converted.kept('pitch must be a number from 0.5 to 2');
        case '':
          final String target = arg.value.toLowerCase();
          if (audience != null) return const _Converted.kept('sound for several targets has no Animus equivalent');
          if (target == '<player>' || target == '<player.location>') {
            audience = 'trigger';
          } else if (target == '<npc>' || target == '<npc.location>') {
            audience = 'nearby';
          } else if (sound == null && !target.startsWith('<')) {
            sound = arg.value;
          } else {
            return const _Converted.kept('sound location must be the NPC or the player');
          }
        default:
          return _Converted.kept('playsound argument ${arg.prefix}: has no Animus equivalent');
      }
    }
    if (sound == null || sound.contains('<')) return const _Converted.kept('playsound needs a literal sound');
    final String? key = denizenSoundKey(sound, custom);
    if (key == null) return _Converted.kept('sound $sound has no resource key');
    sounds[sound] = key;
    arguments.addString('sound', key);
    if (volume != null) arguments.addDouble('volume', volume);
    if (pitch != null) arguments.addDouble('pitch', pitch);
    if (audience == 'nearby') arguments.addString('audience', 'nearby');
    return _Converted.of(<JObject>[_action('animus:play-sound', arguments)]);
  }

  _Converted particle(DenizenCommand command) {
    String? effect;
    String audience = 'nearby';
    final JObject arguments = JObject();
    int? count;
    double? spread;
    for (final DenizenArg arg in command.args) {
      switch (arg.prefix) {
        case 'effect':
          effect = arg.value.toLowerCase();
        case 'at':
          final String at = arg.value.toLowerCase();
          if (at != '<npc>' && at != '<npc.location>' && at != '<npc.eye_location>' && !_full(r'<npc\.location\.above(\[[0-9.]{1,6}])?>', at)) {
            return const _Converted.kept('particles show only at the NPC in Animus');
          }
        case 'quantity':
          if (!_full('[0-9]{1,2}', arg.value) || int.parse(arg.value) < 1 || int.parse(arg.value) > 64) return const _Converted.kept('quantity must be from 1 to 64');
          count = int.parse(arg.value);
        case 'offset':
          double widest = 0;
          for (final String part in arg.value.split(',')) {
            final double? value = _number(part, 0, 8);
            if (value == null) return const _Converted.kept('offset must be from 0 to 8 blocks');
            if (value > widest) widest = value;
          }
          spread = widest;
        case 'targets':
          if (!_playerTarget(arg.value)) return const _Converted.kept('particles for other targets have no Animus equivalent');
          audience = 'trigger';
        case 'visibility':
        case 'data':
          break;
        default:
          return _Converted.kept('playeffect argument ${arg.prefix.isEmpty ? arg.value : '${arg.prefix}:'} has no Animus equivalent');
      }
    }
    if (effect == null || effect.contains('<')) return const _Converted.kept('playeffect needs a literal effect');
    String name = effect.startsWith('minecraft:') ? effect.substring(10) : effect;
    if (_dataParticles.contains(name)) return const _Converted.kept('particles that need extra data have no Animus equivalent');
    name = _particleNames[name] ?? name;
    if (!_full('[a-z0-9_]{1,64}', name)) return _Converted.kept('effect $effect has no particle key');
    arguments.addString('particle', 'minecraft:$name');
    if (count != null) arguments.addInt('count', count);
    if (spread != null) arguments.addDouble('spread', spread);
    if (audience == 'nearby') arguments.addString('audience', 'nearby');
    return _Converted.of(<JObject>[_action('animus:spawn-particle', arguments)]);
  }

  _Converted actionbar(DenizenCommand command, _Scope scope) {
    final List<String> words = <String>[];
    for (final DenizenArg arg in command.args) {
      if (arg.prefix.isEmpty) {
        words.add(arg.value);
        continue;
      }
      if (arg.prefix == 'targets' && _playerTarget(arg.value)) continue;
      return _Converted.kept('actionbar argument ${arg.prefix}: has no Animus equivalent');
    }
    final _Mapped text = mapText(words.join(' '), scope, true, 0);
    if (text.reason != null) return _Converted.kept(text.reason!);
    return _Converted.of(<JObject>[_commandAction('minecraft:title {player} actionbar ${_component(text.value!)}')]);
  }

  _Converted title(DenizenCommand command, _Scope scope) {
    String? title;
    String? subtitle;
    final List<int> times = <int>[10, 70, 20];
    bool timed = false;
    for (final DenizenArg arg in command.args) {
      switch (arg.prefix) {
        case 'title':
          title = arg.value;
        case 'subtitle':
          subtitle = arg.value;
        case 'fade_in':
        case 'stay':
        case 'fade_out':
          final int? ticks = denizenTicks(arg.value);
          if (ticks == null || ticks > 72000) return const _Converted.kept('title timing needs a literal duration');
          times[arg.prefix == 'fade_in' ? 0 : arg.prefix == 'stay' ? 1 : 2] = ticks;
          timed = true;
        case 'targets':
          if (!_playerTarget(arg.value)) return const _Converted.kept('titles for other targets have no Animus equivalent');
        default:
          return _Converted.kept('title argument ${arg.prefix.isEmpty ? arg.value : '${arg.prefix}:'} has no Animus equivalent');
      }
    }
    if (title == null && subtitle == null) return const _Converted.kept('title without text');
    final List<JObject> steps = <JObject>[];
    if (timed) steps.add(_commandAction('minecraft:title {player} times ${times[0]} ${times[1]} ${times[2]}'));
    if (subtitle != null) {
      final _Mapped text = mapText(subtitle, scope, true, 0);
      if (text.reason != null) return _Converted.kept(text.reason!);
      steps.add(_commandAction('minecraft:title {player} subtitle ${_component(text.value!)}'));
    }
    final _Mapped main = mapText(title ?? '', scope, true, 0);
    if (main.reason != null) return _Converted.kept(main.reason!);
    steps.add(_commandAction('minecraft:title {player} title ${_component(main.value!)}'));
    for (final JObject step in steps) {
      if (step.object('arguments')!.get('command')!.asPrimitive.asString.length > 1024) return const _Converted.kept('command longer than 1024 characters');
    }
    return _Converted(steps, '');
  }

  // ---- Text and tags ----

  _Mapped mapText(String raw, _Scope scope, bool command, int depth) {
    final StringBuffer out = StringBuffer();
    for (final DenizenSegment segment in denizenSegments(raw)) {
      if (!segment.tag) {
        out.write(segment.text);
        continue;
      }
      final DenizenTag tag = denizenTag(segment.text);
      final String? mapped = mapTag(tag, scope, command, depth);
      if (mapped == null) return _Mapped(null, 'tag ${tag.label} has no Animus placeholder');
      out.write(mapped);
    }
    return _Mapped(out.toString(), null);
  }

  String? mapTag(DenizenTag tag, _Scope scope, bool command, int depth) {
    final List<DenizenPart> parts = tag.parts;
    final String base = tag.base.toLowerCase();
    final String code = command ? '§' : '&';
    if (parts.length == 1 && parts.first.parameter == null) {
      if (_full('&[0-9a-fk-or]', base)) return '$code${base[1]}';
      if (_escapes.containsKey(base)) return _escapes[base];
      if (_colors.containsKey(base)) return '$code${_colors[base]}';
    }
    if (parts.length == 1 && parts.first.parameter != null) {
      final String parameter = parts.first.parameter!;
      if (base == '&color') {
        if (_full('#[0-9a-fA-F]{6}', parameter)) {
          if (!command) return '&$parameter';
          final StringBuffer hex = StringBuffer('§x');
          for (final String digit in parameter.substring(1).toLowerCase().split('')) {
            hex
              ..write('§')
              ..write(digit);
          }
          return hex.toString();
        }
        if (_colors.containsKey(parameter.toLowerCase())) return '$code${_colors[parameter.toLowerCase()]}';
        return null;
      }
      if (base == '&chr' && _full('[0-9a-fA-F]{1,6}', parameter)) {
        final int point = int.parse(parameter, radix: 16);
        if (point > 0x10FFFF || point >= 0xD800 && point <= 0xDFFF || point < 0x20) return null;
        return String.fromCharCode(point);
      }
    }
    if (parts.length == 2 && parts.first.parameter == null && parts[1].parameter == null) {
      final String attribute = parts[1].name.toLowerCase();
      if (base == 'player' && attribute == 'name') return '{player_name}';
      if (base == 'player' && attribute == 'uuid' && command) return '{player}';
      if (base == 'npc' && attribute == 'name' && scope.npcName != null) return scope.npcName;
    }
    if (base == 'script' && depth < 4) {
      DenizenContainer? target = scope.container;
      const int next = 1;
      if (parts.first.parameter != null) {
        target = containers[parts.first.parameter!.toLowerCase()];
        if (target == null) return null;
      }
      if (parts.length != 2 || !<String>['data_key', 'yaml_key'].contains(parts[next].name.toLowerCase()) || parts[next].parameter == null) return null;
      DenizenNode node = target.body;
      for (final String key in parts[next].parameter!.split('.')) {
        if (node is! DenizenMapping) return null;
        final DenizenNode? child = node.get(key.toLowerCase());
        if (child == null) return null;
        node = child;
      }
      if (node is! DenizenScalar) return null;
      final _Mapped mapped = mapText(node.text, _Scope(scope.routines, scope.calling, scope.npcName, false, target, scope.interact, scope.step), command, depth + 1);
      return mapped.reason == null ? mapped.value : null;
    }
    return null;
  }

  // ---- Flag data ----

  int importFlags(String source, DenizenFlagValues values, String scope, String? npc, String? player,
      SplayTreeMap<String, SplayTreeMap<String, JObject>> state, SplayTreeMap<String, int> imported) {
    int kept = 0;
    for (final String problem in values.problems) {
      issues.add('$source: $problem');
    }
    for (final DenizenFlag flag in values.flags.values) {
      if (flag.name.startsWith('__interact_step.') && player != null) {
        kept += interactStep(source, flag, player, state, imported);
        continue;
      }
      String? reason;
      final String? name = _flagName(flag.name);
      final _Kind kind = kindOf(flag.name);
      final JObject value = JObject();
      if (flag.name.startsWith('__')) {
        reason = "Denizen's own bookkeeping flag";
      } else if (flag.expires) {
        reason = 'expiring flags have no Animus equivalent';
      } else if (name == null) {
        reason = 'the name cannot be an Animus flag name';
      } else if (kind == _Kind.boolean && (flag.value == 'true' || flag.value == 'false')) {
        value
          ..addString('type', 'boolean')
          ..addBool('value', flag.value == 'true');
      } else if (kind == _Kind.integer && denizenStage(flag.value) != null) {
        value
          ..addString('type', 'integer')
          ..addInt('value', denizenStage(flag.value)!);
      } else {
        reason = 'the value is not true, false or a whole number from 0 to 10^12 used the same way everywhere';
      }
      if (reason == null) {
        final String path = _statePath(scope, npc, player, kind == _Kind.boolean ? 'flags' : 'quests');
        final SplayTreeMap<String, JObject> document = state.putIfAbsent(path, () => SplayTreeMap<String, JObject>());
        if (document.length >= _maximumStateKeys && !document.containsKey(name)) {
          reason = 'more than 128 flags for one subject';
        } else {
          document[name!] = value;
        }
      }
      if (reason != null) {
        kept++;
        preserve(source, 0, '', 'flag', flag.name, '', reason);
      } else {
        imported[scope] = imported[scope]! + 1;
      }
    }
    return kept;
  }

  int interactStep(String source, DenizenFlag flag, String player, SplayTreeMap<String, SplayTreeMap<String, JObject>> state, SplayTreeMap<String, int> imported) {
    final String script = flag.name.substring('__interact_step.'.length);
    final _Interact? interact = interacts[script];
    String? reason;
    int? stage;
    if (interact == null) {
      reason = 'no interact script $script';
    } else if (flag.expires) {
      reason = 'a step with a duration has no Animus equivalent';
    } else {
      stage = interact.stage(flag.value);
      if (stage == null) reason = 'no step ${flag.value} in $script';
    }
    final List<_Npc> bound = interact == null ? <_Npc>[] : boundNpcs(interact.container);
    if (reason == null && bound.isEmpty) reason = 'no NPC is assigned $script';
    if (reason != null) {
      preserve(source, 0, '', 'flag', flag.name, '', reason);
      return 1;
    }
    for (final _Npc npc in bound) {
      final SplayTreeMap<String, JObject> document = state.putIfAbsent(_statePath('relationship', npc.uuid, player, 'quests'), () => SplayTreeMap<String, JObject>());
      document[interact!.quest] = JObject()
        ..addString('type', 'integer')
        ..addInt('value', stage!);
      imported['relationship'] = imported['relationship']! + 1;
    }
    return 0;
  }

  // ---- Report counts ----

  JObject containerCounts() {
    final SplayTreeMap<String, List<int>> counts = SplayTreeMap<String, List<int>>();
    for (final DenizenContainer container in containers.values) {
      final List<int> count = counts.putIfAbsent(container.type, () => <int>[0, 0, 0, 0]);
      count[0]++;
      bool any = false;
      bool all = true;
      for (final _Outcome outcome in outcomesOf(container)) {
        if (outcome.converted) {
          any = true;
        } else {
          all = false;
        }
      }
      if (any && all) {
        count[1]++;
      } else if (any) {
        count[2]++;
      } else {
        count[3]++;
      }
    }
    final JObject result = JObject();
    for (final MapEntry<String, List<int>> count in counts.entries) {
      result.add(count.key, JObject()
        ..addInt('total', count.value[0])
        ..addInt('converted', count.value[1])
        ..addInt('partial', count.value[2])
        ..addInt('preserved', count.value[3]));
    }
    return result;
  }

  JObject counts(String kind, String yes, String no) {
    final SplayTreeMap<String, List<int>> counts = SplayTreeMap<String, List<int>>();
    for (final _Outcome outcome in outcomes.values) {
      if (outcome.kind != kind) continue;
      counts.putIfAbsent(outcome.name.isEmpty ? '(empty)' : outcome.name, () => <int>[0, 0])[outcome.converted ? 0 : 1]++;
    }
    final JObject result = JObject();
    for (final MapEntry<String, List<int>> count in counts.entries) {
      result.add(count.key, JObject()
        ..addInt(yes, count.value[0])
        ..addInt(no, count.value[1]));
    }
    return result;
  }

  JObject tagCounts() {
    final SplayTreeMap<String, List<int>> counts = SplayTreeMap<String, List<int>>();
    for (final _Outcome outcome in outcomes.values) {
      if (outcome.kind != 'command') continue;
      for (final DenizenTag tag in denizenTags(outcome.text)) {
        counts.putIfAbsent(tag.label, () => <int>[0, 0])[outcome.converted ? 0 : 1]++;
      }
    }
    final JObject result = JObject();
    for (final MapEntry<String, List<int>> count in counts.entries) {
      result.add(count.key, JObject()
        ..addInt('mapped', count.value[0])
        ..addInt('unmapped', count.value[1]));
    }
    return result;
  }
}
