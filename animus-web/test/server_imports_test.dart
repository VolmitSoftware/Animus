import 'dart:typed_data';

import 'package:animus_editor/services/server/server_imports.dart';
import 'package:animus_editor/services/server/transport.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

void main() {
  final Map<String, Object?> applied = readFixture('editor/messages/import-review.0.json')! as Map<String, Object?>;

  test('should read the server import-review', () {
    final ImportReview review = ImportReview.fromJson(applied);
    expect(review.state, 'applied');
    expect(review.importId, startsWith('4c1f0d2b'));
    expect(review.commandNamespaces, <String>['citizens']);
    expect(review.uploads, <String, String>{'citizens': 'imports/saves.yml'});
    expect(review.reports['citizens']!['records'], 5);
  });

  test('should send uploads as bytes with file and name, and steps with their bodies', () async {
    final List<ApiRequest> sent = <ApiRequest>[];
    final ServerImports imports = ServerImports(
      send: (ApiRequest request) async {
        sent.add(request);
        return ApiResponse(200, applied);
      },
      path: () => ConnectionPath.direct,
    );
    await imports.upload(ImportUpload(ImportFile.dtltraders, Uint8List.fromList(<int>[1, 2, 3]), name: 'shops/baker/baker.yml'));
    await imports.stage(mode: 'duplicate', label: 'copy', worlds: <String, String>{'old': 'world'});
    await imports.apply('a' * 64);
    await imports.rollback('a' * 64);
    expect(sent.map((ApiRequest r) => '${r.method} ${r.path}'), <String>[
      'POST /v1/imports?file=dtltraders&name=shops%2Fbaker%2Fbaker.yml',
      'POST /v1/imports/stage',
      'POST /v1/imports/${'a' * 64}/apply',
      'POST /v1/imports/${'a' * 64}/rollback',
    ]);
    expect(sent.first.bytes, <int>[1, 2, 3]);
    expect(sent.first.relayBody, 'AQID', reason: 'the relay carries the bytes as base64');
    expect(sent[1].body, <String, Object?>{'mode': 'duplicate', 'label': 'copy', 'worlds': <String, String>{'old': 'world'}});
    expect(sent[2].body, isNull, reason: 'apply takes no body: everything that converted turns on');
  });

  test('should refuse an upload larger than a relay frame while on the relay, and say to use the direct path', () async {
    ConnectionPath path = ConnectionPath.relay;
    int sent = 0;
    final ServerImports imports = ServerImports(
      send: (ApiRequest request) async {
        sent++;
        return ApiResponse(200, applied);
      },
      path: () => path,
    );
    final ImportUpload large = ImportUpload(ImportFile.citizens, Uint8List(relayUploadBytes + 1));
    expect(uploadProblem(large, ConnectionPath.relay), contains('direct path'));
    await expectLater(imports.upload(large), throwsA(isA<ImportRefused>().having((ImportRefused r) => r.code, 'code', 'too-large')));
    expect(sent, 0);
    path = ConnectionPath.direct;
    await imports.upload(large);
    expect(sent, 1);
    expect(uploadProblem(ImportUpload(ImportFile.citizens, Uint8List(importBodyBytes + 1)), ConnectionPath.direct), contains('over the 16.0 MiB upload limit'));
  });

  test('should raise the server refusal as ImportRefused', () async {
    final ServerImports imports = ServerImports(
      send: (ApiRequest request) async => const ApiResponse(409, <String, Object?>{
        'error': <String, Object?>{'code': 'conflict', 'message': 'No import is applied.'},
      }),
      path: () => ConnectionPath.direct,
    );
    await expectLater(
      imports.rollback('b' * 64),
      throwsA(isA<ImportRefused>()
          .having((ImportRefused r) => r.code, 'code', 'conflict')
          .having((ImportRefused r) => r.detail, 'detail', 'No import is applied.')),
    );
  });

  test('should show a refused import step in the server\'s words, which name the step to take', () {
    const String stale = 'Import 0d7bc5863a64 was previewed by an older Animus and is not applied: npcs/world/citizens-1.json is schemaVersion 1, '
        'this one writes 2; upload and stage the save again, then apply';
    expect(importRefusalText(const ImportRefused('conflict', 'Changed on the server since you opened it.', detail: stale)), stale);
    expect(importRefusalText(const ImportRefused('busy', 'Server is busy with another write. Try again in a moment.', detail: 'Failed on purpose.')),
        'Server is busy with another write. Try again in a moment. Server: Failed on purpose.');
    expect(importRefusalText(const ImportRefused('too-large', 'Over the relay limit.')), 'Over the relay limit.');
  });

  test('should place picked dtlTraders files where the dtlTraders folder keeps them', () {
    expect(dtlTradersName('guis.yml'), 'guis.yml');
    expect(dtlTradersName('data.dat'), 'data.dat');
    expect(dtlTradersName('baker.yml'), 'shops/baker/baker.yml');
    expect(dtlTradersName('notes.txt'), isNull);
  });
}
