/// The browser port of core's `CitizensImporter.preview`: a Citizens saves.yml becomes the same staged
/// `imports/<importId>/` tree the server converter writes, byte for byte. Nothing leaves the browser.
///
/// Shared fixtures under schema/fixtures/citizens prove the two agree (core `CitizensGoldenTest`, animus-web
/// `citizens_converter_test.dart`). Known gaps are listed in snake_yaml.dart, definition_validation.dart and
/// sentinel.dart (`validNamePattern`).
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:archive/archive.dart';

import '../npc/npc_paths.dart' show npcSchemaVersion;
import 'citizens_stacks.dart';
import 'citizens_looks.dart';
import 'citizens_traits.dart';
import 'definition_validation.dart';
import 'destinations.dart';
import 'field_notes.dart';
import 'java_json.dart';
import 'java_lang.dart';
import 'model_engine.dart';
import 'presentation.dart';
import 'runtime_behaviors.dart';
import 'sentinel.dart';
import 'snake_yaml.dart';
import 'trader_builder.dart';

export 'snake_yaml.dart' show CitizensImportException;

/// Must equal `CitizensImporter.CONVERTER_VERSION`; the shared golden fixtures fail when they drift.
const int citizensConverterVersion = 20;
const String _healthModule = 'animus:health';
const String _scheduleModule = 'animus:schedule';

const String _notice =
    'Data conversion only. Rendering, movement, behavior and combat require separate runtime validation. Imported NPCs spawn as Citizens had them, and converted patrols, schedules, combat, riding, shops and builds are on.';

const Set<String> _integrations = <String>{'sentinel', 'denizen', 'assignment', 'denizen_flags', 'denizenflags', 'triggers', 'constants'};
const Set<String> _review = <String>{
  'skintrait', 'location', 'owner', 'equipment', 'inventory', 'waypoints', 'text', 'hologramtrait', 'poses', 'lookclose', 'type', //
  'spawned', 'mounttrait', 'controllable', builderTrait, constructorTrait,
};
/// Location fields Animus reads directly: the facing is one yaw for head and body, and the world is matched by name.
const Set<String> _convertedPaths = <String>{
  '/uuid', '/name', '/traits/type', '/traits/location/world', '/traits/location/x', '/traits/location/y', '/traits/location/z', //
  '/traits/location/yaw', '/traits/location/pitch', '/traits/location/bodyYaw', '/traits/location/worldid',
};

/// A converted saves.yml: [files] maps each path under `imports/<id>/` to its text.
class CitizensPreview {
  const CitizensPreview(this.id, this.files, this.summary, this.source);

  final String id;
  final Map<String, String> files;
  final JObject summary;
  final String source;

  int _count(String key) => (summary.get(key)! as JInt).value.toInt();
  int get records => _count('records');
  int get valid => _count('valid');
  int get skins => _count('skins');
  int get issueCount => (summary.get('issues')! as JArray).length;
  int get warningCount => (summary.get('migrationWarnings')! as JArray).length;

  /// The files to unzip into the data folder: `imports/saves.yml` and the staged `imports/<id>/` tree.
  Map<String, Uint8List> bundle() => <String, Uint8List>{
        'imports/saves.yml': javaUtf8(source),
        for (final MapEntry<String, String> file in files.entries) 'imports/$id/${file.key}': javaUtf8(file.value),
      };

  /// `ImportDigest.outputHash` over the staged tree.
  String outputHash() => importOutputHash(<String, Uint8List>{
        for (final MapEntry<String, String> file in files.entries) file.key: javaUtf8(file.value),
      });
}

/// The data-folder bundle as a reproducible zip: entries in path order, stored, one fixed timestamp.
Uint8List citizensBundleZip(CitizensPreview preview) {
  final Map<String, Uint8List> files = preview.bundle();
  final Archive archive = Archive();
  for (final String path in files.keys.toList()..sort()) {
    archive.add(ArchiveFile.bytes(path, files[path]!)
      ..lastModTime = 315532800
      ..compression = CompressionType.none);
  }
  return Uint8List.fromList(ZipEncoder().encodeBytes(archive));
}

/// SHA-256 of `path + "\n" + sha256(bytes) + "\n"` for every file in `/` path order.
String importOutputHash(Map<String, Uint8List> files) {
  final List<String> paths = files.keys.toList()..sort();
  final StringBuffer canonical = StringBuffer();
  for (final String path in paths) {
    canonical
      ..write(path)
      ..write('\n')
      ..write(sha256Bytes(files[path]!))
      ..write('\n');
  }
  return sha256Bytes(utf8.encode(canonical.toString()));
}

/// Decodes saves.yml bytes as the server does: strict UTF-8 of at most 16 MiB.
String decodeSaves(Uint8List bytes) {
  if (bytes.length > maxDocumentBytes) throw const CitizensImportException('saves.yml exceeds 16777216 bytes');
  try {
    return utf8.decode(bytes);
  } on FormatException {
    throw const CitizensImportException('saves.yml is not valid UTF-8');
  }
}

/// `CitizensImporter.preview` without the file system. Throws [CitizensImportException] where Java throws `IOException`.
CitizensPreview convertCitizens(String original, Map<String, String> worldMap) {
  final List<String> worldKeys = worldMap.keys.toList()..sort();
  final JObject options = JObject();
  for (final String key in worldKeys) {
    final String value = worldMap[key]!;
    if (javaIsBlank(key) || javaIsBlank(value)) throw const CitizensImportException('World mappings require nonblank source and destination names');
    options.addString(key, value);
  }
  final String sourceHash = sha256Hex(original);
  final String id = sha256Hex('animus-citizens-v$citizensConverterVersion\n$sourceHash\n$options');
  final JObject root = parseCitizensYaml(original);
  if (root.get('npc') is! JObject) throw const CitizensImportException(r'$.npc: expected Citizens NPC mapping');
  final JObject npcs = root.get('npc')! as JObject;
  final Map<String, String> files = <String, String>{'source.yml': original};
  final JObject summary = JObject();
  summary.addInt('schemaVersion', 1);
  summary.addInt('converterVersion', citizensConverterVersion);
  summary.addString('fieldPathFormat', 'json-pointer');
  summary.addString('importId', id);
  summary.addString('sourceSha256', sourceHash);
  summary.add('worldMap', options);
  summary.addString('notice', _notice);
  final JObject mapping = _identityMap(npcs);
  final JObject defaults = citizensDefaults(npcs);
  final Set<String> npcUuids = <String>{for (final MapEntry<String, JElement> entry in mapping.entries) entry.value.asPrimitive.asString};
  final JObject conversions = JObject();
  for (final String counter in const <String>['routes', 'routePoints', 'routeGuides', 'lookClose', 'textBehaviors', 'commandBehaviors', 'skinsKept']) {
    conversions.addInt(counter, 0);
  }
  int behaviorDocuments = 0;
  final Map<String, List<String>> reverseIds = <String, List<String>>{};
  for (final MapEntry<String, JElement> entry in mapping.entries) {
    reverseIds.putIfAbsent(entry.value.asPrimitive.asString, () => <String>[]).add(entry.key);
  }
  final JObject counts = JObject();
  final JObject types = JObject();
  final JObject sentinelFields = JObject();
  final JObject sentinelMapping = JObject();
  final JArray issues = JArray();
  final JArray migrationWarnings = JArray();
  for (final JElement inferred in (defaults.get('inferred')! as JArray).elements) {
    final String field = inferred.asPrimitive.asString;
    final bool value = (defaults.get('lookclose')! as JObject).get(field)!.asPrimitive.asBoolean;
    if (value != lookDefaults[field]) {
      migrationWarnings.addString('lookclose/$field: this save only ever writes ${!value}, so NPCs without the field are converted with $value (the server default that wrote the save); '
          'if that default was ${!value}, set $field in animus:look-close on those NPCs');
    }
  }
  int remappedLocations = 0;
  int patrols = 0;
  int raisedFalls = 0;
  int resolvedReferences = 0;
  int unresolvedReferences = 0;
  final JObject manifest = JObject();
  final Set<BigInt> uuids = <BigInt>{};
  final Set<int> numericIds = <int>{};
  int valid = 0;
  final Set<String> skins = <String>{};
  void writeBundle(String relative, String content) {
    files[relative] = content;
    manifest.addString(relative, sha256Hex(content));
  }

  for (final MapEntry<String, JElement> npc in npcs.entries) {
    final String key = npc.key;
    final int numeric = int.parse(key);
    if (!numericIds.add(numeric) || npc.value is! JObject) throw CitizensImportException('Invalid or duplicate NPC $key');
    final JObject record = npc.value as JObject;
    final JObject traits = record.get('traits') is JObject ? record.get('traits')! as JObject : JObject();
    for (final String trait in traits.keys) {
      _increment(counts, trait);
    }
    final JElement? sentinel = traits.get('sentinel');
    if (sentinel is JObject) {
      for (final String field in sentinel.keys) {
        _increment(sentinelFields, field);
      }
    }
    final String type = javaLower(_scalar(traits, 'type', 'PLAYER'));
    _increment(types, type);
    final JObject definition = JObject();
    definition.addInt('schemaVersion', npcSchemaVersion);
    definition.addInt('revision', 1);
    final String uuid = mapping.get(key)!.asPrimitive.asString;
    definition.addString('uuid', uuid);
    definition.addString('id', 'citizens-$key');
    definition.addString('group', 'citizens');
    definition.addString('name', _scalar(record, 'name', 'Citizen $key'));
    definition.addString('type', type.contains(':') ? type : 'minecraft:$type');
    final JElement? spawned = traits.get('spawned');
    definition.addBool('spawned', !(spawned is JBool && !spawned.value));
    final JObject migration = JObject();
    migration.addString('importId', id);
    migration.addInt('citizensId', numeric);
    definition.add('migration', migration);
    final JArray worldRemaps = JArray();
    final JObject reviewed = traits.deepCopy();
    _remapKnownLocations(reviewed, worldMap, worldRemaps);
    final JArray references = _references(traits, mapping, reverseIds);
    migration.add('references', references.deepCopy());
    for (final JElement reference in references.elements) {
      final String state = (reference as JObject).get('status')!.asPrimitive.asString;
      if (state == 'resolved') {
        resolvedReferences++;
      } else if (state != 'none') {
        unresolvedReferences++;
        migrationWarnings.addString('citizens-$key: ${reference.get('source')!.asPrimitive.asString} NPC reference $state');
      }
    }
    remappedLocations += worldRemaps.length;
    final JObject position = JObject();
    final JObject location = reviewed.get('location') is JObject ? reviewed.get('location')! as JObject : JObject();
    final String world = _scalar(location, 'world', '');
    position.addString('world', world);
    for (final String coordinate in const <String>['x', 'y', 'z', 'yaw', 'pitch']) {
      position.add(
          coordinate,
          location.has(coordinate)
              ? location.get(coordinate)!.deepCopy()
              : (coordinate == 'yaw' || coordinate == 'pitch' ? JInt.of(0) : JNull.instance));
    }
    final PresentationResult presentation = mapPresentation(definition.get('type')!.asPrimitive.asString, reviewed, record);
    final Facing? facing = presentation.facing;
    if (facing != null) {
      position.addDouble('yaw', facing.yaw);
      position.addDouble('pitch', facing.pitch);
    }
    definition.add('position', position);
    definition.addString('group', citizensGroup(world));
    for (final MapEntry<String, JElement> field in presentation.fields.entries) {
      definition.add(field.key, field.value);
    }
    // A skin is only drawn on a player body; any other body keeps it under citizens:review.
    final bool skinApplied = traits.has('skintrait') && definition.get('type')!.asPrimitive.asString == 'minecraft:player';
    final List<String> presentationWarnings = List<String>.of(presentation.warnings);
    if (traits.has('skintrait') && !skinApplied) _increment(conversions, 'skinsKept');
    for (final String warning in presentationWarnings) {
      migrationWarnings.addString('citizens-$key: $warning');
    }
    final JObject modules = JObject();
    if (traits.has('sentinel')) {
      final SentinelModules sentinel = convertSentinel(reviewed.get('sentinel'), reviewed.get('equipment'), references);
      modules.add('animus:combat', sentinel.combat);
      if (sentinel.health != null) modules.add(_healthModule, sentinel.health);
      final JElement? reviewedSentinel = reviewed.get('sentinel');
      if (reviewedSentinel is JObject) {
        for (final String field in reviewedSentinel.keys.toList()) {
          _increment(sentinelMapping, switch (sentinelDisposition(field)) {
            Disposition.converted => 'converted',
            Disposition.unsupported => 'unsupported',
            Disposition.statistic => 'statistics',
            Disposition.unknown => 'unknown',
          });
          if (field.startsWith('stats_')) reviewedSentinel.remove(field);
        }
      }
    }
    if (traits.has('trader')) {
      final TraderResult trader = mapTrader(traits.get('trader'), definition.get('name')!.asPrimitive.asString);
      if (trader.module != null) modules.add('animus:shop', trader.module);
      if (trader.warning != null) migrationWarnings.addString('citizens-$key: ${trader.warning}');
    }
    if (citizensTargetable(reviewed)) {
      if (!modules.has('animus:combat')) modules.add('animus:combat', targetableCombat());
      (modules.get('animus:combat')! as JObject).addBool('targetableByMobs', true);
      if (!modules.has(_healthModule)) modules.add(_healthModule, targetableHealth(record));
    }
    final JObject? guard = citizensFollow(reviewed, npcUuids);
    if (guard != null) {
      if (!modules.has('animus:combat')) modules.add('animus:combat', targetableCombat());
      final JObject combat = modules.get('animus:combat')! as JObject;
      if (!combat.has('guard')) combat.add('guard', guard);
    }
    final JObject? riding = _riding(traits.get('controllable'));
    if (riding != null) modules.add('animus:riding', riding);
    final ModelEngineResult? model = traits.has(modelEngineTrait) ? mapModelEngine(reviewed.get(modelEngineTrait)!) : null;
    if (model != null) {
      if (model.module != null) modules.add(modelEngineModule, model.module);
      for (final String warning in model.warnings) {
        migrationWarnings.addString('citizens-$key: $warning');
      }
    }
    final List<String> economyWarnings = <String>[];
    final Set<String> economyConverted = <String>{};
    if (!modules.has('animus:shop')) reportCitizensShop(traits.get('shop'), economyWarnings);
    applyCitizensDrops(reviewed.get('dropstrait'), record, modules, economyWarnings, economyConverted);
    for (final String warning in economyWarnings) {
      migrationWarnings.addString('citizens-$key: $warning');
    }
    if (traits.has(builderTrait)) {
      final BuilderResult build = convertBuilder(reviewed.get(builderTrait));
      modules.add('animus:builder', build.module);
      for (final String warning in build.warnings) {
        migrationWarnings.addString('citizens-$key: $warning');
      }
    }
    final JObject? resume = traits.has(builderTrait) ? builderResume(reviewed.get(builderTrait), key) : null;
    if (traits.has(constructorTrait)) {
      for (final String warning in constructorWarnings(reviewed.get(constructorTrait))) {
        migrationWarnings.addString('citizens-$key: $warning');
      }
    }
    final RuntimeResult runtime = mapRuntime(key, definition.get('name')!.asPrimitive.asString, world, reviewed, record, defaults);
    if (runtime.navigation != null) {
      modules.add('animus:navigation', runtime.navigation);
      _increment(conversions, 'routes');
    }
    conversions.addInt('routePoints', (conversions.get('routePoints')! as JInt).value.toInt() + runtime.points);
    conversions.addInt('routeGuides', (conversions.get('routeGuides')! as JInt).value.toInt() + runtime.guides);
    if (runtime.lookClose != null) {
      modules.add('animus:look-close', runtime.lookClose);
      if ((runtime.lookClose!.get('enabled')! as JBool).value) _increment(conversions, 'lookClose');
    }
    // A route's waypoint behavior counts with its route.
    for (final String behavior in runtime.behaviors) {
      if (!behavior.startsWith('citizens:waypoints-')) _increment(conversions, behavior.startsWith('citizens:text-') ? 'textBehaviors' : 'commandBehaviors');
    }
    for (final BehaviorDocument document in runtime.documents) {
      writeBundle(document.path, document.content);
      behaviorDocuments++;
    }
    for (final String warning in runtime.warnings) {
      migrationWarnings.addString('citizens-$key: $warning');
    }
    final DestinationsResult? schedule =
        traits.has(destinationsTrait) ? convertDestinations(reviewed.get(destinationsTrait), definition.get('type')!.asPrimitive.asString, world, worldMap) : null;
    if (schedule != null) {
      for (final String warning in schedule.warnings) {
        migrationWarnings.addString('citizens-$key: $warning');
      }
      for (final DestinationsSkin skin in schedule.skins) {
        writeBundle(skin.path, skin.content);
        skins.add(skin.hash);
      }
      if (schedule.module != null) {
        if (modules.remove('animus:navigation') != null) {
          migrationWarnings.addString("citizens-$key: NPC Destinations moved this NPC, so its Citizens route was left out; the schedule replaces it");
        }
        modules.add(_scheduleModule, schedule.module);
      }
    }
    if (!modules.has('animus:navigation') && !modules.has(_scheduleModule) && world.isNotEmpty && citizensWanders(definition.get('type')!.asPrimitive.asString, record)) {
      modules.add('animus:navigation', vanillaWander(record));
      _increment(conversions, 'routes');
    }
    if (modules.has('animus:navigation')) {
      final JObject? pause = citizensPause(reviewed);
      if (pause != null) (modules.get('animus:navigation')! as JObject).add('pause', pause);
    }
    final JObject? home = citizensHome(reviewed, world);
    if (home != null) modules.add('animus:home', home);
    final JObject? mirror = citizensMirror(reviewed);
    if (mirror != null) modules.add('animus:mirror', mirror);
    final TraitsResult state = mapTraits(definition.get('type')!.asPrimitive.asString, record, reviewed, modules.has('animus:combat'),
        modules.has(_healthModule), modules.has('animus:navigation'), runtime.navigation != null, references);
    if (runtime.navigation != null) patrols++;
    if (state.raisedFall) raisedFalls++;
    for (final MapEntry<String, JElement> field in state.fields.entries) {
      definition.add(field.key, field.value);
    }
    if (schedule != null) mergeDestinationsMovement(definition, schedule.movement);
    if (state.migration.isNotEmpty) migration.add('skin', state.migration);
    for (final String warning in state.warnings) {
      migrationWarnings.addString('citizens-$key: $warning');
    }
    final LooksResult looks = mapLooks(definition.get('type')!.asPrimitive.asString, reviewed, uuid, !citizensUnprotected(record));
    for (final MapEntry<String, JElement> field in looks.fields.entries) {
      definition.add(field.key, field.value);
    }
    for (final String warning in looks.warnings) {
      migrationWarnings.addString('citizens-$key: $warning');
    }
    if (skinApplied) reviewed.remove('skintrait');
    modules.add('citizens:review', reviewed);
    final List<String> listed = List<String>.of(runtime.behaviors);
    if (resume != null) {
      writeBundle('behaviors/citizens/builder-$key.json', writeJson(resume));
      behaviorDocuments++;
      listed.add(resume.get('id')!.asPrimitive.asString);
    }
    if (listed.isNotEmpty) definition.add('behaviors', JArray(<JElement>[for (final String id in listed) JString(id)]));
    definition.add('modules', modules);
    if (skinApplied) {
      final String serialized = writeJson(traits.get('skintrait')!);
      final String hash = sha256Hex(serialized);
      definition.addString('skin', hash);
      skins.add(hash);
      writeBundle('skins/$hash.json', serialized);
    }
    final JObject report = JObject();
    report.addInt('citizensId', numeric);
    final JObject fields = JObject();
    final Map<String, FieldNote> notes = <String, FieldNote>{...presentation.notes, ...runtime.notes, ...state.notes, ...?schedule?.notes, ...?model?.notes, ...looks.notes};
    if (traits.has('skintrait') && !skinApplied) {
      notes['/traits/skintrait'] = FieldNote(unsupportedPreserved, unusedSkin(definition.get('type')!.asPrimitive.asString));
    }
    final JObject reasons = JObject();
    _classify(record, '', fields, <String>{...presentation.converted, ...runtime.converted, ...state.converted, ...?schedule?.converted, ...economyConverted, ...looks.converted}, notes, reasons);
    report.add('presentationWarnings', JArray(<JElement>[for (final String warning in presentationWarnings) JString(warning)]));
    report.add('runtimeWarnings', JArray(<JElement>[for (final String warning in runtime.warnings) JString(warning)]));
    report.addString('fieldPathFormat', 'json-pointer');
    report.add('fields', fields);
    report.add('reasons', reasons);
    report.add('worldRemaps', worldRemaps);
    report.add('references', references);
    final List<String> errors = List<String>.of(validateDefinition(definition));
    if (errors.isEmpty) errors.addAll(activationErrors(definition));
    final BigInt? parsed = javaUuidValue(uuid);
    if (parsed != null && !uuids.add(parsed)) errors.add(r'$.uuid: duplicate NPC UUID');
    report.add('validationErrors', JArray(<JElement>[for (final String error in errors) JString(error)]));
    if (errors.isNotEmpty) {
      issues.addString('citizens-$key: ${errors.join('; ')}');
    } else {
      valid++;
    }
    writeBundle('npcs/${definition.get('group')!.asPrimitive.asString}/citizens-$key.json', writeJson(definition));
    files['records/$key.json'] = writeJson(record);
    files['reports/$key.json'] = writeJson(report);
  }
  summary.addInt('records', numericIds.length);
  summary.addInt('valid', valid);
  summary.addInt('skins', skins.length);
  summary.addInt('behaviors', behaviorDocuments);
  summary.addInt('shops', 0);
  summary.add('conversions', conversions);
  summary.add('citizensDefaults', defaults);
  summary.add('traits', counts);
  summary.add('types', types);
  summary.add('sentinelFields', sentinelFields);
  summary.add('sentinelMapping', sentinelMapping);
  summary.add('issues', issues);
  if (patrols > 0) {
    migrationWarnings.addString('movement: $patrols Citizens routes now open doors and drop at least $routeFall blocks (openDoors, maximumFall), '
        "$raisedFalls of them raised from a lower fall distance; lower these in the NPC's movement to undo");
  }
  summary.add('migrationWarnings', migrationWarnings);
  summary.addInt('remappedLocations', remappedLocations);
  summary.addInt('resolvedReferences', resolvedReferences);
  summary.addInt('unresolvedReferences', unresolvedReferences);
  final JObject sourceFields = JObject();
  final JObject sourceMetadata = JObject();
  for (final MapEntry<String, JElement> entry in root.entries) {
    if (entry.key != 'npc') sourceMetadata.add(entry.key, entry.value.deepCopy());
  }
  if (sourceMetadata.isNotEmpty) _classify(sourceMetadata, '', sourceFields, const <String>{}, const <String, FieldNote>{}, JObject());
  summary.add('sourceFields', sourceFields);
  summary.add('idMap', mapping);
  summary.add('documents', manifest);
  files['report.json'] = writeJson(summary);
  return CitizensPreview(id, files, summary, original);
}

/// Why a skin on a body that is not a player is kept: Citizens never drew it either.
String unusedSkin(String type) {
  final String body = type.substring(type.indexOf(':') + 1).replaceAll('_', ' ');
  return 'Citizens draws skins only on player NPCs, so this $body never showed it; kept for when the NPC becomes a player';
}

/// `CitizensImporter.group`: the world (after mapping) as a path segment, or `citizens` without one.
String citizensGroup(String world) {
  String lowered = javaLower(world)
      .replaceAll(RegExp('[^a-z0-9_-]+', unicode: true), '-')
      .replaceAll(RegExp('-{2,}'), '-')
      .replaceAll(RegExp('^[^a-z0-9]+', unicode: true), '')
      .replaceAll(RegExp(r'[-_]+$'), '');
  if (lowered.length > 48) lowered = lowered.substring(0, 48).replaceAll(RegExp(r'[-_]+$'), '');
  return lowered.isEmpty ? 'citizens' : lowered;
}

JObject? _riding(JElement? trait) {
  if (trait is! JObject) return null;
  final JElement? enabled = trait.get('enabled');
  if (enabled is JBool && !enabled.value) return null;
  return JObject()
    ..addBool('enabled', true);
}

String _scalar(JObject object, String key, String fallback) {
  final JElement? value = object.get(key);
  return value is JPrimitive ? value.asString : fallback;
}

void _increment(JObject counts, String key) {
  final JElement? current = counts.get(key);
  counts.addInt(key, current is JInt ? current.value.toInt() + 1 : 1);
}

final RegExp _numericId = RegExp(r'^(?:0|[1-9][0-9]{0,9})$');

JObject _identityMap(JObject records) {
  if (records.length > 10000) throw const CitizensImportException('Import exceeds 10000 NPC records');
  final JObject map = JObject();
  for (final MapEntry<String, JElement> entry in records.entries) {
    if (!_numericId.hasMatch(entry.key) || entry.value is! JObject) throw CitizensImportException('Invalid NPC ID or record: ${entry.key}');
    if (int.parse(entry.key) > 2147483647) throw const CitizensImportException('NPC ID exceeds integer range');
    map.addString(entry.key, _scalar(entry.value as JObject, 'uuid', nameUuid('animus:citizens:${entry.key}')));
  }
  return map;
}

String _pointer(String key) => key.replaceAll('~', '~0').replaceAll('/', '~1');

/// Every leaf gets a status: converted paths first, then the nearest note on the path or a parent, then the trait's rule.
void _classify(JElement value, String path, JObject fields, Set<String> converted, Map<String, FieldNote> notes, JObject reasons) {
  if (value is JObject && value.isNotEmpty) {
    for (final MapEntry<String, JElement> entry in value.entries) {
      _classify(entry.value, '$path/${_pointer(entry.key)}', fields, converted, notes, reasons);
    }
    return;
  }
  if (value is JArray && value.isNotEmpty) {
    for (int i = 0; i < value.length; i++) {
      _classify(value[i], '$path/$i', fields, converted, notes, reasons);
    }
    return;
  }
  final FieldNote note =
      converted.contains(path) || _convertedPaths.contains(path) ? const FieldNote(statusConverted, '') : (_nearest(path, notes) ?? _rule(path, value));
  fields.addString(path, note.status);
  if (note.reason.isNotEmpty) reasons.addString(path, note.reason);
}

FieldNote? _nearest(String path, Map<String, FieldNote> notes) {
  for (String at = path; at.isNotEmpty; at = at.substring(0, at.lastIndexOf('/'))) {
    final FieldNote? note = notes[at];
    if (note != null) return note;
  }
  return null;
}

/// The status and reason of a field no mapper decided on.
FieldNote _rule(String path, JElement value) {
  if (!path.startsWith('/traits/')) return const FieldNote(unsupportedPreserved, 'Citizens field with no Animus equivalent; kept in records/');
  final List<String> split = path.substring(8).split('/');
  final List<String> parts = split.length > 3 ? <String>[split[0], split[1], split.sublist(2).join('/')] : split;
  final String trait = parts[0];
  if (parts.length == 1 && (value is JObject || value is JArray)) return const FieldNote(statusConverted, '');
  if (trait == 'sentinel' && parts.length > 1) {
    return switch (sentinelDisposition(parts[1])) {
      Disposition.converted => const FieldNote(statusConverted, ''),
      Disposition.statistic => const FieldNote(unsupportedPreserved, "Sentinel's record of past fights; history, not a setting"),
      Disposition.unsupported => const FieldNote(unsupportedPreserved, 'Sentinel setting with no animus:combat equivalent; kept in the combat review data'),
      Disposition.unknown => const FieldNote(requiresIntegration, 'Sentinel field this converter does not know; kept in the combat review data'),
    };
  }
  if (trait == 'assignment' || trait == 'triggers' || trait.startsWith('denizen') || trait == 'constants') {
    return const FieldNote(requiresIntegration, 'Denizen data; its scripts are not in the save, so rewrite them as Animus behaviors by hand');
  }
  if (_integrations.contains(trait)) return const FieldNote(requiresIntegration, 'needs a plugin integration Animus does not have');
  if (trait == 'skintrait') return const FieldNote(statusConverted, '');
  if (trait == 'trader') {
    return parts.length > 1 && (parts[1] == 'guiName' || parts[1] == 'converted-to-v5')
        ? const FieldNote(statusConverted, '')
        : const FieldNote(requiresIntegration, 'dtlTraders data; convert it with the dtlTraders shop import');
  }
  if (_review.contains(trait)) return const FieldNote(requiresReview, 'not converted; check it in citizens:review');
  return FieldNote(unsupportedPreserved, 'trait $trait has no Animus equivalent; kept in citizens:review');
}

void _remapKnownLocations(JObject traits, Map<String, String> worlds, JArray changes) {
  _remapLocation(traits.get('location'), '/traits/location', worlds, changes);
  _walkWaypointLocations(traits.get('waypoints'), '/traits/waypoints', worlds, changes);
  final JElement? sentinel = traits.get('sentinel');
  if (sentinel is JObject) {
    for (final String key in const <String>['spawnPoint', 'avoidReturnPoint']) {
      _remapLocation(sentinel.get(key), '/traits/sentinel/$key', worlds, changes);
    }
  }
  _remapAnchors(traits.get('anchors'), worlds, changes);
  final JElement? home = traits.get('hometrait');
  if (home is JObject) _remapLocation(home.get('location'), '/traits/hometrait/location', worlds, changes);
  final JElement? sit = traits.get('sittrait');
  if (sit is JObject) _remapLocation(sit.get('sittingAt'), '/traits/sittrait/sittingAt', worlds, changes);
}

/// Anchors are `name;world;x;y;z` text; the world part takes the world mapping like any location.
void _remapAnchors(JElement? value, Map<String, String> worlds, JArray changes) {
  if (value is! JObject || value.get('list') is! JObject) return;
  final JObject list = value.get('list')! as JObject;
  for (final String key in list.keys.toList()) {
    final JElement entry = list.get(key)!;
    if (entry is! JPrimitive) continue;
    final List<String> parts = entry.asString.split(';');
    if (parts.length != 5 || !worlds.containsKey(parts[1]) || worlds[parts[1]] == parts[1]) continue;
    changes.add(JObject()
      ..addString('source', '/traits/anchors/list/${_pointer(key)}')
      ..addString('from', parts[1])
      ..addString('to', worlds[parts[1]]!));
    parts[1] = worlds[parts[1]]!;
    list.addString(key, parts.join(';'));
  }
}

void _walkWaypointLocations(JElement? value, String path, Map<String, String> worlds, JArray changes) {
  if (value is JObject) {
    for (final MapEntry<String, JElement> entry in value.entries.toList()) {
      final String child = '$path/${_pointer(entry.key)}';
      if (entry.key == 'location' || entry.key == 'at') {
        _remapLocation(entry.value, child, worlds, changes);
      } else {
        _walkWaypointLocations(entry.value, child, worlds, changes);
      }
    }
  } else if (value is JArray) {
    for (int index = 0; index < value.length; index++) {
      _walkWaypointLocations(value[index], '$path/$index', worlds, changes);
    }
  }
}

void _remapLocation(JElement? value, String path, Map<String, String> worlds, JArray changes) {
  if (value is! JObject) return;
  final JElement? worldValue = value.get('world');
  if (worldValue is! JString) return;
  final String before = worldValue.value;
  final String? after = worlds[before];
  if (after == null || after == before) return;
  value.addString('world', after);
  final JObject change = JObject();
  change.addString('source', path);
  change.addString('from', before);
  change.addString('to', after);
  if (value.has('worldid')) change.add('removedWorldId', value.remove('worldid'));
  changes.add(change);
}

JArray _references(JObject traits, JObject ids, Map<String, List<String>> reverseIds) {
  final JArray references = JArray();
  final JElement? mount = traits.get('mounttrait');
  if (mount is JObject && mount.has('mountedon')) {
    references.add(_reference('/traits/mounttrait/mountedon', 'mount', mount.get('mountedon')!, false, ids, reverseIds));
  }
  final JElement? sentinel = traits.get('sentinel');
  if (sentinel is JObject && sentinel.has('guardedNPC')) {
    references.add(_reference('/traits/sentinel/guardedNPC', 'guard', sentinel.get('guardedNPC')!, true, ids, reverseIds));
  }
  return references;
}

JObject _reference(String source, String kind, JElement original, bool numeric, JObject ids, Map<String, List<String>> reverseIds) {
  final JObject result = JObject();
  result.addString('source', source);
  result.addString('kind', kind);
  result.add('original', original.deepCopy());
  String? key;
  String status = 'invalid';
  bool failed = false;
  if (original is! JPrimitive) {
    failed = true;
  } else if (numeric) {
    final int? id = JavaDecimal.parse(original.asString)?.intValueExact();
    if (id == null) {
      failed = true;
    } else if (id == -1) {
      status = 'none';
    } else if (id >= 0) {
      key = '$id';
      status = ids.has(key) ? 'resolved' : 'missing';
    }
  } else {
    final String uuid = original.asString;
    if (!canonicalUuid(uuid)) {
      failed = true;
    } else {
      final List<String> matches = reverseIds[uuid] ?? const <String>[];
      status = matches.isEmpty ? 'missing' : (matches.length == 1 ? 'resolved' : 'ambiguous');
      if (matches.length == 1) key = matches.first;
    }
  }
  if (!failed && status == 'resolved') {
    final String uuid = ids.get(key!)!.asPrimitive.asString;
    if (!canonicalUuid(uuid)) {
      failed = true;
    } else if (reverseIds[uuid]!.length != 1) {
      status = 'ambiguous';
    } else {
      result.addString('npcUuid', uuid);
      result.addString('npcId', 'citizens-$key');
      result.addInt('citizensId', int.parse(key));
    }
  }
  if (failed) status = 'invalid';
  result.addString('status', status);
  return result;
}
