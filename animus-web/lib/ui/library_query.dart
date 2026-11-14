/// The rows a [LibraryList] shows and how search, filters and sorting pick
/// and order them. Pure Dart (no browser), so it is unit tested at 1500 rows.
library;

import 'package:jaspr/jaspr.dart' show Component;

import 'natural_order.dart';
import 'tone.dart';

/// A labelled state on a row ("Despawned", "2 problems").
class LibraryBadge {
  const LibraryBadge(this.label, {this.tone = BadgeTone.neutral});

  final String label;
  final BadgeTone tone;
}

/// One entry of a list: a document, a server, a workspace.
class LibraryRow {
  const LibraryRow({
    required this.key,
    required this.title,
    this.meta,
    this.advancedMeta,
    this.badges = const <LibraryBadge>[],
    this.tags = const <String>{},
    this.href,
    this.leading,
    this.trailing,
    this.keywords = '',
    this.sortValues = const <String, String>{},
  });

  /// Stable and unique in the list (`npc:town/baker`).
  final String key;
  final String title;

  /// One plain line under the title.
  final String? meta;

  /// Ids and paths, shown in Advanced only.
  final String? advancedMeta;
  final List<LibraryBadge> badges;

  /// The [LibraryFilter] ids this row matches.
  final Set<String> tags;

  /// Where a click on the row goes.
  final String? href;
  final Component? leading;
  final Component? trailing;

  /// More words the search finds the row by.
  final String keywords;

  /// A value per [LibrarySort] id; rows without one sort by title.
  final Map<String, String> sortValues;

  String get searchText => '$title ${meta ?? ''} ${advancedMeta ?? ''} $keywords'.toLowerCase();
}

/// A chip above the list. Rows carrying its id in [LibraryRow.tags] match.
class LibraryFilter {
  const LibraryFilter(this.id, this.label);

  final String id;
  final String label;
}

/// A sort order offered in the list's Sort menu.
class LibrarySort {
  const LibrarySort(this.id, this.label);

  static const LibrarySort name = LibrarySort('name', 'Name');

  final String id;
  final String label;
}

/// [rows] matching every word of [search] and any of [filters], in natural
/// order of [sort] (then title).
List<LibraryRow> queryRows(List<LibraryRow> rows, {String search = '', Set<String> filters = const <String>{}, String sort = 'name'}) {
  final List<String> words = <String>[
    for (final String word in search.toLowerCase().split(RegExp(r'\s+')))
      if (word.isNotEmpty) word,
  ];
  final List<LibraryRow> found = <LibraryRow>[
    for (final LibraryRow row in rows)
      if ((filters.isEmpty || row.tags.any(filters.contains)) && (words.isEmpty || _matches(row.searchText, words))) row,
  ];
  String key(LibraryRow row) => row.sortValues[sort] ?? row.title;
  final String whole = words.join(' ');
  found.sort((LibraryRow a, LibraryRow b) {
    if (whole.isNotEmpty) {
      final int exact = (_exact(b, whole) ? 1 : 0) - (_exact(a, whole) ? 1 : 0);
      if (exact != 0) return exact;
    }
    final int order = naturalCompare(key(a), key(b));
    return order != 0 ? order : naturalCompare(a.title, b.title);
  });
  return found;
}

/// Whether [search] names [row] exactly: its title, its id, or the last
/// part of its id ("citizens-60" finds world/citizens-60 before citizens-600).
bool _exact(LibraryRow row, String search) {
  if (row.title.toLowerCase() == search) return true;
  final String? meta = row.advancedMeta?.toLowerCase();
  if (meta == null) return false;
  for (final String id in meta.split(RegExp(r' \u00b7 |, | '))) {
    if (id == search || id.endsWith('/$search') || id.endsWith(':$search')) return true;
  }
  return false;
}

bool _matches(String text, List<String> words) {
  for (final String word in words) {
    if (!text.contains(word)) return false;
  }
  return true;
}

/// The rows a viewport of [viewport] px scrolled to [scrollTop] shows, with
/// [overscan] extra rows on each side: `(first, end)` with end exclusive.
(int, int) visibleRange({required int count, required double rowHeight, required double scrollTop, required double viewport, int overscan = 6}) {
  if (count == 0 || rowHeight <= 0) return (0, 0);
  final int first = ((scrollTop / rowHeight).floor() - overscan).clamp(0, count);
  final int shown = (viewport / rowHeight).ceil() + overscan * 2;
  return (first, (first + shown).clamp(0, count));
}
