import 'dart:convert';
import 'dart:io';

import 'package:animus_editor/config/shared_schemas.g.dart';
import 'package:animus_editor/logic/data_folder.dart';
import 'package:animus_editor/logic/json_schema.dart';
import 'package:animus_editor/logic/shared_schemas.dart';
import 'package:animus_editor/model/animus_document.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

import '../tool/embed_schemas.dart';
import 'fixtures.dart';

void main() {
  final SharedSchemas schemas = SharedSchemas.embedded;

  test('should embed the current ../schema files', () {
    expect(renderSharedSchemas(readSharedSchemas(schemaDirectory)), File(generatedPath).readAsStringSync(),
        reason: 'run dart run tool/embed_schemas.dart');
    expect(sharedSchemaSources.keys, containsAll(<String>['generated/npc.schema.json', 'editor-v1.schema.json', 'gloss/source.json']));
  });

  test('should validate every document fixture as the index expects', () {
    final List<Object?> documents = fixtureIndex()['documents']! as List<Object?>;
    expect(documents, hasLength(greaterThanOrEqualTo(20)));
    for (final Object? raw in documents) {
      final Map<String, Object?> entry = raw! as Map<String, Object?>;
      final DocumentKind kind = DocumentKind.parse(entry['kind']! as String)!;
      final bool expected = (entry['expect']! as Map<String, Object?>)['schema']! as bool;
      final List<Diagnostic> diagnostics = schemas.validate(kind, readFixture(entry['path']! as String));
      final List<Diagnostic> errors = diagnostics.where((Diagnostic d) => d.severity == DiagnosticSeverity.error).toList();
      expect(errors.isEmpty, expected, reason: '${entry['path']}: $errors');
    }
  });

  test('should validate every editor message fixture against its definition', () {
    final List<Object?> messages = fixtureIndex()['messages']! as List<Object?>;
    expect(messages, hasLength(greaterThanOrEqualTo(30)));
    for (final Object? raw in messages) {
      final Map<String, Object?> entry = raw! as Map<String, Object?>;
      final List<SchemaError> errors = schemas.validateMessage(entry['definition']! as String, readFixture(entry['path']! as String));
      expect(errors.isEmpty, entry['valid'], reason: '${entry['path']}: $errors');
    }
  });

  test('should decode every valid pairing code into a schema-valid payload', () {
    final Map<String, Object?> pairing = readFixture('editor/pairing.json')! as Map<String, Object?>;
    for (final Object? raw in pairing['valid']! as List<Object?>) {
      final Map<String, Object?> entry = raw! as Map<String, Object?>;
      final String code = entry['code']! as String;
      expect(code, startsWith('ANM1.'));
      final String body = code.substring(5);
      final Object? payload = jsonDecode(utf8.decode(base64Url.decode(base64Url.normalize(body))));
      expect(payload, entry['payload']);
      expect(schemas.validateMessage('pairing-payload', payload), isEmpty);
    }
  });

  test('should name only existing definitions and kinds in the endpoint table', () {
    final Map<String, Object?> endpoints = schemas.endpoints;
    final Map<String, Object?> definitions = (schemas.registry.schema(SharedSchemas.editorSchema)! as Map<String, Object?>)[r'$defs']! as Map<String, Object?>;
    for (final Object? raw in endpoints['endpoints']! as List<Object?>) {
      final Map<String, Object?> endpoint = raw! as Map<String, Object?>;
      final List<Object?> named = <Object?>[endpoint['request'], ...(endpoint['responses']! as Map<String, Object?>).values];
      for (final Object? name in named) {
        if (name is String && !name.contains('/')) expect(definitions.containsKey(name), isTrue, reason: '${endpoint['path']} names $name');
      }
      final Object? scope = endpoint['scope'];
      if (scope is String && scope != 'apply:*') {
        expect(schemas.validateMessage('scope', scope), isEmpty, reason: '${endpoint['path']} scope $scope');
      }
    }
    final Map<String, Object?> kinds = endpoints['kinds']! as Map<String, Object?>;
    expect(kinds.keys.toList(), DocumentKind.values.map((DocumentKind kind) => kind.wireName).toList());
    for (final DocumentKind kind in DocumentKind.values) {
      expect((kinds[kind.wireName]! as Map<String, Object?>)['folder'], kind.folder);
      final String? schema = schemas.schemaFor(kind);
      if (schema != null) expect(schemas.registry.contains(schema), isTrue, reason: schema);
    }
    final List<Object?> kindEnum = (definitions['kind']! as Map<String, Object?>)['enum']! as List<Object?>;
    expect(kindEnum, DocumentKind.values.map((DocumentKind kind) => kind.wireName).toList());
  });

  test('should list the export fixture exactly as export.expected.json says', () {
    final Map<String, Object?> expected = readFixture('export.expected.json')! as Map<String, Object?>;
    final DataFolderResult result = openDataFolder(fixtureFiles('export'), schemas);
    expect(<Map<String, Object?>>[
      for (final AnimusDocument document in result.documents)
        <String, Object?>{'kind': document.kind.wireName, 'id': document.id, 'path': document.path, 'valid': document.valid},
    ], expected['documents']);
    expect(result.ignored, expected['ignored']);
  });
}
