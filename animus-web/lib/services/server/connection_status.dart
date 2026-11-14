/// Where one server's connection stands, in the words the editor shows,
/// and the error a request gets while it is not online.
library;

import 'error_copy.dart';
import 'transport.dart';

enum ConnectionPhase {
  /// Not connected and not trying.
  offline,

  /// Trying a path.
  connecting,

  /// Requests and the event stream work.
  online,

  /// Lost or never reached; waiting to try again.
  reconnecting,

  /// The token is missing, expired or revoked: pair again.
  needsPairing,

  /// A path answered with another server's fingerprint. Nothing more is
  /// tried until the user acts.
  refused,
}

class ConnectionStatus {
  const ConnectionStatus(this.phase, {this.path, this.attempt = 0, this.retryAt, this.problem, this.hello, this.probed = false});

  /// From a reachability check of a saved server (`GET /v1/hello`), not a
  /// connection: online, connecting (checking) or offline (not reachable).
  final bool probed;

  final ConnectionPhase phase;
  final ConnectionPath? path;

  /// Failed attempts since the last time the connection was online.
  final int attempt;
  final DateTime? retryAt;

  /// Why the connection is not online, in plain words.
  final ErrorCopy? problem;

  String? get message => problem?.text;

  /// The last `hello-response` from the server.
  final Map<String, Object?>? hello;

  bool get online => phase == ConnectionPhase.online;

  String get label => switch (phase) {
        ConnectionPhase.online when probed => 'Online',
        ConnectionPhase.connecting when probed => 'Checking',
        ConnectionPhase.offline when probed => 'Not reachable',
        ConnectionPhase.offline => 'Offline',
        ConnectionPhase.connecting => 'Connecting${path == null ? '' : ' (${path!.label.toLowerCase()})'}',
        ConnectionPhase.online => 'Online (${path!.label.toLowerCase()})',
        ConnectionPhase.reconnecting => 'Reconnecting',
        ConnectionPhase.needsPairing => 'Pair again',
        ConnectionPhase.refused => 'Refused',
      };
}

/// The request needs a connection that is not online.
class NotConnected implements Exception {
  const NotConnected(this.status);

  final ConnectionStatus status;

  @override
  String toString() => 'Not connected: ${status.message ?? status.label}';
}
