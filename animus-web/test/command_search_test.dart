import 'package:animus_editor/logic/command_search.dart';
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
    expect(labels('BAKER'), <String>['Undo rename town:baker', 'Baker']);
    expect(labels('routes'), <String>['market-loop']);
    expect(labels('dark'), <String>['Use light theme']);
  });

  test('should need every word of the query, in any field', () {
    expect(labels('open npc'), <String>['Baker']);
    expect(labels('open nothing'), isEmpty);
  });
}
