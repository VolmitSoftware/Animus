import 'package:animus_editor/logic/gui/gui_text.dart';
import 'package:animus_editor/mc/npc/mc_text.dart';
import 'package:animus_editor/mc/npc/npc_look.dart';
import 'package:animus_editor/mc/npc/npc_playground.dart';
import 'package:animus_editor/mc/npc/npc_scene.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

/// A per-letter `&#RRGGBB` gradient, as Gloss and gradient generators write
/// NPC names; the first letters are the owner's own name.
const String gradientName = '&#1d976cT&#259e72h&#2ea577e &#36ac7cC&#3fb382a&#47ba87r&#50c18dt&#58c892o&#61cf97g'
    '&#69d69dr&#72dda2a&#7ae4a8p&#83ebadh&#8bf2b3e&#93f9b8r';

const List<int> gradientColours = <int>[
  0x1d976c, 0x259e72, 0x2ea577, 0x2ea577, 0x36ac7c, 0x3fb382, 0x47ba87, 0x50c18d, 0x58c892, 0x61cf97, //
  0x69d69d, 0x72dda2, 0x7ae4a8, 0x83ebad, 0x8bf2b3, 0x93f9b8,
];

List<McTextRun> lineRuns(String sectionCoded) => guiSectionRuns(sectionCoded);

/// A default colour no code names, so a run left in the display's default
/// colour reads apart from one coloured white.
const int _unset = -1;

Map<String, Object?> _runJson(McTextRun run) => <String, Object?>{
      'text': run.text,
      'color': run.color == _unset ? null : '#${run.color.toRadixString(16).padLeft(6, '0')}',
      'bold': run.bold,
      'italic': run.italic,
      'underline': run.underlined,
      'strikethrough': run.strikethrough,
      'obfuscated': run.obfuscated,
    };

const int _red = 0xFF5555;

void main() {
  group('shared text formatter', () {
    test('should colour every letter of a per-letter hex gradient name', () {
      final List<McTextRun> runs = guiTextRuns(gradientName);
      expect(runs.map((McTextRun run) => run.text).join(), 'The Cartographer');
      final List<int> perLetter = <int>[
        for (final McTextRun run in runs)
          for (int i = 0; i < run.text.length; i++) run.color,
      ];
      expect(perLetter, gradientColours);
      expect(guiPlainText(gradientName), 'The Cartographer');
    });

    test('should read the &x and section-sign hex forms', () {
      expect(guiTextRuns('&x&1&d&9&7&6&cA').single, const McTextRun('A', color: 0x1d976c));
      expect(guiTextRuns('§x§1§d§9§7§6§cA').single, const McTextRun('A', color: 0x1d976c));
    });

    test('should read legacy section and ampersand codes as the client does', () {
      const List<McTextRun> expected = <McTextRun>[
        McTextRun('Quest ', color: 0xFFAA00, bold: true),
        McTextRun('Giver', color: 0xAAAAAA),
        McTextRun('!'),
      ];
      expect(guiTextRuns('§6§lQuest §7Giver§r!'), expected);
      expect(guiTextRuns('&6&lQuest &7Giver&r!'), expected);
      expect(guiPlainText('Charts & Clocks'), 'Charts & Clocks', reason: 'a lone ampersand is text');
    });

    test('should spread a MiniMessage gradient over the letters', () {
      expect(guiTextRuns('<gradient:#ff0000:#0000ff>abc</gradient>'), const <McTextRun>[
        McTextRun('a', color: 0xff0000),
        McTextRun('b', color: 0x800080),
        McTextRun('c', color: 0x0000ff),
      ]);
      expect(guiTextRuns('<gradient:red:blue>ab</gradient>').map((McTextRun run) => run.color), <int>[0xFF5555, 0x5555FF]);
      final List<McTextRun> styled = guiTextRuns('<gradient:#000000:#ffffff><bold>ab</bold></gradient> after');
      expect(styled.take(2).every((McTextRun run) => run.bold), isTrue, reason: 'decorations inside the gradient stay on each letter');
      expect(styled.last, const McTextRun(' after'), reason: 'the gradient ends at its closing tag');
      expect(guiPlainText('<gradient:#ff0000:#0000ff>Charts & Clocks</gradient>'), 'Charts & Clocks');
      expect(guiPlainText('<gradient:#ff0000>x</gradient>'), '<gradient:#ff0000>x</gradient>', reason: 'one colour is no gradient');
    });
  });

  group('display rows', () {
    test('should draw every shared vector as the client draws legacy codes', () {
      final Map<String, Object?> fixture = readFixture(fixtureIndex()['textRows']! as String)! as Map<String, Object?>;
      final List<Object?> vectors = fixture['vectors']! as List<Object?>;
      expect(vectors, hasLength(greaterThanOrEqualTo(10)));
      for (final Object? raw in vectors) {
        final Map<String, Object?> vector = raw! as Map<String, Object?>;
        final List<String> lines = <String>[for (final Object? line in vector['lines']! as List<Object?>) line! as String];
        final List<List<List<McTextRun>>> rows = guiDisplayRows(lines, separate: vector['separate']! as bool, sample: false, defaultColor: _unset);
        expect(rows, hasLength(lines.length), reason: '${vector['name']}: one entry per line');
        expect(
          <Object?>[for (final List<List<McTextRun>> line in rows) for (final List<McTextRun> row in line) <Object?>[for (final McTextRun run in row) _runJson(run)]],
          vector['rows'],
          reason: vector['name']! as String,
        );
      }
    });

    test('should give each line its own rows, a newline making another', () {
      final List<List<List<McTextRun>>> rows = guiDisplayRows(<String>['&cA\nB', 'C']);
      expect(rows.map((List<List<McTextRun>> line) => line.length), <int>[2, 1]);
      expect(rows.last.single.single, const McTextRun('C', color: _red));
      expect(guiDisplayRows(const <String>[]), isEmpty);
    });
  });

  group('NPC text on the stage', () {
    test('should colour a gradient nameplate instead of showing its codes', () {
      final NpcLook look = NpcLook.fromDefinition(<String, Object?>{'type': 'minecraft:player', 'name': gradientName});
      expect(look.nativeName, isFalse);
      final List<McTextRun> runs = lineRuns(look.textLines.single);
      expect(runs.map((McTextRun run) => run.text).join(), 'The Cartographer');
      expect(runs.first.color, 0x1d976c);
      expect(runs.last.color, 0x93f9b8);
    });

    test('should treat any colour notation as formatted, so the client nameplate is not used', () {
      for (final String name in <String>['&#123456Bob', '<red>Bob', '[123456]Bob', '&x&1&2&3&4&5&6B']) {
        expect(NpcLook.fromDefinition(<String, Object?>{'type': 'minecraft:player', 'name': name}).nativeName, isFalse, reason: name);
      }
      expect(NpcLook.fromDefinition(<String, Object?>{'type': 'minecraft:player', 'name': 'Bob & Al'}).nativeName, isTrue);
    });

    test('should colour hologram lines and prefix the team colour, as the server does', () {
      final NpcLook look = NpcLook.fromDefinition(<String, Object?>{
        'type': 'minecraft:zombie',
        'name': 'Guard',
        'teamColor': 'red',
        'hologram': <String, Object?>{'lines': <String>['<gold>Shop']},
      });
      expect(lineRuns(look.textLines.first).single, const McTextRun('Shop', color: 0xFFAA00));
      expect(lineRuns(look.textLines.last).single, const McTextRun('Guard', color: 0xFF5555));
    });

    test('should carry styles down the lines of one display and start the name fresh', () {
      final NpcLook look = NpcLook.fromDefinition(<String, Object?>{
        'type': 'minecraft:zombie',
        'name': 'Guard',
        'hologram': <String, Object?>{'lines': <Object?>['&cA&lB', 'C']},
      });
      expect(look.separateRows, isFalse);
      expect(look.textRows, const <List<McTextRun>>[
        <McTextRun>[McTextRun('A', color: _red), McTextRun('B', color: _red, bold: true)],
        <McTextRun>[McTextRun('C', color: _red, bold: true)],
        <McTextRun>[McTextRun('Guard')],
      ]);
      final NpcText text = npcScene(look).texts.single;
      expect(text.styled, look.textRows, reason: 'the stage draws the rows the look reads');
    });

    test('should start every row fresh when each line has its own display', () {
      for (final Map<String, Object?> hologram in <Map<String, Object?>>[
        <String, Object?>{'lineHeight': 0.3, 'lines': <Object?>['&cA&lB', 'C']},
        <String, Object?>{'lines': <Object?>['&cA&lB', <String, Object?>{'text': 'C', 'marginTop': 0.1}]},
      ]) {
        final NpcLook look = NpcLook.fromDefinition(<String, Object?>{'type': 'minecraft:zombie', 'name': 'Guard', 'hologram': hologram});
        expect(look.separateRows, isTrue, reason: '$hologram');
        expect(look.hologram, <String>['&cA&lB', 'C'], reason: 'a line object shows its text');
        expect(look.textRows, const <List<McTextRun>>[
          <McTextRun>[McTextRun('A', color: _red), McTextRun('B', color: _red, bold: true)],
          <McTextRun>[McTextRun('C')],
          <McTextRun>[McTextRun('Guard')],
        ], reason: '$hologram');
      }
      expect(NpcLook.separateRowsOf(<String, Object?>{'lines': <Object?>[<String, Object?>{'text': 'C', 'marginTop': 0}]}), isFalse, reason: 'a zero margin keeps one display');
    });

    test('should colour speech on the stage', () {
      final NpcLook look = NpcLook.fromDefinition(<String, Object?>{'type': 'minecraft:zombie', 'name': 'Guard'});
      final NpcScene scene = npcPlaygroundScene(look, speech: '&#ff0000Halt');
      final NpcText said = scene.texts.last;
      expect(lineRuns(said.lines.single).single, const McTextRun('Halt', color: 0xff0000));
    });
  });
}
