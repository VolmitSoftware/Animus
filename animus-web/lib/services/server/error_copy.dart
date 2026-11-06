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

const String _pairAgain = 'Pair again: run /animus editor pair on the server and open the new link.';
const String _checkServer = 'Start the server, or check /animus editor status on it.';

/// Every `error-code` in editor-v1.schema.json.
const Map<String, ErrorCopy> serverErrorCopy = <String, ErrorCopy>{
  'unauthorized': ErrorCopy('The server does not recognise the token this browser holds.', _pairAgain),
  'token-expired': ErrorCopy('The pairing for this browser expired.', _pairAgain),
  'token-revoked': ErrorCopy('The pairing for this browser was revoked on the server.', _pairAgain),
  'scope-missing': ErrorCopy('This pairing does not allow that action.',
      'Run /animus editor pair on the server with that scope added (such as scopes=read,import or scopes=read,spawn) and open the new link.'),
  'group-denied': ErrorCopy('This pairing is limited to other NPC groups.', 'Edit an NPC in an allowed group, or pair again without the group limit.'),
  'origin-not-allowed': ErrorCopy('The server does not accept requests from this page\'s address.',
      'Add this address to allowedOrigins under [editor] in the Animus config, then reload the config.'),
  'counter-replayed': ErrorCopy('The server saw an out-of-order request from this browser, which happens when two tabs share one pairing.',
      'Try again; if it repeats, close the other tab.'),
  'pairing-invalid': ErrorCopy('The server does not know this pairing code, or it was already used.',
      'Run /animus editor pair on the server again and open the new link.'),
  'pairing-expired': ErrorCopy('This pairing code expired.', 'Run /animus editor pair on the server again and open the new link.'),
  'not-found': ErrorCopy('The server has no such document or request.', 'Reload the catalog; it may have been deleted or renamed.'),
  'invalid-request': ErrorCopy('The server could not accept the request as sent.', 'Reload the editor and try again; if it repeats, check the server console.'),
  'too-large': ErrorCopy('The request is larger than the server accepts.', 'Send fewer documents at once, or connect over the direct path for large files.'),
  'rate-limited': ErrorCopy('The server is limiting how fast this browser sends requests.', 'Wait a few seconds and try again.'),
  'busy': ErrorCopy('The server is busy with another write.', 'Wait a moment and try again.'),
  'conflict': ErrorCopy('These documents changed on the server since you opened them.', 'Merge or take the server version, then apply again.'),
  'preview-refused': ErrorCopy('The preview would run something previews never run, such as commands, combat or the economy.',
      'Remove those parts from the draft, or apply it instead.'),
  'player-offline': ErrorCopy('Previews show only to your player, who is not on the server.',
      'Join the server as the player who ran /animus editor pair, then preview again.'),
  'gloss-unavailable': ErrorCopy('This needs the Gloss plugin, which is not running on the server.', 'Install or enable Gloss on the server, then try again.'),
  'unsupported-protocol': ErrorCopy('The server speaks a different editor protocol than this page.',
      'Update Animus on the server, or reload this page for the newest editor.'),
  'server-offline': ErrorCopy('The server is not connected to the relay.', _checkServer),
  'timeout': ErrorCopy('The server took too long to answer.', 'Check the connection; the editor tries again by itself.'),
  'internal': ErrorCopy('The server hit an error it could not handle.', 'Check the server console for the error, then try again.'),
};

/// Every `relay-error` code in editor-v1.schema.json.
const Map<String, ErrorCopy> relayErrorCopy = <String, ErrorCopy>{
  'invalid-frame': ErrorCopy('The relay could not read a message from this page.', 'Reload the page.'),
  'unsupported-protocol': ErrorCopy('The relay speaks a different protocol than this page.', 'Reload the page for the newest editor.'),
  'too-large': ErrorCopy('The message is larger than one relay frame (1 MiB).', 'Connect over the direct path, or send less at once.'),
  'rate-limited': ErrorCopy('The relay is limiting how fast this browser sends.', 'Wait a few seconds; the editor tries again by itself.'),
  'handshake-failed': ErrorCopy('The server could not prove its identity to the relay.', _checkServer),
  'timeout': ErrorCopy('The relay stopped waiting for this page.', 'The editor reconnects by itself; check your network if it repeats.'),
  'replaced': ErrorCopy('A newer connection replaced this one at the relay.', 'Close other editor tabs open on this server; this one reconnects.'),
  'capacity': ErrorCopy('The relay is full right now.', 'The editor tries again by itself; connect over the direct path if it stays full.'),
  'queue-full': ErrorCopy('Too many requests are waiting at the relay.', 'Wait for the running ones to finish, then try again.'),
};

/// Why the browser's own path failed (a `TransportFailure` code).
const Map<String, ErrorCopy> transportErrorCopy = <String, ErrorCopy>{
  'unreachable': ErrorCopy('The server address did not answer.', 'Check that the server is running and reachable from this computer.'),
  'timeout': ErrorCopy('The server took too long to answer.', 'Check the connection; the editor tries again by itself.'),
  'dropped': ErrorCopy('The connection to the server dropped.', 'The editor reconnects by itself.'),
  'closed': ErrorCopy('This connection was closed.', 'Connect again from the server page.'),
  'stream-closed': ErrorCopy('The server stopped sending live updates.', 'The editor reconnects by itself.'),
  'server-offline': ErrorCopy('The relay has no connection from this server.', _checkServer),
  'server-left': ErrorCopy('The server left the relay.', _checkServer),
  'relay-unreachable': ErrorCopy('The relay did not answer.', 'Check your network; the editor tries again by itself.'),
  'wrong-server': ErrorCopy('A path answered as a different server than the one paired, so nothing was sent.',
      'Check the server address, or pair again if the server was reinstalled.'),
  'no-path': ErrorCopy('The pairing code names no way to reach the server.', 'Run /animus editor pair on the server again and open the new link.'),
  'no-token': ErrorCopy('This tab holds no token for this server. Without Remember this browser, the token stays in the tab that paired.', _pairAgain),
};

/// Why the server closed the event stream (the `closing` event's reason).
const Map<String, ErrorCopy> closingCopy = <String, ErrorCopy>{
  'token-revoked': ErrorCopy('The pairing for this browser was revoked on the server.', _pairAgain),
  'token-expired': ErrorCopy('The pairing for this browser expired.', _pairAgain),
  'server-stopping': ErrorCopy('The server is stopping.', 'The editor reconnects when it is back.'),
  'limit': ErrorCopy('This editor fell too far behind on server events.', 'It reconnects and reloads what changed.'),
};

const ErrorCopy _unknown = ErrorCopy('The server refused the request.', 'Try again; if it repeats, check the server console.');

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

ErrorCopy relayCopy(String? code) => relayErrorCopy[code] ?? const ErrorCopy('The relay refused this connection.', 'The editor reconnects by itself.');

ErrorCopy transportCopy(String code) => transportErrorCopy[code] ?? relayErrorCopy[code] ?? transportErrorCopy['dropped']!;

ErrorCopy closingCopyOf(Object? reason) => closingCopy[reason] ?? const ErrorCopy('The server closed the connection.', 'The editor reconnects by itself.');
