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

import 'definition_validation.dart';
import 'java_json.dart';
import 'java_lang.dart';
import 'presentation.dart';
import 'sentinel.dart';
import 'snake_yaml.dart';
import 'trader_builder.dart';

export 'snake_yaml.dart' show CitizensImportException;

/// Must equal `CitizensImporter.CONVERTER_VERSION`; the shared golden fixtures fail when they drift.
const int citizensConverterVersion = 7;

const String _notice =
    'Data conversion only. Rendering, movement, behavior and combat require separate runtime validation. Imported NPCs are staged with spawned=false; converted combat stays disabled until its review is accepted.';

const Set<String> _integrations = <String>{'sentinel', 'denizen', 'assignment', 'denizen_flags', 'denizenflags', 'triggers', 'constants'};
const Set<String> _review = <String>{
  'skintrait', 'location', 'owner', 'equipment', 'inventory', 'waypoints', 'text', 'hologramtrait', 'poses', 'lookclose', 'type', //
  'spawned', 'mounttrait', 'controllable', builderTrait, constructorTrait,
};
const Set<String> _convertedPaths = <String>{
  '/uuid', '/name', '/traits/type', '/traits/location/world', '/traits/location/x', '/traits/location/y', '/traits/location/z', //
  '/traits/location/yaw', '/traits/location/pitch',
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
  int remappedLocations = 0;
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
    definition.addInt('schemaVersion', 1);
    definition.addInt('revision', 1);
    final String uuid = mapping.get(key)!.asPrimitive.asString;
    definition.addString('uuid', uuid);
    definition.addString('id', 'citizens-$key');
    definition.addString('group', 'citizens');
    definition.addString('name', _scalar(record, 'name', 'Citizen $key'));
    definition.addString('type', type.contains(':') ? type : 'minecraft:$type');
    definition.addBool('spawned', false);
    final JObject migration = JObject();
    migration.addString('importId', id);
    migration.addInt('citizensId', numeric);
    migration.add('originalSpawned', traits.has('spawned') ? traits.get('spawned')!.deepCopy() : JNull.instance);
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
    definition.addString('group', citizensGroup(world, position.get('x'), position.get('z')));
    for (final MapEntry<String, JElement> field in presentation.fields.entries) {
      definition.add(field.key, field.value);
    }
    for (final String warning in presentation.warnings) {
      migrationWarnings.addString('citizens-$key: $warning');
    }
    final JObject modules = JObject();
    if (traits.has('sentinel')) {
      modules.add('animus:combat', convertSentinel(reviewed.get('sentinel'), reviewed.get('equipment'), references));
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
    final JObject? riding = _riding(traits.get('controllable'));
    if (riding != null) modules.add('animus:riding', riding);
    if (traits.has(builderTrait)) {
      final BuilderResult build = convertBuilder(reviewed.get(builderTrait));
      modules.add('animus:builder', build.module);
      for (final String warning in build.warnings) {
        migrationWarnings.addString('citizens-$key: $warning');
      }
    }
    if (traits.has(constructorTrait)) {
      for (final String warning in constructorWarnings(reviewed.get(constructorTrait))) {
        migrationWarnings.addString('citizens-$key: $warning');
      }
    }
    reviewed.remove('skintrait');
    modules.add('citizens:review', reviewed);
    definition.add('modules', modules);
    if (traits.has('skintrait')) {
      final String serialized = writeJson(traits.get('skintrait')!);
      final String hash = sha256Hex(serialized);
      definition.addString('skin', hash);
      skins.add(hash);
      writeBundle('skins/$hash.json', serialized);
    }
    final JObject report = JObject();
    report.addInt('citizensId', numeric);
    final JObject fields = JObject();
    _classify(record, '', fields, presentation.converted);
    report.add('presentationWarnings', JArray(<JElement>[for (final String warning in presentation.warnings) JString(warning)]));
    report.addString('fieldPathFormat', 'json-pointer');
    report.add('fields', fields);
    report.add('worldRemaps', worldRemaps);
    report.add('references', references);
    final List<String> errors = List<String>.of(validateDefinition(definition));
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
  summary.add('traits', counts);
  summary.add('types', types);
  summary.add('sentinelFields', sentinelFields);
  summary.add('sentinelMapping', sentinelMapping);
  summary.add('issues', issues);
  summary.add('migrationWarnings', migrationWarnings);
  summary.addInt('remappedLocations', remappedLocations);
  summary.addInt('resolvedReferences', resolvedReferences);
  summary.addInt('unresolvedReferences', unresolvedReferences);
  final JObject sourceFields = JObject();
  final JObject sourceMetadata = JObject();
  for (final MapEntry<String, JElement> entry in root.entries) {
    if (entry.key != 'npc') sourceMetadata.add(entry.key, entry.value.deepCopy());
  }
  if (sourceMetadata.isNotEmpty) _classify(sourceMetadata, '', sourceFields, const <String>{});
  summary.add('sourceFields', sourceFields);
  summary.add('idMap', mapping);
  summary.add('documents', manifest);
  files['report.json'] = writeJson(summary);
  return CitizensPreview(id, files, summary, original);
}

/// `CitizensImporter.group`: the world (after mapping) as a path segment, plus the 512-block region when the
/// position is a usable coordinate pair.
String citizensGroup(String world, JElement? x, JElement? z) {
  String lowered = javaLower(world)
      .replaceAll(RegExp('[^a-z0-9_-]+', unicode: true), '-')
      .replaceAll(RegExp('-{2,}'), '-')
      .replaceAll(RegExp('^[^a-z0-9]+', unicode: true), '')
      .replaceAll(RegExp(r'[-_]+$'), '');
  if (lowered.length > 48) lowered = lowered.substring(0, 48).replaceAll(RegExp(r'[-_]+$'), '');
  if (lowered.isEmpty) return 'citizens';
  if (!_coordinate(x) || !_coordinate(z)) return lowered;
  final BigInt regionX = javaDoubleToLong((x!.asPrimitive.asDouble / 512).floorToDouble());
  final BigInt regionZ = javaDoubleToLong((z!.asPrimitive.asDouble / 512).floorToDouble());
  return '$lowered-r${regionX}_$regionZ';
}

bool _coordinate(JElement? value) => value is JNumber && value.asDouble.isFinite && value.asDouble.abs() <= 30000000;

JObject? _riding(JElement? trait) {
  if (trait is! JObject) return null;
  final JElement? enabled = trait.get('enabled');
  if (enabled is JBool && !enabled.value) return null;
  return JObject()
    ..addBool('enabled', false)
    ..addBool('reviewAccepted', false);
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

void _classify(JElement value, String path, JObject fields, Set<String> converted) {
  if (value is JObject && value.isNotEmpty) {
    for (final MapEntry<String, JElement> entry in value.entries) {
      _classify(entry.value, '$path/${_pointer(entry.key)}', fields, converted);
    }
    return;
  }
  if (value is JArray && value.isNotEmpty) {
    for (int i = 0; i < value.length; i++) {
      _classify(value[i], '$path/$i', fields, converted);
    }
    return;
  }
  String status = 'UNSUPPORTED_PRESERVED';
  if (converted.contains(path) || _convertedPaths.contains(path)) {
    status = 'CONVERTED';
  } else if (path.startsWith('/traits/')) {
    final List<String> split = path.substring(8).split('/');
    final List<String> parts = split.length > 3 ? <String>[split[0], split[1], split.sublist(2).join('/')] : split;
    final String trait = parts[0];
    if (trait == 'sentinel' && parts.length > 1) {
      status = switch (sentinelDisposition(parts[1])) {
        Disposition.converted => 'CONVERTED',
        Disposition.unsupported || Disposition.statistic => 'UNSUPPORTED_PRESERVED',
        Disposition.unknown => 'REQUIRES_INTEGRATION',
      };
    } else if (_integrations.contains(trait) || trait.startsWith('denizen')) {
      status = 'REQUIRES_INTEGRATION';
    } else if (trait == 'skintrait') {
      status = 'CONVERTED';
    } else if (trait == 'trader') {
      status = parts.length > 1 && (parts[1] == 'guiName' || parts[1] == 'converted-to-v5') ? 'CONVERTED' : 'REQUIRES_INTEGRATION';
    } else if (_review.contains(trait)) {
      status = 'REQUIRES_REVIEW';
    }
  }
  fields.addString(path, status);
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
  final JElement? sit = traits.get('sittrait');
  if (sit is JObject) _remapLocation(sit.get('sittingAt'), '/traits/sittrait/sittingAt', worlds, changes);
}

void _walkWaypointLocations(JElement? value, String path, Map<String, String> worlds, JArray changes) {
  if (value is JObject) {
    for (final MapEntry<String, JElement> entry in value.entries.toList()) {
      final String child = '$path/${_pointer(entry.key)}';
      if (entry.key == 'location') {
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
