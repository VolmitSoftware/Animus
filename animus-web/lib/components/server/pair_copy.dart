/// What the pairing screens say when a link cannot be used: one plain cause,
/// the one thing to do next, and whether a new link is needed. Pure Dart.
library;

import '../../services/server/error_copy.dart';
import '../../services/server/server_pairing.dart';

/// The in-game command that makes a pairing link.
const String pairCommand = '/animus editor pair';

/// The next step whenever the link itself is spent.
const String newLinkStep = 'Run $pairCommand in game, then open or paste the new link.';

class PairProblem {
  const PairProblem(this.title, this.next, {this.newLink = false});

  /// What happened, one sentence.
  final String title;

  /// The one thing to do next, one sentence.
  final String next;

  /// The link cannot work again: offer the field for a new one.
  final bool newLink;

  @override
  String toString() => '$title $next';
}

/// A pasted or opened link that is not a pairing link.
const PairProblem notALink = PairProblem('This is not a pairing link.', 'Copy the whole link from the chat, or run $pairCommand for a new one.', newLink: true);

/// The pair page with no link: reloaded after the link left the address,
/// or opened from history.
const PairProblem noLink = PairProblem('No pairing link here.', 'Open the link from the chat again, or paste it below.', newLink: true);

/// A link whose ten minutes are up, found before anything is sent.
const PairProblem expiredLink = PairProblem('This link expired.', newLinkStep, newLink: true);

/// How this browser is listed on the server, from its user agent: "Chrome on
/// macOS", or "Web editor" when it cannot tell.
String browserName(String userAgent) {
  final String browser = switch (userAgent) {
    final String ua when ua.contains('Edg/') => 'Edge',
    final String ua when ua.contains('OPR/') => 'Opera',
    final String ua when ua.contains('Firefox/') => 'Firefox',
    final String ua when ua.contains('Chrome/') => 'Chrome',
    final String ua when ua.contains('Safari/') => 'Safari',
    _ => '',
  };
  final String system = switch (userAgent) {
    final String ua when ua.contains('iPhone') || ua.contains('iPad') => 'iOS',
    final String ua when ua.contains('Android') => 'Android',
    final String ua when ua.contains('Mac OS X') || ua.contains('Macintosh') => 'macOS',
    final String ua when ua.contains('Windows') => 'Windows',
    final String ua when ua.contains('Linux') => 'Linux',
    _ => '',
  };
  if (browser.isEmpty) return 'Web editor';
  return system.isEmpty ? browser : '$browser on $system';
}

/// A loopback or private-network address, as a browser treats it for its
/// local network access prompt.
bool isLocalAddress(String? url) {
  if (url == null) return false;
  final Uri? uri = Uri.tryParse(url);
  final String host = uri?.host ?? '';
  if (host == 'localhost' || host == '127.0.0.1' || host == '::1' || host.endsWith('.local')) return true;
  final List<int?> parts = host.split('.').map(int.tryParse).toList();
  if (parts.length != 4 || parts.any((int? part) => part == null)) return false;
  final int first = parts[0]!, second = parts[1]!;
  return first == 10 || first == 127 || (first == 192 && second == 168) || (first == 172 && second >= 16 && second <= 31);
}

/// The one adapter from a refused pairing to what the pair page says: the
/// typed failure ([PairingRefused.failure]) picks the words; a refusal
/// outside those cases is worded by its code. [pageOrigin] is this page's
/// origin, [directUrl] the address the link names.
PairProblem pairProblemFor(PairingRefused refused, {required String pageOrigin, String? directUrl}) =>
    _problem(refused.failure, refused.code, pageOrigin: pageOrigin, directUrl: directUrl, copy: refused.copy);

const PairProblem _blocked = PairProblem('The browser blocked the connection to your server.',
    'Allow local network access for this site in the settings next to the address, then press Pair again.');

const PairProblem _silent = PairProblem('The server did not answer.', 'Check that it is running, then press Pair again.');

/// The problem for a refusal [code]: a server `error-code`, the browser's
/// own (such as `unreachable`) or a typed failure's code.
PairProblem pairProblemOf(String code, {required String pageOrigin, String? directUrl, ErrorCopy? copy}) =>
    _problem(PairingFailure.of(code), code, pageOrigin: pageOrigin, directUrl: directUrl, copy: copy);

PairProblem _problem(PairingFailure failure, String code, {required String pageOrigin, String? directUrl, ErrorCopy? copy}) => switch (failure) {
      PairingFailure.used => const PairProblem('This link was already used.', newLinkStep, newLink: true),
      PairingFailure.expired => expiredLink,
      PairingFailure.originNotAllowed => PairProblem('The server does not accept this editor\'s address.',
          'Add $pageOrigin to allowedOrigins under [editor] in plugins/Animus/config.toml, then press Pair again.'),
      PairingFailure.localNetworkBlocked => _blocked,
      PairingFailure.relayUnavailable => _silent,
      PairingFailure.unreachable => _unanswered(pageOrigin, directUrl),
      PairingFailure.wrongServer => const PairProblem('A different server answered.', newLinkStep, newLink: true),
      PairingFailure.other => _untyped(code, pageOrigin: pageOrigin, directUrl: directUrl, copy: copy),
    };

/// No answer: a page served over https reaching a server on this machine or
/// the local network meets the browser's local network permission first.
PairProblem _unanswered(String pageOrigin, String? directUrl) =>
    pageOrigin.startsWith('https://') && isLocalAddress(directUrl) ? _blocked : _silent;

/// A refusal no [PairingFailure] covers, worded by its own code.
PairProblem _untyped(String code, {required String pageOrigin, String? directUrl, ErrorCopy? copy}) => switch (code) {
      'no-path' => const PairProblem('This link has no server address.', newLinkStep, newLink: true),
      'no-answer' || 'timeout' || 'relay-unreachable' || 'server-offline' || 'dropped' || 'closed' => _unanswered(pageOrigin, directUrl),
      'rate-limited' || 'busy' => const PairProblem('The server is busy.', 'Wait a few seconds, then press Pair again.'),
      _ => PairProblem(copy?.cause ?? 'The server refused the link.', copy?.next ?? 'Press Pair again, or make a new link.'),
    };
