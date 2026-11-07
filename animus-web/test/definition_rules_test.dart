import 'package:animus_editor/logic/citizens/definition_validation.dart';
import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

const String _skin = 'dc33741fb113d5f1f3876ba48fb235adefc63628862c1e659f4061d8c7c18717';

JsonMap _npc([JsonMap extra = const <String, Object?>{}]) => <String, Object?>{
      'schemaVersion': 1,
      'revision': 1,
      'uuid': '00000000-0000-4000-8000-000000000001',
      'id': 'a',
      'group': 'town',
      'name': 'Anna',
      'type': 'minecraft:player',
      'spawned': false,
      'position': <String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0, 'yaw': 0, 'pitch': 0},
      ...extra,
    };

List<String> _messages(List<Diagnostic> diagnostics) => <String>[for (final Diagnostic d in diagnostics) '${d.path}: ${d.message}'];

void main() {
  group('definitionActivationErrors', () {
    test('a skin needs a player body, with the server path and message', () {
      expect(definitionActivationErrors(_npc(<String, Object?>{'type': 'minecraft:llama', 'skin': _skin})), <String>[r'$.skin: textures require a player body']);
      expect(definitionActivationErrors(_npc(<String, Object?>{'skin': _skin})), isEmpty);
      expect(definitionActivationErrors(_npc(<String, Object?>{'type': 'minecraft:llama'})), isEmpty);
    });

    test('a sitting NPC cannot ride another NPC', () {
      final JsonMap riding = _npc(<String, Object?>{
        'pose': 'sitting',
        'modules': <String, Object?>{
          'animus:mount': <String, Object?>{'vehicle': 'town/horse'},
        },
      });
      expect(definitionActivationErrors(riding), <String>[r'$.pose: a sitting NPC cannot also ride another NPC; remove the pose or the mount']);
      expect(
        definitionActivationErrors(<String, Object?>{
          ...riding,
          'modules': <String, Object?>{
            'animus:mount': <String, Object?>{'vehicle': 'town/horse', 'enabled': false},
          },
        }),
        isEmpty,
      );
    });
  });

  group('editor documents', () {
    test('an NPC document reports the body and skin rule', () {
      final AnimusDocument document = validateSource(const DocumentLocation(DocumentKind.npc, 'town/a'), 'npcs/town/a.json', prettyJson(_npc(<String, Object?>{'type': 'minecraft:llama', 'skin': _skin})), SharedSchemas.embedded);
      expect(_messages(document.diagnostics), contains(r'$.skin: textures require a player body'));
      expect(document.valid, isFalse);
    });

    test('a skin that only groups give a non-player NPC is reported on the effective definition', () {
      final Map<String, JsonMap> groups = <String, JsonMap>{
        'guards': <String, Object?>{
          'values': <String, Object?>{'skin': _skin},
        },
        'crowd': <String, Object?>{
          'values': <String, Object?>{
            'skins': <Object?>[_skin],
          },
        },
      };
      expect(_messages(effectiveActivationDiagnostics(_npc(<String, Object?>{'type': 'minecraft:llama', 'groups': <Object?>['guards']}), groups)),
          <String>[r'$.skin: textures require a player body (the skin comes from group guards)']);
      expect(_messages(effectiveActivationDiagnostics(_npc(<String, Object?>{'type': 'minecraft:llama', 'groups': <Object?>['crowd']}), groups)),
          <String>[r'$.skin: textures require a player body (the skin comes from group crowd)']);
      expect(effectiveActivationDiagnostics(_npc(<String, Object?>{'groups': <Object?>['guards']}), groups), isEmpty);
      expect(effectiveActivationDiagnostics(_npc(<String, Object?>{'type': 'minecraft:llama', 'skin': _skin, 'groups': <Object?>['guards']}), groups), isEmpty,
          reason: 'the NPC document already reports its own skin');
    });
  });

  group('definitionRuleErrors', () {
    List<String> rules(JsonMap extra, {String id = 'town/a'}) => definitionRuleErrors(_npc(extra), id: id);

    test('a clean definition has none', () {
      expect(rules(const <String, Object?>{}), isEmpty);
    });

    test('name and hologram lines refuse control characters', () {
      expect(rules(<String, Object?>{'name': 'An\u0007na'}), <String>[r'$.name: expected at most 256 characters without control characters']);
      expect(
        rules(<String, Object?>{
          'hologram': <String, Object?>{
            'lines': <Object?>[
              'ok',
              <String, Object?>{'text': 'b\u0001ad'},
            ],
          },
        }),
        <String>[r'$.hologram.lines[1]: expected at most 256 characters without control characters'],
      );
    });

    test('pitch stays within -90..90', () {
      expect(rules(<String, Object?>{
        'position': <String, Object?>{'world': 'world', 'x': 0, 'y': 64, 'z': 0, 'yaw': 0, 'pitch': 91},
      }), <String>[r'$.position.pitch: must be within -90..90']);
    });

    test('an attribute is set once, with or without its namespace', () {
      expect(rules(<String, Object?>{
        'attributes': <String, Object?>{'scale': 2, 'minecraft:scale': 3},
      }), <String>[r'$.attributes.minecraft:scale: scale is set twice']);
    });

    test('inventory slots stay inside its size', () {
      expect(
        rules(<String, Object?>{
          'inventory': <String, Object?>{
            'size': 9,
            'slots': <String, Object?>{
              '3': <String, Object?>{'id': 'minecraft:stone'},
              '12': <String, Object?>{'id': 'minecraft:stone'},
            },
          },
        }),
        <String>[r'$.inventory.slots.12: slot outside the inventory size 9'],
      );
    });

    test('an NPC cannot ride itself', () {
      expect(rules(<String, Object?>{
        'modules': <String, Object?>{
          'animus:mount': <String, Object?>{'vehicle': 'town/a'},
        },
      }), <String>[r'$.modules.animus:mount.vehicle: an NPC cannot ride itself']);
      expect(rules(<String, Object?>{
        'modules': <String, Object?>{
          'animus:mount': <String, Object?>{'vehicle': 'town/horse'},
        },
      }), isEmpty);
    });

    test('look-close random ranges keep the minimum first', () {
      expect(
        rules(<String, Object?>{
          'modules': <String, Object?>{
            'animus:look-close': <String, Object?>{
              'randomLook': <String, Object?>{
                'yaw': <Object?>[40, 10],
                'pitch': <Object?>[-10, 10],
              },
            },
          },
        }),
        <String>[r'$.modules.animus:look-close.randomLook.yaw: the minimum is above the maximum'],
      );
    });

    test('NPC documents report them', () {
      final AnimusDocument document = validateSource(
        const DocumentLocation(DocumentKind.npc, 'town/a'),
        'npcs/town/a.json',
        prettyJson(_npc(<String, Object?>{
          'modules': <String, Object?>{
            'animus:mount': <String, Object?>{'vehicle': 'town/a'},
          },
        })),
        SharedSchemas.embedded,
      );
      expect(_messages(document.diagnostics), contains(r'$.modules.animus:mount.vehicle: an NPC cannot ride itself'));
    });
  });

  group('groupDocumentErrors', () {
    test('id matches the file, description has no control characters, file at most 256 KiB', () {
      expect(groupDocumentErrors(<String, Object?>{'id': 'guards'}, 'guards', 100), isEmpty);
      expect(groupDocumentErrors(<String, Object?>{'id': 'other'}, 'guards', 100), <String>[r'$.id: the group id is other but the file is guards']);
      expect(groupDocumentErrors(<String, Object?>{'id': 'guards', 'description': 'a\u0002b'}, 'guards', 100),
          <String>[r'$.description: expected at most 256 characters without control characters']);
      expect(groupDocumentErrors(<String, Object?>{'id': 'guards'}, 'guards', 256 * 1024 + 1), <String>[r'$: the group file exceeds 256 KiB']);
    });

    test('group values get the NPC rules under values, combat included', () {
      final List<String> errors = groupDocumentErrors(<String, Object?>{
        'id': 'guards',
        'values': <String, Object?>{
          'hologram': <String, Object?>{
            'lines': <Object?>['x\u0003'],
          },
          'modules': <String, Object?>{
            'animus:combat': <String, Object?>{'targetableByMobs': 'yes'},
          },
        },
      }, 'guards', 100);
      expect(errors, contains(r'$.values.hologram.lines[0]: expected at most 256 characters without control characters'));
      expect(errors.where((String e) => e.startsWith(r'$.values.modules.animus:combat')), isNotEmpty);
    });

    test('group documents report them', () {
      final AnimusDocument document = validateSource(
        const DocumentLocation(DocumentKind.group, 'guards'),
        'groups/guards.json',
        prettyJson(<String, Object?>{'schemaVersion': 1, 'revision': 1, 'id': 'other', 'values': <String, Object?>{}}),
        SharedSchemas.embedded,
      );
      expect(_messages(document.diagnostics), contains(r'$.id: the group id is other but the file is guards'));
    });
  });
}
