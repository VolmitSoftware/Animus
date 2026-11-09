import 'dart:async';
import 'dart:io';

import 'package:animus_relay/animus_relay.dart';

Future<void> main(List<String> args) async {
  final Map<String, String> environment = Platform.environment;
  final RelaySettings settings;
  try {
    settings = RelaySettings.fromEnvironment(environment);
  } on Object catch (error) {
    stderr.writeln('animus-relay: $error');
    exitCode = 64;
    return;
  }
  final HttpServer server = await startRelayServer(
    port: settings.port,
    address: settings.address,
    limits: settings.limits,
    allowedAppOrigins: settings.allowedAppOrigins,
    trustedProxyHops: settings.trustedProxyHops,
  );
  stdout.writeln(
    'animus-relay listening on ${server.address.host}:${server.port} '
    'origins=${settings.allowedAppOrigins.join(',')}',
  );
  final Completer<void> shutdown = Completer<void>();
  void stop(ProcessSignal signal) {
    if (!shutdown.isCompleted) shutdown.complete();
  }

  final StreamSubscription<ProcessSignal> terminate = ProcessSignal.sigterm
      .watch()
      .listen(stop);
  final StreamSubscription<ProcessSignal> interrupt = ProcessSignal.sigint
      .watch()
      .listen(stop);
  await shutdown.future;
  await terminate.cancel();
  await interrupt.cancel();
  await server.close(force: true);
}
