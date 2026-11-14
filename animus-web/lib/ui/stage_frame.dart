/// The frame every preview sits in: a header with the title, toggles
/// (Terrain, Route, Radius) and Reset view and Fullscreen; the body; one
/// status line under it. The frame never scrolls: its height follows what
/// it shows, and the page scrolls when that is taller than the window. The
/// body keeps a 4:3 box (never under 240 px nor over 60% of the window) for
/// a canvas, and for the skeleton, the empty line or the failure alert
/// while the preview is not ready. [StageSide] is the pane that holds it
/// beside a form.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:web/web.dart' as web;

import 'action_row.dart';
import 'alert.dart';
import 'info_tip.dart';
import 'menu.dart';
import 'skeleton.dart';

enum StageState { ready, loading, empty, error }

int _nextStageId = 0;

class StageFrame extends StatefulWidget {
  const StageFrame({
    required this.title,
    required this.child,
    this.toggles = const <Widget>[],
    this.actions = const <RowAction>[],
    this.primary,
    this.more = const <AppMenuEntry>[],
    this.onReset,
    this.fullscreen = true,
    this.status,
    this.statusTip,
    this.state = StageState.ready,
    this.emptyMessage = 'Nothing to preview',
    this.error,
    this.onRetry,
    this.free = false,
    this.collapsible = false,
    super.key,
  });

  final String title;

  /// The canvas, or whatever the preview draws.
  final Widget child;

  /// Controls that change what the canvas shows (a ToggleChoice), left in
  /// the header after the title.
  final List<Widget> toggles;

  /// Header buttons, before Reset view and Fullscreen.
  final List<RowAction> actions;

  /// The frame's one filled button, last in the header (the Test's Run).
  final RowAction? primary;

  /// Rarer view options, in the header's "..." menu.
  final List<AppMenuEntry> more;

  /// Puts the camera back; null hides Reset view.
  final void Function()? onReset;

  /// Offers Fullscreen for the whole frame.
  final bool fullscreen;

  /// One short line under the canvas; details go in [statusTip].
  final String? status;
  final String? statusTip;
  final StageState state;
  final String emptyMessage;

  /// What failed, for [StageState.error].
  final String? error;
  final void Function()? onRetry;

  /// Lets the content set its own height (a chest window, a dry run) instead
  /// of the 4:3 box.
  final bool free;

  /// Adds Hide and Show to the header (the band above a form at 761-1099 px).
  final bool collapsible;

  @override
  State<StageFrame> createState() => _StageFrameState();
}

class _StageFrameState extends State<StageFrame> {
  final String _id = 'ui-stage-${_nextStageId++}';
  bool _collapsed = false;

  Future<void> _toggleFullscreen() async {
    final web.Element? frame = web.document.getElementById(_id);
    if (frame == null) return;
    try {
      if (web.document.fullscreenElement != null) {
        await web.document.exitFullscreen().toDart;
      } else {
        await frame.requestFullscreen().toDart;
      }
    } on Object {
      // The browser refused (a frame without permission, a kiosk): the preview stays as it is.
      ArcaneSonner.info('Full screen is not available here.');
    }
  }

  Widget _body() => switch (component.state) {
        StageState.loading => const Skeleton(height: '100%', label: 'Loading the preview'),
        StageState.empty => Center(child: Text(component.emptyMessage, color: TextColor.muted)),
        StageState.error => AppAlert(
            tone: AlertTone.error,
            message: component.error ?? 'The preview could not load.',
            live: true,
            action: component.onRetry == null ? null : Button.outline(label: 'Retry', size: ButtonSize.sm, onPressed: component.onRetry),
          ),
        StageState.ready => component.child,
      };

  /// The frame is the library's flat card, its parts one per grid row with
  /// the library's separators between them.
  @override
  Widget build(BuildContext context) => Card(
        variant: CardVariant.flat,
        padding: EdgeInsets.zero,
        classes: 'ui-stage',
        attributes: <String, String>{'id': _id, 'role': 'region', 'aria-label': component.title},
        styles: _frame,
        children: <Widget>[
          dom.header(classes: 'ui-stage-head', <Widget>[
            Padding(
              padding: const EdgeInsets.only(left: 12, top: 6, right: 8, bottom: 6),
              child: ActionRow(
                dense: true,
                more: component.more,
                leading: <Widget>[
                  Text(component.title, element: 'h2', size: FontSize.sm, weight: FontWeight.w600, whiteSpace: WhiteSpace.nowrap, cssStyle: const ArcaneStyleData(margin: MarginPreset.none)),
                  ...component.toggles,
                ],
                secondary: <RowAction>[
                  ...component.actions,
                  if (component.onReset != null) RowAction('Reset view', component.onReset, icon: ArcaneIcon.rotateCcw(), iconOnly: true),
                  if (component.fullscreen && !_collapsed) RowAction('Fullscreen', _toggleFullscreen, icon: ArcaneIcon.maximize(), iconOnly: true),
                  if (component.collapsible)
                    RowAction(
                      _collapsed ? 'Show ${component.title.toLowerCase()}' : 'Hide ${component.title.toLowerCase()}',
                      () => setState(() => _collapsed = !_collapsed),
                      icon: _collapsed ? ArcaneIcon.chevronDown() : ArcaneIcon.chevronUp(),
                      iconOnly: true,
                      expanded: !_collapsed,
                    ),
                ],
                primary: component.primary,
              ),
            ),
          ]),
          if (!_collapsed) ...<Widget>[
            const ArcaneSeparator(),
            dom.div(classes: 'ui-stage-canvas${component.free && component.state == StageState.ready ? '' : ' is-box'}', <Widget>[_body()]),
          ],
          if (component.status != null && !_collapsed) ...<Widget>[
            const ArcaneSeparator(),
            dom.footer(classes: 'ui-stage-status', <Widget>[
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                child: TipLabel(label: Text(component.status!, size: FontSize.sm, color: TextColor.muted), tip: component.statusTip),
              ),
            ]),
          ],
        ],
      );
}

/// One column that clips, so a canvas never widens the frame, without
/// making the frame a scroll box.
const ArcaneStyleData _frame = ArcaneStyleData(display: Display.grid, gridColumnsCustom: 'minmax(0, 1fr)', minWidth: '0', overflow: Overflow.clip);

int _nextSideId = 0;

/// The pane beside a form that holds the preview. It stays in view while the
/// form scrolls; a preview taller than the page stays by its bottom edge
/// instead, so every part of it can be scrolled into view.
class StageSide extends StatefulWidget {
  const StageSide({required this.child, super.key});

  final Widget child;

  @override
  State<StageSide> createState() => _StageSideState();
}

class _StageSideState extends State<StageSide> {
  final String _id = 'ui-side-${_nextSideId++}';
  web.ResizeObserver? _observer;
  double _top = 0;

  @override
  void initState() {
    super.initState();
    web.window.requestAnimationFrame(((JSNumber _) => _observe()).toJS);
  }

  @override
  void dispose() {
    _observer?.disconnect();
    super.dispose();
  }

  void _observe() {
    final web.Element? side = web.document.getElementById(_id);
    final web.Element? page = side?.closest('.ui-page');
    if (!mounted || side == null || page == null) return;
    _observer = web.ResizeObserver(((JSArray<web.ResizeObserverEntry> _, web.ResizeObserver _) => _measure()).toJS)
      ..observe(side)
      ..observe(page);
  }

  void _measure() {
    final web.Element? side = web.document.getElementById(_id);
    final web.Element? page = side?.closest('.ui-page');
    if (!mounted || side == null || page == null) return;
    final double over = page.clientHeight - side.getBoundingClientRect().height;
    final double top = over < 0 ? over.floorToDouble() : 0;
    if (top != _top) setState(() => _top = top);
  }

  @override
  Widget build(BuildContext context) => dom.div(
        id: _id,
        classes: 'ui-doc-side',
        styles: _top < 0 ? dom.Styles(raw: <String, String>{'top': '${_top.toStringAsFixed(0)}px'}) : null,
        <Widget>[component.child],
      );
}
