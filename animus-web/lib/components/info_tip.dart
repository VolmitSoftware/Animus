/// Help on a label or heading: the text itself carries a short tooltip. It
/// gets a dotted underline and takes keyboard focus. Hover (after a short
/// delay) or focus shows the tooltip (the text's aria-describedby), Escape
/// hides it, Enter opens the wiki page, and a tap toggles it. The tooltip
/// holds a Docs link and stays open while the pointer is over it. A mouse
/// click does nothing of its own, so a label still focuses its control.
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
  const InfoTip({required this.tip, required this.child, this.docs, super.key});

  /// One or two short sentences; longer text is cut to two.
  final String tip;

  /// The visible text that carries the tip.
  final Widget child;

  /// The wiki heading; null uses the surrounding [DocsScope].
  final DocLink? docs;

  @override
  State<InfoTip> createState() => _InfoTipState();
}

class _InfoTipState extends State<InfoTip> {
  final String _id = 'animus-tip-${_nextTipId++}';
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

  bool _inside(web.EventTarget? target) {
    final web.Element? root = web.document.getElementById('$_id-wrap');
    return root != null && target != null && target.isA<web.Node>() && root.contains(target as web.Node);
  }

  void _setOpen(bool open) {
    _stopOutside();
    if (open) {
      _outside = ((web.Event event) {
        if (!_inside(event.target)) _setOpen(false);
      }).toJS;
      web.document.addEventListener('pointerdown', _outside);
    }
    if (mounted) setState(() => _open = open);
    _place();
  }

  /// Keeps the tooltip inside the viewport: it opens under the text and
  /// shifts left when the right edge would pass the window.
  void _place() {
    web.window.setTimeout((() {
      final web.HTMLElement? tip = web.document.getElementById(_id) as web.HTMLElement?;
      final web.HTMLElement? pop = tip?.parentElement as web.HTMLElement?;
      if (pop == null) return;
      pop.style.setProperty('--animus-tip-shift', '0px');
      final web.DOMRect box = pop.getBoundingClientRect();
      final double room = web.document.documentElement!.clientWidth - 8 - box.right;
      final double shift = room < 0 ? (room < -box.left + 8 ? -box.left + 8 : room) : 0;
      pop.style.setProperty('--animus-tip-shift', '${shift}px');
    }).toJS, 0.toJS);
  }

  void _onKey(web.Event raw, String url) {
    final web.KeyboardEvent event = raw as web.KeyboardEvent;
    if (event.key == 'Enter') {
      event.preventDefault();
      event.stopPropagation();
      web.window.open(url, '_blank', 'noopener');
    } else if (event.key == 'Escape' && (_open || !_dismissed)) {
      event.preventDefault();
      event.stopPropagation();
      _stopOutside();
      setState(() {
        _open = false;
        _dismissed = true;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final String url = (component.docs ?? DocsScope.of(context)).url;
    return dom.span(
      id: '$_id-wrap',
      classes: 'animus-tip${_open ? ' is-open' : ''}${_dismissed ? ' is-dismissed' : ''}',
      events: <String, void Function(web.Event)>{
        'pointerenter': (web.Event _) => _place(),
        'pointerleave': (web.Event _) {
          if (_dismissed) setState(() => _dismissed = false);
        },
        'focusin': (web.Event _) => _place(),
        'focusout': (web.Event event) {
          if (_inside((event as web.FocusEvent).relatedTarget)) return;
          if (_dismissed) setState(() => _dismissed = false);
          if (_open && !_touch) _setOpen(false);
        },
      },
      <Widget>[
        dom.span(
          classes: 'animus-tip-text',
          attributes: <String, String>{'tabindex': '0', 'role': 'link', 'aria-describedby': _id},
          events: <String, void Function(web.Event)>{
            'keydown': (web.Event event) => _onKey(event, url),
            'pointerdown': (web.Event raw) => _touch = (raw as web.PointerEvent).pointerType == 'touch',
            'click': (web.Event event) {
              if (!_touch) return;
              // A tap shows the tip instead of focusing a label's control
              // or toggling a section.
              event.preventDefault();
              _setOpen(!_open);
            },
          },
          <Widget>[component.child],
        ),
        // Hidden from the accessibility tree so a heading or label name
        // stays its own text; the text's aria-describedby still reads the
        // tip, and Enter on the text reaches the Docs link's page.
        dom.span(classes: 'animus-tip-pop', attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[
          dom.span(id: _id, attributes: const <String, String>{'role': 'tooltip'}, <Widget>[Component.text(tipText(component.tip))]),
          dom.a(
            href: url,
            classes: 'animus-tip-docs',
            attributes: const <String, String>{'target': '_blank', 'rel': 'noopener', 'tabindex': '-1'},
            const <Widget>[Component.text('Docs')],
          ),
        ]),
      ],
    );
  }
}

/// [text] carrying [tip], or [text] alone when there is no tip.
Widget withTip(Widget text, String? tip, {DocLink? docs}) =>
    tip == null || tip.trim().isEmpty ? text : InfoTip(tip: tip, docs: docs, child: text);
