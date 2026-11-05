import 'package:web/web.dart' as web;

import 'server_profiles.dart';

/// localStorage or sessionStorage; private modes that refuse storage keep
/// values for the page only.
class BrowserKeyValueStore implements KeyValueStore {
  BrowserKeyValueStore.local() : _storage = _safe(() => web.window.localStorage);

  BrowserKeyValueStore.session() : _storage = _safe(() => web.window.sessionStorage);

  final web.Storage? _storage;
  final Map<String, String> _fallback = <String, String>{};

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
  void write(String key, String value) {
    _fallback[key] = value;
    try {
      _storage?.setItem(key, value);
    } on Object {
      // Kept in memory for this page.
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
