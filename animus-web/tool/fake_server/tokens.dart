import 'dart:convert';
import 'dart:math';

import 'package:animus_editor/config/shared_schemas.g.dart';
import 'package:animus_editor/model/document_kind.dart';

import 'protocol.dart';

/// The editor protocol's scopes, in schema order.
final List<String> allScopes = () {
  final Map<String, Object?> schema = jsonDecode(sharedSchemaSources['editor-v1.schema.json']!) as Map<String, Object?>;
  final Map<String, Object?> scope = (schema[r'$defs']! as Map<String, Object?>)['scope']! as Map<String, Object?>;
  return <String>[for (final Object? name in scope['enum']! as List<Object?>) name! as String];
}();

/// What a pairing grants without `scopes=`: everything but apply:commands.
final List<String> defaultScopes = <String>[for (final String scope in allScopes) if (scope != 'apply:commands') scope];

/// The scopes a `/__fake/pair?scopes=` list grants. A list with read is the
/// whole grant, as `/animus editor pair scopes=`; one without read adds to
/// the defaults, so `scopes=apply:commands` opts in and `scopes=import`
/// changes nothing.
List<String> grantedScopes(List<String> named) {
  if (named.isEmpty) return defaultScopes;
  final Set<String> granted = named.contains('read') ? named.toSet() : <String>{...defaultScopes, ...named};
  return <String>[for (final String scope in allScopes) if (granted.contains(scope)) scope];
}

/// An NPC folder below `npcs/`, as NpcPaths.FOLDER without the empty root.
final RegExp npcFolderPattern = RegExp(r'^[a-z0-9][a-z0-9_-]{0,63}(?:/[a-z0-9][a-z0-9_-]{0,63}){0,7}$');

/// The NPC folder of an NPC id: everything before its last slash.
String npcFolder(String id) => id.substring(0, max(0, id.lastIndexOf('/')));

/// A paired browser's token. Only the SHA-256 of the token text is kept.
class FakeToken {
  FakeToken(this.id, this.hash, this.scopes, this.groups, this.created, this.lifetime, this.client) : expires = created.add(lifetime);

  final String id;
  final String hash;
  final List<String> scopes;

  /// The NPC folders a `folders=` pairing limits the token to; empty
  /// reaches every folder.
  final List<String> groups;
  final DateTime created;

  /// How long the token lives after its last use.
  final Duration lifetime;

  /// Moved forward by every authenticated request and by `/__fake/expire`.
  DateTime expires;
  final String? client;
  int counter = 0;
  DateTime? lastUsed;

  bool has(String scope) => scopes.contains(scope);

  /// True when the token may touch NPCs in [folder]: one of its groups or a
  /// folder inside one.
  bool group(String folder) => groups.isEmpty || groups.any((String group) => folder == group || folder.startsWith('$group/'));

  /// Every document but an NPC outside the token's folders, as EditorApi.visible.
  bool sees(DocumentKind kind, String id) => kind != DocumentKind.npc || group(npcFolder(id));

  /// Refuses an NPC outside the token's folders with `group-denied`.
  void reach(DocumentKind kind, String id) {
    if (!sees(kind, id)) throw ApiError('group-denied', 'This token cannot reach NPC folder npcs/${npcFolder(id)}.');
  }

  Map<String, Object?> get state => <String, Object?>{
        'id': id,
        'counter': counter,
        'scopes': scopes,
        if (groups.isNotEmpty) 'groups': groups,
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
  const _Pending(this.secretHash, this.expires, this.scopes, this.groups, this.tokenDays);

  final String secretHash;
  final DateTime expires;

  /// The token's lifetime, the pairing default unless a test asked.
  final num tokenDays;

  /// What the token is granted.
  final List<String> scopes;

  /// The NPC folders the token is limited to; empty for all.
  final List<String> groups;
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

  /// A fresh one-time secret, good for `secretMinutes`; its token gets
  /// [scopes], the defaults unless a test asked, limited to the NPC
  /// [groups] when any are given.
  Pairing pair([List<String>? scopes, num? tokenDays, List<String> groups = const <String>[]]) {
    final String id = 'tok_${randomText(12)}';
    final String secret = randomText(32);
    final DateTime expires = now().add(Duration(minutes: pairingDefaults['secretMinutes']! as int));
    _pairings[id] = _Pending(sha256Text(secret), expires, scopes ?? defaultScopes, groups, tokenDays ?? (pairingDefaults['defaultTokenDays']! as int));
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
    final FakeToken token = FakeToken(tokenId, sha256Text(text), pending.scopes, pending.groups, time, Duration(minutes: (pending.tokenDays * 1440).round()), client);
    _tokens[tokenId] = token;
    return (text, token);
  }

  /// The token a bearer value names, or why it is refused. Its expiry
  /// slides to a full lifetime from now, never earlier.
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
    final DateTime slid = time.add(token.lifetime);
    if (slid.isAfter(token.expires)) token.expires = slid;
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
