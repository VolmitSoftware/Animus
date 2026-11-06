/// The one info control: a square "i" next to a label. Hover or keyboard
/// focus shows a short tooltip (the link's aria-describedby), a click opens
/// the wiki page, and a tap on a touch screen toggles the tooltip, which then
/// carries the wiki link. Escape hides it.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component, InheritedComponent;
import 'package:web/web.dart' as web;

import '../config/docs_links.dart';
import '../logic/help_text.dart';

int _nextTipId = 0;

/// The wiki page an [InfoTip] without its own link points at: the page of
/// the document kind being edited.
class DocsScope extends InheritedComponent {
  const DocsScope({required this.link, required super.child, super.key});

  final DocLink link;

  static DocLink of(BuildContext context) => context.dependOnInheritedComponentOfExactType<DocsScope>()?.link ?? Docs.editor;

  @override
  bool updateShouldNotify(DocsScope oldComponent) => oldComponent.link.url != link.url;
}

class InfoTip extends StatefulWidget {
  const InfoTip({required this.text, required this.topic, this.docs, super.key});

  /// One or two short sentences; longer text is cut to two.
  final String text;

  /// What the tip is about, for the link's accessible name ("Folder docs").
  final String topic;

  /// The wiki heading; null uses the surrounding [DocsScope].
  final DocLink? docs;

  @override
  State<InfoTip> createState() => _InfoTipState();
}

class _InfoTipState extends State<InfoTip> {
  final String _id = 'animus-info-${_nextTipId++}';
  bool _open = false;
  bool _dismissed = false;
  bool _touch = false;
  JSFunction? _outside;

  @override
  void dispose() {
    _stopOutside();
    super.dispose();
  }

  void _stopOutside() {
    if (_outside != null) web.document.removeEventListener('pointerdown', _outside);
    _outside = null;
  }

  void _setOpen(bool open) {
    _stopOutside();
    if (open) {
      _outside = ((web.Event event) {
        final web.Element? root = web.document.getElementById('$_id-wrap');
        final web.EventTarget? target = event.target;
        if (root != null && target != null && target.isA<web.Node>() && root.contains(target as web.Node)) return;
        _setOpen(false);
      }).toJS;
      web.document.addEventListener('pointerdown', _outside);
    }
    if (mounted) setState(() => _open = open);
    _place();
  }

  /// Keeps the tooltip inside the viewport: it opens under the button and
  /// shifts left when the right edge would pass the window.
  void _place() {
    web.window.setTimeout((() {
      final web.HTMLElement? tip = web.document.getElementById(_id) as web.HTMLElement?;
      final web.HTMLElement? pop = tip?.parentElement as web.HTMLElement?;
      if (pop == null) return;
      pop.style.setProperty('--animus-info-shift', '0px');
      final web.DOMRect box = pop.getBoundingClientRect();
      final double room = web.document.documentElement!.clientWidth - 8 - box.right;
      final double shift = room < 0 ? (room < -box.left + 8 ? -box.left + 8 : room) : 0;
      pop.style.setProperty('--animus-info-shift', '${shift}px');
    }).toJS, 0.toJS);
  }

  @override
  Widget build(BuildContext context) {
    final DocLink link = component.docs ?? DocsScope.of(context);
    return dom.span(
      id: '$_id-wrap',
      classes: 'animus-info${_open ? ' is-open' : ''}${_dismissed ? ' is-dismissed' : ''}',
      events: <String, void Function(web.Event)>{
        'pointerenter': (web.Event _) => _place(),
        'pointerleave': (web.Event _) {
          if (_dismissed) setState(() => _dismissed = false);
        },
        'focusin': (web.Event _) => _place(),
        'focusout': (web.Event event) {
          final web.EventTarget? next = (event as web.FocusEvent).relatedTarget;
          final web.Element? root = web.document.getElementById('$_id-wrap');
          if (next != null && root != null && next.isA<web.Node>() && root.contains(next as web.Node)) return;
          if (_dismissed) setState(() => _dismissed = false);
          if (_open) _setOpen(false);
        },
        'keydown': (web.Event raw) {
          if ((raw as web.KeyboardEvent).key != 'Escape') return;
          if (_open || !_dismissed) {
            raw.preventDefault();
            raw.stopPropagation();
            _stopOutside();
            setState(() {
              _open = false;
              _dismissed = true;
            });
          }
        },
      },
      <Widget>[
        dom.a(
          href: link.url,
          classes: 'animus-info-button',
          attributes: <String, String>{
            'target': '_blank',
            'rel': 'noopener',
            'aria-label': '${component.topic} docs',
            'aria-describedby': _id,
          },
          events: <String, void Function(web.Event)>{
            'pointerdown': (web.Event raw) => _touch = (raw as web.PointerEvent).pointerType == 'touch',
            'click': (web.Event event) {
              if (!_touch) return;
              event.preventDefault();
              _setOpen(!_open);
            },
          },
          const <Widget>[Component.text('i')],
        ),
        dom.span(classes: 'animus-info-pop', <Widget>[
          dom.span(id: _id, attributes: const <String, String>{'role': 'tooltip'}, <Widget>[Component.text(tipText(component.text))]),
          if (_open)
            dom.a(
              href: link.url,
              classes: 'animus-info-docs',
              attributes: const <String, String>{'target': '_blank', 'rel': 'noopener'},
              const <Widget>[Component.text('Docs')],
            ),
        ]),
      ],
    );
  }
}

/// [label] followed by an [InfoTip] when there is [tip] text.
Widget labelWithTip(Widget label, String? tip, String topic, {DocLink? docs}) {
  if (tip == null || tip.trim().isEmpty) return label;
  return dom.span(classes: 'animus-label-tip', <Widget>[label, InfoTip(text: tip, topic: topic, docs: docs)]);
}
