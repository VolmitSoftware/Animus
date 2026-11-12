import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:animus_editor/logic/citizens/citizens_converter.dart' show importOutputHash;
import 'package:animus_editor/logic/citizens/java_json.dart';
import 'package:animus_editor/logic/denizen/denizen_args.dart';
import 'package:animus_editor/logic/denizen/denizen_converter.dart';
import 'package:animus_editor/logic/denizen/denizen_flags.dart';
import 'package:animus_editor/logic/denizen/denizen_scripts.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:archive/archive.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

const String _root = 'denizen';

Map<String, Object?> _index() => readFixture('$_root/index.json')! as Map<String, Object?>;

String _path(String name) => '${schemaDirectory.path}/fixtures/$_root/$name';

/// The same files `DenizenImporter.read` takes from a Denizen data folder.
DenizenInput _folder(String folder, {String? citizens}) {
  final Directory root = Directory(_path(folder));
  final Map<String, String> scripts = <String, String>{};
  final Directory scriptFolder = Directory('${root.path}/scripts');
  if (scriptFolder.existsSync()) {
    for (final File file in scriptFolder.listSync(recursive: true).whereType<File>()) {
      if (!file.path.endsWith('.dsc') && !file.path.endsWith('.yml')) continue;
      scripts[file.path.substring(scriptFolder.path.length + 1).replaceAll(r'\', '/')] = file.readAsStringSync();
    }
  }
  final File server = File('${root.path}/server_flags.dat');
  final Map<String, String> players = <String, String>{};
  final Directory playerFolder = Directory('${root.path}/player_flags');
  if (playerFolder.existsSync()) {
    for (final File file in playerFolder.listSync().whereType<File>()) {
      final String name = file.uri.pathSegments.last;
      if (name.endsWith('.dat')) players[name.substring(0, name.length - 4)] = file.readAsStringSync();
    }
  }
  final File notables = File('${root.path}/notables.yml');
  return DenizenInput(
    scripts: scripts,
    citizens: citizens == null ? null : File(_path(citizens)).readAsStringSync(),
    serverFlags: server.existsSync() ? server.readAsStringSync() : null,
    playerFlags: players,
    notables: notables.existsSync() ? notables.readAsStringSync() : null,
  );
}

final DateTime _fixed = DateTime(2026, 5, 1);

void main() {
  group('shared Denizen golden fixtures', () {
    for (final Object? entry in _index()['cases']! as List<Object?>) {
      final Map<String, Object?> definition = entry! as Map<String, Object?>;
      final String name = definition['name']! as String;
      test('$name converts to the committed staged tree byte for byte', () {
        final DenizenPreview preview = convertDenizen(_folder(definition['folder']! as String, citizens: definition['citizens'] as String?));
        final Map<String, Uint8List> expected = fixtureFiles('$_root/expected/$name');
        final Map<String, Uint8List> actual = <String, Uint8List>{
          for (final MapEntry<String, String> file in preview.files.entries) file.key: javaUtf8(file.value),
        };
        expect(actual.keys.toList()..sort(), expected.keys.toList()..sort());
        for (final String path in expected.keys) {
          expect(utf8.decode(actual[path]!), utf8.decode(expected[path]!), reason: '$name: $path');
          expect(actual[path], expected[path], reason: '$name: $path bytes');
        }
        expect(preview.outputHash(), importOutputHash(expected));
        final Map<String, Object?> report = jsonDecode(utf8.decode(expected['report.json']!)) as Map<String, Object?>;
        expect(preview.id, report['importId']);
        expect(preview.behaviors, report['behaviors']);
      });
    }

    final List<File> invalid = Directory(_path(_index()['invalid']! as String)).listSync().whereType<File>().where((File file) => file.path.endsWith('.yml')).toList()
      ..sort((File a, File b) => a.path.compareTo(b.path));
    test('invalid inputs exist', () => expect(invalid, isNotEmpty));
    for (final File file in invalid) {
      test('${file.uri.pathSegments.last} is rejected', () {
        final DenizenInput input = _folder('minimal/Denizen');
        expect(() => convertDenizen(DenizenInput(scripts: input.scripts, citizens: file.readAsStringSync())), throwsA(isA<DenizenImportException>()));
      });
    }
  });

  group('converted behaviors against the shared schema', () {
    for (final Object? entry in _index()['cases']! as List<Object?>) {
      final String name = (entry! as Map<String, Object?>)['name']! as String;
      test('$name behaviors validate against behavior.schema.json', () {
        final Map<String, Uint8List> expected = fixtureFiles('$_root/expected/$name');
        int checked = 0;
        for (final MapEntry<String, Uint8List> file in expected.entries) {
          if (!file.key.startsWith('behaviors/')) continue;
          final List<Diagnostic> errors = <Diagnostic>[
            for (final Diagnostic found in SharedSchemas.embedded.validate(DocumentKind.behavior, jsonDecode(utf8.decode(file.value))))
              if (found.severity == DiagnosticSeverity.error) found,
          ];
          expect(errors, isEmpty, reason: '$name: ${file.key}');
          checked++;
        }
        expect(checked, greaterThan(0));
      });
    }

    test('accepting a review appends its server behaviors to server-behaviors.json once', () {
      final DenizenPreview preview = convertDenizen(_folder('town/Denizen', citizens: 'town/saves.yml'));
      expect(preview.files.keys, isNot(contains('server-behaviors.json')), reason: 'the staged tree matches the server byte for byte');
      final String created = preview.serverBehaviorsAfterApply(null)!;
      final Map<String, Object?> list = jsonDecode(created) as Map<String, Object?>;
      expect(list['behaviors'], <String>['denizen:book/notice_board', 'denizen:book/town_guide', 'denizen:command/reset_command', 'denizen:world/lantern_world']);
      expect(SharedSchemas.embedded.validate(DocumentKind.serverBehaviors, list), isEmpty);
      final String merged = preview.serverBehaviorsAfterApply('{"schemaVersion":1,"behaviors":["town:welcome","denizen:world/lantern_world"]}')!;
      expect((jsonDecode(merged) as Map<String, Object?>)['behaviors'], <String>['town:welcome', 'denizen:world/lantern_world', 'denizen:book/notice_board', 'denizen:book/town_guide', 'denizen:command/reset_command']);
      expect(preview.serverBehaviorsAfterApply(created), created, reason: 'nothing new, nothing written');
      final DenizenPreview minimal = convertDenizen(_folder('minimal/Denizen'));
      expect(minimal.serverBehaviorsAfterApply(null), isNull);
    });
  });

  group('bundle', () {
    test('unzips the Denizen folder and the staged import in path order', () {
      final DenizenPreview preview = convertDenizen(_folder('town/Denizen', citizens: 'town/saves.yml'));
      final Map<String, Uint8List> bundle = preview.bundle();
      expect(bundle.keys, contains('imports/denizen/scripts/town.dsc'));
      expect(bundle.keys, contains('imports/denizen/server_flags.dat'));
      expect(bundle.keys, contains('imports/denizen/player_flags/00000000-0000-4000-8000-0000000000a1.dat'));
      expect(bundle.keys.where((String path) => path.startsWith('imports/denizen/${preview.id}/')).length, preview.files.length);
      final Archive archive = ZipDecoder().decodeBytes(denizenBundleZip(preview));
      final List<String> names = <String>[for (final ArchiveFile file in archive.files) file.name];
      expect(names, List<String>.of(names)..sort());
      expect(denizenBundleZip(preview, modified: _fixed), denizenBundleZip(convertDenizen(_folder('town/Denizen', citizens: 'town/saves.yml')), modified: _fixed));
    });

    test('aggregate counts', () {
      final DenizenPreview preview = convertDenizen(_folder('town/Denizen', citizens: 'town/saves.yml'));
      expect(preview.behaviors, 13);
      expect(preview.bindings, 4);
      expect(preview.flagsImported, 16);
      expect(preview.flagsPreserved, 2);
    });
  });

  group('Denizen reading', () {
    test('commands keep quotes, prefixes and tags with spaces together', () {
      final DenizenCommand command = denizenCommand('~narrate "Hi <player.name>" targets:<player> format:"a b" <element[x y].length>');
      expect(command.name, 'narrate');
      expect(command.args.map((DenizenArg arg) => arg.value).toList(), <String>['Hi <player.name>', '<player>', 'a b', '<element[x y].length>']);
      expect(command.args[1].prefix, 'targets');
      expect(command.args[2].prefix, 'format');
      expect(command.args.first.quoted, isTrue);
    });

    test('tags split into parts, parameters and fallbacks', () {
      final DenizenTag tag = denizenTag('player.flag[quest.<[step]>]||0');
      expect(tag.parts.map((DenizenPart part) => part.name).toList(), <String>['player', 'flag']);
      expect(tag.parts[1].parameter, 'quest.<[step]>');
      expect(tag.fallback, '0');
      expect(tag.label, 'player.flag');
      expect(denizenTag('[count]').label, '[definition]');
      expect(denizenSegments('a <&a>b < c').where((DenizenSegment segment) => segment.tag).single.text, '&a');
    });

    test('durations round half up to ticks', () {
      expect(denizenTicks('2s'), 40);
      expect(denizenTicks('30t'), 30);
      expect(denizenTicks('0.025s'), 1);
      expect(denizenTicks('1m'), 1200);
      expect(denizenTicks('3'), 60);
      expect(denizenTicks('<[time]>'), isNull);
    });

    test('blocks nest under entries that end in a colon', () {
      final DenizenParsed parsed = parseDenizenScripts('a.dsc', 'x:\n  type: task\n  script:\n  - if <a>:\n    - narrate hi\n  - else:\n    - stop\n');
      expect(parsed.issues, isEmpty);
      final DenizenSequence script = parsed.containers.single.body.get('script')! as DenizenSequence;
      expect(script.items.map((DenizenItem item) => item.text).toList(), <String>['if <a>', 'else']);
      expect(script.items.first.children!.items.single.text, 'narrate hi');
    });

    test('flag files flatten sub-flags and read expiry', () {
      final DenizenFlagValues values = denizenFlagFile(
          'a:true\n\\exb:map@[__value=5;__expiration=time@2030/01/01_00:00:00:0000_+00:00]\nc:map@[__value=map@[d=map@[__value=e&scf]]]\nk\\cox:1\nm:map@[__value=map@[x=1;y=two]]\n');
      expect(values.flags.keys.toList(), <String>['a', 'b', 'c.d', 'k:x', 'm']);
      expect(values.flags['b']!.expires, isTrue);
      expect(values.flags['b']!.expiresAt, 1893456000000);
      expect(values.flags['c.d']!.value, 'e;f');
      expect(values.flags['m']!.value, 'map@[x=1;y=two]', reason: "a __value map of plain values is the flag's map value");
      expect(denizenStage('12'), 12);
      expect(denizenStage('-1'), isNull);
    });

    test('flag values become typed JSON', () {
      expect(denizenTyped('true').type, 'boolean');
      expect(denizenTyped('-12').type, 'integer');
      expect(denizenTyped('2.5').type, 'decimal');
      expect(denizenTyped('Bob').type, 'string');
      final DenizenTyped list = denizenTyped('li@gold|3|a&pipeb|');
      expect(list.type, 'list');
      expect(list.value.toString(), '["gold",3,"a|b"]');
      final DenizenTyped map = denizenTyped('map@[name=Ann;level=4;tags=li@x|y|]');
      expect(map.type, 'map');
      expect(map.value.toString(), '{"level":4,"name":"Ann","tags":["x","y"]}');
      expect(denizenTyped('li@${'x|' * 300}').reason, isNotEmpty, reason: 'lists above 256 items are refused');
      expect(denizenExpiration('time@soon'), 0);
    });

    test('sound names become resource keys', () {
      expect(denizenSoundKey('ENTITY_VILLAGER_YES', false), 'minecraft:entity.villager.yes');
      expect(denizenSoundKey('block_note_block_pling', false), 'minecraft:block.note_block.pling');
      expect(denizenSoundKey('ambient', false), isNull);
      expect(denizenSoundKey('custom.sound', true), 'minecraft:custom.sound');
    });
  });
}
