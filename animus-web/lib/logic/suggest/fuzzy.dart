/// Fuzzy matching for completion lists: the query's characters must appear
/// in order in the candidate. A prefix match scores highest, then a match at
/// a word start (after `.`, `_`, `-`, `:`, `/` or a case change), then
/// scattered characters, with runs of consecutive characters scoring more.
library;

const String _separators = '._-:/ <#%{';

/// The score of [candidate] for [query], higher is better, or null when the
/// query's characters do not all appear in order. Case is ignored.
int? fuzzyScore(String candidate, String query) {
  if (query.isEmpty) return 0;
  final String lower = candidate.toLowerCase();
  final String wanted = query.toLowerCase();
  if (lower.startsWith(wanted)) return 1000 + wanted.length * 10 - (lower.length - wanted.length);
  for (int contained = lower.indexOf(wanted); contained > 0; contained = lower.indexOf(wanted, contained + 1)) {
    if (_wordStart(candidate, contained)) return 800 + wanted.length * 10 - contained;
  }
  int score = 0;
  int at = 0;
  int previous = -2;
  for (int index = 0; index < wanted.length; index++) {
    final String glyph = wanted[index];
    int found = -1;
    // Prefer continuing a run, then the next word start, over the first hit.
    for (int scan = at; scan < lower.length; scan++) {
      if (lower[scan] != glyph) continue;
      if (found < 0) found = scan;
      if (scan == previous + 1 || _wordStart(candidate, scan)) {
        found = scan;
        break;
      }
    }
    if (found < 0) return null;
    if (found == previous + 1) {
      score += 15;
    } else if (_wordStart(candidate, found)) {
      score += 10;
    } else {
      score += 1;
    }
    previous = found;
    at = found + 1;
  }
  return score - lower.length ~/ 8;
}

bool _wordStart(String text, int index) {
  if (index == 0) return true;
  final String before = text[index - 1];
  if (_separators.contains(before)) return true;
  final String here = text[index];
  return here != here.toLowerCase() && before == before.toLowerCase();
}

/// [items] that match [query], best first. With an empty query the given
/// order stays; otherwise ties keep the shorter label, then alphabetical
/// order. [label] reads the text matched against.
List<T> fuzzyRank<T>(Iterable<T> items, String query, String Function(T item) label, {int limit = 50}) {
  final List<(T, int, int)> scored = <(T, int, int)>[];
  int order = 0;
  for (final T item in items) {
    final int? score = fuzzyScore(label(item), query);
    if (score != null) scored.add((item, score, order));
    order++;
  }
  if (query.isNotEmpty) {
    scored.sort(((T, int, int) a, (T, int, int) b) {
      final int byScore = b.$2.compareTo(a.$2);
      if (byScore != 0) return byScore;
      final String left = label(a.$1), right = label(b.$1);
      final int byLength = left.length.compareTo(right.length);
      if (byLength != 0) return byLength;
      final int byText = left.compareTo(right);
      return byText != 0 ? byText : a.$3.compareTo(b.$3);
    });
  }
  return <T>[for (final (T item, int _, int _) in scored.take(limit)) item];
}
