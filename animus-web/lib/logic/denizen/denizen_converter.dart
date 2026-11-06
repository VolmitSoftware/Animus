/// A line-for-line copy of core's `DenizenConverter`: Denizen scripts and flags become Animus behaviors in the
/// `denizen` namespace, chest GUIs and behavior state, with the same staged bytes as the server. Assignment and
/// interact scripts become NPC behaviors; task scripts routines and manual-run behaviors, procedures routines that
/// return a value; world, command and book scripts server behaviors; inventory scripts chest GUIs. Everything that does
/// not convert is listed in the report with its reason. See wiki animus/13-migrating-from-citizens.
library;

import 'dart:collection';
import 'dart:convert';
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

part 'denizen_commands.dart';
part 'denizen_events.dart';
part 'denizen_expressions.dart';

const int denizenConverterVersion = 2;
const String _namespace = 'denizen';
const int denizenMaximumFiles = 512;
const int denizenMaximumPlayers = 10000;
const int _maximumStateKeys = 128;
const int _maximumText = 1024;
const int _defaultRadius = 5;
const String _stateRoot = 'state/behavior-state/';
const String _guiRoot = 'guis/denizen/';
const String _noNpc = 'server behaviors have no NPC';
const String _keyCharacters = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
const Set<String> _scriptTypes = <String>{'task', 'procedure', 'command', 'assignment', 'interact', 'world'};
const List<String> _itemKeys = <String>['type', 'material', 'display name', 'lore', 'debug'];

final RegExp _uuid = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');
final RegExp _name = RegExp(r'^[a-z0-9][a-z0-9_-]{0,63}$');
final RegExp _javaSpaces = RegExp('[ \t\n\x0B\f\r]+');
final Map<String, RegExp> _patterns = <String, RegExp>{};

/// Java `String.matches`: the whole text matches the pattern.
bool _full(String pattern, String text) => (_patterns[pattern] ??= RegExp('^(?:$pattern)\$')).hasMatch(text);

/// Java `replaceAll("\\s+", " ").trim()` after lowercasing.
String _collapse(String text) => javaTrim(text.toLowerCase().replaceAll(_javaSpaces, ' '));

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

  /// The converted behaviors with `"server": true`, in id order.
  List<String> get serverBehaviorIds => <String>[
        for (final JElement id in (report.get('serverBehaviors')! as JArray).elements)
          if (id is JString) id.value,
      ];

  /// `server-behaviors.json` after the review is accepted: the [existing]
  /// list (null when the data folder has none) with this import's server
  /// behaviors appended once each, as the server's `DenizenDropIn` writes
  /// it. Null when the import made no server behavior and there is no list.
  /// The staged tree itself never holds the file, so it stays the same as
  /// the server's.
  String? serverBehaviorsAfterAccept(String? existing) {
    final List<String> added = serverBehaviorIds;
    if (added.isEmpty) return existing;
    final List<String> ids = <String>[];
    if (existing != null) {
      final Object? parsed = jsonDecode(existing);
      if (parsed is Map<String, Object?> && parsed['behaviors'] is List<Object?>) {
        for (final Object? id in parsed['behaviors']! as List<Object?>) {
          if (id is String && !ids.contains(id)) ids.add(id);
        }
      }
    }
    bool changed = false;
    for (final String id in added) {
      if (!ids.contains(id)) {
        ids.add(id);
        changed = true;
      }
    }
    if (ids.length > 256) throw const DenizenImportException('server-behaviors.json would list more than 256 behaviors');
    if (!changed && existing != null) return existing;
    return '${const JsonEncoder.withIndent('  ').convert(<String, Object?>{'schemaVersion': 1, 'behaviors': ids})}\n';
  }
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

class _Interact {
  const _Interact(this.container, this.slug, this.quest, this.steps, this.order, this.defaultIndex);

  final DenizenContainer container;
  final String slug;
  final String quest;
  final List<String> steps;
  final List<String> order;
  final int defaultIndex;

  /// Stage of a step by its name, ignoring case and the default marker.
  int? stage(String name) {
    final String wanted = _stepName(name);
    for (int index = 0; index < steps.length; index++) {
      if (_stepName(steps[index]) == wanted) return index;
    }
    return null;
  }
}

String _stepName(String name) => javaTrim(name.toLowerCase().replaceAll('*', ''));

/// One behavior document under construction: its routines, declared variables and counters for generated names.
class _Doc {
  _Doc(this.server);

  final bool server;
  final LinkedHashMap<String, JObject> routines = LinkedHashMap<String, JObject>();
  final SplayTreeMap<String, String> variables = SplayTreeMap<String, String>();
  int temporaries = 0;
  int loops = 0;
}

/// Where the commands being converted come from and what they may read.
class _Scope {
  _Scope(this.doc, this.calling, this.record, this.container, this.interact, this.step, this.definitions, this.loops, this.saves, this.context)
      : server = doc.server;

  _Scope.of(_Doc doc, bool record, DenizenContainer container, _Interact? interact, int step, Map<String, String> context)
      : this(doc, <String>{}, record, container, interact, step, SplayTreeMap<String, String>(), const <String>[], SplayTreeMap<String, String>(), context);

  final _Doc doc;
  final Set<String> calling;
  final bool record;
  final bool server;
  final DenizenContainer container;
  final _Interact? interact;
  final int step;

  /// Definitions a loop gives another meaning, such as `loop_index`, by lowercase name.
  final Map<String, String> definitions;

  /// The kinds of the enclosing loops, innermost last.
  final List<String> loops;

  /// `save:` names of converted runs and the variables holding their results.
  final Map<String, String> saves;

  /// Denizen context keys the trigger passes, and their Animus names.
  final Map<String, String> context;

  /// The scope of another script run from this one: same document, its own definitions, no loops and no event context.
  _Scope inContainer(DenizenContainer other) => _Scope(doc, calling, false, other, null, -1, SplayTreeMap<String, String>(), const <String>[],
      SplayTreeMap<String, String>(), const <String, String>{});

  _Scope loop(Map<String, String> inner, List<String> kinds) => _Scope(doc, calling, record, container, interact, step, inner, kinds, saves, context);
}

/// The Animus flag or quest name for a Denizen flag: lowercase, sub-flag dots as dashes. Null when it cannot be one.
String? _flagName(String name) {
  final String lowered = name.toLowerCase();
  if (!_full('[a-z0-9_.]+', lowered)) return null;
  final String mapped = lowered.replaceAll('.', '-');
  return _name.hasMatch(mapped) ? mapped : null;
}

/// A variable name from a prefix and a Denizen name: lowercase, other characters as `_`, at most 64 characters.
String _variable(String prefix, String name) {
  final String text = prefix + name.toLowerCase().replaceAll(RegExp('[^a-z0-9_]'), '_');
  return text.length > 64 ? text.substring(0, 64) : text;
}

/// The execution variable holding a Denizen definition.
String _definitionVariable(String name) => _variable('d_', name);

/// The definition names a task or procedure declares, in order: `definitions: a|b` or `a[description]|b`.
List<String> _definitionNames(DenizenContainer container) {
  final List<String> names = <String>[];
  final String? text = container.body.text('definitions');
  if (text == null || javaIsBlank(text)) return names;
  for (final String piece in text.split('|')) {
    final String name = piece.contains('[') ? piece.substring(0, piece.indexOf('[')) : piece;
    if (javaTrim(name).isNotEmpty) names.add(javaTrim(name).toLowerCase());
  }
  return names;
}

String _scopeOf(String target) => switch (target) { 'npc' => 'npc', 'server' => 'global', _ => 'player' };

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

/// One step, or a sequence; more than 128 steps nest in sequences of at most 128.
JObject _sequence(List<JObject> steps) {
  if (steps.length == 1) return steps.first;
  List<JObject> current = steps;
  while (current.length > 128) {
    final List<JObject> grouped = <JObject>[];
    for (int index = 0; index < current.length; index += 128) {
      grouped.add(_plainSequence(current.sublist(index, index + 128 < current.length ? index + 128 : current.length)));
    }
    current = grouped;
  }
  return _plainSequence(current);
}

JObject _plainSequence(List<JObject> steps) {
  if (steps.length == 1) return steps.first;
  return JObject()
    ..addString('type', 'sequence')
    ..add('steps', JArray(<JElement>[...steps]));
}

/// A Bukkit sound name such as `ENTITY_VILLAGER_YES` becomes `minecraft:entity.villager.yes`: the first and last
/// underscores become dots and the middle words keep theirs. Names written as keys keep their key.
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

/// The staged flag document of one subject.
String _statePath(String scope, String? npc, String? player) {
  final StringBuffer path = StringBuffer(_stateRoot)
    ..write(scope)
    ..write('/');
  if (npc != null) path.write('$npc/');
  if (player != null) path.write('$player/');
  path.write('animus/flags/$_namespace.json');
  return path.toString();
}

/// The same document `BehaviorStateStore` writes for these values.
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

/// The `[item]` cells of a slot row; `[]` is an empty cell.
List<String>? _cells(String line) {
  final List<String> cells = <String>[];
  int depth = 0;
  final StringBuffer current = StringBuffer();
  final String trimmed = javaTrim(line);
  for (int index = 0; index < trimmed.length; index++) {
    final String at = trimmed[index];
    if (at == '[') {
      if (depth++ > 0) current.write(at);
      continue;
    }
    if (at == ']') {
      if (depth == 0) return null;
      if (--depth == 0) {
        cells.add(current.toString());
        current.clear();
      } else {
        current.write(at);
      }
      continue;
    }
    if (depth > 0) {
      current.write(at);
    } else if (at != ' ') {
      return null;
    }
  }
  return depth == 0 ? cells : null;
}

class _Converter {
  _Converter() {
    expressions = _Expressions(this);
    commands = _Commands(this, expressions);
  }

  final SplayTreeMap<String, DenizenContainer> containers = SplayTreeMap<String, DenizenContainer>();
  final SplayTreeMap<String, _Interact> interacts = SplayTreeMap<String, _Interact>();
  final SplayTreeMap<String, String> sounds = SplayTreeMap<String, String>();
  final SplayTreeMap<String, _Outcome> outcomes = SplayTreeMap<String, _Outcome>();
  final List<String> issues = <String>[];
  final JArray preserved = JArray();
  final SplayTreeMap<String, String> slugs = SplayTreeMap<String, String>();
  final Set<String> usedSlugs = <String>{};
  final List<_Npc> npcs = <_Npc>[];
  final SplayTreeMap<String, String> guis = SplayTreeMap<String, String>();
  final SplayTreeSet<String> serverBehaviors = SplayTreeSet<String>();
  final SplayTreeMap<String, List<String>> guards = SplayTreeMap<String, List<String>>();
  late final _Expressions expressions;
  late final _Commands commands;

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

    final SplayTreeMap<String, String> files = SplayTreeMap<String, String>();
    final SplayTreeMap<String, List<String>> bindings = SplayTreeMap<String, List<String>>();
    final JObject behaviorStatus = JObject();
    final SplayTreeMap<String, String> documents = SplayTreeMap<String, String>();
    // Values come first: other scripts read data, give items and books and open inventories by name.
    for (final DenizenContainer container in containers.values) {
      switch (container.type) {
        case 'data':
          record(container, container.line, 'container', 'data', container.name, true, '');
        case 'item':
          itemContainer(container);
        case 'book':
          bookContainer(container, documents);
        case 'inventory':
          inventoryContainer(container);
        default:
          break;
      }
    }
    for (final DenizenContainer container in containers.values) {
      switch (container.type) {
        case 'data':
        case 'item':
        case 'book':
        case 'inventory':
          break;
        case 'task':
          task(container, documents);
        case 'procedure':
          procedureContainer(container);
        case 'assignment':
          assignment(container, documents);
        case 'interact':
          interactDocument(interacts[container.name.toLowerCase()]!, documents);
        case 'world':
          world(container, documents);
        case 'command':
          commandContainer(container, documents);
        default:
          preserveContainer(
              container,
              switch (container.type) {
                'format' => "format scripts shape Denizen's own chat output; Animus messages are written out in full",
                'economy' => 'economy scripts provide a Vault economy; Animus has none to provide',
                'enchantment' => 'custom enchantments have no Animus equivalent',
                'map' => 'map scripts draw on map items; Animus has no map renderer',
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
    for (final MapEntry<String, String> gui in guis.entries) {
      files['$_guiRoot${gui.key}.json'] = gui.value;
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
    for (final MapEntry<String, List<String>> guard in guards.entries) {
      final SplayTreeMap<String, JObject> document = state.putIfAbsent(_statePath('npc', guard.key, null), () => SplayTreeMap<String, JObject>());
      for (final String flag in guard.value) {
        if (document.length >= _maximumStateKeys && !document.containsKey(flag)) {
          issues.add('${guard.key}: no room for the guard flag $flag; its on assignment runs again on the next spawn');
          continue;
        }
        document[flag] = JObject()
          ..addString('type', 'boolean')
          ..addBool('value', true);
        imported['npc'] = imported['npc']! + 1;
      }
    }
    for (final MapEntry<String, SplayTreeMap<String, JObject>> document in state.entries) {
      files[document.key] = _stateDocument(document.key, document.value);
    }

    final JObject report = JObject()
      ..addInt('schemaVersion', 1)
      ..addInt('converterVersion', denizenConverterVersion)
      ..addString('importId', id)
      ..addString('notice',
          'Data conversion only. Generated behaviors are listed on no NPC and server behaviors stay off until the denizen review is accepted, and commands stay denied until allow-listed.')
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
    report.add(
        'flags',
        JObject()
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
    report.add(
        'commandBehaviors',
        JArray(<JElement>[
          for (final MapEntry<String, String> document in documents.entries)
            if (document.value.contains('"animus:command"')) JString(document.key),
        ]));
    report.add('serverBehaviors', JArray(<JElement>[for (final String behavior in serverBehaviors) if (documents.containsKey(behavior)) JString(behavior)]));
    report.add('guis', JArray(<JElement>[for (final String gui in guis.keys) JString('$_guiRoot$gui.json')]));
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

  /// NPCs whose assignments reach the container: the assignment itself or an assignment listing this interact script.
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

  /// The trigger setting of the NPCs reached by the container: enabled when any has it on, otherwise Denizen's defaults.
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

  /// A number of seconds stored per trigger in the NPCs' triggers trait, from the first NPC that has one.
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

  /// A path segment for a script name: lowercase, other characters as dashes, at most 64 characters, unique.
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

  // ---- Variables ----

  /// Declares an execution variable of the document once; `any` holds every value, `integer` loop counters.
  void declare(_Scope scope, String name, String type) => scope.doc.variables.putIfAbsent(name, () => type);

  /// A fresh temporary variable of the document.
  String temporary(_Scope scope) {
    final String name = 't_${++scope.doc.temporaries}';
    declare(scope, name, 'any');
    return name;
  }

  /// A fresh loop counter of the document; each loop has its own, since every routine of a run shares variables.
  String loopIndex(_Scope scope) {
    final String name = 'i_${++scope.doc.loops}';
    declare(scope, name, 'integer');
    return name;
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

  void preserveContainer(DenizenContainer container, String reason) {
    record(container, container.line, 'container', container.type, container.name, false, reason);
    // Only script-running containers hold command lines; lists in data, book or inventory scripts are values.
    if (!_scriptTypes.contains(container.type)) return;
    final _Scope scope = _Scope.of(_Doc(false), true, container, null, -1, const <String, String>{});
    preserveNode(scope, container.body, reason);
  }

  void preserveNode(_Scope scope, DenizenNode node, String reason) {
    if (node is DenizenMapping) {
      for (final DenizenEntry entry in node.entries) {
        preserveNode(scope, entry.value, reason);
      }
    } else if (node is DenizenSequence) {
      for (final DenizenItem item in node.items) {
        commands.preserveTree(scope, item, reason);
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
      'task' || 'assignment' || 'interact' || 'world' || 'command' || 'book' => behaviorId(container.type, container),
      _ => '',
    };
  }

  List<_Outcome> outcomesOf(DenizenContainer container) => <_Outcome>[
        for (final _Outcome outcome in outcomes.values)
          if (outcome.container.toLowerCase() == container.name.toLowerCase() && outcome.file == container.file) outcome,
      ];

  /// Records every key of a container outside [allowed] as kept, with a reason naming it.
  void otherKeys(DenizenContainer container, List<String> allowed, String what) {
    for (final DenizenEntry entry in container.body.entries) {
      final String key = entry.key.toLowerCase();
      if (allowed.contains(key)) continue;
      record(container, entry.line, 'event', key, entry.key, false, '$what setting $key has no Animus equivalent');
    }
  }

  // ---- Tasks and procedures ----

  void task(DenizenContainer container, SplayTreeMap<String, String> documents) {
    final DenizenNode? script = container.body.get('script');
    if (script is! DenizenSequence) {
      preserveContainer(container, 'task script without a script list');
      return;
    }
    final _Doc doc = _Doc(false);
    final _Scope scope = _Scope.of(doc, true, container, null, -1, const <String, String>{});
    scope.calling.add(routineFor(container, null));
    doc.routines['main'] = JObject();
    // Other lists in a task are paths that run with path:; each becomes a routine of the task's own behavior.
    for (final DenizenEntry entry in container.body.entries) {
      final String key = entry.key.toLowerCase();
      final DenizenNode path = entry.value;
      if (key == 'script' || path is! DenizenSequence) continue;
      final String routine = routineFor(container, key);
      if (!_reserve(doc.routines, routine)) {
        record(container, entry.line, 'event', 'task path', entry.key, false, 'more than 32 routines in one behavior');
        commands.preserveItems(scope, path.items, 0, 'inside a task path past the routine limit');
        continue;
      }
      scope.calling.add(routine);
      doc.routines[routine] = _sequence(commands.block(path.items, 0, scope));
      scope.calling.remove(routine);
    }
    doc.routines['main'] = _sequence(commands.block(script.items, 0, scope));
    final String empty = _sequence(<JObject>[]).toString();
    bool allEmpty = true;
    for (final JObject routine in doc.routines.values) {
      if (routine.toString() != empty) allEmpty = false;
    }
    if (allEmpty) return;
    document(behaviorId('task', container), doc, container, JArray(), documents);
  }

  String routineFor(DenizenContainer task, String? path) {
    final String routine = 'task-${slug(task.name)}${path == null ? '' : '-${path.toLowerCase().replaceAll(RegExp('[^a-z0-9_-]+'), '-')}'}';
    return routine.length > 64 ? routine.substring(0, 64) : routine;
  }

  /// A procedure's own lines are converted once to record what converts; its routine is made in each document that reads it.
  void procedureContainer(DenizenContainer container) {
    final DenizenNode? script = container.body.get('script');
    if (script is! DenizenSequence) {
      preserveContainer(container, 'procedure script without a script list');
      return;
    }
    final _Doc doc = _Doc(false);
    final _Scope scope = _Scope.of(doc, true, container, null, -1, const <String, String>{});
    scope.calling.add(procedureName(container));
    commands.block(script.items, 0, scope);
    otherKeys(container, <String>['type', 'script', 'definitions', 'debug'], 'procedure');
    if (outcomesOf(container).isEmpty) record(container, container.line, 'container', 'procedure', container.name, true, '');
  }

  String procedureName(DenizenContainer procedure) {
    final String routine = 'proc-${slug(procedure.name)}';
    return routine.length > 64 ? routine.substring(0, 64) : routine;
  }

  /// The routine of a procedure in the scope's document, made on first use. Its value comes back with `return`.
  _Result<String> procedureRoutine(DenizenContainer procedure, _Scope scope) {
    final String routine = procedureName(procedure);
    if (scope.calling.contains(routine)) return const _Result<String>.fail('recursive procedure calls have no Animus equivalent');
    if (scope.doc.routines.containsKey(routine)) return _Result<String>.ok(routine);
    final DenizenNode? script = procedure.body.get('script');
    if (script is! DenizenSequence) return _Result<String>.fail('procedure ${procedure.name} has no script list');
    if (!_reserve(scope.doc.routines, routine)) return const _Result<String>.fail('more than 32 routines in one behavior');
    scope.calling.add(routine);
    final List<JObject> steps = commands.block(script.items, 0, scope.inContainer(procedure));
    scope.calling.remove(routine);
    scope.doc.routines[routine] = _sequence(steps);
    return _Result<String>.ok(routine);
  }

  // ---- Assignments ----

  void assignment(DenizenContainer container, SplayTreeMap<String, String> documents) {
    final _Doc doc = _Doc(false);
    final JArray triggers = JArray();
    final DenizenNode? actions = container.body.get('actions');
    DenizenSequence? onAssignment;
    if (actions is DenizenMapping) {
      final DenizenNode? assigned = actions.get('on assignment');
      if (assigned is DenizenSequence) onAssignment = assigned;
    }
    final int range = radius(container, onAssignment);
    if (actions is DenizenMapping) {
      for (final DenizenEntry entry in actions.entries) {
        final String event = _collapse(entry.key);
        final DenizenNode sequence = entry.value;
        if (sequence is! DenizenSequence) {
          record(container, entry.line, 'event', event, entry.key, false, 'action without a script list');
          continue;
        }
        final _Scope scope = _Scope.of(doc, true, container, null, -1, const <String, String>{});
        if (event == 'on assignment') {
          assignmentAction(container, entry, sequence, scope, triggers);
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
          commands.preserveItems(scope, sequence.items, 0, 'inside an action that is kept: no Animus trigger for this action');
          continue;
        }
        if (mapped[2].isNotEmpty && !enabled(container, mapped[2], onAssignment)) {
          record(container, entry.line, 'event', event, entry.key, false, 'the ${mapped[2]} trigger is off on every NPC with this assignment');
          commands.preserveItems(scope, sequence.items, 0, 'inside an action whose trigger is off');
          continue;
        }
        final String routine = event.replaceAll(' ', '-');
        if (doc.routines.length >= behaviorMaximumRoutines) {
          record(container, entry.line, 'event', event, entry.key, false, 'more than 32 routines in one behavior');
          commands.preserveItems(scope, sequence.items, 0, 'inside an action past the routine limit');
          continue;
        }
        doc.routines[routine] = JObject();
        doc.routines[routine] = _sequence(commands.block(sequence.items, 0, scope));
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
    otherKeys(container, <String>['type', 'actions', 'interact scripts', 'debug'], 'assignment');
    if (doc.routines.isEmpty) {
      if (outcomesOf(container).isEmpty) record(container, container.line, 'container', 'assignment', container.name, true, '');
      return;
    }
    if (document(behaviorId('assignment', container), doc, container, triggers, documents) && doc.routines.containsKey('on-assignment')) {
      for (final _Npc npc in npcs) {
        if (npc.assignments.contains(container.name.toLowerCase())) guards.putIfAbsent(npc.uuid, () => <String>[]).add(guardFlag(container));
      }
    }
  }

  String guardFlag(DenizenContainer container) {
    final String flag = 'assigned-${slug(container.name)}';
    return flag.length > 64 ? flag.substring(0, 64) : flag;
  }

  /// `on assignment`: trigger lines are read as settings; any other commands become the `on-assignment` routine, run
  /// by a spawn trigger and guarded by the NPC flag `assigned-<slug>`, so it runs once per NPC. The import sets that
  /// flag on the NPCs that already had the assignment.
  void assignmentAction(DenizenContainer container, DenizenEntry entry, DenizenSequence sequence, _Scope scope, JArray triggers) {
    record(container, entry.line, 'event', 'on assignment', entry.key, true, '');
    final List<DenizenItem> rest = <DenizenItem>[];
    for (final DenizenItem item in sequence.items) {
      if (denizenCommand(item.text).name == 'trigger' && item.children == null) {
        outcome(scope, item, 'command', 'trigger', true, '');
      } else {
        rest.add(item);
      }
    }
    if (rest.isEmpty) return;
    if (!_reserve(scope.doc.routines, 'on-assignment')) {
      commands.preserveItems(scope, rest, 0, 'inside on assignment past the routine limit');
      return;
    }
    final String flag = guardFlag(container);
    final List<JObject> steps = <JObject>[
      _action(
          'animus:set-flag',
          JObject()
            ..addString('name', flag)
            ..addString('scope', 'npc')),
      ...commands.block(rest, 0, scope),
    ];
    final JObject guard = JObject()
      ..addString('type', 'condition')
      ..addString('expression', "!has_flag('npc', ${_quote(flag)})")
      ..add('then', _sequence(steps));
    scope.doc.routines['on-assignment'] = guard;
    triggers.add(JObject()
      ..addString('id', 'on-assignment')
      ..addString('event', 'spawn')
      ..addString('routine', 'on-assignment'));
  }

  /// The interact scripts an assignment lists, in order, as `- name` or `- 10 name`.
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

  // ---- Interact scripts ----

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
    final _Doc doc = _Doc(false);
    final LinkedHashMap<String, JObject> routines = doc.routines;
    final JArray triggers = JArray();
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
      final _Scope scope = _Scope.of(doc, true, container, interact, position, const <String, String>{});
      final List<JObject> clickSteps = <JObject>[];
      bool click = false;
      final JArray options = JArray();
      for (final DenizenEntry trigger in body.entries) {
        final String triggerName = _collapse(trigger.key);
        switch (triggerName) {
          case 'click trigger':
            final DenizenSequence? script = _scriptOf(trigger.value);
            if (script == null) {
              record(container, trigger.line, 'event', triggerName, trigger.key, false, 'trigger without a script list');
              continue;
            }
            if (!enabled(container, 'click', assigned)) {
              record(container, trigger.line, 'event', triggerName, trigger.key, false, 'the click trigger is off on every NPC with this script');
              commands.preserveItems(scope, script.items, 0, 'inside a trigger that is off');
              continue;
            }
            clickSteps.addAll(commands.block(script.items, 0, scope));
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
                if (script != null) commands.preserveItems(scope, script.items, 0, 'inside a proximity phase that is kept');
                continue;
              }
              if (!on) {
                record(container, phase.line, 'event', label, phase.key, false, 'the proximity trigger is off on every NPC with this script');
                commands.preserveItems(scope, script.items, 0, 'inside a trigger that is off');
                continue;
              }
              final String routine = 's$stage-${which == 'entry' ? 'enter' : 'exit'}';
              if (!_reserve(routines, routine)) {
                record(container, phase.line, 'event', label, phase.key, false, 'more than 32 routines in one behavior');
                commands.preserveItems(scope, script.items, 0, 'inside a trigger past the routine limit');
                continue;
              }
              routines[routine] = _sequence(commands.block(script.items, 0, scope));
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
                if (script != null) commands.preserveItems(scope, script.items, 0, 'inside a chat answer that is kept');
                continue;
              }
              routines[routine] = _sequence(commands.block(script!.items, 0, scope));
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
            if (script != null) commands.preserveItems(scope, script.items, 0, 'inside a damage trigger that is kept');
          default:
            record(container, trigger.line, 'event', triggerName, trigger.key, false, 'no Animus trigger for $triggerName');
            final DenizenSequence? script = _scriptOf(trigger.value);
            if (script != null) commands.preserveItems(scope, script.items, 0, 'inside a trigger that is kept');
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
          chain = _build(_Capability('animus:quest-state', arguments), call, chain);
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
    otherKeys(container, <String>['type', 'steps', 'debug'], 'interact');
    if (routines.isEmpty) {
      record(container, container.line, 'container', 'interact', container.name, false, 'no step trigger converts');
      return;
    }
    // Dispatchers go first so the behavior's entry is the click dispatcher when there is one.
    final LinkedHashMap<String, JObject> sorted = LinkedHashMap<String, JObject>();
    for (final String first in <String>['click', 'enter', 'exit']) {
      if (routines.containsKey(first)) sorted[first] = routines[first]!;
    }
    for (final MapEntry<String, JObject> routine in routines.entries) {
      if (!sorted.containsKey(routine.key)) sorted[routine.key] = routine.value;
    }
    routines
      ..clear()
      ..addAll(sorted);
    document(behaviorId('interact', container), doc, container, triggers, documents);
  }

  /// The on assignment action of the first assignment, by name, that is this script or lists it.
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

  // ---- Server behaviors ----

  /// A world script: one server behavior whose routines are its events, each run by the matching server trigger.
  void world(DenizenContainer container, SplayTreeMap<String, String> documents) {
    final DenizenNode? events = container.body.get('events');
    final _Doc doc = _Doc(true);
    final JArray triggers = JArray();
    if (events is DenizenMapping) {
      for (final DenizenEntry entry in events.entries) {
        final String key = _collapse(entry.key);
        final String label = 'world $key';
        final _Scope kept = _Scope.of(doc, true, container, null, -1, const <String, String>{});
        final DenizenNode sequence = entry.value;
        if (sequence is! DenizenSequence) {
          record(container, entry.line, 'event', label, entry.key, false, 'event without a script list');
          preserveNode(kept, entry.value, 'inside a world event that is kept');
          continue;
        }
        final _Mapped mapped = _denizenEvent(key);
        String? reason = mapped.reason;
        String base = 'on-${_eventPhrase(key).replaceAll(RegExp('[^a-z0-9_-]+'), '-').replaceAll(RegExp(r'^-+|-+$'), '')}';
        if (base.length > 60) base = base.substring(0, 60);
        String routine = base;
        for (int suffix = 2; doc.routines.containsKey(routine); suffix++) {
          routine = '$base-$suffix';
        }
        if (reason == null && triggers.length >= behaviorMaximumTriggers) reason = 'more than 32 triggers in one behavior';
        if (reason == null && !_reserve(doc.routines, routine)) reason = 'more than 32 routines in one behavior';
        if (reason != null) {
          record(container, entry.line, 'event', label, entry.key, false, reason);
          commands.preserveItems(kept, sequence.items, 0, 'inside a world event that is kept');
          continue;
        }
        record(container, entry.line, 'event', label, entry.key, true, '');
        final _Scope scope = _Scope.of(doc, true, container, null, -1, mapped.context);
        JObject body = _sequence(commands.block(sequence.items, 0, scope));
        for (int index = mapped.guards.length - 1; index >= 0; index--) {
          body = _build(mapped.guards[index], body, null);
        }
        doc.routines[routine] = body;
        final JObject trigger = JObject()..addString('id', routine);
        for (final MapEntry<String, JElement> field in mapped.trigger!.entries) {
          trigger.add(field.key, field.value);
        }
        trigger.addString('routine', routine);
        triggers.add(trigger);
      }
    } else {
      record(container, container.line, 'container', 'world', container.name, false, 'world script without events');
    }
    otherKeys(container, <String>['type', 'events', 'debug', 'enabled'], 'world');
    if (doc.routines.isEmpty) return;
    document(behaviorId('world', container), doc, container, triggers, documents);
  }

  /// A command script: a server behavior with a `command` trigger whose run reads `context.args`.
  void commandContainer(DenizenContainer container, SplayTreeMap<String, String> documents) {
    final DenizenNode? script = container.body.get('script');
    final String? written = container.body.text('name');
    final String? name = written == null ? null : javaTrim(written).toLowerCase();
    if (script is! DenizenSequence) {
      preserveContainer(container, 'command script without a script list');
      return;
    }
    if (name == null || !_full('[a-z0-9_-]{1,32}', name)) {
      preserveContainer(container, 'command name ${name ?? '(none)'} is not 1-32 lowercase letters, digits, _ or -');
      return;
    }
    final JObject trigger = JObject()
      ..addString('id', 'command')
      ..addString('event', 'command')
      ..addString('name', name);
    final DenizenNode? aliases = container.body.get('aliases');
    if (aliases != null) {
      final JArray list = JArray();
      final List<String> names = <String>[];
      if (aliases is DenizenSequence) {
        for (final DenizenItem item in aliases.items) {
          names.add(item.text);
        }
      } else if (aliases is DenizenScalar) {
        names.addAll(aliases.text.split('|'));
      }
      for (final String alias in names) {
        final String lowered = javaTrim(alias).toLowerCase();
        if (!_full('[a-z0-9_-]{1,32}', lowered) || list.length >= 8) {
          issues.add('${container.file}:${aliases.line}: command alias $alias is not used; Animus takes up to 8 lowercase aliases');
        } else {
          list.addString(lowered);
        }
      }
      if (list.isNotEmpty) trigger.add('aliases', list);
    }
    for (final String field in <String>['permission', 'description', 'usage']) {
      final String? value = container.body.text(field);
      if (value == null || javaIsBlank(value)) continue;
      if (value.length > 256) {
        issues.add('${container.file}:${container.line}: command $field is longer than 256 characters and is not used');
        continue;
      }
      trigger.addString(field, javaTrim(value));
    }
    trigger.addString('routine', 'main');
    final _Doc doc = _Doc(true);
    doc.routines['main'] = JObject();
    final _Scope scope = _Scope.of(doc, true, container, null, -1, _commandContext());
    doc.routines['main'] = _sequence(commands.block(script.items, 0, scope));
    otherKeys(container, <String>['type', 'name', 'script', 'aliases', 'permission', 'description', 'usage', 'debug'], 'command');
    document(behaviorId('command', container), doc, container, JArray()..add(trigger), documents);
  }

  // ---- Books, items and inventories ----

  /// A book script as an `animus:give-book` action, or the reason it has none.
  _Result<JObject> bookAction(DenizenContainer book, _Scope scope, _Pending pending) {
    final _Action action = _Action();
    for (final String field in <String>['title', 'author']) {
      final String? value = book.body.text(field);
      if (value == null) return _Result<JObject>.fail('book script without a $field');
      final _Result<_Text> text = expressions.text(value, scope, pending, false, 0);
      if (text.failed) return _Result<JObject>.fail(text.reason!);
      if (!text.value!.isDynamic && text.value!.literal!.length > 32) return _Result<JObject>.fail('book $field longer than 32 characters');
      action.text(field, text.value!);
    }
    final DenizenNode? pages = book.body.get('text');
    if (pages is! DenizenSequence || pages.items.isEmpty) return const _Result<JObject>.fail('book script without text pages');
    if (pages.items.length > 50) return const _Result<JObject>.fail('book with more than 50 pages');
    final JArray list = JArray();
    for (int index = 0; index < pages.items.length; index++) {
      final _Result<_Text> text = expressions.text(pages.items[index].text, scope, pending, false, 0);
      if (text.failed) return _Result<JObject>.fail(text.reason!);
      if (!text.value!.isDynamic) {
        if (text.value!.literal!.length > 1024) return const _Result<JObject>.fail('book page longer than 1024 characters');
        list.addString(text.value!.literal!);
      } else {
        list.add(JNull.instance);
        action.templates.addString('/pages/$index', text.value!.template);
      }
    }
    action.put('pages', list);
    return _Result<JObject>.ok(action.node('animus:give-book'));
  }

  JObject? book(DenizenContainer book, _Scope scope, _Pending pending) {
    final _Result<JObject> action = bookAction(book, scope, pending);
    return action.failed ? null : action.value;
  }

  void bookContainer(DenizenContainer container, SplayTreeMap<String, String> documents) {
    final _Doc doc = _Doc(true);
    final _Scope scope = _Scope.of(doc, true, container, null, -1, const <String, String>{});
    final _Pending pending = _Pending();
    final _Result<JObject> action = bookAction(container, scope, pending);
    if (action.failed) {
      preserveContainer(container, action.reason!);
      return;
    }
    doc.routines['main'] = _sequence(<JObject>[...pending.steps, action.value!]);
    record(container, container.line, 'container', 'book', container.name, true, '');
    otherKeys(container, <String>['type', 'title', 'author', 'signed', 'text', 'debug'], 'book');
    document(behaviorId('book', container), doc, container, JArray(), documents);
  }

  /// Fills a give-item action from an item script with only a material, a display name and lore; otherwise the reason.
  String? itemScript(DenizenContainer item, _Action action, _Scope scope, _Pending pending) {
    for (final DenizenEntry entry in item.body.entries) {
      if (!_itemKeys.contains(entry.key.toLowerCase())) return 'item script ${item.name} sets ${entry.key}, which Animus items lack';
    }
    final String? written = item.body.text('material');
    final String? material = written == null ? null : commands.material(written);
    if (material == null) return 'item script ${item.name} has no plain material';
    action.put('item', JString(material));
    final String? name = item.body.text('display name');
    if (name != null) {
      final _Result<_Text> text = expressions.text(name, scope, pending, false, 0);
      if (text.failed) return text.reason;
      action.text('name', text.value!);
    }
    final DenizenNode? lore = item.body.get('lore');
    if (lore != null) {
      if (lore is! DenizenSequence || lore.items.length > 16) return 'item lore must be a list of up to 16 lines';
      final JArray list = JArray();
      for (final DenizenItem line in lore.items) {
        final _Result<_Text> text = expressions.text(line.text, scope, pending, false, 0);
        if (text.failed) return text.reason;
        if (text.value!.isDynamic) return 'item lore with tags has no Animus equivalent';
        list.addString(text.value!.literal!);
      }
      action.put('lore', list);
    }
    return null;
  }

  void itemContainer(DenizenContainer container) {
    final String? problem =
        itemScript(container, _Action(), _Scope.of(_Doc(false), false, container, null, -1, const <String, String>{}), _Pending());
    if (problem != null) {
      record(container, container.line, 'container', 'item', container.name, false, '$problem; only give converts item scripts');
    } else {
      record(container, container.line, 'container', 'item', container.name, true, '');
    }
  }

  String? guiId(DenizenContainer inventory) {
    final String slugged = slug(inventory.name);
    return guis.containsKey(slugged) ? '$_namespace:$slugged' : null;
  }

  /// An inventory script as a chest GUI: its title, size and fixed items as a mask of decoration keys.
  void inventoryContainer(DenizenContainer container) {
    final _Scope scope = _Scope.of(_Doc(false), true, container, null, -1, const <String, String>{});
    final _Pending pending = _Pending();
    final String type = javaTrim(container.body.text('inventory') ?? 'chest').toLowerCase();
    int width = 9;
    int rows;
    String? resolution;
    switch (type) {
      case 'chest':
        resolution = null;
      case 'hopper':
        width = 5;
        resolution = '5x1';
      case 'dropper':
      case 'dispenser':
        width = 3;
        resolution = '3x3';
      default:
        preserveContainer(container, 'inventory type $type has no Animus chest GUI');
        return;
    }
    final DenizenNode? slots = container.body.get('slots');
    final List<List<String>> lines = <List<String>>[];
    if (slots != null) {
      if (slots is! DenizenSequence) {
        preserveContainer(container, 'slots is not a list of rows');
        return;
      }
      for (final DenizenItem line in slots.items) {
        final List<String>? cells = _cells(line.text);
        if (cells == null || cells.length > width) {
          preserveContainer(container, 'slot row ${line.text} is not up to $width [item] cells');
          return;
        }
        lines.add(cells);
      }
    }
    if (resolution == null) {
      final String? size = container.body.text('size');
      rows = lines.length > 1 ? lines.length : 1;
      if (size != null) {
        final String trimmed = javaTrim(size);
        if (!_full('[0-9]{1,2}', trimmed) || int.parse(trimmed) % 9 != 0 || int.parse(trimmed) < 9 || int.parse(trimmed) > 54) {
          preserveContainer(container, 'size $size is not 9 to 54 in rows of 9');
          return;
        }
        rows = int.parse(trimmed) ~/ 9;
      } else if (lines.isEmpty) {
        rows = 3;
      }
      if (lines.length > rows) {
        preserveContainer(container, 'more slot rows than the size holds');
        return;
      }
      if (rows > 6) {
        preserveContainer(container, 'more than 6 rows');
        return;
      }
      resolution = '9x$rows';
    } else if (lines.length > (width == 5 ? 1 : 3)) {
      preserveContainer(container, 'more slot rows than a $type holds');
      return;
    }
    final SplayTreeMap<String, String> definitions = SplayTreeMap<String, String>();
    final DenizenNode? defined = container.body.get('definitions');
    if (defined is DenizenMapping) {
      for (final DenizenEntry entry in defined.entries) {
        final DenizenNode value = entry.value;
        if (value is DenizenScalar) definitions[entry.key.toLowerCase()] = value.text;
      }
    }
    final LinkedHashMap<String, String> keys = LinkedHashMap<String, String>();
    final JObject keyObjects = JObject();
    final JArray mask = JArray();
    for (final List<String> line in lines) {
      final StringBuffer row = StringBuffer();
      for (final String cell in line) {
        final String value = javaTrim(definitions[cell.toLowerCase()] ?? cell);
        if (value.isEmpty || value.toLowerCase() == 'air') {
          row.write(' ');
          continue;
        }
        final _Result<JObject> icon = this.icon(value, scope, pending);
        if (icon.failed) {
          preserveContainer(container, icon.reason!);
          return;
        }
        final String text = icon.value!.toString();
        if (!keys.containsKey(text)) {
          if (keys.length >= _keyCharacters.length) {
            preserveContainer(container, 'more distinct items than mask keys');
            return;
          }
          final String key = _keyCharacters[keys.length];
          keys[text] = key;
          keyObjects.add(
              key,
              JObject()
                ..addString('type', 'decoration')
                ..add('icon', icon.value));
        }
        row.write(keys[text]);
      }
      while (row.length < width) {
        row.write(' ');
      }
      mask.addString(row.toString());
    }
    final int height = int.parse(resolution.substring(resolution.indexOf('x') + 1));
    while (mask.isNotEmpty && mask.length < height) {
      mask.addString(' ' * width);
    }
    final JObject gui = JObject()
      ..addInt('schemaVersion', 1)
      ..addInt('revision', 1);
    final String? title = container.body.text('title');
    if (title != null) {
      final _Result<_Text> text = expressions.text(title, scope, pending, false, 0);
      if (text.failed) {
        preserveContainer(container, 'title: ${text.reason}');
        return;
      }
      if (text.value!.isDynamic) {
        preserveContainer(container, 'a title with tags has no chest GUI equivalent');
        return;
      }
      gui.addString('title', text.value!.literal!);
    }
    gui.addString('resolution', resolution);
    if (mask.isNotEmpty) {
      gui
        ..add('mask', mask)
        ..add('keys', keyObjects);
    }
    if (pending.steps.isNotEmpty) {
      preserveContainer(container, 'inventory items read map constants or procedures');
      return;
    }
    record(container, container.line, 'container', 'inventory', container.name, true, '');
    for (final DenizenEntry entry in container.body.entries) {
      final String key = entry.key.toLowerCase();
      if (<String>['type', 'inventory', 'title', 'size', 'slots', 'definitions', 'debug'].contains(key)) continue;
      final DenizenNode value = entry.value;
      if (key == 'gui' && value is DenizenScalar && javaTrim(value.text).toLowerCase() == 'true') continue;
      final String reason = switch (key) {
        'gui' => 'gui: false lets players move items; Animus windows never let items move',
        'procedural items' => 'procedural items run a script to fill slots; Animus chest GUIs draw fixed items',
        _ => 'inventory setting $key has no Animus equivalent',
      };
      record(container, entry.line, 'event', key, entry.key, false, reason);
    }
    guis[slug(container.name)] = writeJson(gui);
  }

  /// A GUI icon: a material, a material with `[quantity=n]`, or a simple item script.
  _Result<JObject> icon(String value, _Scope scope, _Pending pending) {
    String name = value;
    int count = 1;
    if (name.endsWith(']') && name.contains('[')) {
      final String properties = name.substring(name.indexOf('[') + 1, name.length - 1);
      name = name.substring(0, name.indexOf('['));
      if (!_full('quantity=[0-9]{1,2}', properties.toLowerCase())) return _Result<JObject>.fail('item property $properties has no chest GUI equivalent');
      count = int.parse(properties.substring(9));
      if (count < 1 || count > 64) return const _Result<JObject>.fail('item quantity must be from 1 to 64');
    }
    final JObject icon = JObject()..addString('type', 'item');
    final DenizenContainer? script = containers[name.toLowerCase()];
    if (script != null && script.type == 'item') {
      final _Action action = _Action();
      final String? problem = itemScript(script, action, scope, pending);
      if (problem != null) return _Result<JObject>.fail(problem);
      if (action.templates.isNotEmpty) return _Result<JObject>.fail('item script ${script.name} has a name with tags');
      icon.add('item', action.arguments.get('item'));
      if (count > 1) icon.addInt('count', count);
      if (action.arguments.has('name')) icon.add('name', action.arguments.get('name'));
      if (action.arguments.has('lore')) icon.add('lore', action.arguments.get('lore'));
      return _Result<JObject>.ok(icon);
    }
    final String? material = commands.material(name);
    if (material == null) return _Result<JObject>.fail('slot item $value is not a material or a simple item script');
    icon.addString('item', material);
    if (count > 1) icon.addInt('count', count);
    return _Result<JObject>.ok(icon);
  }

  // ---- Documents ----

  /// Writes the document when it fits the compiler's limits; otherwise records why the container is kept.
  bool document(String id, _Doc doc, DenizenContainer container, JArray triggers, SplayTreeMap<String, String> documents) {
    final JObject document = JObject()
      ..addInt('schemaVersion', 1)
      ..addInt('revision', 1)
      ..addString('id', id);
    if (doc.server) document.addBool('server', true);
    document.addString('entry', doc.routines.keys.first);
    if (doc.variables.isNotEmpty) {
      final JObject variables = JObject();
      for (final MapEntry<String, String> variable in doc.variables.entries) {
        final JObject declaration = JObject()..addString('type', variable.value);
        if (variable.value == 'integer') {
          declaration.addInt('initial', 0);
        } else {
          declaration.addString('initial', '');
        }
        variables.add(variable.key, declaration);
      }
      document.add('variables', variables);
    }
    final JObject body = JObject();
    for (final MapEntry<String, JObject> routine in doc.routines.entries) {
      body.add(routine.key, routine.value);
    }
    document.add('routines', body);
    if (triggers.isNotEmpty) document.add('triggers', triggers);
    final String text = writeJson(document);
    final String? problem = behaviorLimitProblem(document, text);
    if (problem != null) {
      issues.add('${container.name}: the converted behavior $problem; it is not generated');
      record(container, container.line, 'container', container.type, container.name, false, 'the converted behavior $problem');
      return false;
    }
    documents[id] = text;
    if (doc.server) serverBehaviors.add(id);
    return true;
  }

  // ---- Flag data ----

  /// Writes one subject's flags into staged state documents with their typed values and expiry, and returns how many
  /// were kept instead.
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
      DenizenTyped? typed;
      if (flag.name.startsWith('__')) {
        reason = "Denizen's own bookkeeping flag";
      } else if (name == null) {
        reason = 'the name cannot be an Animus flag name';
      } else if (flag.expires && flag.expiresAt == 0) {
        reason = 'the expiration time cannot be read';
      } else {
        typed = denizenTyped(flag.value);
        if (typed.reason.isNotEmpty) reason = typed.reason;
      }
      if (reason == null) {
        final SplayTreeMap<String, JObject> document = state.putIfAbsent(_statePath(scope, npc, player), () => SplayTreeMap<String, JObject>());
        if (document.length >= _maximumStateKeys && !document.containsKey(name)) {
          reason = 'more than 128 flags for one subject';
        } else {
          final JObject value = JObject()
            ..addString('type', typed!.type)
            ..add('value', typed.value);
          if (flag.expires) value.addInt('expires', flag.expiresAt);
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
      final String path = '${_stateRoot}relationship/${npc.uuid}/$player/animus/quests/$_namespace.json';
      final SplayTreeMap<String, JObject> document = state.putIfAbsent(path, () => SplayTreeMap<String, JObject>());
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
      result.add(
          count.key,
          JObject()
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
      result.add(
          count.key,
          JObject()
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
      result.add(
          count.key,
          JObject()
            ..addInt('mapped', count.value[0])
            ..addInt('unmapped', count.value[1]));
    }
    return result;
  }
}
