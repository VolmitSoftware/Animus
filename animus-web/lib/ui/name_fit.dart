/// Shortening a name to fit a one-line bar (pure): the top bar's switcher.
library;

/// The longest switcher name shown before the bar has measured itself.
const int contextNameLimit = 40;

/// The longest breadcrumb label.
const int crumbLimit = 28;

/// The fewest characters a shortened name keeps.
const int contextNameFloor = 6;

/// [title] cut to [budget] characters with an ellipsis; whole when it fits.
String shortenName(String title, int budget) {
  final List<int> runes = title.runes.toList();
  if (runes.length <= budget) return title;
  return '${String.fromCharCodes(runes.take(budget - 1)).trimRight()}…';
}

/// The next, shorter budget for a name of [length] characters that sticks
/// out [overflow] px: about 7 px a character, at least 3 at a time; null
/// when it is as short as it goes.
int? shorterBudget(int length, double overflow) {
  if (length <= contextNameFloor) return null;
  final int cut = (overflow / 7).ceil().clamp(3, length);
  return (length - cut).clamp(contextNameFloor, length);
}
