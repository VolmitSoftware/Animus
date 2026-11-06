/// The keyboard model of a nested tree, as the WAI-ARIA tree pattern has it:
/// Up and Down step through the visible items, Home and End jump, Right
/// expands a collapsed item or enters an expanded one, Left collapses an
/// expanded item or returns to its parent.
library;

/// One visible tree item as the keyboard model sees it.
class TreeRow {
  const TreeRow({required this.parent, this.expandable = false, this.expanded = false});

  /// The index of the item's parent row, or null for a top item.
  final int? parent;

  /// Whether the item has children to show.
  final bool expandable;
  final bool expanded;
}

/// What a key does to the tree.
enum TreeStep { focus, expand, collapse }

/// The step [key] takes from row [current] of [rows] and the row it acts on,
/// or null when the key does nothing there.
(TreeStep, int)? treeKey(String key, int current, List<TreeRow> rows) {
  if (rows.isEmpty) return null;
  final int last = rows.length - 1;
  final int at = current.clamp(0, last);
  final TreeRow row = rows[at];
  switch (key) {
    case 'ArrowDown':
      return (TreeStep.focus, at < last ? at + 1 : last);
    case 'ArrowUp':
      return (TreeStep.focus, at > 0 ? at - 1 : 0);
    case 'Home':
      return (TreeStep.focus, 0);
    case 'End':
      return (TreeStep.focus, last);
    case 'ArrowRight':
      if (!row.expandable) return null;
      if (!row.expanded) return (TreeStep.expand, at);
      return at < last && rows[at + 1].parent == at ? (TreeStep.focus, at + 1) : null;
    case 'ArrowLeft':
      if (row.expandable && row.expanded) return (TreeStep.collapse, at);
      final int? parent = row.parent;
      return parent == null ? null : (TreeStep.focus, parent);
  }
  return null;
}
