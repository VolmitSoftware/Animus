/// The browser side of [PendingProblems]: one shared instance, and the
/// focus that starts and ends a field's pending state.
library;

import 'dart:js_interop';

import 'package:web/web.dart' as web;

import '../logic/pending_problems.dart';

/// The open document's pending problems; the document view filters by it.
final PendingProblems pendingProblems = PendingProblems();

JSFunction? _leave;

/// The next edit adds an empty field at element [id]. Its problems are held
/// until focus leaves that element; with [focus], the first control inside
/// the element with that id is focused once the page shows it. Gives up
/// after about a second when the element never shows or never takes focus.
void holdField(String id, {String? focus}) {
  _stop();
  pendingProblems.expect(id);
  int tries = 0;
  void attempt() {
    if (pendingProblems.field != id) return;
    final web.Element? element = web.document.getElementById(id);
    final web.Element? target = focus == null ? null : web.document.getElementById(focus);
    if (target != null && !_holds(target, web.document.activeElement)) {
      final web.Element? control = _firstControl(target);
      if (control != null) (control as web.HTMLElement).focus();
    }
    final bool focused = focus == null || (target != null && _holds(target, web.document.activeElement));
    if (element != null && focused && _holds(element, web.document.activeElement)) {
      _leave = ((web.Event raw) {
        final web.FocusEvent event = raw as web.FocusEvent;
        final web.Element? now = web.document.getElementById(id);
        if (now == null || !_holds(now, event.target) || _holds(now, event.relatedTarget)) return;
        _stop();
        if (pendingProblems.field == id) pendingProblems.release();
      }).toJS;
      web.document.addEventListener('focusout', _leave);
      return;
    }
    if (++tries > 70) {
      if (pendingProblems.field == id) pendingProblems.release();
      return;
    }
    web.window.setTimeout(attempt.toJS, 16.toJS);
  }

  web.window.setTimeout(attempt.toJS, 0.toJS);
}

void _stop() {
  if (_leave != null) web.document.removeEventListener('focusout', _leave);
  _leave = null;
}

bool _holds(web.Element element, web.EventTarget? target) =>
    target != null && target.isA<web.Node>() && element.contains(target as web.Node);

/// A text box inside [element] first, else any control.
web.Element? _firstControl(web.Element element) {
  if (element.matches('input, textarea, button, [tabindex]')) return element;
  return element.querySelector('input:not([type=hidden]), textarea') ?? element.querySelector('button, [tabindex="0"]');
}
