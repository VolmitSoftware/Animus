/// Help beside a label: a square (i) button named "More info" (it often sits
/// inside a heading, whose name would otherwise repeat the label) and
/// described by the tip. Hover or focus shows the tip (one or two sentences
/// and a Docs link) after a short rest; a click or tap pins it open until
/// Escape, a second click or a click elsewhere. Built on shadcn's hover
/// card, so the tip is a neutral popover surface.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component, InheritedComponent;
import 'package:web/web.dart' as web;

import '../config/docs_links.dart';
import '../logic/help_text.dart';

/// The wiki page a tip without its own link points at: the page of the
/// document kind being edited.
class DocsScope extends InheritedComponent {
  const DocsScope({required this.link, required super.child, super.key});

  final DocLink link;

  static DocLink of(BuildContext context) => context.dependOnInheritedComponentOfExactType<DocsScope>()?.link ?? Docs.editor;

  @override
  bool updateShouldNotify(DocsScope oldComponent) => oldComponent.link.url != link.url;
}

int _nextTipId = 0;

/// How long a resting pointer waits before the tip opens.
const int tipOpenDelayMs = 300;

class InfoButton extends StatefulWidget {
  const InfoButton({required this.tip, this.docs, super.key});

  /// One or two short sentences; longer text is cut to two.
  final String tip;

  /// The wiki heading; null uses the surrounding [DocsScope].
  final DocLink? docs;

  @override
  State<InfoButton> createState() => _InfoButtonState();
}

class _InfoButtonState extends State<InfoButton> {
  final String _id = 'animus-tip-${_nextTipId++}';
  bool _open = false;

  /// Opened by a click or tap: stays until dismissed, not until the
  /// pointer leaves.
  bool _pinned = false;

  /// Closed with Escape while the pointer rests on the button: hover does
  /// not open it again until the pointer leaves.
  bool _dismissed = false;

  void _changed(bool open) {
    if (open && _dismissed) return;
    if (!open && _pinned) {
      // The hover card closes on leave and blur; a pinned tip waits for an
      // explicit close (Escape and outside clicks clear the pin first).
      final web.Element? active = web.document.activeElement;
      final bool inside = active != null && (web.document.getElementById('$_id-wrap')?.contains(active) ?? false);
      if (inside) return;
    }
    if (!open && _pinned) {
      _pinned = false;
      _listen(false);
    }
    if (mounted && open != _open) setState(() => _open = open);
  }

  void _click() {
    _dismissed = false;
    setState(() {
      if (_pinned) {
        _pinned = false;
        _open = false;
      } else {
        _pinned = true;
        _open = true;
      }
    });
    _listen(_pinned);
  }

  /// Escape while pinned. The hover card swallows Escape on its trigger and
  /// asks to close, which [_changed] ignores while pinned, so the key is
  /// caught on the window before it gets there.
  JSFunction? _escape;

  void _listen(bool on) {
    final JSFunction? current = _escape;
    if (current != null) {
      web.window.removeEventListener('keydown', current, web.EventListenerOptions(capture: true));
      _escape = null;
    }
    if (!on) return;
    final JSFunction escape = ((web.KeyboardEvent event) {
      if (event.key != 'Escape' || !_pinned) return;
      event.preventDefault();
      event.stopPropagation();
      _pinned = false;
      _dismissed = true;
      _listen(false);
      if (mounted) setState(() => _open = false);
    }).toJS;
    _escape = escape;
    web.window.addEventListener('keydown', escape, web.AddEventListenerOptions(capture: true));
  }

  @override
  void dispose() {
    _listen(false);
    super.dispose();
  }

  /// A form rebuilds every tip on each keystroke; one whose text and page
  /// did not change keeps what it drew.
  @override
  bool shouldRebuild(InfoButton newComponent) => newComponent.tip != component.tip || newComponent.docs?.url != component.docs?.url;

  @override
  Widget build(BuildContext context) {
    final String url = (component.docs ?? DocsScope.of(context)).url;
    // The text describes the button whether the tip shows or not; the rest
    // of the body is built only while it is open.
    final Widget text = dom.span(id: '$_id-text', <Widget>[Component.text(tipText(component.tip))]);
    return dom.span(
      id: '$_id-wrap',
      classes: 'animus-info${_open ? ' is-open' : ''}',
      events: <String, void Function(web.Event)>{'mouseleave': (web.Event _) => _dismissed = false},
      // The library's 32 px icon button takes no more room than its 16 px
      // glyph: its box meets the label, the glyph 8 px after it, never
      // making a heading taller.
      styles: _snug,
      <Widget>[
        ArcaneHoverCard(
          id: _id,
          isOpen: _open,
          onOpenChange: _changed,
          openDelay: tipOpenDelayMs,
          closeDelay: 150,
          position: FloatingPosition.bottomStart,
          showArrow: false,
          offset: 6,
          maxWidth: 288,
          trigger: Button.ghost(
            icon: ArcaneIcon.info(size: IconSize.sm),
            size: ButtonSize.iconSm,
            attributes: <String, String>{'data-animus-info': '', 'aria-label': 'More info', 'aria-describedby': '$_id-text', 'aria-expanded': '$_open'},
            onPressed: _click,
          ),
          content: dom.div(classes: 'animus-info-tip', <Widget>[
            if (_open)
              // Labels round the button may not wrap; the tip's text does.
              Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, spacing: 8, style: const ArcaneStyleData(whiteSpace: WhiteSpace.normal), children: <Widget>[
                text,
                Button.link(label: 'Docs', href: url, size: ButtonSize.sm, attributes: const <String, String>{'target': '_blank', 'rel': 'noopener'}),
              ])
            else
              text,
          ]),
        ),
      ],
    );
  }
}

/// [label] followed by its (i) button, or [label] alone without a tip.
const dom.Styles _snug = dom.Styles(raw: <String, String>{'margin': '-0.5rem'});

class TipLabel extends StatelessWidget {
  const TipLabel({required this.label, this.tip, this.docs, super.key});

  final Widget label;
  final String? tip;
  final DocLink? docs;

  @override
  Widget build(BuildContext context) {
    final String? tip = this.tip;
    if (tip == null || tip.trim().isEmpty) return label;
    return dom.span(classes: 'animus-tip-label', <Widget>[label, InfoButton(tip: tip, docs: docs)]);
  }
}
