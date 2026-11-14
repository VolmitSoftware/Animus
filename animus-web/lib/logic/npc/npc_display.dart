/// How NPC forms show long values: numbers rounded for reading (the stored
/// value stays until it is edited) and long lists a page at a time.
library;

import 'dart:math' as math;

/// [value] rounded to [digits] decimals for a field to show; whole numbers
/// and null stay as they are.
num? roundedForField(num? value, int digits) {
  if (value == null || value == value.roundToDouble()) return value;
  final num factor = math.pow(10, digits);
  return (value * factor).round() / factor;
}

/// [value] as text rounded to [digits] decimals, without trailing zeros:
/// `71.24459649525852` reads `71.24`, `68.0` reads `68`.
String shownNumber(num value, {int digits = 2}) {
  final num rounded = roundedForField(value, digits)!;
  return rounded == rounded.roundToDouble() ? '${rounded.toInt()}' : '$rounded';
}

/// Rows a list inside a tab shows at once; a longer one is paged.
const int listPageSize = 30;

/// One page of a list of [total] rows: the rows `from` (inclusive) to `to`
/// (exclusive), which page it is and how many there are. [page] is clamped
/// to the pages there are.
typedef ListPage = ({int from, int to, int page, int pages});

ListPage pageOf(int total, int page, {int size = listPageSize}) {
  final int pages = total <= 0 ? 1 : (total + size - 1) ~/ size;
  final int shown = page.clamp(0, pages - 1);
  return (from: shown * size, to: math.min(total, (shown + 1) * size), page: shown, pages: pages);
}
