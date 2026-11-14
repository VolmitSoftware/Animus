/// Keyboard for the kit's dialog and sheet while open: focus moves in and
/// back to the opener on close, Tab stays inside, Escape closes. The arcane
/// runtime does this for surfaces it opened itself; these open from Dart
/// state, which the runtime does not track.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:web/web.dart' as web;

/// The open modals, the last one on top: only it takes Escape and Tab, so a
/// confirmation over the Review sheet closes alone.
final List<Object> _openModals = <Object>[];

const String _focusable = 'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), '
    'textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

mixin ModalFocus<T extends StatefulWidget> on State<T> {
  /// The id of the element that holds the modal.
  String get modalId;
  bool get modalOpen;
  void modalClose();

  /// With no field inside, focus lands on the heading instead of a control.
  bool get focusHeading => false;

  web.HTMLElement? _opener;
  JSFunction? _keys;

  @override
  void initState() {
    super.initState();
    if (modalOpen) _opened();
  }

  /// Call from didUpdateWidget with the previous open state.
  void followOpen(bool wasOpen) {
    if (modalOpen && !wasOpen) _opened();
    if (!modalOpen && wasOpen) _closed();
  }

  @override
  void dispose() {
    if (modalOpen) _closed();
    super.dispose();
  }

  List<web.HTMLElement> _inside() {
    final web.NodeList? found = web.document.getElementById(modalId)?.querySelectorAll(_focusable);
    if (found == null) return const <web.HTMLElement>[];
    return <web.HTMLElement>[
      for (int index = 0; index < found.length; index++)
        if ((found.item(index)! as web.HTMLElement).offsetParent != null) found.item(index)! as web.HTMLElement,
    ];
  }

  void _opened() {
    final web.Element? active = web.document.activeElement;
    _opener = active != null && active.isA<web.HTMLElement>() ? active as web.HTMLElement : null;
    _openModals.remove(this);
    _openModals.add(this);
    _keys = ((web.KeyboardEvent event) {
      if (!modalOpen || !identical(_openModals.last, this)) return;
      if (event.key == 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        modalClose();
      } else if (event.key == 'Tab') {
        final List<web.HTMLElement> items = _inside();
        if (items.isEmpty) return;
        final web.Element? now = web.document.activeElement;
        final int at = items.indexWhere((web.HTMLElement item) => identical(item, now));
        if (at < 0 || (event.shiftKey && at == 0) || (!event.shiftKey && at == items.length - 1)) {
          event.preventDefault();
          (event.shiftKey ? items.last : items.first).focus();
        }
      }
    }).toJS;
    web.window.addEventListener('keydown', _keys, web.AddEventListenerOptions(capture: true));
    web.window.requestAnimationFrame(((JSNumber _) {
      final web.Element? holder = web.document.getElementById(modalId);
      if (holder == null || holder.contains(web.document.activeElement)) return;
      final List<web.HTMLElement> items = _inside();
      // The first field, else the heading (a sheet: focusing a row's button
      // would show its tooltip over the sheet), else the first control that
      // is not the corner Close.
      final web.HTMLElement? field = items.where((web.HTMLElement item) => item.matches('input, textarea, select, [role="listbox"]')).firstOrNull;
      final web.Element? heading = focusHeading ? holder.querySelector('[role="dialog"] h2, h2') : null;
      if (field == null && heading != null) {
        heading.setAttribute('tabindex', '-1');
        (heading as web.HTMLElement).focus();
        return;
      }
      (field ?? (items.length > 1 ? items[1] : items.firstOrNull))?.focus();
    }).toJS);
  }

  void _closed() {
    _openModals.remove(this);
    if (_keys != null) web.window.removeEventListener('keydown', _keys, web.EventListenerOptions(capture: true));
    _keys = null;
    final web.HTMLElement? back = _opener;
    _opener = null;
    if (back != null && back.isConnected) {
      web.window.setTimeout((() {
        // Only when focus is lost (on the page or still in this modal): a
        // surface opened meanwhile (Search right after Escape) keeps it.
        final web.Element? now = web.document.activeElement;
        final bool lost = now == null || identical(now, web.document.body) || (web.document.getElementById(modalId)?.contains(now) ?? false);
        if (back.isConnected && lost) back.focus();
      }).toJS, 0.toJS);
    }
  }
}
