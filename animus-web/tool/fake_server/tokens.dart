import 'protocol.dart';

/// A paired browser's token. Only the SHA-256 of the token text is kept.
class FakeToken {
  FakeToken(this.id, this.hash, this.scopes, this.created, this.expires, this.client);

  final String id;
  final String hash;
  final List<String> scopes;
  final DateTime created;

  /// Moved by `/__fake/expire`.
  DateTime expires;
  final String? client;
  int counter = 0;
  DateTime? lastUsed;

  bool has(String scope) => scopes.contains(scope);

  Map<String, Object?> get state => <String, Object?>{
        'id': id,
        'counter': counter,
        'scopes': scopes,
        'expiresAt': instant(expires),
        if (client != null) 'client': client,
        if (lastUsed != null) 'lastUsed': instant(lastUsed!),
      };
}

/// A pairing secret waiting for its browser.
class Pairing {
  const Pairing(this.tokenId, this.secret, this.expires);

  final String tokenId;
  final String secret;
  final DateTime expires;
}

class _Pending {
  const _Pending(this.secretHash, this.expires, this.extraScopes, this.tokenDays);

  final String secretHash;
  final DateTime expires;

  /// The token's lifetime, the pairing default unless a test asked.
  final num tokenDays;

  /// Scopes granted on top of the pairing defaults, such as import.
  final List<String> extraScopes;
}

/// Pairings, tokens and counters, as EditorTokens without the file.
class FakeTokens {
  final Map<String, _Pending> _pairings = <String, _Pending>{};
  final Map<String, FakeToken> _tokens = <String, FakeToken>{};
  final Map<String, DateTime> _revoked = <String, DateTime>{};

  List<FakeToken> get tokens => List<FakeToken>.unmodifiable(_tokens.values);

  List<String> get revoked => List<String>.unmodifiable(_revoked.keys);

  int get pendingPairings {
    final DateTime time = now();
    return _pairings.values.where((_Pending pending) => time.isBefore(pending.expires)).length;
  }

  void clear() {
    _pairings.clear();
    _tokens.clear();
    _revoked.clear();
  }

  /// A fresh one-time secret, good for `secretMinutes`; its token gets the
  /// pairing defaults plus [extraScopes].
  Pairing pair([List<String> extraScopes = const <String>[], num? tokenDays]) {
    final String id = 'tok_${randomText(12)}';
    final String secret = randomText(32);
    final DateTime expires = now().add(Duration(minutes: pairingDefaults['secretMinutes']! as int));
    _pairings[id] = _Pending(sha256Text(secret), expires, extraScopes, tokenDays ?? (pairingDefaults['defaultTokenDays']! as int));
    return Pairing(id, secret, expires);
  }

  /// Exchanges a pairing secret once. Returns the token text and the token.
  (String, FakeToken) exchange(String tokenId, String secret, String? client) {
    final _Pending? pending = _pairings[tokenId];
    if (pending == null || !sameText(pending.secretHash, sha256Text(secret))) {
      throw ApiError('pairing-invalid', 'This pairing code is not known to the server.');
    }
    _pairings.remove(tokenId);
    final DateTime time = now();
    if (!time.isBefore(pending.expires)) {
      throw ApiError('pairing-expired', 'This pairing code expired; mint another with POST /__fake/pair.');
    }
    final String text = 'anm_$tokenId.${randomText(32)}';
    final List<String> scopes = <String>[
      for (final Object? scope in pairingDefaults['defaultScopes']! as List<Object?>) scope! as String,
      for (final String scope in pending.extraScopes) scope,
    ];
    final FakeToken token = FakeToken(tokenId, sha256Text(text), scopes, time,
        time.add(Duration(minutes: (pending.tokenDays * 1440).round())), client);
    _tokens[tokenId] = token;
    return (text, token);
  }

  /// The token a bearer value names, or why it is refused.
  FakeToken authenticate(String? bearer) {
    if (bearer == null || !RegExp(r'^anm_[A-Za-z0-9_-]{8,64}\.[A-Za-z0-9_-]{43}$').hasMatch(bearer)) {
      throw ApiError('unauthorized', 'A valid editor token is required.');
    }
    final String id = bearer.substring(4, bearer.indexOf('.'));
    if (_revoked.containsKey(id)) throw ApiError('token-revoked', 'This token was revoked.');
    final FakeToken? token = _tokens[id];
    if (token == null || !sameText(token.hash, sha256Text(bearer))) {
      throw ApiError('unauthorized', 'A valid editor token is required.');
    }
    final DateTime time = now();
    if (!time.isBefore(token.expires)) throw ApiError('token-expired', 'This token expired; pair the browser again.');
    token.lastUsed = time;
    return token;
  }

  /// Accepts a mutating request's counter, which must exceed every earlier one.
  void advance(FakeToken token, int counter) {
    if (counter <= token.counter) {
      throw ApiError('counter-replayed', 'X-Animus-Counter must exceed ${token.counter}.');
    }
    token.counter = counter;
  }

  /// Revokes one token, or every token for `all`. Returns the revoked ids.
  List<String> revoke(String idOrAll) {
    final List<String> ids = idOrAll == 'all'
        ? _tokens.keys.toList()
        : (_tokens.containsKey(idOrAll) ? <String>[idOrAll] : <String>[]);
    for (final String id in ids) {
      _revoked[id] = _tokens.remove(id)!.expires;
    }
    return ids;
  }

  /// Moves the expiry of one token, or every token for `all`, to [at].
  /// Returns the ids moved.
  List<String> expireAt(String idOrAll, DateTime at) {
    final List<String> ids = <String>[
      for (final FakeToken token in _tokens.values)
        if (idOrAll == 'all' || token.id == idOrAll) token.id,
    ];
    for (final String id in ids) {
      _tokens[id]!.expires = at;
    }
    return ids;
  }

  /// Ends tokens past their expiry. Returns the ids that expired now.
  List<String> expire() {
    final DateTime time = now();
    final List<String> expired = <String>[
      for (final FakeToken token in _tokens.values)
        if (!time.isBefore(token.expires)) token.id,
    ];
    for (final String id in expired) {
      _revoked[id] = _tokens.remove(id)!.expires;
    }
    _pairings.removeWhere((String id, _Pending pending) => time.isAfter(pending.expires.add(const Duration(hours: 1))));
    return expired;
  }
}
