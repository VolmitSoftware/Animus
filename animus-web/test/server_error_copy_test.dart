import 'package:animus_editor/components/server/connection_words.dart';
import 'package:animus_editor/services/server/connection_status.dart';
import 'package:animus_editor/services/server/error_copy.dart';
import 'package:animus_editor/services/server/transport.dart';
import 'package:test/test.dart';

import 'fixtures.dart';

List<String> _enum(Map<String, Object?> defs, List<String> path) {
  Object? node = defs;
  for (final String step in path) {
    node = (node! as Map<String, Object?>)[step];
  }
  return <String>[for (final Object? value in (node! as Map<String, Object?>)['enum']! as List<Object?>) value! as String];
}

void _sound(String code, ErrorCopy copy) {
  expect(copy.cause, endsWith('.'), reason: code);
  expect(copy.next, endsWith('.'), reason: code);
  expect(copy.text, isNot(contains('\u2014')), reason: code);
  expect(copy.text, isNot(contains('..')), reason: code);
}

void main() {
  final Map<String, Object?> defs = (readJson('editor-v1.schema.json')! as Map<String, Object?>)[r'$defs']! as Map<String, Object?>;

  test('every server error code has a cause and a next action', () {
    for (final String code in _enum(defs, <String>['error-code'])) {
      expect(serverErrorCopy.containsKey(code), isTrue, reason: 'no copy for $code');
      _sound(code, serverErrorCopy[code]!);
    }
  });

  test('every relay error code has a cause and a next action', () {
    for (final String code in _enum(defs, <String>['relay-error', 'properties', 'code'])) {
      expect(relayErrorCopy.containsKey(code), isTrue, reason: 'no copy for $code');
      _sound(code, relayErrorCopy[code]!);
    }
  });

  test('every closing reason has copy', () {
    final List<Object?> variants = (defs['event']! as Map<String, Object?>)['oneOf']! as List<Object?>;
    final Map<String, Object?> closing = variants
        .cast<Map<String, Object?>>()
        .firstWhere((Map<String, Object?> variant) => ((variant['properties']! as Map<String, Object?>)['type']! as Map<String, Object?>)['const'] == 'closing');
    for (final Object? reason in ((closing['properties']! as Map<String, Object?>)['reason']! as Map<String, Object?>)['enum']! as List<Object?>) {
      expect(closingCopy.containsKey(reason), isTrue, reason: 'no copy for $reason');
      _sound('$reason', closingCopy[reason]!);
    }
  });

  test('answers without an error body read by their status, never as a bare number', () {
    expect(serverCopy(null, status: 503).text, serverErrorCopy['busy']!.text);
    expect(serverCopy(null, status: 500).text, serverErrorCopy['internal']!.text);
    expect(serverCopy(null, status: 418).text, isNot(contains('418')));
    expect(serverCopy('no-such-code').text, isNot(contains('no-such-code')));
  });

  test('transport codes fall back to relay copy, then to a dropped connection', () {
    expect(transportCopy('capacity').text, relayErrorCopy['capacity']!.text);
    expect(transportCopy('server-offline').next, contains('/animus editor status'));
    expect(transportCopy('whatever').text, transportErrorCopy['dropped']!.text);
    for (final MapEntry<String, ErrorCopy> entry in transportErrorCopy.entries) {
      _sound(entry.key, entry.value);
    }
  });

  test('should name the missing permission and the pair command that keeps the token\'s own', () {
    final ErrorCopy copy = scopeMissingCopy('apply:commands', held: const <String>['read', 'apply:gui', 'spawn']);
    expect(copy.cause, 'This pairing lacks the Run commands permission.');
    expect(copy.next, 'Run /animus editor pair scopes=read,apply:gui,spawn,apply:commands and open the new link.');
    _sound('scope-missing', copy);
  });

  test('should suggest no scopes when the server does not name the missing one', () {
    final ErrorCopy copy = scopeMissingCopy(null, held: const <String>['read', 'import', 'spawn']);
    expect(copy.text, serverErrorCopy['scope-missing']!.text);
    expect(copy.text, isNot(contains('scopes=')));
    expect(copy.text, isNot(contains('import')));
  });

  test('should name the scope without a command when the token\'s scopes are not known', () {
    final ErrorCopy copy = scopeMissingCopy('terrain');
    expect(copy.cause, 'This pairing lacks the See terrain permission.');
    expect(copy.text, isNot(contains('scopes=')));
  });

  test('should read the missing scope from a refusal\'s error detail', () {
    const ApiResponse named = ApiResponse(403, <String, Object?>{
      'error': <String, Object?>{'code': 'scope-missing', 'message': 'needs apply:commands', 'scope': 'apply:commands'},
    });
    const ApiResponse older = ApiResponse(403, <String, Object?>{'error': <String, Object?>{'code': 'scope-missing', 'message': 'no'}});
    expect(named.missingScope, 'apply:commands');
    expect(named.problem.cause, 'This pairing lacks the Run commands permission.');
    expect(older.missingScope, isNull);
    expect(older.problem.text, serverErrorCopy['scope-missing']!.text);
  });

  test('should keep the offline hint short where a Connect button sits beside it', () {
    const ConnectionStatus offline = ConnectionStatus(ConnectionPhase.offline);
    expect(phaseHint(offline, hasToken: true), 'Not connected. Press Reconnect in the bar below.');
    expect(phaseHint(offline, hasToken: true, connectHere: true), 'Not connected.');
  });

  test('should name a server platform as its makers spell it', () {
    expect(platformName('paper'), 'Paper');
    expect(platformName('neoforge'), 'NeoForge');
    expect(platformName('Fabric'), 'Fabric');
    expect(platformName('custom'), 'Custom');
  });
}
