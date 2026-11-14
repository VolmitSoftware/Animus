import 'dart:math' as math;

import 'package:animus_editor/logic/gui/gui_catalog.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/gui/gui_presets.dart';
import 'package:animus_editor/logic/gui/hologram_lines.dart';
import 'package:animus_editor/mc/gui/gui_scene.dart';
import 'package:animus_editor/mc/npc/npc_scene.dart';
import 'package:animus_editor/mc/scene/mc_math.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

void main() {
  group('actions', () {
    test('should keep nested lists the new type has and name the ones it drops', () {
      final JsonMap action = <String, Object?>{
        'type': 'if',
        'when': 'viewer.level > 2',
        'then': <Object?>[<String, Object?>{'type': 'close'}],
        'else': <Object?>[<String, Object?>{'type': 'stop'}],
        'trigger': 'left_click',
        'atTicks': 4,
      };
      final (JsonMap chance, List<String> lost) = changeActionType(action, 'chance');
      expect(lost, isEmpty);
      expect(chance['then'], action['then']);
      expect(chance['else'], action['else']);
      expect(chance['trigger'], 'left_click');
      expect(chance['atTicks'], 4);
      final (JsonMap message, List<String> dropped) = changeActionType(action, 'message');
      expect(dropped, <String>['then', 'else']);
      expect(message.keys, isNot(contains('then')));
      expect(changeActionType(<String, Object?>{'type': 'if', 'then': <Object?>[]}, 'message').$2, isEmpty);
    });

    test('should start give and take without forcing an item type', () {
      expect(newAction('give')['item'], <String, Object?>{'item': 'minecraft:bread'});
    });

    test('should humanize JSON keys for labels', () {
      expect(humanizeKey('cooldownTicks'), 'Cooldown ticks');
      expect(humanizeKey('customModelValue'), 'Custom model value');
      expect(humanizeKey('when'), 'When');
      expect(humanizeKey('link_distance'), 'Link distance');
      expect(humanizeKey('backgroundArgb'), 'Background colour');
      expect(humanizeKey('npcId'), 'NPC ID');
      expect(humanizeKey('argb'), 'Colour');
    });
  });

  group('hologram rows', () {
    test('should keep show and scale when a row value is edited', () {
      final JsonMap row = <String, Object?>{'text': '&fOld', 'show': 'viewer.level > 1', 'scale': 2};
      expect(withHologramLineValue(row, '&aNew'), <String, Object?>{'text': '&aNew', 'show': 'viewer.level > 1', 'scale': 2});
      final JsonMap item = <String, Object?>{'item': <String, Object?>{'item': 'minecraft:stone', 'count': 3}, 'scale': 0.5};
      expect(withHologramLineValue(item, <String, Object?>{'item': 'minecraft:diamond', 'count': 3}),
          <String, Object?>{'item': <String, Object?>{'item': 'minecraft:diamond', 'count': 3}, 'scale': 0.5});
    });

    test('should keep a plain text row a string until it needs row fields', () {
      expect(withHologramLineValue('&fOld', '&fNew'), '&fNew');
      expect(withHologramLineField('&fHi', 'show', 'viewer.op'), <String, Object?>{'text': '&fHi', 'show': 'viewer.op'});
      expect(withHologramLineField(<String, Object?>{'text': 'a', 'scale': 2}, 'scale', null), <String, Object?>{'text': 'a'});
      expect(withHologramLineField(null, 'scale', 3), <String, Object?>{'text': '', 'scale': 3});
    });

    test('should keep row fields when the kind changes and drop the old value', () {
      final JsonMap row = <String, Object?>{'head': 'Notch', 'show': false, 'scale': 1.5};
      expect(withHologramLineKind(row, 'entity'), <String, Object?>{'entity': 'minecraft:pig', 'show': false, 'scale': 1.5});
      expect(withHologramLineKind('&fText', 'block'), <String, Object?>{'block': 'minecraft:stone'});
      expect(withHologramLineKind(<String, Object?>{'block': 'minecraft:stone'}, 'text'), '&fText');
      expect(withHologramLineKind(<String, Object?>{'block': 'x', 'scale': 2}, 'text'), <String, Object?>{'text': '&fText', 'scale': 2});
    });

    test('should read the kind of every row shape', () {
      expect(hologramLineKind(null), 'text');
      expect(hologramLineKind('a'), 'text');
      for (final String kind in hologramLineKinds) {
        expect(hologramLineKind(newHologramLine(kind)), kind);
      }
    });
  });

  group('presets', () {
    final JsonMap presets = <String, Object?>{
      'schemaVersion': 1,
      'revision': 1,
      'defaults': <String, Object?>{
        'holograms': <String, Object?>{'viewDistance': 32, 'style': <String, Object?>{'shadow': true}},
      },
      'presets': <String, Object?>{
        'holograms': <String, Object?>{
          'base': <String, Object?>{'extends': null, 'values': <String, Object?>{'refreshTicks': 20, 'style': <String, Object?>{'billboard': 'fixed'}, 'id': 'x'}},
          'sign': <String, Object?>{'extends': 'base', 'values': <String, Object?>{'refreshTicks': 5, 'yaw': 90}},
          'loop': <String, Object?>{'extends': 'loop', 'values': <String, Object?>{}},
        },
      },
    };

    test('should merge defaults then the extends chain from the base down', () {
      final PresetValues values = resolvePreset(presets, 'holograms', 'sign');
      expect(values.problem, isNull);
      expect(values.values, <String, Object?>{
        'viewDistance': 32,
        'style': <String, Object?>{'shadow': true, 'billboard': 'fixed'},
        'refreshTicks': 5,
        'yaw': 90,
      });
    });

    test('should name a missing preset, a missing base and a loop', () {
      expect(resolvePreset(presets, 'holograms', 'nope').problem, "presets.holograms has no preset 'nope'");
      expect(resolvePreset(null, 'holograms', 'sign').problem, contains('no presets document'));
      expect(resolvePreset(presets, 'holograms', 'loop').problem, contains('extends itself'));
    });

    test('should inline under the document fields and remove preset', () {
      final JsonMap document = <String, Object?>{
        'schemaVersion': 3,
        'preset': 'sign',
        'yaw': 45,
        'style': <String, Object?>{'billboard': 'center'},
        'lines': <Object?>['&fHi'],
      };
      final (JsonMap? inlined, String? problem) = inlinePreset(document, DocumentKind.hologram, presets);
      expect(problem, isNull);
      expect(inlined, <String, Object?>{
        'schemaVersion': 3,
        'yaw': 45,
        'style': <String, Object?>{'shadow': true, 'billboard': 'center'},
        'lines': <Object?>['&fHi'],
        'viewDistance': 32,
        'refreshTicks': 5,
      });
      expect(inlined!.keys.toList().indexOf('yaw'), 1, reason: 'own keys keep their order');
    });

    test('should refuse kinds without presets and odd names', () {
      expect(inlinePreset(<String, Object?>{'preset': 'a'}, DocumentKind.menu, presets).$2, contains('have no presets'));
      expect(validPresetName('ok name'), isTrue);
      expect(validPresetName(' lead'), isFalse);
      expect(validPresetName('a/b'), isFalse);
      expect(validPresetName('a..b'), isFalse);
      expect(presetKinds, hasLength(26));
    });
  });

  group('scenes', () {
    test('should place menu components at every offset axis and expose pick targets', () {
      final GuiScene scene = menuScene(<String, Object?>{
        'offset': <Object?>[2, 1, -3],
        'components': <Object?>[
          <String, Object?>{'id': 'a', 'offset': <Object?>[1, 0, 0], 'data': <String, Object?>{'type': 'decoration', 'icon': <String, Object?>{'type': 'text', 'text': 'Hi'}}},
          <String, Object?>{'id': 'b', 'offset': <Object?>[0, 0, 0.5], 'data': <String, Object?>{'type': 'tabs', 'tabs': <Object?>[<String, Object?>{'id': 'x'}]}},
          <String, Object?>{'id': 'c', 'offset': <Object?>[0, 0, 0], 'data': <String, Object?>{'type': 'decoration', 'icon': <String, Object?>{'type': 'entity', 'entity': 'minecraft:cow'}}},
        ],
      });
      expect(scene.targets.map((GuiSceneTarget t) => t.key), <String>['a', 'b', 'c']);
      expect(scene.targets.first.centre.x, closeTo(3, 1e-9));
      expect(scene.targets.first.centre.z, closeTo(-3, 1e-9));
      expect(scene.targets[1].centre.z, closeTo(-2.5, 1e-9));
      expect(scene.notes.any((String note) => note.contains('labelled box')), isTrue);
      expect(scene.texts.where((NpcText text) => text.lines.join().contains('Cow')), hasLength(1));
    });

    test('should lay the default menu out as the server does: one line each, centred on its offset, framed whole', () {
      final GuiScene scene = menuScene(readJson('gloss/fixtures/menu-default.json')! as JsonMap);
      NpcText text(String part) => scene.texts.firstWhere((NpcText text) => text.lines.join().contains(part));
      final NpcText title = text('Gloss Menu'), body = text('Edit menus'), close = text('Close');
      expect(body.lineWidth, 16384, reason: 'menu text does not wrap unless its style says so');
      expect(<bool>[title.placed, body.placed, close.placed], <bool>[true, true, true], reason: 'the stage leaves menu text where the menu puts it');
      expect(body.background, '#00000000');
      expect(title.anchor.y, greaterThan(body.anchor.y));
      expect(body.anchor.y, greaterThan(close.anchor.y));
      for (final (NpcText shown, double offset) in <(NpcText, double)>[(title, 0.35), (body, 0.05), (close, -0.35)]) {
        expect(shown.anchor.y + shown.lines.length * guiTextRowBlocks / 2, closeTo(1.7 + offset, 1e-9), reason: 'the block is centred on the offset');
      }
      expect(title.anchor.y, greaterThanOrEqualTo(body.anchor.y + guiTextRowBlocks), reason: 'title and body do not overlap');
      expect(body.anchor.y, greaterThanOrEqualTo(close.anchor.y + guiTextRowBlocks), reason: 'body and close do not overlap');
      final Iterable<double> xs = scene.scene.frame.map((McVec3 point) => point.x);
      expect(xs.reduce(math.max) - xs.reduce(math.min), greaterThanOrEqualTo(5), reason: 'the camera frames the whole body line');
      final GuiSceneTarget pick = scene.targets.firstWhere((GuiSceneTarget target) => target.key == 'body');
      expect(pick.half.x * 2, greaterThanOrEqualTo(5));
      expect(pick.half.y * 2, closeTo(guiTextRowBlocks, 1e-9));
    });

    test('should draw menu text with its icon style', () {
      final NpcText text = menuScene(<String, Object?>{
        'components': <Object?>[
          <String, Object?>{
            'id': 't',
            'offset': <Object?>[0, 0, 0],
            'data': <String, Object?>{
              'type': 'decoration',
              'icon': <String, Object?>{'type': 'text', 'text': 'Hi', 'style': <String, Object?>{'scaleY': 2, 'lineWidth': 80, 'shadow': true}},
            },
          },
        ],
      }).texts.single;
      expect(text.scaleY, 2);
      expect(text.lineWidth, 80);
      expect(text.shadow, isTrue);
      expect(text.anchor.y + guiTextRowBlocks * 2 / 2, closeTo(1.5, 1e-9));
    });

    test('should tilt a fixed hologram by pitch, keep a facing one upright and note entity rows', () {
      final GuiScene flat = hologramScene(<String, Object?>{'lines': <Object?>['a', 'b']});
      final GuiScene facing = hologramScene(<String, Object?>{'pitch': 90, 'lines': <Object?>['a', 'b']});
      final GuiScene tilted = hologramScene(<String, Object?>{
        'pitch': 90,
        'style': <String, Object?>{'billboard': 'fixed'},
        'lines': <Object?>['a', 'b'],
      });
      expect(flat.texts.single.anchor.z, closeTo(0, 1e-9));
      expect(facing.texts.single.anchor.z, closeTo(0, 1e-9), reason: 'a centre billboard faces the viewer whatever the pitch');
      expect(tilted.texts.single.anchor.z.abs(), greaterThan(0.1));
      expect(tilted.notes.where((String note) => note.contains('billboard')), hasLength(1));
      final GuiScene entity = hologramScene(<String, Object?>{
        'lines': <Object?>[<String, Object?>{'entity': 'cow', 'show': false}, <String, Object?>{'entity': 'pig'}],
      });
      expect(entity.notes.where((String note) => note.contains('hidden')), hasLength(1));
      expect(entity.texts.where((NpcText text) => text.lines.join().contains('Pig')), hasLength(1), reason: 'the box is labelled with the entity by name');
      expect(entity.texts.where((NpcText text) => text.lines.join().contains('minecraft:')), isEmpty);
    });

    test('should draw hologram text rows as one text display with the hologram style', () {
      final GuiScene scene = hologramScene(<String, Object?>{
        'lines': <Object?>[
          '&6Title',
          'plain',
          <String, Object?>{'item': <String, Object?>{'type': 'item', 'item': 'minecraft:map'}, 'scale': 0.6},
          '<bold>Bold',
        ],
        'style': <String, Object?>{
          'backgroundArgb': '#80112233',
          'shadow': true,
          'textAlignment': 'left',
          'textOpacity': 128,
          'lineWidth': 120,
          'scaleX': 2,
          'scaleY': 1.5,
          'blockLight': 4,
          'skyLight': 2,
        },
      });
      final NpcText text = scene.texts.single;
      expect(text.background, '#80112233');
      expect(text.shadow, isTrue);
      expect(text.alignment, McTextAlign.left);
      expect(text.opacity, closeTo(128 / 255, 1e-9));
      expect(text.lineWidth, 120);
      expect(text.scaleX, 2);
      expect(text.scaleY, 1.5);
      expect(text.brightness, lessThan(0.5));
      // An item 0.6 tall takes two blank rows of 0.375 blocks.
      expect(text.lines, <String>['&6Title', 'plain', ' ', ' ', '<bold>Bold']);
      expect(text.styled![1].single.color, 0xFFAA00, reason: 'a colour carries to the next row of one text display');
      expect(text.styled![4].single.bold, isTrue);
      expect(scene.notes.where((String note) => note.contains('Style')), isEmpty);
    });

    test('should draw an unstyled hologram as the server sends it', () {
      final NpcText text = hologramScene(<String, Object?>{'lines': <Object?>['a']}).texts.single;
      expect(text.background, '#00000000', reason: 'no background unless backgroundArgb sets one');
      expect(text.shadow, isFalse);
      expect(text.alignment, McTextAlign.center);
      expect(text.opacity, 1);
      expect(text.brightness, isNull);
      expect(hologramScene(<String, Object?>{'style': <String, Object?>{'textOpacity': 2}, 'lines': <Object?>['a']}).texts.single.opacity, 1,
          reason: 'the client draws opacity 3 and below fully opaque');
      final GuiScene hidden = hologramScene(<String, Object?>{'style': <String, Object?>{'seeThrough': false}, 'lines': <Object?>['a']});
      expect(hidden.notes.where((String note) => note.contains('see-through')), hasLength(1));
    });
  });
}
