/// Where a popup list fits next to its field: below when there is room,
/// above when that side has room under the sticky top bar, and otherwise
/// below after scrolling the page so the field moves up. Pure: the
/// components measure the page and apply the result.
library;

import 'dart:math' as math;

/// The gap kept to the window's bottom edge and under the top bar.
const double popupMargin = 8;

/// The tallest a popup list gets.
const double popupMaxHeight = 360;

/// The field's vertical extent in viewport px.
class PopupBox {
  const PopupBox({required this.top, required this.bottom, required this.viewHeight, this.topInset = 0});

  final double top;
  final double bottom;
  final double viewHeight;

  /// The bottom of the sticky top bar: nothing may draw above it.
  final double topInset;
}

class PopupFit {
  const PopupFit({required this.up, required this.maxHeight, this.scroll = 0});

  /// Above the field rather than below.
  final bool up;
  final double maxHeight;

  /// Px to scroll the page down before opening; the field moves up by it.
  final double scroll;
}

/// The fit for a list about [wanted] px tall next to [box]. With
/// [allowScroll] false (after a scroll, or when the page cannot scroll)
/// it takes the roomier side, capped so it stays inside the window and
/// under the top bar.
PopupFit fitPopup(PopupBox box, double wanted, {bool allowScroll = true}) {
  final double want = math.min(wanted, popupMaxHeight);
  final double below = math.max(0, box.viewHeight - box.bottom - popupMargin - 2);
  final double above = math.max(0, box.top - box.topInset - popupMargin - 2);
  if (below >= want) return PopupFit(up: false, maxHeight: want);
  if (above >= want) return PopupFit(up: true, maxHeight: want);
  if (allowScroll) {
    final double scroll = math.min(want - below, math.max(0, box.top - box.topInset - popupMargin));
    if (scroll >= 1) return PopupFit(up: false, maxHeight: math.min(want, below + scroll), scroll: scroll.ceilToDouble());
  }
  final bool up = above > below;
  return PopupFit(up: up, maxHeight: up ? above : below);
}
