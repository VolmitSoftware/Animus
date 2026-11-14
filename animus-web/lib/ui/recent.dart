/// The documents opened last in a workspace, newest first, for the nav's
/// Recent group and the palette's empty search. Kept per browser.
library;

import 'dart:convert';

/// How many recent documents are kept.
const int recentLimit = 6;

String recentStorageKey(String workspaceId) => 'animus.recent.$workspaceId';

/// [key] (`kind:id`) first, then [before] without it, at most [recentLimit].
List<String> pushRecent(List<String> before, String key) => <String>[key, ...before.where((String each) => each != key)].take(recentLimit).toList();

List<String> decodeRecent(String? stored) {
  if (stored == null) return const <String>[];
  try {
    final Object? value = jsonDecode(stored);
    return value is List<Object?> ? <String>[for (final Object? item in value) if (item is String) item].take(recentLimit).toList() : const <String>[];
  } on FormatException {
    return const <String>[];
  }
}

String encodeRecent(List<String> keys) => jsonEncode(keys.take(recentLimit).toList());
