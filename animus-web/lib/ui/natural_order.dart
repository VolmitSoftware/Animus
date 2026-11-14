/// Natural ordering for names with numbers in them: "Courier 4" comes
/// before "Courier 14". Case is ignored first and decides only ties.
library;

final RegExp _chunks = RegExp(r'\d+|\D+');

/// Compares [a] and [b] with digit runs read as numbers.
int naturalCompare(String a, String b) {
  final List<String> left = <String>[for (final RegExpMatch match in _chunks.allMatches(a.toLowerCase())) match[0]!];
  final List<String> right = <String>[for (final RegExpMatch match in _chunks.allMatches(b.toLowerCase())) match[0]!];
  for (int index = 0; index < left.length && index < right.length; index++) {
    final String x = left[index], y = right[index];
    final bool numbers = _digit(x) && _digit(y);
    final int order = numbers ? _compareDigits(x, y) : x.compareTo(y);
    if (order != 0) return order;
  }
  if (left.length != right.length) return left.length.compareTo(right.length);
  return a.compareTo(b);
}

bool _digit(String chunk) => chunk.codeUnitAt(0) >= 48 && chunk.codeUnitAt(0) <= 57;

/// Digit runs of any length, without parsing (ids can hold long numbers).
int _compareDigits(String x, String y) {
  final String a = x.replaceFirst(RegExp(r'^0+(?=\d)'), '');
  final String b = y.replaceFirst(RegExp(r'^0+(?=\d)'), '');
  if (a.length != b.length) return a.length.compareTo(b.length);
  final int order = a.compareTo(b);
  return order != 0 ? order : x.length.compareTo(y.length);
}
