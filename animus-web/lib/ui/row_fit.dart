/// Whether an action row's contents stick out of it, from the boxes the
/// browser measured after layout: a group past either end of the row, or an
/// item past either end of its group (the end group is right-aligned, so it
/// overflows to the start). Less than a pixel is rounding, not overflow:
/// edges that meet in layout differ in their last digits once a transform
/// scales them (a dialog opening), and folding on that left a row folded
/// for good.
library;

/// A box's left and right edges, in pixels.
typedef RowSpan = ({double left, double right});

/// [groups] pairs each group's box with its items' boxes.
bool rowOverflows(RowSpan row, List<(RowSpan, List<RowSpan>)> groups) {
  bool past(RowSpan inner, RowSpan outer) => inner.right > outer.right + 1 || inner.left < outer.left - 1;
  for (final (RowSpan group, List<RowSpan> items) in groups) {
    if (past(group, row) || items.any((RowSpan item) => past(item, group))) return true;
  }
  return false;
}
