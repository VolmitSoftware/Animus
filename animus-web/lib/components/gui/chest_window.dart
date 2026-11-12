/// A chest or shop window painted like the in-game screen: the title in the
/// Minecraft font, slots with item icons from the stage's item meshers,
/// stack counts, and the name and lore tooltip on hover or focus, and on
/// tap for the selected slot when [ChestWindow.pinSelected] is set. A paint
/// handler turns a press and drag across slots into brush strokes. It never
/// paints a half-ready window: a new layout waits, behind the previous one
/// (or hidden on first show), until every icon in it is drawn and decoded.
library;

import 'dart:async';
import 'dart:js_interop';
import 'dart:js_interop_unsafe';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_layout.dart';
import '../../logic/gui/gui_text.dart';
import '../info_tip.dart';
import '../mc/mc_item_icons.dart';
import 'gui_fields.dart';

/// The default inventory title colour (`0x404040`).
const int mcInventoryTitleColor = 0x404040;

/// Lore's default colour, dark purple italic in the client; shown plain here.
const int mcLoreColor = 0xAA00AA;

class ChestWindow extends StatefulWidget {
  const ChestWindow({
    required this.layout,
    this.selected,
    this.onSlot,
    this.showMask = false,
    this.label = 'Chest window',
    this.onPaint,
    this.onPaintEnd,
    this.pinSelected = false,
    super.key,
  });

  /// Called for each slot a press and drag crosses; set while a brush is
  /// chosen. Clicks and Enter still go to [onSlot].
  final void Function(int slot)? onPaint;

  /// Called when the press that painted is released.
  final void Function()? onPaintEnd;

  /// Shows the selected slot's name and lore without hover, for touch.
  final bool pinSelected;

  final ChestLayout layout;
  final int? selected;

  /// Called with the slot a click picks; null makes the window read-only.
  final void Function(int slot)? onSlot;

  /// Marks each slot with its mask character, for editing.
  final bool showMask;
  final String label;

  @override
  State<ChestWindow> createState() => _ChestWindowState();
}

/// Icon URLs whose images are decoded, so a new `img` paints at once.
final Set<String> _decodedIcons = <String>{};
final Set<String> _decoding = <String>{};

class _ChestWindowState extends State<ChestWindow> {
  int? _lastPainted;
  JSFunction? _release;

  /// The last layout whose icons were all ready; drawn while a newer one waits.
  ChestLayout? _shown;

  /// Runs while a new layout waits; once it fires the layout is drawn as it is.
  Timer? _patience;

  @override
  void initState() {
    super.initState();
    McItemIcons.addListener(_refresh);
    _release = ((web.Event _) => _stopPaint()).toJS;
    web.window.addEventListener('pointerup', _release);
    web.window.addEventListener('pointercancel', _release);
  }

  @override
  void dispose() {
    _patience?.cancel();
    McItemIcons.removeListener(_refresh);
    web.window.removeEventListener('pointerup', _release);
    web.window.removeEventListener('pointercancel', _release);
    super.dispose();
  }

  void _stopPaint() {
    if (_lastPainted != null) component.onPaintEnd?.call();
    _lastPainted = null;
  }

  void _paint(int slot) {
    if (slot == _lastPainted) return;
    _lastPainted = slot;
    component.onPaint?.call(slot);
  }

  /// The slot under a pointer, for drags that leave the pressed button
  /// (touch keeps the pointer captured on it).
  int? _slotAt(web.Event event) {
    final web.Element? hit = web.document.elementFromPoint(_number(event, 'clientX'), _number(event, 'clientY'));
    final String? slot = hit?.closest('[data-slot]')?.getAttribute('data-slot');
    return slot == null ? null : int.tryParse(slot);
  }

  /// A numeric event property, read the way the stage reads pointer
  /// coordinates (typed getters can fail on fractional values).
  static double _number(web.Event event, String property) {
    final JSAny? value = (event as JSObject).getProperty<JSAny?>(property.toJS);
    return value != null && value.isA<JSNumber>() ? (value as JSNumber).toDartDouble : 0;
  }

  void _refresh() {
    if (mounted) setState(() {});
  }

  /// True when every icon of [layout] is drawn (or has none) and decoded;
  /// starts whatever is still missing.
  bool _iconsReady(ChestLayout layout) {
    bool ready = true;
    for (final ChestCell cell in layout.cells) {
      final String? key = McItemIcons.keyOf(cell.itemId);
      if (key == null) continue;
      final String? url = McItemIcons.url(key);
      if (url == null) {
        if (!McItemIcons.missing(key)) ready = false;
      } else if (!_decodedIcons.contains(url)) {
        ready = false;
        _decode(url);
      }
    }
    return ready;
  }

  void _decode(String url) {
    if (!_decoding.add(url)) return;
    final web.HTMLImageElement image = web.HTMLImageElement()..src = url;
    void done() {
      _decoding.remove(url);
      _decodedIcons.add(url);
      _refresh();
    }

    image.decode().toDart.then((JSAny? _) => done(), onError: (Object _) => done());
  }

  @override
  Widget build(BuildContext context) {
    final bool ready = _iconsReady(component.layout);
    final bool forced = _patience != null && !_patience!.isActive;
    if (ready || forced) {
      _patience?.cancel();
      _patience = null;
      _shown = component.layout;
    } else {
      _patience ??= Timer(const Duration(milliseconds: 800), _refresh);
    }
    final ChestLayout? shown = _shown;
    // First show with icons still coming: laid out but hidden.
    final ChestLayout layout = shown ?? component.layout;
    return dom.div(classes: 'animus-chest-screen${shown == null ? ' is-pending' : ''}', attributes: <String, String>{'role': 'group', 'aria-label': component.label}, <Widget>[
      dom.div(classes: 'animus-chest-title', <Widget>[
        withTip(
          GuiStyledText(layout.title, defaultColor: mcInventoryTitleColor),
          component.showMask ? 'The window as players see it. Letters mark each slot\'s mask key; click a slot to edit it.' : 'The window as players see it.',
          docs: component.showMask ? Docs.chestGuis : null,
        ),
      ]),
      dom.div(
        classes: 'animus-chest animus-inset-focus${component.onPaint != null ? ' is-painting' : ''}',
        styles: dom.Styles(raw: <String, String>{'grid-template-columns': 'repeat(${layout.width}, var(--animus-slot))'}),
        // Always bound: an events map that only gains a key on a later build
        // never gets that listener.
        events: <String, void Function(web.Event)>{
          'pointermove': (web.Event event) {
            // The pressed button, not a flag: a repaint after each stroke may
            // rebuild this window mid-drag.
            if (component.onPaint == null || (_number(event, 'buttons').toInt() & 1) == 0) return;
            final int? slot = _slotAt(event);
            if (slot != null) _paint(slot);
          },
        },
        <Widget>[for (final ChestCell cell in layout.cells) _slot(cell)],
      ),
    ]);
  }

  Widget _slot(ChestCell cell) {
    final String? item = cell.itemId;
    final String? key = McItemIcons.keyOf(item);
    final String? url = key == null ? null : McItemIcons.url(key);
    final String name = asString(cell.icon['name']) ?? (item ?? '');
    final List<String> lore = stringList(cell.icon['lore']);
    final int count = asInt(cell.icon['count']) ?? 1;
    final bool selected = component.selected == cell.slot;
    final String plain = guiPlainText(name).trim();
    final List<String> plainLore = <String>[for (final String line in lore) guiPlainText(line).trim()]..removeWhere((String line) => line.isEmpty);
    final String described = cell.source == ChestCellSource.empty
        ? 'Slot ${cell.slot}, empty'
        : 'Slot ${cell.slot}, ${plain.isEmpty ? (item ?? 'item') : plain}${count > 1 ? ', $count' : ''}${plainLore.isEmpty ? '' : '. ${plainLore.join('. ')}'}';
    final List<String> classes = <String>[
      'animus-chest-slot',
      'is-${cell.source.name}',
      if (selected) 'is-selected',
      if (cell.hidden) 'is-hidden',
    ];
    final Widget face = dom.span(classes: 'animus-chest-face', <Widget>[
      if (url != null)
        dom.img(src: url, alt: '', attributes: const <String, String>{'draggable': 'false'})
      else if (item != null && item.isNotEmpty)
        dom.span(classes: 'animus-chest-missing', <Widget>[Component.text(_short(item))]),
      if (item != null && count > 1) dom.span(classes: 'animus-chest-count', <Widget>[Component.text('$count')]),
      if (component.showMask && cell.maskChar != null) dom.span(classes: 'animus-chest-key', <Widget>[Component.text(cell.maskChar!)]),
      if (cell.label != null && cell.source == ChestCellSource.list) dom.span(classes: 'animus-chest-key is-list', <Widget>[Component.text(cell.label!)]),
      if (cell.source != ChestCellSource.empty && (plain.isNotEmpty || lore.isNotEmpty))
        dom.span(classes: 'animus-chest-tooltip${selected && component.pinSelected ? ' is-pinned' : ''}', attributes: const <String, String>{'aria-hidden': 'true'}, <Widget>[
          dom.span(classes: 'animus-chest-tooltip-name', <Widget>[GuiStyledText(name)]),
          for (final String line in lore) dom.span(classes: 'animus-chest-tooltip-lore', <Widget>[GuiStyledText(line, defaultColor: mcLoreColor)]),
        ]),
    ]);
    if (component.onSlot == null) {
      return dom.span(classes: classes.join(' '), attributes: <String, String>{'title': described}, <Widget>[face]);
    }
    return dom.button(
      classes: classes.join(' '),
      attributes: <String, String>{'type': 'button', 'aria-label': described, 'aria-pressed': '$selected', 'data-slot': '${cell.slot}'},
      events: <String, void Function(web.Event)>{
        'click': (web.Event _) => component.onSlot!(cell.slot),
        'pointerdown': (web.Event event) {
          if (component.onPaint == null || _number(event, 'button') != 0) return;
          _lastPainted = null;
          _paint(cell.slot);
        },
      },
      <Widget>[face],
    );
  }

  static String _short(String item) {
    final String bare = item.substring(item.indexOf(':') + 1).replaceAll('_', ' ');
    return bare.length > 14 ? '${bare.substring(0, 13)}.' : bare;
  }
}
