/// The pure parts of the editor's dropdowns: options, filtering, and the
/// keyboard state machine of the combobox and listbox pattern. The
/// components in `lib/components/select` render this state and run its
/// effects; nothing here touches the page.
library;

/// One choice of a dropdown.
class SelectOption {
  const SelectOption({
    required this.value,
    required this.label,
    this.group,
    this.description,
    this.disabled = false,
    this.keywords = const <String>[],
    this.swatch,
    this.skinFace,
  });

  final String value;
  final String label;

  /// The heading this option is listed under; options with the same group
  /// are shown together in the order the group first appears.
  final String? group;
  final String? description;
  final bool disabled;

  /// Extra words the search matches, such as an id behind a display name.
  final List<String> keywords;

  /// A CSS colour shown as a square swatch before the label.
  final String? swatch;

  /// A skin texture (a 64x64 PNG address) whose face is shown before the
  /// label.
  final String? skinFace;
}

/// A row below the options that runs an action instead of choosing a value,
/// such as "+ New rig" or "Manage rigs".
class SelectAction {
  const SelectAction({required this.id, required this.label, this.hint});

  final String id;
  final String label;

  /// A line of help under the label.
  final String? hint;
}

/// Above this many options a dropdown shows its search field.
const int searchThreshold = 8;

bool searchable(int optionCount) => optionCount > searchThreshold;

/// The options whose label, value, description or keywords contain every
/// word of [query], ignoring case, in their original order and kept with
/// their groups.
List<SelectOption> filterOptions(List<SelectOption> options, String query) {
  final List<String> words = query.toLowerCase().split(RegExp(r'\s+')).where((String word) => word.isNotEmpty).toList();
  if (words.isEmpty) return grouped(options);
  bool matches(SelectOption option) {
    final String haystack = <String>[
      option.label,
      option.value,
      ?option.description,
      ?option.group,
      ...option.keywords,
    ].join(' ').toLowerCase();
    return words.every(haystack.contains);
  }

  return grouped(<SelectOption>[for (final SelectOption option in options) if (matches(option)) option]);
}

/// [options] with each group's options together, groups in first-seen order
/// and ungrouped options first.
List<SelectOption> grouped(List<SelectOption> options) {
  if (options.every((SelectOption option) => option.group == null)) return options;
  final List<SelectOption> loose = <SelectOption>[];
  final Map<String, List<SelectOption>> groups = <String, List<SelectOption>>{};
  for (final SelectOption option in options) {
    final String? group = option.group;
    if (group == null) {
      loose.add(option);
    } else {
      groups.putIfAbsent(group, () => <SelectOption>[]).add(option);
    }
  }
  return <SelectOption>[...loose, for (final List<SelectOption> members in groups.values) ...members];
}

/// What the keyboard state machine asks the component to do after a key.
enum SelectEffect {
  /// Nothing beyond showing the new state.
  none,

  /// Choose the active option, close and focus the trigger.
  commit,

  /// Run the active footer action and close.
  action,

  /// Close without a change and focus the trigger.
  close,

  /// Close without a change and let focus move on (Tab).
  dismiss,
}

/// The open state of one dropdown.
///
/// [active] indexes the navigable rows: the visible options first, then the
/// footer actions. -1 means no row is active.
class SelectState {
  const SelectState({this.open = false, this.active = -1, this.query = '', this.typed = '', this.typedAt = 0});

  final bool open;
  final int active;

  /// The search text (searchable dropdowns only).
  final String query;

  /// The type-ahead buffer and when its last key came, in milliseconds.
  final String typed;
  final int typedAt;

  SelectState copyWith({bool? open, int? active, String? query, String? typed, int? typedAt}) => SelectState(
        open: open ?? this.open,
        active: active ?? this.active,
        query: query ?? this.query,
        typed: typed ?? this.typed,
        typedAt: typedAt ?? this.typedAt,
      );

  @override
  bool operator ==(Object other) =>
      other is SelectState && other.open == open && other.active == active && other.query == query && other.typed == typed && other.typedAt == typedAt;

  @override
  int get hashCode => Object.hash(open, active, query, typed, typedAt);

  @override
  String toString() => 'SelectState(open: $open, active: $active, query: "$query", typed: "$typed")';
}

/// The result of one key: the next state, what to do, and whether the key
/// was used (so the page should not act on it as well).
class SelectStep {
  const SelectStep(this.state, [this.effect = SelectEffect.none, this.handled = true]);

  const SelectStep.ignored(this.state)
      : effect = SelectEffect.none,
        handled = false;

  final SelectState state;
  final SelectEffect effect;
  final bool handled;
}

/// How long the type-ahead buffer lives between keys.
const int typeAheadMillis = 600;

/// How far Page Up and Page Down move.
const int pageSize = 10;

/// The rows the keyboard walks: [options] (already filtered) then
/// [actionCount] footer actions.
class SelectRows {
  const SelectRows(this.options, this.actionCount);

  final List<SelectOption> options;
  final int actionCount;

  int get length => options.length + actionCount;

  bool enabled(int index) => index >= options.length ? index < length : !options[index].disabled;

  /// The first enabled row at or after [from] going [step], or -1.
  int seek(int from, int step) {
    for (int index = from; index >= 0 && index < length; index += step) {
      if (enabled(index)) return index;
    }
    return -1;
  }

  int get first => seek(0, 1);
  int get last => seek(length - 1, -1);

  /// The row of [value], or -1.
  int indexOf(String? value) => value == null ? -1 : options.indexWhere((SelectOption option) => option.value == value && !option.disabled);
}

/// The row a freshly opened dropdown starts on: the current value, else the
/// first enabled option (or the last, opening upwards).
int openingRow(SelectRows rows, String? value, {bool fromEnd = false}) {
  final int current = rows.indexOf(value);
  if (current >= 0) return current;
  final int optionsEnd = rows.options.length - 1;
  final int found = fromEnd ? rows.seek(optionsEnd, -1) : rows.seek(0, 1);
  return found >= 0 && found <= optionsEnd ? found : (fromEnd ? rows.last : rows.first);
}

/// The next option whose label starts with [typed], searching from after
/// [from] and wrapping; a buffer of one repeated letter cycles through the
/// options starting with it.
int typeAheadRow(SelectRows rows, String typed, int from) {
  if (typed.isEmpty || rows.options.isEmpty) return from;
  final String needle = typed.toLowerCase();
  final bool repeated = needle.split('').every((String letter) => letter == needle[0]);
  final String prefix = repeated ? needle[0] : needle;
  final int count = rows.options.length;
  final int start = repeated || from < 0 ? from + 1 : from;
  for (int offset = 0; offset < count; offset++) {
    final int index = ((start + offset) % count + count) % count;
    final SelectOption option = rows.options[index];
    if (!option.disabled && option.label.toLowerCase().startsWith(prefix)) return index;
  }
  return from;
}

/// One key on a dropdown.
///
/// [inSearch] says the key went to the search field, where printable keys,
/// Space, Home and End edit the text. [now] is the time in milliseconds.
SelectStep selectKey(
  SelectState state,
  SelectRows rows,
  String key, {
  String? value,
  bool alt = false,
  bool modifier = false,
  bool inSearch = false,
  int now = 0,
}) {
  final bool printable = key.length == 1 && !modifier;
  if (!state.open) {
    switch (key) {
      case 'ArrowDown' || 'Enter' || ' ':
        return SelectStep(SelectState(open: true, active: openingRow(rows, value)));
      case 'ArrowUp':
        return SelectStep(SelectState(open: true, active: alt ? openingRow(rows, value) : openingRow(rows, value, fromEnd: rows.indexOf(value) < 0)));
      case 'Home':
        return SelectStep(SelectState(open: true, active: rows.first));
      case 'End':
        return SelectStep(SelectState(open: true, active: rows.seek(rows.options.length - 1, -1)));
    }
    if (printable) {
      final int from = rows.indexOf(value);
      return SelectStep(SelectState(open: true, active: typeAheadRow(rows, key, from), typed: key, typedAt: now));
    }
    return SelectStep.ignored(state);
  }
  int clampActive(int next) => next < 0 ? state.active : next;
  switch (key) {
    case 'Escape':
      return const SelectStep(SelectState(), SelectEffect.close);
    case 'Tab':
      return const SelectStep(SelectState(), SelectEffect.dismiss, false);
    case 'ArrowDown':
      if (alt) return SelectStep(state);
      return SelectStep(state.copyWith(active: clampActive(state.active < 0 ? rows.first : rows.seek(state.active + 1, 1))));
    case 'ArrowUp':
      if (alt) return _choose(state, rows);
      return SelectStep(state.copyWith(active: clampActive(state.active < 0 ? rows.last : rows.seek(state.active - 1, -1))));
    case 'PageDown':
      final int down = rows.seek((state.active + pageSize).clamp(0, rows.length - 1), -1);
      return SelectStep(state.copyWith(active: clampActive(down)));
    case 'PageUp':
      final int up = rows.seek((state.active - pageSize).clamp(0, rows.length - 1), 1);
      return SelectStep(state.copyWith(active: clampActive(up)));
    case 'Home':
      if (inSearch) return SelectStep.ignored(state);
      return SelectStep(state.copyWith(active: rows.first));
    case 'End':
      if (inSearch) return SelectStep.ignored(state);
      return SelectStep(state.copyWith(active: rows.last));
    case 'Enter':
      return _choose(state, rows);
    case ' ':
      if (inSearch) return SelectStep.ignored(state);
      if (state.typed.isNotEmpty && now - state.typedAt < typeAheadMillis) return _typeAhead(state, rows, key, now);
      return _choose(state, rows);
  }
  if (printable && !inSearch) return _typeAhead(state, rows, key, now);
  return SelectStep.ignored(state);
}

SelectStep _typeAhead(SelectState state, SelectRows rows, String key, int now) {
  final String typed = now - state.typedAt < typeAheadMillis ? '${state.typed}$key' : key;
  final bool repeated = typed.split('').every((String letter) => letter == typed[0]);
  final int from = repeated ? state.active : (state.active < 0 ? 0 : state.active);
  return SelectStep(state.copyWith(active: typeAheadRow(rows, typed, from), typed: typed, typedAt: now));
}

SelectStep _choose(SelectState state, SelectRows rows) {
  final int active = state.active;
  if (active < 0 || active >= rows.length || !rows.enabled(active)) return SelectStep(state);
  return SelectStep(const SelectState(), active >= rows.options.length ? SelectEffect.action : SelectEffect.commit);
}

/// A new search text: the active row moves to the first match, or the first
/// footer action when nothing matches.
SelectState searchChanged(SelectState state, List<SelectOption> all, String query, int actionCount) {
  final SelectRows rows = SelectRows(filterOptions(all, query), actionCount);
  return state.copyWith(query: query, active: rows.first, typed: '');
}

/// The value and options of a dropdown whose empty entry stands for
/// [fallback] ("sequence (default)"): the fallback is not listed a second
/// time, and a stored value equal to it shows as that empty entry.
({String? value, List<String> options}) withDefault(String? value, List<String> options, String fallback) => (
      value: value == fallback ? null : value,
      options: <String>[for (final String option in options) if (option != fallback) option],
    );
