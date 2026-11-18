import 'dart:convert';

import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/gui/gui_json.dart';
import 'package:animus_editor/logic/inspect/behavior_rules.dart';
import 'package:animus_editor/logic/inspect/inspect_schemas.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

/// The editor's whole check of a behavior, as the badge and Apply see it.
List<Diagnostic> _errors(String id, Object? json) => validateSource(DocumentLocation(DocumentKind.behavior, id), 'behaviors/$id.json', jsonEncode(json), SharedSchemas.embedded)
    .diagnostics
    .where((Diagnostic diagnostic) => diagnostic.severity == DiagnosticSeverity.error)
    .toList();

JsonMap _behavior(Object? main, {JsonMap? variables, List<Object?>? triggers, JsonMap? routines}) => <String, Object?>{
      'schemaVersion': 1,
      'revision': 1,
      'id': 'rules:sample',
      'entry': 'main',
      'variables': ?variables,
      'routines': <String, Object?>{'main': main, ...?routines},
      'triggers': ?triggers,
    };

void main() {
  test('should refuse exactly what the server refuses, at the field the server names', () {
    int checked = 0;
    for (final Object? raw in asList(asMap(fixtureIndex())['documents'])) {
      final JsonMap entry = asMap(raw);
      final JsonMap wanted = asMap(entry['expect']);
      if (entry['kind'] != 'behavior' || wanted['java'] is! bool) continue;
      final List<Diagnostic> errors = _errors(entry['id']! as String, readFixture(entry['path']! as String));
      expect(errors.isEmpty, wanted['java'], reason: '${entry['path']}: ${errors.map((Diagnostic d) => '${d.path} ${d.message}')}');
      final String? field = asString(wanted['field']);
      if (field != null) {
        expect(errors.map((Diagnostic d) => d.path), contains(field), reason: '${entry['path']}: ${errors.map((Diagnostic d) => '${d.path} ${d.message}')}');
      }
      checked++;
    }
    expect(checked, greaterThanOrEqualTo(45));
  });

  test('should word the loop and trigger rules plainly, as errors at the step', () {
    final List<Diagnostic> stray = behaviorRuleDiagnostics(_behavior(<String, Object?>{'type': 'break'}), catalog: InspectSchemas.embedded.catalog);
    expect(stray.single.path, r'$.routines.main');
    expect(stray.single.severity, DiagnosticSeverity.error);
    expect(stray.single.message, 'Leave loop works only inside a repeat, a for each or a repeat while of this routine.');
    final List<Diagnostic> twice = behaviorRuleDiagnostics(
      _behavior(<String, Object?>{'type': 'wait', 'ticks': 1}, triggers: <Object?>[
        <String, Object?>{'id': 'click', 'event': 'interaction'},
        <String, Object?>{'id': 'click', 'event': 'spawn'},
      ]),
      catalog: InspectSchemas.embedded.catalog,
    );
    expect(twice.single.path, r'$.triggers[1].id');
    expect(twice.single.message, 'Another trigger already has the id click.');
  });

  test('should refuse a condition that cannot give true or false, though the server compiles it', () {
    final List<Diagnostic> errors = _errors('rules:sample', _behavior(
      <String, Object?>{'type': 'condition', 'expression': 'deliveries + 1', 'then': <String, Object?>{'type': 'wait', 'ticks': 1}},
      variables: <String, Object?>{'deliveries': <String, Object?>{'type': 'integer', 'initial': 0}},
    ));
    expect(errors.single.path, r'$.routines.main.expression');
    expect(errors.single.message, 'deliveries + 1 gives a number, not true or false.');
  });

  test('should warn when a sensed trigger reaches past the default entity radius', () {
    List<Diagnostic> radius(String event, num value) => behaviorRuleDiagnostics(
          _behavior(<String, Object?>{'type': 'wait', 'ticks': 1}, triggers: <Object?>[
            <String, Object?>{'id': 'near', 'event': event, 'phase': 'enter', 'radius': value},
          ]),
          catalog: InspectSchemas.embedded.catalog,
        );
    expect(radius('entity_proximity', 20).single.severity, DiagnosticSeverity.warning);
    expect(radius('entity_proximity', 20).single.path, r'$.triggers[0].radius');
    expect(radius('entity_proximity', 20).single.message, 'Above 16 blocks the server leaves this trigger off unless maximumEntityRadius in config.toml is raised; it goes up to 32.');
    expect(radius('entity_proximity', 16), isEmpty);
    expect(radius('proximity', 40), isEmpty, reason: 'player proximity is not sensed');
  });
}
