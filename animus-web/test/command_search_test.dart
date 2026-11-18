import 'package:animus_editor/logic/command_search.dart';
import 'package:animus_editor/logic/palette_commands.dart';
import 'package:test/test.dart';

void main() {
  void none() {}

  final List<PaletteCommand> commands = <PaletteCommand>[
    PaletteCommand('Edit', 'Undo rename town:baker', none, hint: 'Ctrl+Z', keywords: const <String>['undo']),
    PaletteCommand('Documents', 'Baker', none, hint: 'NPCs, town/baker', keywords: const <String>['town/baker', 'npc', 'open']),
    PaletteCommand('Documents', 'market-loop', none, hint: 'Routes', keywords: const <String>['market-loop', 'route', 'open']),
    PaletteCommand('App', 'Use light theme', none, keywords: const <String>['theme', 'dark', 'light']),
  ];

  List<String> labels(String query) => <String>[for (final PaletteCommand command in filterCommands(commands, query)) command.label];

  test('should list every command for an empty or blank query', () {
    expect(labels(''), hasLength(4));
    expect(labels('   '), hasLength(4));
  });

  test('should match the label, hint and keywords without case', () {
    expect(labels('BAKER'), <String>['Baker', 'Undo rename town:baker']);
    expect(labels('routes'), <String>['market-loop']);
    expect(labels('dark'), <String>['Use light theme']);
  });

  test('should need every word of the query, in any field', () {
    expect(labels('open npc'), <String>['Baker']);
    expect(labels('open nothing'), isEmpty);
  });

  test('should rank an exact id first, then ids that start with it, then the rest', () {
    final List<PaletteCommand> npcs = <PaletteCommand>[
      for (final String id in <String>['town/asiantown5', 'world/citizens-600', 'harbor/old-citizens-60', 'world/citizens-60', 'world/citizens-61'])
        PaletteCommand('Documents', id.contains('asian') ? 'Asian town 5' : id.split('/').last, none, hint: 'NPCs', keywords: <String>[id, 'npc']),
      PaletteCommand('Documents', 'Mentions citizens-60 in its name', none, keywords: const <String>['town/talker', 'npc']),
    ];
    final List<String> found = <String>[for (final PaletteCommand command in filterCommands(npcs, 'citizens-60')) command.label];
    expect(found.first, 'citizens-60');
    expect(found.indexOf('citizens-600'), lessThan(found.indexOf('old-citizens-60')));
    expect(found, isNot(contains('Asian town 5')));
    expect(found, hasLength(4));
  });

  group('files then commands', () {
    final List<PaletteCommand> mixed = <PaletteCommand>[
      PaletteCommand('Server', 'Pair a server', none, keywords: const <String>['pair', 'link']),
      PaletteCommand('Library', 'Pair of boots seller', none, hint: 'NPC', file: true),
      PaletteCommand('Library', 'Bakery', none, hint: 'Shop', keywords: const <String>['shop:bakery'], content: const <String>['Bread', 'Emerald'], file: true),
      PaletteCommand('Library', 'Bread stall', none, hint: 'Shop', file: true),
    ];

    test('should list files before commands, whatever ranks better', () {
      expect(<String>[for (final PaletteCommand command in filterCommands(mixed, 'pair')) command.label], <String>['Pair of boots seller', 'Pair a server']);
    });

    test('should find a file by an item it uses, after files named like it', () {
      expect(<String>[for (final PaletteCommand command in filterCommands(mixed, 'bread')) command.label], <String>['Bread stall', 'Bakery']);
      expect(contentMatch(mixed[2], 'bread'), 'Bread');
      expect(contentMatch(mixed[2], 'bakery'), isNull, reason: 'the title matched, no item to name');
      expect(contentMatch(mixed[3], 'bread'), isNull);
    });
  });

  group('itemMentions', () {
    test('should collect the items a file uses, by id, once each', () {
      final Map<String, Object?> shop = <String, Object?>{
        'id': 'shop:bakery',
        'pages': <Object?>[
          <String, Object?>{
            'name': 'Bread',
            'trade': <String, Object?>{
              '11': <String, Object?>{
                'id': 'bread-for-emerald',
                'item': <String, Object?>{'id': 'minecraft:bread', 'count': 4},
                'needs': <Object?>[<String, Object?>{'item': <String, Object?>{'id': 'minecraft:emerald'}, 'amount': 1}],
                'gives': <Object?>[<String, Object?>{'item': <String, Object?>{'id': 'bread'}, 'amount': 4}],
              },
            },
          },
        ],
      };
      expect(itemMentions(shop), <String>['minecraft:bread', 'minecraft:emerald']);
    });

    test('should read equipment slots, icons and plain item strings, and nothing else', () {
      final Map<String, Object?> npc = <String, Object?>{
        'id': 'baker',
        'type': 'minecraft:player',
        'equipment': <String, Object?>{'main_hand': <String, Object?>{'id': 'minecraft:golden_apple'}},
        'icon': <String, Object?>{'id': 'minecraft:cake'},
        'action': <String, Object?>{'item': 'minecraft:cookie', 'sound': 'minecraft:entity.villager.yes'},
      };
      expect(itemMentions(npc), <String>['minecraft:golden_apple', 'minecraft:cake', 'minecraft:cookie']);
      expect(itemMentions(null), isEmpty);
      expect(itemMentions('minecraft:bread'), isEmpty, reason: 'only values under an item key count');
    });

    test('should name items for the search index', () {
      expect(itemSearchTerms(<String>['minecraft:golden_apple', 'farmers:pie']), <String>['Golden apple', 'Pie']);
    });
  });

  group('actionCommands', () {
    void run() {}
    PaletteRuns runs({bool server = false, bool workspace = true}) => PaletteRuns(
          pair: run,
          home: run,
          mode: run,
          shortcuts: run,
          theme: run,
          openFolder: run,
          openZip: run,
          importer: run,
          undo: workspace ? run : null,
          redo: workspace ? run : null,
          newFile: workspace ? run : null,
          exportZip: workspace ? run : null,
          apply: server ? run : null,
          review: server ? run : null,
          send: workspace && !server ? run : null,
        );
    String keys(String keys) => keys;

    List<String> find(String query, {bool server = false, bool workspace = true, bool advanced = false, bool dark = true, String? undoLabel}) => <String>[
          for (final PaletteCommand command in filterCommands(
            actionCommands(runs(server: server, workspace: workspace), advanced: advanced, dark: dark, undoLabel: undoLabel, keys: keys, importLabel: 'Import'),
            query,
          ))
            command.label,
        ];

    test('should find Pair a server inside and outside a workspace', () {
      expect(find('pair'), contains('Pair a server'));
      expect(find('pair', workspace: false), contains('Pair a server'));
    });

    test('should always offer Undo and Redo in a workspace, named by what they revert', () {
      expect(find('undo'), <String>['Undo']);
      expect(find('undo', undoLabel: 'rename Baker'), <String>['Undo: rename Baker']);
      expect(find('redo'), <String>['Redo']);
      expect(find('undo', workspace: false), isEmpty);
    });

    test('should find Apply and Review on a server, and Send to server in a local workspace', () {
      expect(find('apply', server: true), containsAll(<String>['Apply changes', 'Review changes']));
      expect(find('review', server: true).first, 'Review changes');
      expect(find('apply'), <String>['Send to server']);
      expect(find('upload'), <String>['Send to server']);
      expect(find('send', server: true), isNot(contains('Send to server')));
    });

    test('should find the settings by the words people use', () {
      expect(find('theme'), <String>['Light theme']);
      expect(find('light'), <String>['Light theme']);
      expect(find('dark', dark: false), <String>['Dark theme']);
      expect(find('advanced'), <String>['Switch to Advanced']);
      expect(find('simple', advanced: true), <String>['Switch to Simple']);
      expect(find('settings'), containsAll(<String>['Light theme', 'Switch to Advanced', 'Keyboard shortcuts']));
      expect(find('new file'), <String>['New file']);
    });
  });
}
