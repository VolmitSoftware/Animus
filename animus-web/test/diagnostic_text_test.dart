import 'package:animus_editor/logic/diagnostic_text.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:test/test.dart';

void main() {
  Diagnostic error(String path, String message) => Diagnostic(DiagnosticSeverity.error, path, message);

  test('should name the field by its label instead of its JSON path', () {
    expect(diagnosticSentence(error(r'$.uuid', 'is required')), 'UUID is required');
    expect(diagnosticSentence(error(r'$.modules.animus:navigation.stepHeight', 'expected at most 1.5')), 'Step height: expected at most 1.5');
    expect(diagnosticSentence(error(r'$.lines[2]', 'expected a string')), 'Lines item 3: expected a string');
    expect(diagnosticSentence(error(r'$', 'Not a JSON object.')), 'Not a JSON object.');
    expect(diagnosticSentence(error(r'$.owner', 'Expected a lowercase UUID.')), 'Owner: Expected a lowercase UUID.');
  });

  test('should show a server diagnostic only when the browser does not already say it', () {
    final List<Diagnostic> local = <Diagnostic>[error(r'$.uuid', 'is required')];
    final List<Diagnostic> server = <Diagnostic>[error(r'$.uuid', 'is required'), error(r'$.name', 'is too long')];
    expect(serverOnly(local, server).map((Diagnostic d) => d.path), <String>[r'$.name']);
    expect(withoutRepeats(<Diagnostic>[...local, ...server]).length, 2);
  });

  test('should say a repeated problem once with how many items have it', () {
    final List<Diagnostic> grouped = groupRepeats(<Diagnostic>[
      for (final int index in <int>[2, 5, 9, 11, 14]) error('\$.events[$index].item', 'is required'),
      error(r'$.world', 'is required'),
    ]);
    expect(grouped.length, 2);
    expect(diagnosticSentence(grouped.first), 'Item is required (5 events)');
    expect(grouped.first.path, r'$.events[2].item');
    expect(diagnosticSentence(grouped.last), 'World is required');
    expect(groupRepeats(<Diagnostic>[error(r'$.lines[0]', 'expected a string')]).single.message, 'expected a string');
  });

  test('should keep the config instruction with its ids and keys for Advanced only', () {
    const Diagnostic note = Diagnostic(
      DiagnosticSeverity.warning,
      r'$.pages[0].buy["10"].commands',
      'runs commands, so the server refuses this shop until config.toml allows it: add "town" to commandNamespaces, or "town:market" to commandBehaviors, under [behaviors.actions]',
      code: 'command-allow-list',
    );
    expect(diagnosticSentenceIn(note, advanced: true), diagnosticSentence(note));
    expect(diagnosticSentenceIn(note, advanced: false), 'Commands: runs commands, so the server refuses this shop until config.toml allows it. Edit in Advanced to see how.');
    expect(diagnosticSentenceIn(error(r'$.name', 'is required'), advanced: false), 'Name is required');
  });

  test('should call a document valid unless problems show or wait to show', () {
    const Diagnostic noSchema = Diagnostic(DiagnosticSeverity.info, r'$', 'No schema for this kind.', code: 'no-schema');
    final Diagnostic missing = error(r'$.name', 'is required');
    expect(documentCheck(all: const <Diagnostic>[noSchema], shown: const <Diagnostic>[noSchema], problems: const <Diagnostic>[]), DocumentCheck.valid);
    expect(documentCheck(all: <Diagnostic>[missing], shown: const <Diagnostic>[], problems: const <Diagnostic>[]), DocumentCheck.checking);
    expect(documentCheck(all: <Diagnostic>[missing], shown: <Diagnostic>[missing], problems: <Diagnostic>[missing]), DocumentCheck.problems);
  });
}
