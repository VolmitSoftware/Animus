/// Holds a field's problems back while its box is being typed in, so a
/// half-typed id does not flash red at every key. Problems show once typing
/// pauses for [typingPause] or focus leaves the field.
library;

import 'dart:async';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:web/web.dart' as web;

import '../services/browser.dart';

/// How long typing must pause before a field's problems show.
const Duration typingPause = Duration(milliseconds: 800);

/// For a field row's state: put [typingEvents] on the row's element and
/// render its problems only while [typing] is false.
mixin TypingPause<T extends StatefulWidget> on State<T> {
  bool _typing = false;
  Timer? _pause;

  /// True from a key typed in the row until the pause or a blur.
  bool get typing => _typing;

  /// Listeners for the row's element; `input` and `focusout` bubble to it.
  late final Map<String, void Function(web.Event)> typingEvents = <String, void Function(web.Event)>{
    'input': (web.Event event) {
      if (!isTypingTarget(event.target)) return;
      _pause?.cancel();
      _pause = Timer(typingPause, _settle);
      if (!_typing) setState(() => _typing = true);
    },
    'focusout': (web.Event _) => _settle(),
  };

  void _settle() {
    _pause?.cancel();
    if (mounted && _typing) setState(() => _typing = false);
  }

  @override
  void dispose() {
    _pause?.cancel();
    super.dispose();
  }
}
