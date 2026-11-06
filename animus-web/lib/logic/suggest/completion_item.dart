/// One completion row and the edit it makes. An insert may be a snippet:
/// `$1`, `${1:text}` and `$0` mark tab stops, numbered in the order Tab
/// visits them, `$0` last (the end of the insert when absent).
library;

/// What a row offers, shown as its kind column.
enum CompletionKind {
  variable('var'),
  fact('fact'),
  placeholder('placeholder'),
  papi('papi'),
  tag('tag'),
  color('colour'),
  function('fn'),
  capability('capability'),
  argument('argument'),
  event('event'),
  filter('filter'),
  document('id'),
  flag('flag'),
  quest('quest'),
  registry('id'),
  command('command'),
  key('key'),
  value('value');

  const CompletionKind(this.short);

  /// The word the row shows.
  final String short;
}

class CompletionItem {
  const CompletionItem({
    required this.label,
    required this.insert,
    required this.kind,
    this.type = '',
    this.detail = '',
    this.swatch,
    this.reopen = false,
  });

  /// What the row shows and fuzzy matching reads.
  final String label;

  /// The text written over the typed prefix; a snippet when it holds `$`.
  final String insert;
  final CompletionKind kind;

  /// A short type word (`integer`, `item`, `behavior`).
  final String type;

  /// One line saying what it is.
  final String detail;

  /// A CSS colour shown as a square swatch.
  final String? swatch;

  /// Whether the list opens again after this is accepted (`player.` then
  /// its facts, a key then its value).
  final bool reopen;
}

/// A tab stop of an applied snippet, as offsets in the new text.
class TabStop {
  const TabStop(this.start, this.end);

  final int start;
  final int end;

  @override
  bool operator ==(Object other) => other is TabStop && other.start == start && other.end == end;

  @override
  int get hashCode => Object.hash(start, end);

  @override
  String toString() => 'TabStop($start, $end)';
}

/// The result of accepting a completion: the new text, the selection, and
/// any further tab stops in visiting order.
class AppliedCompletion {
  const AppliedCompletion(this.text, this.selectionStart, this.selectionEnd, this.stops);

  final String text;
  final int selectionStart;
  final int selectionEnd;

  /// Stops after the first; Tab moves to each in turn.
  final List<TabStop> stops;
}

/// Rows offered at the caret and the span of text they replace.
class CompletionResult {
  const CompletionResult(this.items, this.from, this.to, {this.title = '', this.auto = false});

  static const CompletionResult none = CompletionResult(<CompletionItem>[], 0, 0);

  final List<CompletionItem> items;
  final int from;
  final int to;

  /// What the list holds, for its accessible name ("Placeholders").
  final String title;

  /// Whether typing alone opens the list here (a trigger character or a
  /// name being typed); otherwise only Ctrl+Space does.
  final bool auto;

  bool get isEmpty => items.isEmpty;

  /// [text] with [item] written over `from..to`.
  AppliedCompletion apply(String text, CompletionItem item) => applySnippet(text, from, to, item.insert);
}

final RegExp _stop = RegExp(r'\$(\d)|\$\{(\d):([^}]*)\}');

/// Writes snippet [insert] over `from..to` of [text]. The caret selects the
/// first stop's text (or sits at it), later stops come back in order, and
/// `$0` (or the end) is visited last.
AppliedCompletion applySnippet(String text, int from, int to, String insert) {
  final int start = from.clamp(0, text.length);
  final int end = to.clamp(start, text.length);
  final StringBuffer plain = StringBuffer();
  final Map<int, TabStop> stops = <int, TabStop>{};
  int cursor = 0;
  for (final RegExpMatch match in _stop.allMatches(insert)) {
    plain.write(insert.substring(cursor, match.start));
    final int number = int.parse(match.group(1) ?? match.group(2)!);
    final String body = match.group(3) ?? '';
    final int at = start + plain.length;
    plain.write(body);
    stops.putIfAbsent(number, () => TabStop(at, at + body.length));
    cursor = match.end;
  }
  plain.write(insert.substring(cursor));
  final String written = plain.toString();
  final String next = '${text.substring(0, start)}$written${text.substring(end)}';
  final int tail = start + written.length;
  final List<int> order = stops.keys.where((int number) => number > 0).toList()..sort();
  final List<TabStop> visits = <TabStop>[for (final int number in order) stops[number]!, stops[0] ?? TabStop(tail, tail)];
  if (order.isEmpty) return AppliedCompletion(next, visits.first.start, visits.first.end, const <TabStop>[]);
  final TabStop first = visits.first;
  return AppliedCompletion(next, first.start, first.end, visits.sublist(1));
}

/// [stops] after an edit at [at] that changed the length by [delta]: stops
/// past the edit move with it.
List<TabStop> shiftStops(List<TabStop> stops, int at, int delta) => <TabStop>[
      for (final TabStop stop in stops)
        if (stop.start >= at) TabStop(stop.start + delta, stop.end + delta) else if (stop.end >= at) TabStop(stop.start, stop.end + delta) else stop,
    ];
