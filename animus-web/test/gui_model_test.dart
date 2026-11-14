import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/data_folder_export.dart';
import 'package:animus_editor/logic/gui/gloss_bundle.dart';
import 'package:animus_editor/logic/gui/gui_catalog.dart';
import 'package:animus_editor/logic/gui/gui_expression.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/gui/gui_layout.dart';
import 'package:animus_editor/logic/gui/gui_templates.dart';
import 'package:animus_editor/logic/gui/gui_text.dart';
import 'package:animus_editor/logic/gui/gui_validation.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/logic/suggest/completion_item.dart';
import 'package:animus_editor/logic/suggest/field_profile.dart';
import 'package:animus_editor/logic/suggest/text_completion.dart';
import 'package:animus_editor/mc/npc/mc_text.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:animus_editor/state/editor_store.dart';
import 'package:animus_editor/state/workspace_repository.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

/// test/fixtures/gui_editors, the data folder the GUI editor checks open.
Map<String, Uint8List> guiFixture() {
  final Directory directory = Directory('test/fixtures/gui_editors');
  return <String, Uint8List>{
    for (final File file in directory.listSync(recursive: true).whereType<File>())
      file.path.substring(directory.path.length + 1).replaceAll(r'\', '/'): file.readAsBytesSync(),
  };
}

JsonMap readGuiFixture(String path) => jsonDecode(File('test/fixtures/gui_editors/$path').readAsStringSync()) as JsonMap;

List<Diagnostic> errors(AnimusDocument document) =>
    document.diagnostics.where((Diagnostic diagnostic) => diagnostic.severity == DiagnosticSeverity.error).toList();

void main() {
  final SharedSchemas schemas = SharedSchemas.embedded;

  group('expressions', () {
    test('should accept the documented names, functions and list variables', () {
      expect(checkExpression('viewer.level > 10 && time.hour < 20'), isNull);
      expect(checkExpression("upper(entry) + '!'", names: const ExpressionNames(locals: <String>{'entry'})), isNull);
      expect(checkExpression('len(session.cart) == 0 ? args.x : npc.name'), isNull);
    });

    test('should refuse unknown names, unknown functions and syntax errors', () {
      expect(checkExpression('viewer.nope'), "unknown name 'viewer.nope'");
      expect(checkExpression('nope(1)'), "unknown function 'nope'");
      expect(checkExpression("marquee('hello', 4, time.second)"), isNull);
      expect(checkExpression('papi("%x%")'), 'papi(...) is not available yet');
      expect(checkExpression('1 +'), contains('column'));
      expect(checkExpression('1+' * 300), contains('longer than 512'));
    });

    test('should check each hole of a template and report an unclosed one', () {
      expect(checkTemplate('&7Hi {{ viewer.name }}'), isEmpty);
      expect(checkTemplate('{{ viewer.bogus }} and {{ server.online }}'), <String>["{{ viewer.bogus }}: unknown name 'viewer.bogus'"]);
      expect(checkTemplate('{{ viewer.name'), <String>['an expression hole is not closed with }}']);
    });

    test('should render holes with sample values and ? for values only the server has', () {
      expect(renderTemplate('Hi {{ viewer.name }}, {{ time.hour }}:00'), 'Hi Steve, 12:00');
      expect(renderTemplate('{{ session.cart }}'), '?');
      expect(sampleCondition('viewer.level > 10'), isTrue);
      expect(sampleCondition('session.muted'), isTrue);
      expect(sampleCondition(false), isFalse);
    });

    test('should complete names and functions only inside holes of a template', () {
      const CompletionEnv env = CompletionEnv();
      List<String> labels(CompletionResult result) => <String>[for (final CompletionItem item in result.items) item.label];
      final CompletionResult names = completeText('Hi {{ vie', 9, FieldProfile.guiText, env);
      expect(labels(names), contains('viewer.name'));
      final AppliedCompletion applied = names.apply('Hi {{ vie', names.items.firstWhere((CompletionItem c) => c.label == 'viewer.name'));
      expect(applied.text, 'Hi {{ viewer.name');
      expect(applied.selectionStart, applied.text.length);
      expect(completeText('Hi vie', 6, FieldProfile.guiText, env).items, isEmpty);
      expect(labels(completeText('cla', 3, FieldProfile.guiExpression, env)), contains('clamp()'));
      expect(completeText('Hi {{ ', 6, FieldProfile.guiText, env).items, isNotEmpty);
    });
  });

  group('validation', () {
    test('should name a Gloss-only action at its exact field and drop the schema noise beneath it', () {
      final AnimusDocument document = validateSource(
        const DocumentLocation(DocumentKind.inventory, 'shop:counter'),
        'gui/inventories/shop/counter.json',
        jsonEncode(readFixture('documents/inventory/invalid-gloss-only-action.json')),
        schemas,
      );
      expect(errors(document).map((Diagnostic d) => '${d.path}: ${d.message}'), <String>[
        r"$.keys.X.actions[0].type: action 'camera' is not available in Animus",
      ]);
    });

    test('should accept every fixture except the Gloss-only one', () {
      final DataFolderResult result = openDataFolder(guiFixture(), schemas);
      final Map<String, List<String>> problems = <String, List<String>>{
        for (final AnimusDocument document in result.documents)
          document.path: <String>[for (final Diagnostic d in errors(document)) '${d.path}: ${d.message}'],
      };
      expect(problems, <String, List<String>>{
        'gui/holograms/town/welcome.json': <String>[],
        'gui/inventories/town/board.json': <String>[],
        'gui/inventories/town/gloss-only.json': <String>[r"$.keys.X.actions[1].type: action 'camera' is not available in Animus"],
        'gui/menus/town/hub.json': <String>[],
        'gui/presets.json': <String>[],
        'gui/text-animations/town/sparkle.json': <String>[
          r"$.preset: Gloss presets are not available in Animus; inline the preset's fields",
          r'$.mode: expected one of [ascend, ascend_descend, descend, random]',
        ],
        'shops/town/market.json': <String>[],
      });
    });

    test('should refuse menu components, holographic icons, presets and cutscene fields in a chest GUI', () {
      final JsonMap doc = <String, Object?>{
        'schemaVersion': 1,
        'resolution': '9x1',
        'preset': 'x',
        'mask': <String>['ab'],
        'keys': <String, Object?>{
          'a': <String, Object?>{'type': 'slider', 'var': 'v'},
          'b': <String, Object?>{
            'type': 'button',
            'icon': <String, Object?>{'type': 'block', 'block': 'minecraft:stone'},
            'actions': <Object?>[
              <String, Object?>{'type': 'sequence', 'once': 'intro', 'steps': <Object?>[<String, Object?>{'type': 'glow'}]},
            ],
          },
        },
      };
      expect(<String>[for (final Diagnostic d in validateGui(DocumentKind.inventory, doc)) if (d.code == refusedCode) '${d.path}: ${d.message}'], <String>[
        r"$.preset: Gloss presets are not available in Animus; inline the preset's fields",
        r"$.keys.a.type: component 'slider' is a holographic menu component and has no meaning in a chest window",
        r"$.keys.b.icon.type: icon 'block' is a holographic menu icon and cannot be drawn in a chest window",
        r"$.keys.b.actions[0].steps[0].type: action 'glow' is not available in Animus",
        r"$.keys.b.actions[0].once: sequence field 'once' is not available in Animus",
      ]);
    });

    test('should report missing call targets, call loops, mask bounds and the command allow-list', () {
      final JsonMap doc = <String, Object?>{
        'schemaVersion': 1,
        'resolution': '9x1',
        'mask': <String>['ab', 'cc'],
        'keys': <String, Object?>{
          'a': <String, Object?>{
            'type': 'button',
            'icon': <String, Object?>{'type': 'item', 'item': 'minecraft:stone'},
            'actions': <Object?>[
              <String, Object?>{'type': 'call', 'action': 'missing'},
              <String, Object?>{'type': 'command', 'command': 'say hi'},
            ],
          },
        },
        'slots': <String, Object?>{'12': <String, Object?>{'type': 'decoration', 'icon': <String, Object?>{'type': 'item', 'item': 'minecraft:stone'}}},
        'actions': <String, Object?>{
          'one': <Object?>[<String, Object?>{'type': 'call', 'action': 'two'}],
          'two': <Object?>[<String, Object?>{'type': 'call', 'action': 'one'}],
        },
      };
      final List<String> found = <String>[for (final Diagnostic d in validateGui(DocumentKind.inventory, doc)) '${d.severity.name} ${d.path}: ${d.message}'];
      expect(found, containsAll(<String>[
        "error \$.keys.a.actions[0].action: call 'missing' names no list under actions",
        "error \$.actions.one: named action list 'one' calls itself through call",
        'error \$.slots["12"]: Slot 13 is outside a 9x1 window (slots 1 to 9)',
        'error \$.mask: the mask has 2 rows; a 9x1 window has 1',
        "warning \$.mask: mask character 'b' has no key and draws nothing",
      ]));
      expect(found.where((String line) => line.contains('commandNamespaces')), hasLength(1));
    });

    test('should check shop grids, entry ids, page bounds, controls and discounts', () {
      final JsonMap shop = <String, Object?>{
        'schemaVersion': 1,
        'id': 'town:x',
        'modes': <String>['buy'],
        'defaultMode': 'sell',
        'pages': <Object?>[
          <String, Object?>{
            'rows': 2,
            'buy': <String, Object?>{
              '1': <String, Object?>{'id': 'a', 'type': 'sell', 'item': <String, Object?>{'id': 'minecraft:stone'}, 'price': 1.23456},
              '13': <String, Object?>{'id': 'a', 'type': 'buy', 'item': <String, Object?>{'id': 'minecraft:stone'}, 'price': 1},
              '30': <String, Object?>{
                'id': 'b',
                'type': 'buy',
                'item': <String, Object?>{'id': 'minecraft:stone'},
                'price': 1,
                'discount': <String, Object?>{'price': 0.5, 'start': '2026-12-27T00:00:00Z', 'end': '2026-12-20T00:00:00Z'},
              },
            },
            'trade': <String, Object?>{'0': <String, Object?>{'id': 'c', 'item': <String, Object?>{'id': 'minecraft:stone'}}},
          },
        ],
      };
      final List<String> found = <String>[for (final Diagnostic d in validateGui(DocumentKind.shop, shop)) '${d.severity.name} ${d.path}: ${d.message}'];
      expect(found, containsAll(<String>[
        "error \$.defaultMode: defaultMode 'sell' is not one of the enabled modes",
        'error \$.pages[0].buy["1"].type: a sell entry belongs in the sell grid, not buy',
        'error \$.pages[0].buy["1"].price: prices have at most 4 decimal places',
        'warning \$.pages[0].buy["13"]: slot 13 sits under the close control and is hidden while that control shows',
        "error \$.pages[0].buy[\"13\"].id: entry id 'a' is already used at \$.pages[0].buy[\"1\"]; limits and stock are kept under it",
        'error \$.pages[0].buy["30"]: Slot 31 is outside a 2-row page (slots 1 to 18)',
        'warning \$.pages[0].trade: the trade grid has entries but trade is not in modes, so players never see it',
      ]));
      expect(found, contains('error \$.pages[0].buy["30"].discount.end: the discount ends before it starts'));
    });

    test('should refuse window actions in holograms, Gloss targets, and animations in chest GUIs', () {
      final JsonMap hologram = <String, Object?>{
        'schemaVersion': 3,
        'revision': 1,
        'anchor': <String, Object?>{'world': 'w', 'position': <int>[0, 0, 0]},
        'box': <String, Object?>{'enabled': true},
        'actions': <Object?>[
          <String, Object?>{'type': 'close'},
          <String, Object?>{'type': 'navigate', 'mode': 'push', 'target': 'x'},
          <String, Object?>{'type': 'effect', 'effect': 'minecraft:speed', 'ticks': 20, 'target': 'subject'},
          <String, Object?>{'type': 'parallel', 'branches': <Object?>[<Object?>[<String, Object?>{'type': 'camera'}]]},
        ],
      };
      expect(<String>[for (final Diagnostic d in validateGui(DocumentKind.hologram, hologram)) '${d.path}: ${d.message}'], <String>[
        r'$.box.enabled: text boxes are not available in Animus',
        r"$.actions[0].type: action 'close' needs a window or named lists, which a hologram does not have",
        r'$.actions[1].mode: a hologram navigates only with mode page',
        r"$.actions[2].target: target 'subject' belongs to Gloss behaviors; Animus applies effects to the viewer",
        r"$.actions[3].branches[0][0].type: action 'camera' is not available in Animus",
      ]);
      final JsonMap chest = <String, Object?>{
        'schemaVersion': 1,
        'title': '|animation.blink|',
        'resolution': '9x1',
        'keys': <String, Object?>{
          'a': <String, Object?>{
            'type': 'button',
            'icon': <String, Object?>{'type': 'item', 'item': 'minecraft:stone'},
            'actions': <Object?>[<String, Object?>{'type': 'particle', 'particle': 'minecraft:flame', 'at': 'location'}],
          },
        },
      };
      expect(<String>[for (final Diagnostic d in validateGui(DocumentKind.inventory, chest)) '${d.path}: ${d.message}'], <String>[
        r'$.title: chest GUIs cannot show text animations (|animation.| tokens)',
        r'$.keys.a.actions[0].at: a chest window has no location; use viewer',
      ]);
    });

    test('should refuse a newer hologram version and Gloss-only hologram actions', () {
      final JsonMap doc = <String, Object?>{
        'schemaVersion': 4,
        'revision': 1,
        'anchor': <String, Object?>{'world': 'w', 'position': <int>[0, 0, 0]},
        'lines': <Object?>['{{ nope }}'],
        'actions': <Object?>[<String, Object?>{'type': 'sky'}],
      };
      expect(<String>[for (final Diagnostic d in validateGui(DocumentKind.hologram, doc)) '${d.path}: ${d.message}'], <String>[
        r'$.schemaVersion: schemaVersion 4 is not supported; Animus reads holograms of version 3',
        r"$.lines[0]: {{ nope }}: unknown name 'nope'",
        r"$.actions[0].type: action 'sky' is not available in Animus",
      ]);
    });

    test('should offer exactly the supported and Animus actions with field specs for each', () {
      final GuiActionLists lists = GuiActionLists.instance;
      expect(lists.offered, contains('animus:open-shop'));
      expect(lists.offered.toSet().intersection(lists.refused.toSet()), isEmpty);
      for (final String type in lists.offered) {
        expect(actionFields.containsKey(type), isTrue, reason: type);
        final JsonMap fresh = <String, Object?>{
          'schemaVersion': 1,
          'resolution': '9x1',
          'mask': <String>['a'],
          'actions': <String, Object?>{'main': <Object?>[]},
          'keys': <String, Object?>{
            'a': <String, Object?>{
              'type': 'button',
              'icon': <String, Object?>{'type': 'item', 'item': 'minecraft:stone'},
              'actions': <Object?>[newAction(type)],
            },
          },
        };
        final List<Diagnostic> found = <Diagnostic>[
          ...schemas.validate(DocumentKind.inventory, fresh),
          ...validateGui(DocumentKind.inventory, fresh).where((Diagnostic d) => d.severity == DiagnosticSeverity.error),
        ];
        expect(found, isEmpty, reason: '$type: $found');
      }
    });
  });

  group('layout', () {
    test('should draw mask keys, explicit slots over them and list cells over everything', () {
      final ChestLayout layout = layoutChest(readGuiFixture('gui/inventories/town/board.json'));
      expect(layout.width, 9);
      expect(layout.rows, 4);
      expect(layout.cells, hasLength(36));
      expect(layout.cell(0).source, ChestCellSource.slot);
      expect(layout.cell(0).itemId, 'minecraft:player_head');
      expect(layout.cell(0).definition, <Object>['slots', '0']);
      expect(layout.cell(1).source, ChestCellSource.mask);
      expect(layout.cell(1).definition, <Object>['keys', '#']);
      final List<ChestCell> listed = layout.cells.where((ChestCell cell) => cell.source == ChestCellSource.list).toList();
      expect(listed, hasLength(4), reason: 'the sample source has four entries');
      expect(listed.first.icon['name'], '&fBREAD');
      expect(layout.cell(31).icon['name'], '&7Standing', reason: 'the sample viewer is not sneaking');
    });

    test('should use a variant\'s replacements and edit them in the variant', () {
      final JsonMap doc = readGuiFixture('gui/inventories/town/board.json');
      expect(layoutChest(doc, variant: 0).title, '&1Town board at night');
      expect(chestFieldOwner(doc, 0, 'title'), <Object>['variants', 0, 'presentation']);
      expect(chestFieldOwner(doc, 0, 'keys'), isEmpty);
    });

    test('should lay out a shop page with its controls and entry lore', () {
      final JsonMap shop = readGuiFixture('shops/town/market.json');
      final ChestLayout buy = layoutShop(shop, page: 0, mode: 'buy');
      expect(buy.rows, 3);
      expect(buy.cell(10).definition, <Object>['pages', 0, 'buy', '10']);
      expect(buy.cell(22).source, ChestCellSource.control, reason: 'close');
      expect(buy.cell(24).source, ChestCellSource.control, reason: 'next page');
      expect(buy.cell(20).source, ChestCellSource.empty, reason: 'no previous page on the first page');
      expect(buy.cell(26).source, ChestCellSource.control, reason: 'mode switch');
      expect(asList(buy.cell(10).icon['lore']), containsAll(<String>['&7Price: &m12&r &69', '&7Limit: &f64 per player, resets every 1d']));
      final ChestLayout tools = layoutShop(shop, page: 1, mode: 'buy');
      expect(tools.rows, 2);
      expect(tools.cell(11).source, ChestCellSource.control, reason: 'previous page');
    });

    test('should render codes, hex colours and MiniMessage tags as runs', () {
      final List<McTextRun> runs = guiTextRuns('&6Gold <bold>bold</bold> &#112233hex <red>red</red> {{ viewer.name }}');
      expect(runs.map((McTextRun run) => run.text).join(), 'Gold bold hex red Steve');
      expect(runs.firstWhere((McTextRun run) => run.text == 'bold').bold, isTrue);
      expect(runs.firstWhere((McTextRun run) => run.text.startsWith('hex')).color, 0x112233);
      expect(runs.firstWhere((McTextRun run) => run.text == 'red').color, 0xFF5555);
      expect(guiPlainText('<unknown>&ax'), '<unknown>x');
    });

    test('should read colour codes and tags the way the server formats them', () {
      McTextRun only(String text) => guiTextRuns(text).single;
      List<McTextRun> runs(String text) => guiTextRuns(text);
      expect(only('<bold><red>x').bold, isFalse, reason: 'a colour code clears the styles before it');
      expect(only('<red><bold>x'), const McTextRun('x', color: 0xFF5555, bold: true));
      expect(runs('&6a<bold>b</bold>c').last, const McTextRun('c'), reason: 'a closing tag resets, then reopens the tags still open');
      expect(runs('<red>a<bold>b</bold>c').last, const McTextRun('c', color: 0xFF5555));
      expect(only('&x&f&f&0&0&0&0x').color, 0xFF0000);
      expect(only('<color:#00ff00>g').color, 0x00FF00);
      expect(only('<colour:aqua>g').color, 0x55FFFF);
      expect(only('[112233]x').color, 0x112233);
      expect(only(r'\u2764').text, '\u2764');
      expect(guiPlainText('§zq &#12345x'), '§zq &#12345x', reason: 'unknown codes and short hex stay as text');
    });

    test('should draw item names and lore italic until a colour or reset code', () {
      List<McTextRun> item(String text) => guiTextRuns(text, item: true);
      expect(item('Plain sword').single.italic, isTrue, reason: 'a custom name without a colour code is italic in game');
      expect(item('&lBold').single, const McTextRun('Bold', bold: true, italic: true));
      expect(item('&6Gold').single.italic, isFalse, reason: 'a leading colour code makes it upright');
      expect(item('&rReset').single.italic, isFalse);
      expect(item('Lead &6gold').map((McTextRun run) => run.italic).toList(), <bool>[true, false]);
      expect(item('&6Gold &oitalic').last.italic, isTrue);
      expect(guiTextRuns('Plain').single.italic, isFalse, reason: 'chat and titles are upright');
    });
  });

  group('Gloss bundles', () {
    test('should import adopted kinds with Animus ids and skip the rest by name', () {
      final GlossImport imported = readGlossBundle(File('test/fixtures/gloss_bundle/gloss-workspace.json').readAsStringSync());
      expect(imported.error, isNull);
      expect(<String>[for (final GlossImportedDocument d in imported.documents) '${d.location} ${d.path}'], <String>[
        'inventory:gloss:chest gui/inventories/gloss/chest.json',
        'menu:gloss:lobby/main gui/menus/gloss/lobby/main.json',
        'hologram:gloss:spawn gui/holograms/gloss/spawn.json',
      ]);
      expect(imported.skipped, <String>['Sidebar: Gloss scoreboard documents have no Animus equivalent']);
      final AnimusDocument hologram = validateSource(imported.documents[2].location, imported.documents[2].path, imported.documents[2].source, schemas);
      expect(errors(hologram).map((Diagnostic d) => d.path), <String>[r'$.actions[0].type']);
    });

    test('should refuse files that are not bundles of the supported versions', () {
      expect(readGlossBundle('nope').error, 'That file is not valid JSON.');
      expect(readGlossBundle('{"format": "x"}').error, contains('not a Gloss editor workspace bundle'));
      expect(readGlossBundle('{"format": "gloss-editor-workspace", "version": 1, "workspace": {"schemaVersion": 3}}').error,
          contains('schema 3 is not supported'));
    });

    test('should export adopted documents, skip animus actions, and import back to the same documents', () {
      final DataFolderResult folder = openDataFolder(guiFixture(), schemas);
      int counter = 0;
      final GlossExport exported = writeGlossBundle(
        folder.documents,
        newId: () => '00000000-0000-4000-8000-${(counter++).toString().padLeft(12, '0')}',
        now: 0,
      );
      expect(exported.skipped, <String>['Chest GUIs: town:board uses animus: actions, which Gloss cannot run']);
      expect(exported.exported, 5, reason: 'gloss-only, hub, welcome, sparkle and presets; the shop is not a Gloss kind');
      final JsonMap bundle = jsonDecode(exported.bundle) as JsonMap;
      expect(asList(asMap(bundle['workspace'])['documents']).map((Object? d) => asMap(d)['runtimeId']).toSet(), <String>{'town.hub', 'town.welcome', 'town.gloss-only', 'town.sparkle', 'presets'});
      final GlossImport back = readGlossBundle(exported.bundle);
      // The Animus id rides in the Gloss title, so ids come back unchanged
      // even though town:welcome leaves as town.welcome.
      expect(back.notes, isEmpty);
      expect(back.documents, hasLength(5));
      for (final GlossImportedDocument document in back.documents) {
        final AnimusDocument original = folder.documents.firstWhere((AnimusDocument d) => d.kind == document.location.kind && d.id == document.location.id);
        expect(document.path, original.path);
        expect(document.source, original.source);
      }
    });

    test('should map ids as GlossIds and take a title back only when it maps to the Gloss id', () {
      expect(glossIdForAnimus(DocumentKind.inventory, 'town:menu'), 'town.menu');
      expect(animusIdForGloss(DocumentKind.inventory, 'town.menu'), 'gloss:town-menu');
      expect(animusIdFromBundle(DocumentKind.inventory, 'town.menu', 'town:menu'), 'town:menu');
      expect(animusIdFromBundle(DocumentKind.inventory, 'town.menu', 'town:other'), 'gloss:town-menu');
      expect(animusIdFromBundle(DocumentKind.menu, 'lobby.shops/food', 'lobby:shops/food'), 'lobby:shops/food');
      expect(animusIdFromBundle(DocumentKind.hologram, 'spawn', 'Spawn sign'), 'gloss:spawn');
    });

    test('should note folders, images and titles it does not keep, and list conflicts', () {
      final Map<String, Object?> bundle = jsonDecode(File('test/fixtures/gloss_bundle/gloss-workspace.json').readAsStringSync()) as Map<String, Object?>;
      final GlossImport imported = readGlossBundle(jsonEncode(bundle));
      expect(imported.notes, contains(startsWith('Folders (Lobby) are not kept')));
      expect(imported.notes, contains(startsWith('1 image is not imported')));
      expect(imported.notes, contains('Lobby menu: the Gloss title is not kept; the document is gloss:lobby/main.'));
      final AnimusDocument existing = validateSource(imported.documents.first.location, imported.documents.first.path, imported.documents.first.source, schemas);
      expect(glossConflicts(imported, <AnimusDocument>[existing]), <DocumentLocation>[imported.documents.first.location]);
      expect(glossConflicts(imported, const <AnimusDocument>[]), isEmpty);
    });
  });

  group('export and editing', () {
    test('should give every new document a path that locates back to it and passes validation', () {
      for (final DocumentKind kind in guiKinds) {
        final String id = kind == DocumentKind.presets ? 'presets' : 'example:${kind.wireName}';
        final String path = documentPath(kind, id);
        expect(locateDocument(path), DocumentLocation(kind, id), reason: path);
        final AnimusDocument document = validateSource(DocumentLocation(kind, id), path, prettyJson(newDocument(kind, id)), schemas);
        expect(errors(document), isEmpty, reason: '$kind ${document.diagnostics}');
      }
      expect(locateDocument(documentPath(DocumentKind.inventory, 'no-namespace')), isNull);
    });

    test('should export a zip that opens back to the same documents', () {
      final DataFolderResult folder = openDataFolder(guiFixture(), schemas);
      final WorkspaceSnapshot snapshot = WorkspaceSnapshot(id: 'ws', name: 'gui', generation: 1, documents: folder.documents);
      final Uint8List zip = zipFiles(workspaceFiles(snapshot));
      final DataFolderResult reopened = openDataFolder(filesFromZip(zip), schemas);
      expect(reopened.documents.map((AnimusDocument d) => '${d.path}|${d.source}'), folder.documents.map((AnimusDocument d) => '${d.path}|${d.source}'));
    });

    test('should round-trip a JSON edit with one undo step per coalesced burst', () async {
      final EditorStore store = EditorStore(repository: MemoryWorkspaceRepository(), schemas: schemas, newId: () => 'ws');
      await store.openFiles('gui', guiFixture());
      final AnimusDocument board = store.document(DocumentKind.inventory, 'town:board')!;
      Object? json = board.json;
      for (final String title in <String>['&8A', '&8Ab', '&8Abc']) {
        json = writeIn(json, <JsonStep>['title'], title);
        await store.commit(documents: <DocumentChange>[DocumentChange(DocumentKind.inventory, 'town:board', prettyJson(json))], coalesce: 'board:title');
      }
      final AnimusDocument edited = store.document(DocumentKind.inventory, 'town:board')!;
      expect(asMap(jsonDecode(edited.source))['title'], '&8Abc');
      expect(asMap(edited.json)['list'], asMap(board.json)['list'], reason: 'untouched fields survive');
      expect(edited.valid, isTrue);
      await store.undo();
      expect(store.document(DocumentKind.inventory, 'town:board')!.source, board.source);
      expect(store.canUndo, isFalse);
    });

    test('should add bundle documents as one undo step and replace same-id ones', () async {
      final EditorStore store = EditorStore(repository: MemoryWorkspaceRepository(), schemas: schemas, newId: () => 'ws');
      await store.openFiles('gui', guiFixture());
      final int before = store.workspace!.documents.length;
      final GlossImport imported = readGlossBundle(File('test/fixtures/gloss_bundle/gloss-workspace.json').readAsStringSync());
      await store.commit(documents: <DocumentChange>[
        for (final GlossImportedDocument d in imported.documents) DocumentChange(d.location.kind, d.location.id, d.source),
      ]);
      expect(store.workspace!.documents.length, before + 3);
      expect(store.document(DocumentKind.menu, 'gloss:lobby/main')!.valid, isTrue);
      await store.undo();
      expect(store.workspace!.documents.length, before);
      await store.commit(documents: const <DocumentChange>[DocumentChange(DocumentKind.shop, 'town:market', null)]);
      expect(store.document(DocumentKind.shop, 'town:market'), isNull);
    });
  });

  group('owned shops', () {
    test('should keep owner and containers untouched when the shop editor writes a field', () {
      final JsonMap shop = asMap(readFixture('shopkeepers/expected/player/shops/shopkeepers/shop-10.json'));
      expect(shop['owner'], isNotNull);
      expect(shop['containers'], isNotNull);
      final JsonMap edited = asMap(writeIn(shop, <JsonStep>['title'], 'Renamed'));
      expect(edited['title'], 'Renamed');
      expect(jsonEncode(edited['owner']), jsonEncode(shop['owner']));
      expect(jsonEncode(edited['containers']), jsonEncode(shop['containers']));
      expect(<String>[for (final Diagnostic d in validateGui(DocumentKind.shop, edited)) d.path].where((String path) => path.contains('owner') || path.contains('containers')), isEmpty);
    });
  });
}
