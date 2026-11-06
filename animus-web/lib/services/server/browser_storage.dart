import 'dart:async';
import 'dart:js_interop';

import 'package:web/web.dart' as web;

import 'server_profiles.dart';

/// localStorage or sessionStorage. A browser that refuses storage (a private
/// window, a full quota) keeps values for this page only, and [write] says so.
class BrowserKeyValueStore implements KeyValueStore {
  BrowserKeyValueStore.local() : _storage = _safe(() => web.window.localStorage) {
    // Other tabs' writes to localStorage arrive as storage events.
    web.window.addEventListener('storage', ((web.StorageEvent event) => _changes.add(event.key)).toJS);
  }

  BrowserKeyValueStore.session() : _storage = _safe(() => web.window.sessionStorage);

  final web.Storage? _storage;
  final Map<String, String> _fallback = <String, String>{};
  final StreamController<String?> _changes = StreamController<String?>.broadcast(sync: true);

  @override
  Stream<String?> get changes => _changes.stream;

  static web.Storage? _safe(web.Storage Function() open) {
    try {
      return open();
    } on Object {
      return null;
    }
  }

  @override
  String? read(String key) {
    try {
      return _storage?.getItem(key) ?? _fallback[key];
    } on Object {
      return _fallback[key];
    }
  }

  @override
  bool write(String key, String value) {
    _fallback[key] = value;
    final web.Storage? storage = _storage;
    if (storage == null) return false;
    try {
      storage.setItem(key, value);
      return true;
    } on Object {
      // Kept in memory for this page.
      return false;
    }
  }

  @override
  void remove(String key) {
    _fallback.remove(key);
    try {
      _storage?.removeItem(key);
    } on Object {
      // Nothing stored.
    }
  }
}
