/// Fits a game window (chest, trading screen) to its frame: one CSS zoom on
/// the window, measured from the width the frame gives it and the height
/// the frame may take ([windowRoomHeight]: the band above a form when
/// [WindowFit.band], else a share of the browser window, less the frame's
/// header and status), in steps that keep the pixel art crisp (or the slots
/// tappable on a phone) and never above the natural size ([windowZoom]).
/// Zoom scales the layout box as well, so the space the frame keeps is the
/// scaled window: no gap under it, no overlap, nothing cropped.
library;

import 'dart:js_interop';
import 'dart:math' as math;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:web/web.dart' as web;

import 'window_zoom.dart';

int _nextFitId = 0;

class WindowFit extends StatefulWidget {
  const WindowFit({required this.child, this.band = false, super.key});

  /// The window, laid out at its natural size.
  final Widget child;

  /// The frame sits in the band above a form, which is short.
  final bool band;

  @override
  State<WindowFit> createState() => _WindowFitState();
}

class _WindowFitState extends State<WindowFit> {
  final String _id = 'animus-window-fit-${_nextFitId++}';
  web.ResizeObserver? _observer;
  JSFunction? _onResize;
  bool _queued = false;
  double _zoom = 1;

  @override
  void dispose() {
    _observer?.disconnect();
    if (_onResize != null) web.window.removeEventListener('resize', _onResize);
    super.dispose();
  }

  /// Fits on the next frame: a zoom set inside a resize callback would
  /// resize the observed boxes again within the same frame.
  void _queue() {
    if (_queued) return;
    _queued = true;
    web.window.requestAnimationFrame(
      ((JSNumber _) {
        _queued = false;
        _fit();
      }).toJS,
    );
  }

  void _fit() {
    if (!mounted) return;
    final web.Element? host = web.document.getElementById(_id);
    final web.Element? box = host?.firstElementChild;
    if (host == null || box == null) return;
    if (_observer == null) {
      _observer = web.ResizeObserver(((JSArray<web.ResizeObserverEntry> _, web.ResizeObserver _) => _queue()).toJS);
      _observer!.observe(host);
      _observer!.observe(box);
      // A taller or shorter browser window moves the height cap alone.
      _onResize = ((web.Event _) => _queue()).toJS;
      web.window.addEventListener('resize', _onResize);
    }
    final web.DOMRect shown = box.getBoundingClientRect();
    // What the frame holds besides the window: its header, status line and
    // padding, and anything drawn under the window (a legend). A frame cut
    // to a fixed height still counts what it holds in full.
    final web.Element? frame = host.closest('.ui-stage');
    final double whole = frame == null ? 0 : math.max(frame.scrollHeight.toDouble(), frame.getBoundingClientRect().height);
    final double chrome = frame == null ? 0 : whole - host.getBoundingClientRect().height;
    final double zoom = windowZoom(
      natural: shown.width / _zoom,
      room: host.clientWidth.toDouble(),
      naturalHeight: shown.height / _zoom,
      roomHeight: windowRoomHeight(band: component.band, viewportHeight: web.window.innerHeight.toDouble(), chrome: chrome),
      dpr: web.window.devicePixelRatio,
    );
    if ((zoom - _zoom).abs() > 1e-6) setState(() => _zoom = zoom);
  }

  @override
  Widget build(BuildContext context) {
    // The window may have changed its natural size (a title, a column, a page).
    _queue();
    return dom.div(
      id: _id,
      classes: 'animus-window-fit',
      attributes: const <String, String>{'data-window-fit': ''},
      <Widget>[
        dom.div(styles: _zoom == 1 ? null : dom.Styles(raw: <String, String>{'zoom': '$_zoom'}), <Widget>[component.child]),
      ],
    );
  }
}
