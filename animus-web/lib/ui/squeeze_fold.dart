/// Tab strips and toggle groups never scroll sideways: when the row they sit
/// in squeezes them, measured after layout, they turn into a dropdown, and
/// when that row grows they try the strip again.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:web/web.dart' as web;

mixin SqueezeFold<T extends StatefulWidget> on State<T> {
  /// The strip's element id.
  String get squeezeId;

  /// The strip did not fit and shows as a dropdown.
  bool folded = false;
  double _foldedWidth = 0;
  web.Element? _watched;
  web.ResizeObserver? _observer;

  /// Call from build: measures once the strip is laid out.
  void measureSqueeze() {
    web.window.requestAnimationFrame(((JSNumber _) => _check()).toJS);
  }

  double _room(web.Element strip) => strip.parentElement?.getBoundingClientRect().width ?? 0;

  void _check() {
    if (!mounted) return;
    final web.Element? strip = web.document.getElementById(squeezeId);
    if (strip == null) return;
    _watch(strip);
    if (!folded && _squeezed(strip)) {
      _foldedWidth = _room(strip);
      setState(() => folded = true);
    }
  }

  /// The strip overflows, or the row shrank its items until their labels
  /// are cut ("Trad" for "Trading screen").
  bool _squeezed(web.Element strip) {
    if (strip.scrollWidth > strip.clientWidth + 1) return true;
    final web.NodeList items = strip.querySelectorAll('button, [role="tab"]');
    for (int index = 0; index < items.length; index++) {
      final web.Element item = items.item(index)! as web.Element;
      if (item.scrollWidth > item.clientWidth + 1) return true;
    }
    return false;
  }

  /// Watches the strip and its row: a row that grows lets a dropdown try
  /// the strip again; a strip squeezed later (a sibling appeared, fonts
  /// arrived) folds then, not only after a build.
  void _watch(web.Element strip) {
    if (strip == _watched) return;
    _observer?.disconnect();
    _watched = strip;
    _observer = web.ResizeObserver(((JSArray<web.ResizeObserverEntry> _, web.ResizeObserver _) => _resized()).toJS);
    _observer!.observe(strip);
    final web.Element? parent = strip.parentElement;
    if (parent != null) _observer!.observe(parent);
  }

  void _resized() {
    if (!mounted) return;
    final web.Element? strip = web.document.getElementById(squeezeId);
    if (strip == null) return;
    if (folded) {
      if (_room(strip) > _foldedWidth + 1) setState(() => folded = false);
    } else if (_squeezed(strip)) {
      _foldedWidth = _room(strip);
      setState(() => folded = true);
    }
  }

  @override
  void dispose() {
    _observer?.disconnect();
    super.dispose();
  }
}
