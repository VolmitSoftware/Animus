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
}
