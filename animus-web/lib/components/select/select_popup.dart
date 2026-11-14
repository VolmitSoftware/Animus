/// The popup shared by the editor's dropdowns: placement next to the
/// control (flipping above when there is room under the top bar, else
/// scrolling the control up; a full-width sheet on phones), the listbox with its groups and footer rows, and the page
/// listeners that close it.
library;

import 'dart:js_interop';
import 'dart:math' as math;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:web/web.dart' as web;

import '../../logic/select/popup_fit.dart';
import '../../logic/select/select_model.dart';
import '../../ui/stack.dart';
import '../gui/gui_stack.dart' show blockTruncated;

/// Widths at which a dropdown opens as a sheet.
const int sheetWidth = 760;

/// Where an open popup sits.
class PopupPlacement {
  const PopupPlacement({required this.sheet, this.up = false, this.left = 0, this.width = 0, this.edge = 0, this.maxHeight = 320});

  /// A full-width sheet at the bottom of the screen.
  final bool sheet;

  /// Above the control rather than below.
  final bool up;
  final double left;
  final double width;

  /// The distance from the viewport's top (below) or bottom (above).
  final double edge;
  final double maxHeight;

  String get classes => sheet ? ' is-sheet' : (up ? ' is-up' : '');

  Map<String, String> get styles => sheet
      ? const <String, String>{}
      : <String, String>{
          'left': '${left.toStringAsFixed(1)}px',
          'width': '${width.toStringAsFixed(1)}px',
          up ? 'bottom' : 'top': '${edge.toStringAsFixed(1)}px',
          'max-height': '${maxHeight.toStringAsFixed(1)}px',
        };
}

/// The bottom of the sticky top bar in viewport px: popups stay under it.
double topBarBottom() {
  final web.Element? bar = web.document.querySelector('.ui-topbar');
  return bar == null ? 0 : math.max(0, bar.getBoundingClientRect().bottom.toDouble());
}

/// Measures the element [anchor] for [fitPopup] and applies the fit's
/// scroll first, so a field near the bottom moves up and its list opens
/// whole between the top bar and the window's bottom. Returns the fit and
/// the anchor's box after any scroll.
(PopupFit, web.DOMRect) fitBelowTopBar(web.Element anchor, double wanted, {bool allowScroll = true}) {
  final double viewHeight = web.window.innerHeight.toDouble();
  PopupBox measure(web.DOMRect rect) =>
      PopupBox(top: rect.top.toDouble(), bottom: rect.bottom.toDouble(), viewHeight: viewHeight, topInset: topBarBottom());
  web.DOMRect rect = anchor.getBoundingClientRect();
  PopupFit fit = fitPopup(measure(rect), wanted, allowScroll: allowScroll);
  if (fit.scroll > 0) {
    web.window.scrollBy(web.ScrollToOptions(top: fit.scroll, behavior: 'instant'));
    rect = anchor.getBoundingClientRect();
    fit = fitPopup(measure(rect), wanted, allowScroll: false);
  }
  return (fit, rect);
}

/// The placement for a popup about [wanted] px tall next to the element
/// with [anchorId].
/// Without [sheet] it stays anchored on phones too (a text field's
/// suggestions, which sit under the field being typed in). With
/// [allowScroll] false the page is left where it is and the popup takes the
/// roomier side, for following the control while the page scrolls.
PopupPlacement placePopup(String anchorId, double wanted, {bool sheet = true, bool allowScroll = true}) {
  final double viewWidth = web.window.innerWidth.toDouble();
  final double viewHeight = web.window.innerHeight.toDouble();
  if (sheet && viewWidth <= sheetWidth) return const PopupPlacement(sheet: true);
  final web.Element? anchor = web.document.getElementById(anchorId);
  if (anchor == null) return const PopupPlacement(sheet: false, left: 8, width: 240, edge: 8);
  final (PopupFit fit, web.DOMRect rect) = fitBelowTopBar(anchor, wanted, allowScroll: allowScroll);
  final double width = math.min(math.max(rect.width, 220), viewWidth - popupMargin * 2);
  final double left = rect.left.clamp(popupMargin, math.max(popupMargin, viewWidth - width - popupMargin)).toDouble();
  return PopupPlacement(
    sheet: false,
    up: fit.up,
    left: left,
    width: width,
    edge: fit.up ? viewHeight - rect.top + 2 : rect.bottom + 2,
    maxHeight: fit.maxHeight,
  );
}

/// About how tall a suggestion popup with these rows is, for placement.
double popupHeight({required int options, required int actions}) => options * 32 + actions * 34 + 18;

/// Whether the box [rect] has left the part of the window under the top bar.
bool _offscreen(web.DOMRect rect) => rect.bottom <= topBarBottom() || rect.top >= web.window.innerHeight;

/// Listens while a popup is open: a press outside [rootId] or a window width
/// change calls [onClose]. A scroll that moves the control [anchorId]
/// (anything but the popup itself) calls [onMove] with the placement for the
/// popup about [wanted] px tall at the control's new spot, and closes it only
/// once the control has scrolled out of sight. A [sheet] ignores scrolling.
/// Returns the stop.
void Function() watchOutside(
  String rootId,
  String popupId,
  String anchorId,
  void Function() onClose, {
  required double wanted,
  required bool sheet,
  required void Function(PopupPlacement placement) onMove,
  bool sheetOnPhones = true,
}) {
  bool inside(web.EventTarget? target, String id) {
    if (target == null || !target.isA<web.Node>()) return false;
    final web.Element? element = web.document.getElementById(id);
    return element != null && element.contains(target as web.Node);
  }

  final JSFunction press = ((web.Event event) {
    if (!inside(event.target, rootId)) onClose();
  }).toJS;
  bool queued = false;
  // A scroll event can arrive for a scroll that ended before the popup
  // opened; only a move of the control since the last look counts.
  web.DOMRect? last = web.document.getElementById(anchorId)?.getBoundingClientRect();
  void follow() {
    queued = false;
    final web.DOMRect? rect = web.document.getElementById(anchorId)?.getBoundingClientRect();
    final web.DOMRect? before = last;
    if (rect != null && before != null && (rect.top - before.top).abs() < 1 && (rect.left - before.left).abs() < 1) return;
    last = rect;
    if (rect == null || _offscreen(rect)) {
      onClose();
      return;
    }
    onMove(placePopup(anchorId, wanted, sheet: sheetOnPhones, allowScroll: false));
  }

  final JSFunction scroll = ((web.Event event) {
    if (sheet || queued || inside(event.target, popupId)) return;
    queued = true;
    web.window.requestAnimationFrame(((JSNumber _) => follow()).toJS);
  }).toJS;
  final int width = web.window.innerWidth;
  // A phone keyboard changes the height only; that keeps the list open.
  final JSFunction resize = ((web.Event _) {
    if (web.window.innerWidth != width) onClose();
  }).toJS;
  final web.AddEventListenerOptions capture = web.AddEventListenerOptions(capture: true);
  web.document.addEventListener('pointerdown', press, capture);
  web.document.addEventListener('scroll', scroll, capture);
  web.window.addEventListener('resize', resize);
  return () {
    web.document.removeEventListener('pointerdown', press, web.EventListenerOptions(capture: true));
    web.document.removeEventListener('scroll', scroll, web.EventListenerOptions(capture: true));
    web.window.removeEventListener('resize', resize);
  };
}

/// The element id of navigable row [index] of the popup [baseId].
String rowId(String baseId, int index) => '$baseId-row-$index';

/// Scrolls the active row of [baseId] into view after the next paint.
void revealRow(String baseId, int index) {
  if (index < 0) return;
  web.window.requestAnimationFrame(((JSNumber _) {
    web.document.getElementById(rowId(baseId, index))?.scrollIntoView(web.ScrollIntoViewOptions(block: 'nearest'));
  }).toJS);
}

/// Opens every folded `<details>` around the element with [id], scrolls it
/// into view and focuses it: where a "+ New" trip comes back to.
void revealAndFocus(String id) {
  web.window.requestAnimationFrame(((JSNumber _) {
    final web.Element? element = web.document.getElementById(id);
    if (element == null) return;
    for (web.Element? at = element.closest('details'); at != null; at = at.parentElement?.closest('details')) {
      (at as web.HTMLDetailsElement).open = true;
    }
    element.scrollIntoView(web.ScrollIntoViewOptions(block: 'center'));
    (element as web.HTMLElement).focus();
  }).toJS);
}

/// A skin's face from its texture [url], cropped by CSS like the NPC list's.
Widget faceThumb(String url) => dom.span(
      classes: 'animus-select-thumb animus-skin-face',
      attributes: const <String, String>{'aria-hidden': 'true'},
      styles: dom.Styles(raw: <String, String>{'background-image': 'url("$url")'}),
      const <Widget>[],
    );

/// The suggestion listbox of a free-text field, with its "+ New" and
/// "Manage" rows under a separator.
class SelectListbox extends StatelessWidget {
  const SelectListbox({
    required this.baseId,
    required this.label,
    required this.rows,
    required this.active,
    required this.onPick,
    required this.onHover,
    this.selected = const <String>{},
    this.actions = const <SelectAction>[],
    this.query = '',
    super.key,
  });

  final String baseId;

  /// The listbox's accessible name.
  final String label;
  final SelectRows rows;
  final int active;
  final Set<String> selected;
  final List<SelectAction> actions;
  final String query;
  final void Function(int index) onPick;
  final void Function(int index) onHover;

  String get listboxId => '$baseId-listbox';

  Widget _row(int index, {required String label, String? hint, String? value, String? action}) {
    final bool isSelected = value != null && selected.contains(value);
    return dom.div(
      id: rowId(baseId, index),
      classes: 'animus-select-option${index == active ? ' is-active' : ''}',
      attributes: <String, String>{
        'role': 'option',
        'aria-selected': '$isSelected',
        'data-value': ?value,
        'data-action': ?action,
      },
      events: <String, void Function(web.Event)>{
        // Keep focus on the control while the pointer picks.
        'mousedown': (web.Event event) => event.preventDefault(),
        'click': (web.Event _) => onPick(index),
        'pointermove': (web.Event _) {
          if (index != active) onHover(index);
        },
      },
      <Widget>[
        Expanded(
          child: stack(spacing: 0, <Widget>[
            blockTruncated(label),
            if (hint != null) mutedText(hint, small: true),
          ]),
        ),
        if (isSelected) ArcaneIcon.check(size: IconSize.sm),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final String typed = query.trim();
    return dom.div(classes: 'animus-select-scroll', <Widget>[
      dom.div(
        id: listboxId,
        attributes: <String, String>{'role': 'listbox', 'aria-label': label},
        <Widget>[
          for (int index = 0; index < rows.options.length; index++)
            _row(index, label: rows.options[index].label, hint: rows.options[index].description, value: rows.options[index].value),
          if (rows.options.isEmpty)
            dom.div(attributes: const <String, String>{'role': 'presentation'}, <Widget>[
              Padding(
                padding: const EdgeInsets.all(8),
                child: Text(typed.isEmpty ? 'Nothing to pick yet' : 'Nothing matches "$typed"', size: FontSize.xs, color: TextColor.muted, cssStyle: const ArcaneStyleData(display: Display.block)),
              ),
            ]),
          if (actions.isNotEmpty) ...<Widget>[
            const ArcaneSeparator(margin: 4),
            dom.div(attributes: const <String, String>{'role': 'group', 'aria-label': 'Actions'}, <Widget>[
              for (int index = 0; index < actions.length; index++)
                _row(rows.options.length + index, label: actions[index].label, hint: actions[index].hint, action: actions[index].id),
            ]),
          ],
        ],
      ),
    ]);
  }
}
