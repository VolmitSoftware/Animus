/// A button that shows only an icon. Its name is mandatory: it is the
/// button's aria-label and the tooltip a pointer or Tab shows. Square at
/// the height of the row it sits in (the surrounding [DensityScope]), with
/// the icon at 16 px.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:web/web.dart' as web;

import 'density.dart';

/// Whether the last key pressed was Tab, so the focus that follows is the
/// person moving through the page. Focus put back by a closing menu,
/// dialog or sheet comes after Escape, Enter or a click, and opens no tip.
bool _tabbed = false;
bool _watching = false;

void _watchKeys() {
  if (_watching) return;
  _watching = true;
  web.document.addEventListener(
    'keydown',
    ((web.KeyboardEvent event) => _tabbed = event.key == 'Tab').toJS,
    true.toJS,
  );
  web.document.addEventListener(
    'pointerdown',
    ((web.Event _) => _tabbed = false).toJS,
    true.toJS,
  );
}

/// Keeps a focus that did not come from Tab away from the tooltip, which
/// opens on any keyboard-visible focus.
final Map<String, void Function(web.Event)> _quietFocus =
    <String, void Function(web.Event)>{
      'focusin': (web.Event event) {
        if (!_tabbed) event.stopPropagation();
      },
    };

class AppIconButton extends StatelessWidget {
  const AppIconButton({
    required this.icon,
    required this.label,
    required this.onPressed,
    this.disabled = false,
    this.pressed,
    this.expanded,
    this.id,
    this.attributes = const <String, String>{},
    this.tipAtEnd = false,
    super.key,
  });

  final ArcaneGlyph icon;

  /// What it does, in a few words ("Reset view").
  final String label;
  final void Function()? onPressed;
  final bool disabled;

  /// For a toggle: `aria-pressed`.
  final bool? pressed;

  /// For a disclosure or menu trigger: `aria-expanded`.
  final bool? expanded;
  final String? id;

  final Map<String, String> attributes;

  /// Opens the tooltip toward the start, for buttons at the end of a row.
  final bool tipAtEnd;

  @override
  Widget build(BuildContext context) {
    _watchKeys();
    return ArcaneTooltip(
      text: label,
      position: tipAtEnd ? FloatingPosition.bottomEnd : FloatingPosition.bottom,
      child: dom.span(styles: _contents, events: _quietFocus, <Widget>[
        Button.ghost(
          id: id,
          icon: icon.sized(controlIconSize),
          size: iconButtonSize(DensityScope.of(context)),
          disabled: disabled || onPressed == null,
          attributes: <String, String>{
            ...attributes,
            'aria-label': label,
            if (pressed != null) 'aria-pressed': '$pressed',
            if (expanded != null) 'aria-expanded': '$expanded',
          },
          onPressed: onPressed,
        ),
      ]),
    );
  }

  static const dom.Styles _contents = dom.Styles(
    raw: <String, String>{'display': 'contents'},
  );
}
