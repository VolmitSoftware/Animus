/// The one list: search, filter chips, a sort menu, a sticky head and rows
/// drawn only where the viewport is (windowed), so 1500 rows scroll as
/// smoothly as 15. A row is clickable as a whole. With [selectable] rows
/// carry a checkbox and a bulk bar sits at the bottom while any are picked.
///
/// Keys on the focused list: Up and Down (Page Up, Page Down, Home, End)
/// move, Enter opens, Space picks a row when the list is selectable.
///
/// arcane_jaspr's ArcaneVirtualScroll needs its height in pixels (a fluid
/// list shows blank rows past 400 px) and cannot scroll to a row, so the
/// windowing is done here.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../logic/plural.dart';
import '../services/browser.dart';
import 'action_row.dart';
import 'alert.dart';
import 'badge.dart';
import 'density.dart';
import 'empty_state.dart';
import 'library_query.dart';
import 'menu.dart';
import 'mode.dart';
import 'skeleton.dart';
import 'toggle_choice.dart';

/// An action on the picked rows ("Despawn", "Delete").
class LibraryBulkAction {
  const LibraryBulkAction(this.label, this.run, {this.destructive = false});

  final String label;
  final void Function(List<LibraryRow> rows) run;
  final bool destructive;
}

int _nextListId = 0;

class LibraryList extends StatefulWidget {
  const LibraryList({
    required this.label,
    required this.rows,
    this.noun = 'item',
    this.filters = const <LibraryFilter>[],
    this.sorts = const <LibrarySort>[LibrarySort.name],
    this.selectable = false,
    this.bulkActions = const <LibraryBulkAction>[],
    this.loading = false,
    this.error,
    this.onRetry,
    this.emptyMessage = 'Nothing here yet.',
    this.emptyAction,
    this.onOpen,
    this.rowHeight = 56,
    this.fill = true,
    this.id,
    this.toolbar = const <Widget>[],
    this.framed = true,
    this.grow = false,
    super.key,
  });

  /// The list's name ("Shops").
  final String label;
  final List<LibraryRow> rows;

  /// What one row is, for the count line ("shop" gives "107 shops").
  final String noun;
  final List<LibraryFilter> filters;
  final List<LibrarySort> sorts;
  final bool selectable;
  final List<LibraryBulkAction> bulkActions;

  /// Rows are still coming: skeleton rows instead of the list.
  final bool loading;

  /// Why the rows could not be read; shown with Retry.
  final String? error;
  final void Function()? onRetry;

  /// One line for a list with no rows at all, and the action that adds one.
  final String emptyMessage;
  final Widget? emptyAction;

  /// Opens a row; without it a row goes to its href.
  final void Function(LibraryRow row)? onOpen;
  final double rowHeight;

  /// Fills its parent's height (a list page). False is the in-page mode for
  /// a page section, dialog or sheet: as tall as its rows up to a cap
  /// (lower inside a dialog or sheet), windowed and scrolling past it.
  final bool fill;
  final String? id;

  /// More filters (dropdowns) at the start of the list's bar, so every way
  /// to narrow the list sits in one row of its card.
  final List<Widget> toolbar;

  /// Draws the list's own outlined card. Off inside a dialog or sheet,
  /// which is the surface already (no card in a card).
  final bool framed;

  /// In-page and as tall as all its rows: no cap and no scroll of its own,
  /// for a list the page scrolls instead (a route's points).
  final bool grow;

  @override
  State<LibraryList> createState() => _LibraryListState();
}

class _LibraryListState extends State<LibraryList> {
  late final String _id = component.id ?? 'ui-library-${_nextListId++}';
  String _search = '';
  Set<String> _filters = <String>{};
  late String _sort = component.sorts.isEmpty ? LibrarySort.name.id : component.sorts.first.id;
  Set<String> _picked = <String>{};
  int _active = 0;
  double _scrollTop = 0;
  double _viewport = 640;
  web.ResizeObserver? _observer;
  web.Element? _observed;

  List<LibraryRow>? _cachedFrom;
  String _cachedKey = '';
  List<LibraryRow> _cached = const <LibraryRow>[];

  String get _viewportId => '$_id-rows';

  @override
  void dispose() {
    _observer?.disconnect();
    super.dispose();
  }

  /// The rows after search, filters and sort, kept until one of them changes.
  List<LibraryRow> get _shown {
    final String key = '$_search\u0000${(_filters.toList()..sort()).join(',')}\u0000$_sort';
    if (!identical(_cachedFrom, component.rows) || key != _cachedKey) {
      _cached = queryRows(component.rows, search: _search, filters: _filters, sort: _sort);
      _cachedFrom = component.rows;
      _cachedKey = key;
    }
    return _cached;
  }

  /// Runs a frame after a build, perhaps after the list went away (a search
  /// or a page change), so it checks it is still mounted first.
  void _observe() {
    if (!mounted) return;
    final web.Element? viewport = web.document.getElementById(_viewportId);
    if (viewport == null || identical(viewport, _observed)) return;
    _observer?.disconnect();
    _observer = web.ResizeObserver(((JSArray<web.ResizeObserverEntry> _, web.ResizeObserver _) => _measure()).toJS);
    _observer!.observe(viewport);
    _observed = viewport;
    _measure();
  }

  void _measure() {
    final web.Element? viewport = web.document.getElementById(_viewportId);
    if (viewport == null || !mounted) return;
    final double height = viewport.clientHeight.toDouble();
    if (height > 0 && (height - _viewport).abs() >= 1) setState(() => _viewport = height);
  }

  void _open(LibraryRow row) {
    if (component.onOpen != null) {
      component.onOpen!(row);
    } else if (row.href != null) {
      navigate(row.href!);
    }
  }

  void _toggle(LibraryRow row) {
    setState(() => _picked = _picked.contains(row.key) ? (<String>{..._picked}..remove(row.key)) : <String>{..._picked, row.key});
  }

  /// Makes row [index] active and scrolls it into the viewport.
  void _activate(int index, int count) {
    if (count == 0) return;
    final int next = index.clamp(0, count - 1);
    setState(() => _active = next);
    final web.Element? viewport = web.document.getElementById(_viewportId);
    if (viewport == null) return;
    final double top = next * component.rowHeight;
    final double bottom = top + component.rowHeight;
    final double head = _headHeight(viewport);
    if (top < viewport.scrollTop + head) {
      viewport.scrollTop = top;
    } else if (bottom > viewport.scrollTop + viewport.clientHeight) {
      viewport.scrollTop = bottom - viewport.clientHeight + head;
    }
  }

  double _headHeight(web.Element viewport) => (viewport.querySelector('.ui-library-columns') as web.HTMLElement?)?.offsetHeight.toDouble() ?? 0;

  void _keys(web.Event raw, List<LibraryRow> rows) {
    final web.KeyboardEvent event = raw as web.KeyboardEvent;
    if (!identical(event.target, web.document.getElementById(_viewportId))) return;
    final int page = (_viewport / component.rowHeight).floor().clamp(1, 1000);
    final int? next = switch (event.key) {
      'ArrowDown' => _active + 1,
      'ArrowUp' => _active - 1,
      'PageDown' => _active + page,
      'PageUp' => _active - page,
      'Home' => 0,
      'End' => rows.length - 1,
      _ => null,
    };
    if (next != null) {
      event.preventDefault();
      _activate(next, rows.length);
      return;
    }
    if (rows.isEmpty) return;
    final LibraryRow row = rows[_active.clamp(0, rows.length - 1)];
    if (event.key == 'Enter') {
      event.preventDefault();
      _open(row);
    } else if (event.key == ' ') {
      event.preventDefault();
      component.selectable ? _toggle(row) : _open(row);
    } else if (component.selectable && (event.ctrlKey || event.metaKey) && event.key.toLowerCase() == 'a') {
      event.preventDefault();
      setState(() => _picked = <String>{for (final LibraryRow each in rows) each.key});
    }
  }

  String _count(int shown, int total) {
    String of(int n) => countOf(n, component.noun);
    return shown == total ? of(total) : '$shown of ${of(total)}';
  }

  /// Search, filters, the count and the sort: one dense row on a wide list;
  /// on a narrow one search and sort share a line above the filters.
  Widget _bar(List<LibraryRow> rows) {
    final LibrarySort sort = component.sorts.firstWhere((LibrarySort each) => each.id == _sort, orElse: () => LibrarySort.name);
    return DenseBox(classes: 'ui-library-bar', children: <Widget>[
      // One line each, the full width of a narrow list, so no filter is cut off.
      if (component.toolbar.isNotEmpty)
        dom.div(classes: 'ui-library-tools', <Widget>[
          Column(spacing: 8, crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, children: component.toolbar),
        ]),
      dom.div(classes: 'ui-library-search', <Widget>[
        // A plain text box: the browser's own clear cross on a search box
        // drew bold and large; "No matches" offers Clear search.
        TextInput(
          value: _search,
          placeholder: 'Search',
          size: controlSize(Density.dense),
          attributes: <String, String>{'aria-label': 'Search ${component.label.toLowerCase()}', 'aria-controls': _viewportId},
          onChanged: (String value) => setState(() {
            _search = value;
            _active = 0;
          }),
        ),
      ]),
      if (component.filters.isNotEmpty)
        ToggleChips<String>(
          label: 'Filters',
          options: <ChoiceOption<String>>[for (final LibraryFilter filter in component.filters) ChoiceOption<String>(filter.id, filter.label)],
          values: _filters,
          onChanged: (Set<String> next) => setState(() {
            _filters = next;
            _active = 0;
          }),
        ),
      dom.div(classes: 'ui-library-meta', <Widget>[
        dom.span(classes: 'ui-library-count', attributes: const <String, String>{'role': 'status'}, <Widget>[
          Component.text(_count(rows.length, component.rows.length)),
        ]),
        if (component.sorts.length > 1)
          AppMenu(
            id: '$_id-sort',
            label: 'Sort: ${sort.label}',
            icon: ArcaneIcon.arrowUpDown(),
            alignEnd: true,
            entries: <AppMenuEntry>[
              for (final LibrarySort each in component.sorts) AppMenuEntry(each.label, () => setState(() => _sort = each.id), hint: each.id == _sort ? 'Current' : null),
            ],
          ),
      ]),
    ]);
  }

  /// Whether a search found [row] by its id alone, so Simple shows the id
  /// as the reason it is listed.
  bool _foundById(LibraryRow row) {
    final String search = _search.trim().toLowerCase();
    if (search.isEmpty || row.advancedMeta == null) return false;
    final String shown = '${row.title} ${row.meta ?? ''}'.toLowerCase();
    return search.split(RegExp(r'\s+')).any((String word) => !shown.contains(word));
  }

  Widget _row(LibraryRow row, int index, bool advanced) {
    final bool picked = _picked.contains(row.key);
    final bool active = index == _active;
    final bool showId = advanced || _foundById(row);
    return dom.div(
      key: ValueKey<String>(row.key),
      id: '$_id-row-$index',
      classes: 'ui-library-row${active ? ' is-active' : ''}${picked ? ' is-picked' : ''}',
      attributes: <String, String>{
        'role': 'option',
        'aria-selected': component.selectable ? '$picked' : '$active',
        'aria-posinset': '${index + 1}',
        'data-key': row.key,
      },
      styles: dom.Styles(raw: <String, String>{'top': '${index * component.rowHeight}px', 'height': '${component.rowHeight}px'}),
      events: <String, void Function(web.Event)>{
        'click': (web.Event _) {
          setState(() => _active = index);
          _open(row);
        },
      },
      <Widget>[
        if (component.selectable)
          dom.span(
            classes: 'ui-library-pick',
            events: <String, void Function(web.Event)>{'click': (web.Event event) => event.stopPropagation()},
            <Widget>[ArcaneCheckbox(checked: picked, size: ComponentSize.sm, onChanged: (bool _) => _toggle(row))],
          ),
        if (row.leading != null) dom.span(classes: 'ui-library-leading', <Widget>[row.leading!]),
        dom.span(classes: 'ui-library-text', <Widget>[
          dom.span(classes: 'ui-library-title', <Widget>[Component.text(row.title)]),
          if (row.meta != null || (showId && row.advancedMeta != null))
            dom.span(classes: 'ui-library-line', <Widget>[
              Component.text(<String>[?row.meta, if (showId && row.advancedMeta != row.meta) ?row.advancedMeta].join(' · ')),
            ]),
        ]),
        if (row.badges.isNotEmpty)
          dom.span(classes: 'ui-library-badges', <Widget>[
            for (final LibraryBadge badge in row.badges) StatusBadge(badge.label, tone: badge.tone),
          ]),
        if (row.trailing != null) dom.span(classes: 'ui-library-trailing', <Widget>[row.trailing!]),
      ],
    );
  }

  Widget _body(List<LibraryRow> rows, bool advanced) {
    if (component.error != null) {
      return AppAlert(
        tone: AlertTone.error,
        message: component.error!,
        live: true,
        action: component.onRetry == null ? null : Button.outline(label: 'Retry', size: ButtonSize.sm, onPressed: component.onRetry),
      );
    }
    if (component.loading) return Skeleton(rows: 6, label: 'Loading ${component.label.toLowerCase()}');
    if (component.rows.isEmpty) return EmptyState(message: component.emptyMessage, action: component.emptyAction);
    if (rows.isEmpty) {
      return EmptyState(
        message: 'No matches.',
        action: Button.outline(
          label: 'Clear search',
          onPressed: () => setState(() {
            _search = '';
            _filters = <String>{};
          }),
        ),
      );
    }
    final (int first, int end) = visibleRange(count: rows.length, rowHeight: component.rowHeight, scrollTop: _scrollTop, viewport: _viewport);
    final int active = _active.clamp(0, rows.length - 1);
    return DensityScope.dense(child: dom.div(
      id: _viewportId,
      classes: 'ui-library-rows',
      attributes: <String, String>{
        'role': 'listbox',
        'tabindex': '0',
        'aria-label': component.label,
        'aria-activedescendant': '$_id-row-$active',
        if (component.selectable) 'aria-multiselectable': 'true',
      },
      events: <String, void Function(web.Event)>{
        'scroll': (web.Event event) {
          final double top = (event.target! as web.Element).scrollTop.toDouble();
          if ((top - _scrollTop).abs() >= component.rowHeight / 4) setState(() => _scrollTop = top);
        },
        'keydown': (web.Event event) => _keys(event, rows),
      },
      <Widget>[
        dom.div(
          classes: 'ui-library-spacer',
          styles: dom.Styles(raw: <String, String>{'height': '${rows.length * component.rowHeight}px'}),
          <Widget>[for (int index = first; index < end; index++) _row(rows[index], index, advanced)],
        ),
      ],
    ));
  }

  Widget? _bulk(List<LibraryRow> rows) {
    if (!component.selectable || _picked.isEmpty) return null;
    final List<LibraryRow> picked = <LibraryRow>[for (final LibraryRow row in component.rows) if (_picked.contains(row.key)) row];
    return dom.div(classes: 'ui-library-bulk', <Widget>[
      const ArcaneSeparator(),
      Padding(padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8), child: ActionRow(
        dense: true,
        label: 'Picked rows',
        leading: <Widget>[
          dom.span(classes: 'ui-library-count', attributes: const <String, String>{'role': 'status'}, <Widget>[Component.text('${picked.length} picked')]),
        ],
        secondary: <RowAction>[
          for (final LibraryBulkAction action in component.bulkActions) RowAction(action.label, () => action.run(picked), destructive: action.destructive),
        ],
        cancel: RowAction('Clear', () => setState(() => _picked = <String>{})),
      )),
    ]);
  }

  @override
  Widget build(BuildContext context) {
    final List<LibraryRow> rows = _shown;
    final bool advanced = ModeScope.advanced(context);
    web.window.requestAnimationFrame(((JSNumber _) => _observe()).toJS);
    // An empty list is its empty state alone: no search, no filters, no
    // card stretched to the save bar.
    final bool empty = component.rows.isEmpty && !component.loading && component.error == null;
    final bool fill = component.fill && !empty;
    // The library's outlined card: the list's rows scroll inside it.
    return Card(
      variant: component.framed ? CardVariant.outlined : CardVariant.ghost,
      padding: EdgeInsets.zero,
      classes: 'ui-library${fill ? ' is-fill' : ''}${component.grow && !fill ? ' is-grow' : ''}',
      attributes: <String, String>{'id': _id},
      styles: fill ? _filling : _column,
      children: <Widget>[
        if (!empty) _bar(rows),
        _body(rows, advanced),
        ?_bulk(rows),
      ],
    );
  }
}

const ArcaneStyleData _column = ArcaneStyleData(display: Display.flex, flexDirection: FlexDirection.column, minWidth: '0', overflow: Overflow.hidden);

/// Fills the page body; only its rows scroll.
const ArcaneStyleData _filling = ArcaneStyleData(display: Display.flex, flexDirection: FlexDirection.column, minWidth: '0', overflow: Overflow.hidden, flexCustom: '1 1 auto', minHeight: '16rem');
