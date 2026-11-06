/// Plain words for every way a server, the relay or the browser's own
/// connection can refuse or fail: what happened and what to do next. Every
/// message the editor shows for a server problem comes from here, so a code
/// never reaches the screen bare.
library;

/// What happened and the next action, each one sentence.
class ErrorCopy {
  const ErrorCopy(this.cause, this.next);

  final String cause;
  final String next;

  /// Both sentences, for a toast, an alert or the live log.
  String get text => '$cause $next';
}

const String _pairAgain = 'Run /animus editor pair and open the new link.';
const String _checkServer = 'Start the server, or run /animus editor status on it.';
const String _retrying = 'Retrying.';
const String _reconnecting = 'Reconnecting.';

/// Every `error-code` in editor-v1.schema.json.
const Map<String, ErrorCopy> serverErrorCopy = <String, ErrorCopy>{
  'unauthorized': ErrorCopy('Server does not know this token.', _pairAgain),
  'token-expired': ErrorCopy('Pairing expired.', _pairAgain),
  'token-revoked': ErrorCopy('Pairing revoked on the server.', _pairAgain),
  'scope-missing': ErrorCopy('This pairing lacks that scope.',
      'Run /animus editor pair with the scope added (such as scopes=read,import or scopes=read,spawn) and open the new link.'),
  'group-denied': ErrorCopy('This pairing is limited to other NPC groups.', 'Edit an allowed group, or pair again without the limit.'),
  'origin-not-allowed': ErrorCopy('Server does not accept this page\'s address.',
      'Add it to allowedOrigins under [editor] in the Animus config, then reload the config.'),
  'counter-replayed': ErrorCopy('Out-of-order request, likely two tabs on one pairing.', 'Close the other tab and try again.'),
  'pairing-invalid': ErrorCopy('Unknown or used pairing code.', _pairAgain),
  'pairing-expired': ErrorCopy('Pairing code expired.', _pairAgain),
  'not-found': ErrorCopy('No such document or request on the server.', 'Reload the catalog.'),
  'invalid-request': ErrorCopy('Server rejected the request.', 'Reload the editor and try again.'),
  'too-large': ErrorCopy('Request too large.', 'Send fewer documents, or use the direct path.'),
  'rate-limited': ErrorCopy('Server is rate limiting this browser.', 'Wait a few seconds.'),
  'busy': ErrorCopy('Server is busy with another write.', 'Try again in a moment.'),
  'conflict': ErrorCopy('Changed on the server since you opened it.', 'Merge or take the server version, then apply again.'),
  'preview-refused': ErrorCopy('Previews cannot run commands, combat or the economy.', 'Remove those parts, or apply instead.'),
  'player-offline': ErrorCopy('Your player is not on the server.', 'Join as the player who paired, then preview again.'),
  'gloss-unavailable': ErrorCopy('Gloss is not running on the server.', 'Install or enable Gloss.'),
  'unsupported-protocol': ErrorCopy('Editor protocol mismatch.', 'Update Animus on the server, or reload this page.'),
  'server-offline': ErrorCopy('Server is not on the relay.', _checkServer),
  'timeout': ErrorCopy('Server timed out.', _retrying),
  'internal': ErrorCopy('Server error.', 'Check the server console.'),
};

/// Every `relay-error` code in editor-v1.schema.json.
const Map<String, ErrorCopy> relayErrorCopy = <String, ErrorCopy>{
  'invalid-frame': ErrorCopy('Relay could not read a message.', 'Reload the page.'),
  'unsupported-protocol': ErrorCopy('Relay protocol mismatch.', 'Reload the page.'),
  'too-large': ErrorCopy('Message exceeds one relay frame (1 MiB).', 'Use the direct path, or send less.'),
  'rate-limited': ErrorCopy('Relay is rate limiting this browser.', _retrying),
  'handshake-failed': ErrorCopy('Server failed the relay handshake.', _checkServer),
  'timeout': ErrorCopy('Relay timed out.', _reconnecting),
  'replaced': ErrorCopy('Another tab took over this connection.', 'Close other editor tabs for this server.'),
  'capacity': ErrorCopy('Relay is full.', 'Retrying, or use the direct path.'),
  'queue-full': ErrorCopy('Too many requests queued at the relay.', 'Wait, then try again.'),
};

/// Why the browser's own path failed (a `TransportFailure` code).
const Map<String, ErrorCopy> transportErrorCopy = <String, ErrorCopy>{
  'unreachable': ErrorCopy('Server address did not answer.', 'Check the server is running and reachable.'),
  'timeout': ErrorCopy('Server timed out.', _retrying),
  'dropped': ErrorCopy('Connection dropped.', _reconnecting),
  'closed': ErrorCopy('Connection closed.', 'Connect again from the server page.'),
  'stream-closed': ErrorCopy('Live updates stopped.', _reconnecting),
  'server-offline': ErrorCopy('Server is not on the relay.', _checkServer),
  'server-left': ErrorCopy('Server left the relay.', _checkServer),
  'relay-unreachable': ErrorCopy('Relay did not answer.', 'Check your network.'),
  'wrong-server': ErrorCopy('A different server answered, so nothing was sent.', 'Check the address, or pair again if the server was reinstalled.'),
  'no-path': ErrorCopy('Pairing code has no server address.', _pairAgain),
  'no-token': ErrorCopy('This tab has no token for this server.', _pairAgain),
};

/// Why the server closed the event stream (the `closing` event's reason).
const Map<String, ErrorCopy> closingCopy = <String, ErrorCopy>{
  'token-revoked': ErrorCopy('Pairing revoked on the server.', _pairAgain),
  'token-expired': ErrorCopy('Pairing expired.', _pairAgain),
  'server-stopping': ErrorCopy('Server is stopping.', 'Reconnecting when it is back.'),
  'limit': ErrorCopy('Fell behind on server events.', _reconnecting),
};

const ErrorCopy _unknown = ErrorCopy('Server refused the request.', 'Try again, or check the server console.');

/// The code an answer without an `error-response` body stands for.
String codeForStatus(int status) => switch (status) {
      401 => 'unauthorized',
      403 => 'scope-missing',
      404 => 'not-found',
      409 => 'conflict',
      413 => 'too-large',
      429 => 'rate-limited',
      503 => 'busy',
      504 => 'timeout',
      >= 500 => 'internal',
      _ => 'invalid-request',
    };

/// Copy for a server `error-code`, or for [status] when the answer had none.
ErrorCopy serverCopy(String? code, {int? status}) =>
    serverErrorCopy[code] ?? (status == null ? _unknown : serverErrorCopy[codeForStatus(status)]!);

ErrorCopy relayCopy(String? code) => relayErrorCopy[code] ?? const ErrorCopy('Relay refused the connection.', _reconnecting);

ErrorCopy transportCopy(String code) => transportErrorCopy[code] ?? relayErrorCopy[code] ?? transportErrorCopy['dropped']!;

ErrorCopy closingCopyOf(Object? reason) => closingCopy[reason] ?? const ErrorCopy('Server closed the connection.', _reconnecting);
