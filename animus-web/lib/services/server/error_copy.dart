/// Plain words for every way a server, the relay or the browser's own
/// connection can refuse or fail: what happened and what to do next. Every
/// message the editor shows for a server problem comes from here, so a code
/// never reaches the screen bare.
library;

import 'scope_words.dart';

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
const String _pairWithScope = 'Run /animus editor pair with that permission added and open the new link.';

/// Every `error-code` in editor-v1.schema.json.
const Map<String, ErrorCopy> serverErrorCopy = <String, ErrorCopy>{
  'unauthorized': ErrorCopy('Server does not know this token.', _pairAgain),
  'token-expired': ErrorCopy('Pairing expired.', _pairAgain),
  'token-revoked': ErrorCopy('Pairing revoked on the server.', _pairAgain),
  'scope-missing': ErrorCopy('This pairing lacks a permission this needs.', _pairWithScope),
  'group-denied': ErrorCopy('This pairing is limited to other NPC groups.', 'Edit an allowed group, or pair again without the limit.'),
  'origin-not-allowed': ErrorCopy('Server does not accept this page\'s address.',
      'Add it to allowedOrigins under [editor] in the Animus config, then reload the config.'),
  'counter-replayed': ErrorCopy('Out-of-order request, likely two tabs on one pairing.', 'Close the other tab and try again.'),
  'pairing-invalid': ErrorCopy('Pairing code is unknown, used or revoked.', _pairAgain),
  'pairing-expired': ErrorCopy('Pairing code expired.', _pairAgain),
  'not-found': ErrorCopy('No such document or request on the server.', 'Reload the catalog.'),
  'world-unloaded': ErrorCopy('The world is not loaded on the server.', 'Load the world on the server, then reload the view.'),
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
  'server-left': ErrorCopy('Server restarting.', _reconnecting),
  'restarting': ErrorCopy('Server restarting.', _reconnecting),
  'relay-unreachable': ErrorCopy('Relay did not answer.', 'Check your network.'),
  'no-answer': ErrorCopy('Server did not answer on either path.', 'Check that it is running.'),
  'wrong-server': ErrorCopy('A different server answered, so nothing was sent.', 'Check the address, or pair again if the server was reinstalled.'),
  'no-token': ErrorCopy('This tab has no token for this server.', _pairAgain),
};

/// Why the server closed the event stream (the `closing` event's reason).
const Map<String, ErrorCopy> closingCopy = <String, ErrorCopy>{
  'token-revoked': ErrorCopy('Pairing revoked on the server.', _pairAgain),
  'token-expired': ErrorCopy('Pairing expired.', _pairAgain),
  'server-stopping': ErrorCopy('Server restarting.', _reconnecting),
  'limit': ErrorCopy('Fell behind on server events.', _reconnecting),
};

const ErrorCopy _unknown = ErrorCopy('Server refused the request.', 'Try again, or check the server console.');

/// Why pairing gave no token, in the cases the pairing page words apart.
enum PairingFailure {
  /// The link ran out before it was used.
  expired('pairing-expired'),

  /// The server does not know the link: used, revoked or never made there.
  used('pairing-invalid'),

  /// The server refuses requests from this page's address.
  originNotAllowed('origin-not-allowed'),

  /// No path answered.
  unreachable('unreachable'),

  /// The browser held or refused this https page's call to the server's
  /// plain http address on the local network.
  localNetworkBlocked('local-network-blocked'),

  /// The relay is down or refused, or the server is not on it.
  relayUnavailable('relay-unavailable'),

  /// A server with another key answered, so the secret was not sent.
  wrongServer('wrong-server'),

  /// The server refused for another reason, worded by its own code.
  other('refused');

  const PairingFailure(this.code);

  /// The refusal code this failure is reported with.
  final String code;

  /// The failure a refusal [code] stands for: [other] for any code not listed.
  static PairingFailure of(String code) => values.firstWhere((PairingFailure failure) => failure.code == code, orElse: () => other);
}

/// The cause and next step shown for [failure].
ErrorCopy pairingFailureCopy(PairingFailure failure) => switch (failure) {
      PairingFailure.expired => serverErrorCopy['pairing-expired']!,
      PairingFailure.used => serverErrorCopy['pairing-invalid']!,
      PairingFailure.originNotAllowed => serverErrorCopy['origin-not-allowed']!,
      PairingFailure.unreachable => transportErrorCopy['unreachable']!,
      PairingFailure.localNetworkBlocked => const ErrorCopy(
          'This browser blocked the page from reaching the server on your network.',
          'Allow local network access for this site, or pair through the relay.',
        ),
      PairingFailure.relayUnavailable => const ErrorCopy('The relay could not connect this browser to the server.', _checkServer),
      PairingFailure.wrongServer => transportErrorCopy['wrong-server']!,
      PairingFailure.other => _unknown,
    };

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

/// A `scope-missing` refusal: the permission [scope] names (the server's
/// `error.scope`; older servers leave it out) and, when the token's [held]
/// scopes are known, the pair command that grants it beside them. Without
/// [scope] it names no permission and suggests no scopes.
ErrorCopy scopeMissingCopy(String? scope, {Iterable<String>? held}) {
  if (scope == null || scope.isEmpty) return serverErrorCopy['scope-missing']!;
  final String cause = 'This pairing lacks the ${scopeWord(scope)} permission.';
  if (held == null) return ErrorCopy(cause, _pairWithScope);
  final List<String> scopes = <String>[...held.where((String kept) => kept != scope), scope];
  return ErrorCopy(cause, 'Run /animus editor pair scopes=${scopes.join(',')} and open the new link.');
}

/// Copy for a server `error-code`, or for [status] when the answer had none.
ErrorCopy serverCopy(String? code, {int? status}) =>
    serverErrorCopy[code] ?? (status == null ? _unknown : serverErrorCopy[codeForStatus(status)]!);

ErrorCopy relayCopy(String? code) => relayErrorCopy[code] ?? const ErrorCopy('Relay refused the connection.', _reconnecting);

ErrorCopy transportCopy(String code) => transportErrorCopy[code] ?? relayErrorCopy[code] ?? transportErrorCopy['dropped']!;

ErrorCopy closingCopyOf(Object? reason) => closingCopy[reason] ?? const ErrorCopy('Server closed the connection.', _reconnecting);
