/// A server connection's state and what to do about it, in words. Pure
/// Dart.
library;

import '../../services/server/connection_status.dart';
import '../../services/server/error_copy.dart';

/// The state in two or three words, with the path while online.
String phaseLabel(ConnectionStatus status, {required bool hasToken}) {
  if (status.phase == ConnectionPhase.offline && !hasToken) return 'Not paired';
  return switch (status.phase) {
    ConnectionPhase.needsPairing => 'Pairing ended',
    ConnectionPhase.refused => 'Connection refused',
    _ => status.label,
  };
}

/// Why the connection is not online and what to do. Empty while online.
/// [connectHere] says a Connect button sits beside the hint.
String phaseHint(ConnectionStatus status, {required bool hasToken, bool connectHere = false}) {
  if (status.online) return '';
  if (!hasToken && status.phase != ConnectionPhase.needsPairing) return transportCopy('no-token').text;
  final String? message = status.message;
  return switch (status.phase) {
    ConnectionPhase.reconnecting when status.retryAt != null =>
      '${message ?? transportCopy('dropped').cause} Next try at ${status.retryAt!.toLocal().toString().substring(11, 19)}.',
    ConnectionPhase.offline => connectHere ? 'Not connected.' : 'Not connected. Press Reconnect in the bar below.',
    _ => message ?? '',
  };
}

/// A server platform as its makers spell it: "Paper", "NeoForge".
String platformName(String platform) => switch (platform.toLowerCase()) {
      'neoforge' => 'NeoForge',
      'craftbukkit' => 'CraftBukkit',
      _ => platform.isEmpty ? platform : '${platform[0].toUpperCase()}${platform.substring(1)}',
    };
