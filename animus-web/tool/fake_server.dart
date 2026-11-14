/// An in-memory editor-v1 server for local development and tests.
///
///     dart run tool/fake_server.dart [--port=9797] [--data=../schema/fixtures/export]
///         [--origin=http://127.0.0.1:8098 (repeatable)] [--viewer=<uuid>] [--page-size=1000]
///         [--rate=20] [--name="Animus fake server"] [--scale=1200]
///
/// It loads the data folder once and never writes to disk; `--scale=N`
/// generates N documents in place of `--data` (tool/fake_server/scale.dart).
/// `--rate` is each token's requests per second (0 means 20). See
/// tool/fake_server/server.dart for the paths it serves.
library;

import 'dart:async';
import 'dart:io';

import 'fake_server/server.dart';

Future<void> main(List<String> arguments) async {
  int port = 9797;
  String data = '../schema/fixtures/export';
  final List<String> origins = <String>[];
  String viewer = '00000000-0000-4000-8000-000000000001';
  int pageSize = 1000;
  int rate = 20;
  String serverName = 'Animus fake server';
  int? scale;
  for (final String argument in arguments) {
    final int equals = argument.indexOf('=');
    final String name = equals < 0 ? argument : argument.substring(0, equals);
    final String value = equals < 0 ? '' : argument.substring(equals + 1);
    switch (name) {
      case '--port':
        port = int.parse(value);
      case '--data':
        data = value;
      case '--origin':
        origins.add(value);
      case '--viewer':
        if (!RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$').hasMatch(value)) {
          stderr.writeln('--viewer must be a lower-case UUID');
          exit(64);
        }
        viewer = value;
      case '--page-size':
        pageSize = int.parse(value);
      case '--rate':
        final int? parsed = int.tryParse(value);
        if (parsed == null || parsed < 0) {
          stderr.writeln('--rate must be a whole number of requests per second');
          exit(64);
        }
        rate = parsed == 0 ? 20 : parsed;
      case '--name':
        if (value.trim().isEmpty || value.length > 128) {
          stderr.writeln('--name must be 1 to 128 characters');
          exit(64);
        }
        serverName = value;
      case '--scale':
        scale = int.tryParse(value);
        if (scale == null || scale < 1) {
          stderr.writeln('--scale must be a positive number of documents');
          exit(64);
        }
      default:
        stderr.writeln('Unknown argument $argument. Use --port=, --data=, --origin=, --viewer=, --page-size=, --rate=, --name=, --scale=.');
        exit(64);
    }
  }
  final FakeServer server = await FakeServer.start(
    FakeSettings(
      dataDirectory: data,
      allowedOrigins: origins.isEmpty ? const <String>['http://127.0.0.1:8098'] : origins,
      viewer: viewer,
      catalogPageSize: pageSize,
      requestsPerSecond: rate,
      serverName: serverName,
      scale: scale,
    ),
    port: port,
  );
  stdout.writeln('Listening on ${server.directUrl}/animus/v1 (direct)');
  stdout.writeln('Listening on ${server.relayUrl}/app (relay emulation)');
  stdout.writeln('Listening on ${server.directUrl}/__fake/ (test controls)');
  stdout.writeln('Fingerprint ${server.identity.fingerprint}; ${server.api.documents.list().length} documents from ${scale == null ? data : 'the --scale=$scale set'}');
  stdout.writeln('Pair: ${server.pair()['link']}');
  final Completer<void> stopping = Completer<void>();
  void stop(ProcessSignal signal) {
    if (!stopping.isCompleted) stopping.complete();
  }

  final List<StreamSubscription<ProcessSignal>> signals = <StreamSubscription<ProcessSignal>>[
    ProcessSignal.sigint.watch().listen(stop),
    if (!Platform.isWindows) ProcessSignal.sigterm.watch().listen(stop),
  ];
  await stopping.future;
  for (final StreamSubscription<ProcessSignal> signal in signals) {
    await signal.cancel();
  }
  await server.close();
}
