import 'package:animus_editor/logic/citizens/definition_validation.dart';
import 'package:animus_editor/logic/citizens/java_json.dart';
import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

const String _skin = 'dc33741fb113d5f1f3876ba48fb235adefc63628862c1e659f4061d8c7c18717';

JsonMap _npc([JsonMap extra = const <String, Object?>{}]) => <String, Object?>{
      'schemaVersion': 2,
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

    test('a rig and a Model Engine model cannot share a body', () {
      const String refused = r'$.modules.animus:modelengine: an NPC shows either a rig or a Model Engine model; remove one';
      final JsonMap rig = <String, Object?>{'id': 'animus:drake'};
      JsonMap model(Object? module) => <String, Object?>{'animus:modelengine': module};
      expect(definitionActivationErrors(_npc(<String, Object?>{'rig': rig, 'modules': model(<String, Object?>{'model': 'drake'})})), <String>[refused]);
      expect(definitionActivationErrors(_npc(<String, Object?>{'rig': rig, 'modules': model(<String, Object?>{'model': 'drake', 'enabled': false})})), isEmpty);
      expect(definitionActivationErrors(_npc(<String, Object?>{'modules': model(<String, Object?>{'model': 'drake'})})), isEmpty);
    });

    test('a sitting NPC may also ride: the vehicle is its seat while it rides', () {
      expect(
        definitionActivationErrors(_npc(<String, Object?>{
          'pose': 'sitting',
          'modules': <String, Object?>{
            'animus:mount': <String, Object?>{'vehicle': 'horse'},
          },
        })),
        isEmpty,
      );
      expect(
        definitionActivationErrors(_npc(<String, Object?>{
          'type': 'minecraft:villager',
          'pose': 'sitting',
          'modules': <String, Object?>{
            'animus:mount': <String, Object?>{'entity': 'minecraft:minecart'},
          },
        })),
        isEmpty,
      );
    });
  });

  group('validateDefinition poses', () {
    List<String> pose(String type, String pose) {
      final JObject position = JObject()..addString('world', 'world');
      for (final String key in const <String>['x', 'y', 'z', 'yaw', 'pitch']) {
        position.addInt(key, 0);
      }
      return validateDefinition(JObject()
        ..addInt('schemaVersion', 2)
        ..addInt('revision', 1)
        ..addString('uuid', '00000000-0000-4000-8000-000000000000')
        ..addString('id', 'probe')
        ..addString('group', 'probes')
        ..addString('name', 'Probe')
        ..addString('type', type)
        ..addBool('spawned', false)
        ..add('position', position)
        ..addString('pose', pose));
    }

    test('a player takes every pose, as NpcAppearance reads it', () {
      for (final String each in const <String>['standing', 'sneaking', 'sleeping', 'swimming', 'sitting', 'crawling', 'bellyflop', 'spinning']) {
        expect(pose('minecraft:player', each), isEmpty, reason: each);
      }
      expect(pose('minecraft:player', 'lying'), <String>[r'$.pose: expected standing, sneaking, sleeping, swimming, sitting, crawling, bellyflop or spinning']);
    });

    test('other living bodies stand or sit, the dragon and objects only stand', () {
      expect(pose('minecraft:villager', 'sitting'), isEmpty);
      expect(pose('minecraft:horse', 'sitting'), isEmpty);
      expect(pose('minecraft:horse', 'crawling'), <String>[r'$.pose: crawling is available only for minecraft:player bodies']);
      expect(pose('minecraft:ender_dragon', 'sitting'), <String>[r'$.pose: sitting is available only for living bodies other than the ender dragon']);
      expect(pose('minecraft:item_display', 'sitting'), <String>[r'$.pose: sitting is available only for living bodies other than the ender dragon']);
      expect(pose('minecraft:item_display', 'standing'), isEmpty);
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

    test('an NPC cannot ride itself, named by its own id', () {
      expect(rules(<String, Object?>{
        'modules': <String, Object?>{
          'animus:mount': <String, Object?>{'vehicle': 'a'},
        },
      }), <String>[r'$.modules.animus:mount.vehicle: an NPC cannot ride itself']);
      expect(rules(<String, Object?>{
        'modules': <String, Object?>{
          'animus:mount': <String, Object?>{'vehicle': 'horse'},
        },
      }), isEmpty);
    });

    test('a mount names a vehicle NPC or a real entity, as MountDeclaration reads it', () {
      List<String> mount(Object? module) => rules(<String, Object?>{
            'modules': <String, Object?>{'animus:mount': module},
          });
      const String uuid = '0a1b2c3d-0000-4000-8000-000000000003';
      expect(mount(<String, Object?>{'entity': 'minecraft:horse'}), isEmpty);
      expect(mount(<String, Object?>{'entity': 'minecraft:horse', 'range': 16}), isEmpty);
      expect(mount(<String, Object?>{'entity': uuid}), isEmpty);
      expect(mount(<String, Object?>{'enabled': false}), isEmpty);
      expect(mount('horse'), <String>[r'$.modules.animus:mount: expected object']);
      expect(mount(<String, Object?>{'vehicle': 'horse', 'speed': 2}), <String>[r'$.modules.animus:mount.speed: unknown field']);
      expect(mount(<String, Object?>{'vehicle': 'horse', 'enabled': 'yes'}), <String>[r'$.modules.animus:mount.enabled: expected boolean']);
      expect(mount(<String, Object?>{'vehicle': 'horse', 'entity': 'minecraft:horse'}),
          <String>[r'$.modules.animus:mount.entity: name a vehicle NPC or a real entity, not both']);
      expect(mount(<String, Object?>{'entity': 'Horse'}), <String>[r'$.modules.animus:mount.entity: expected an entity UUID or an entity type such as minecraft:horse']);
      expect(mount(<String, Object?>{'entity': 7}), <String>[r'$.modules.animus:mount.entity: expected an entity UUID or an entity type such as minecraft:horse']);
      expect(mount(<String, Object?>{'entity': 'minecraft:player'}), <String>[r'$.modules.animus:mount.entity: NPCs do not ride players']);
      expect(mount(<String, Object?>{'entity': uuid, 'range': 4}), <String>[r'$.modules.animus:mount.range: only an entity type is searched within a range']);
      expect(mount(<String, Object?>{'vehicle': 'horse', 'range': 4}), <String>[r'$.modules.animus:mount.range: only an entity type is searched within a range']);
      for (final Object range in const <Object>[0.5, 33, 'far']) {
        expect(mount(<String, Object?>{'entity': 'minecraft:horse', 'range': range}), <String>[r'$.modules.animus:mount.range: expected 1-32 blocks'], reason: '$range');
      }
      expect(mount(<String, Object?>{'vehicle': 'Horse!'}), <String>[r'$.modules.animus:mount.vehicle: expected an NPC id']);
      expect(mount(<String, Object?>{}), <String>[r'$.modules.animus:mount.vehicle: expected an NPC id']);
      expect(mount(<String, Object?>{'vehicle': 'Horse!', 'enabled': false}), isEmpty, reason: 'a mount that is off names no vehicle');
    });

    test('a seat takes a click, permission, stack and empty hand, as SeatModule reads them', () {
      List<String> seat(Object? module, {JsonMap? riding}) => rules(<String, Object?>{
            'modules': <String, Object?>{'animus:seat': module, 'animus:riding': ?riding},
          });
      expect(seat(<String, Object?>{}), isEmpty);
      expect(seat(<String, Object?>{'enabled': true, 'click': 'sneak-right', 'permission': 'town.seat', 'stack': 8, 'emptyHand': false}), isEmpty);
      expect(seat('yes'), <String>[r'$.modules.animus:seat: expected object']);
      expect(seat(<String, Object?>{'height': 1}), <String>[r'$.modules.animus:seat.height: unknown field']);
      expect(seat(<String, Object?>{'enabled': 'on'}), <String>[r'$.modules.animus:seat.enabled: expected boolean']);
      expect(seat(<String, Object?>{'emptyHand': 1}), <String>[r'$.modules.animus:seat.emptyHand: expected boolean']);
      expect(seat(<String, Object?>{'click': 'left'}), <String>[r'$.modules.animus:seat.click: expected right or sneak-right']);
      expect(seat(<String, Object?>{'permission': 5}), <String>[r'$.modules.animus:seat.permission: expected string']);
      expect(seat(<String, Object?>{'permission': 'Town.Seat'}), <String>[r'$.modules.animus:seat.permission: expected a lowercase permission node']);
      expect(seat(<String, Object?>{'permission': 'a' * 129}), <String>[r'$.modules.animus:seat.permission: expected a lowercase permission node']);
      for (final Object stack in const <Object>[0, 2.5, 9, 'four']) {
        expect(seat(<String, Object?>{'stack': stack}), <String>[r'$.modules.animus:seat.stack: expected 1-8'], reason: '$stack');
      }
    });

    test('a right-click seat cannot share an NPC with riding', () {
      List<String> seat(JsonMap module, JsonMap riding) => rules(<String, Object?>{
            'modules': <String, Object?>{'animus:seat': module, 'animus:riding': riding},
          });
      const String refused = r'$.modules.animus:seat.click: a right-click mounts this NPC through riding; use sneak-right for the seat';
      final JsonMap on = <String, Object?>{'enabled': true};
      expect(seat(<String, Object?>{}, on), <String>[refused]);
      expect(seat(<String, Object?>{'click': 'right'}, on), <String>[refused]);
      expect(seat(<String, Object?>{'click': 'sneak-right'}, on), isEmpty);
      expect(seat(<String, Object?>{'enabled': false}, on), isEmpty);
      expect(seat(<String, Object?>{}, <String, Object?>{'enabled': false}), isEmpty);
      expect(seat(<String, Object?>{}, <String, Object?>{}), isEmpty, reason: 'riding without enabled is off');
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
            'animus:mount': <String, Object?>{'vehicle': 'a'},
          },
        })),
        SharedSchemas.embedded,
      );
      expect(_messages(document.diagnostics), contains(r'$.modules.animus:mount.vehicle: an NPC cannot ride itself'));
    });

    test('a field the schema already reports is reported once', () {
      AnimusDocument npc(JsonMap modules) => validateSource(
            const DocumentLocation(DocumentKind.npc, 'town/a'),
            'npcs/town/a.json',
            prettyJson(_npc(<String, Object?>{'modules': modules})),
            SharedSchemas.embedded,
          );
      final List<Diagnostic> click = <Diagnostic>[
        for (final Diagnostic d in npc(<String, Object?>{'animus:seat': <String, Object?>{'click': 'left'}}).diagnostics)
          if (d.path.endsWith('click')) d,
      ];
      expect(click, hasLength(1));
      expect(
        _messages(npc(<String, Object?>{
          'animus:seat': <String, Object?>{},
          'animus:riding': <String, Object?>{'enabled': true},
        }).diagnostics),
        <String>[r'$.modules.animus:seat.click: a right-click mounts this NPC through riding; use sneak-right for the seat'],
      );
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
        prettyJson(<String, Object?>{'schemaVersion': 2, 'revision': 1, 'id': 'other', 'values': <String, Object?>{}}),
        SharedSchemas.embedded,
      );
      expect(_messages(document.diagnostics), contains(r'$.id: the group id is other but the file is guards'));
    });
  });
}
