/// What the route editor shows people: waits in seconds (the JSON keeps
/// ticks), plain labels for the route's choices and problem paths named by
/// point number from 1. Writes go through [routeWith] and the route model's
/// node setters, which change one key and keep every other one as it was.
library;

import '../../model/animus_document.dart';
import '../gui/gui_json.dart';
import 'route_model.dart';

/// Server ticks in one second.
const int routeTicksPerSecond = 20;

/// [ticks] as the seconds a field shows: 20 is 1, 30 is 1.5, 1 is 0.05.
num ticksToSeconds(int ticks) {
  final double seconds = ticks / routeTicksPerSecond;
  return seconds == seconds.roundToDouble() ? seconds.toInt() : seconds;
}

/// Seconds typed in a field as the whole ticks the JSON stores.
int secondsToTicks(num seconds) => (seconds * routeTicksPerSecond).round();

/// `1 s`, `1.5 s`.
String secondsText(int ticks) => '${ticksToSeconds(ticks)} s';

/// A wait for a list line: `2 s`, or `1-3 s` for a range.
String waitText(RouteWait wait) => wait.ranged ? '${ticksToSeconds(wait.min)}-${ticksToSeconds(wait.max)} s' : secondsText(wait.min);

/// The `waitTicks` value for a wait typed in seconds: one count, or with
/// [range] `[min, max]` (an empty end takes the other's value). Null when
/// nothing is typed.
Object? waitFromSeconds(num? min, num? max, {required bool range}) {
  if (!range) return min == null ? null : secondsToTicks(min);
  final num? low = min ?? max, high = max ?? min;
  if (low == null || high == null) return null;
  return <int>[secondsToTicks(low), secondsToTicks(high)];
}

/// [route] with [key] set to [value], or without it when [value] is null.
/// Every other key keeps its value and place.
JsonMap routeWith(JsonMap route, String key, Object? value) {
  final JsonMap next = <String, Object?>{...route};
  if (value == null) {
    next.remove(key);
  } else {
    next[key] = value;
  }
  return next;
}

const Map<String, String> routeOrderLabels = <String, String>{
  'sequence': 'In order',
  'pingpong': 'Back and forth',
  'random': 'Random',
  'wander': 'Wander nearby',
};

const Map<String, String> routeModeLabels = <String, String>{'walk': 'Walking', 'swim': 'Swimming', 'fly': 'Flying'};

const Map<String, String> movementStyleLabels = <String, String>{
  'walk': 'Walk',
  'sneak': 'Sneak',
  'sprint': 'Sprint',
  'swim': 'Swim',
  'crawl': 'Crawl',
  'fly': 'Fly',
  'glide': 'Glide',
};

const Map<String, String> routeSeedLabels = <String, String>{'npc': 'Same per NPC', 'random': 'New each time'};

const Map<String, String> routeStartLabels = <String, String>{'first': 'First point', 'random': 'Random point'};

/// The words for each route key a problem can sit on.
const Map<String, String> _fieldWords = <String, String>{
  'mode': 'Travel',
  'speed': 'Speed',
  'order': 'Order',
  'loop': 'Loop',
  'waitTicks': 'Wait',
  'points': 'Points',
  'guides': 'Guides',
  'linkDistance': 'Link reach',
  'wander': 'Wander',
  'radius': 'width',
  'height': 'height',
  'seed': 'Random picks',
  'startOffset': 'Start at',
  'jitter': 'Spread',
  'style': 'Style',
  'weight': 'weight',
  'teleport': 'teleport',
  'recording': 'recording',
  'fromTick': 'from',
  'toTick': 'to',
  'to': '',
};

final RegExp _indexed = RegExp(r'^([A-Za-z]+)\[(\d+)\]$');

/// A problem path in words, points numbered from 1:
/// `points[3].next[1].to` reads `Point 4, next point 2`.
String routeFieldName(String field) {
  final List<String> words = <String>[];
  for (final String part in field.split('.')) {
    final RegExpMatch? match = _indexed.firstMatch(part);
    if (match != null) {
      final int number = int.parse(match[2]!) + 1;
      words.add(switch (match[1]) {
        'points' => 'Point $number',
        'guides' => 'Guide $number',
        'next' => 'next point $number',
        _ => '${match[1]} $number',
      });
      continue;
    }
    final String word = _fieldWords[part] ?? part;
    if (word.isNotEmpty) words.add(words.isEmpty ? word : word.toLowerCase());
  }
  return words.join(', ');
}

/// The point or guide a problem path sits on (`points[3].speed` is
/// `('points', 3)`), or null for a route-wide field.
(String, int)? routeFieldNode(String field) {
  final RegExpMatch? match = _indexed.firstMatch(field.split('.').first);
  if (match == null || (match[1] != 'points' && match[1] != 'guides')) return null;
  return (match[1]!, int.parse(match[2]!));
}

/// The route's own problems at exactly [field], or also inside it with
/// [within], worded for people (waits in seconds).
List<Diagnostic> routeProblemsAt(List<(String, String)> problems, String field, {bool within = false}) => <Diagnostic>[
      for (final (String at, String message) in problems)
        if (at == field || (within && (at.startsWith('$field.') || at.startsWith('$field['))))
          Diagnostic(DiagnosticSeverity.error, '', routeProblemText(at, message)),
    ];

/// A route problem's message in the editor's units.
String routeProblemText(String field, String message) {
  final bool wait = field.endsWith('waitTicks');
  final String text = switch (message) {
    'min above max' when wait => 'the shortest wait is longer than the longest',
    'expected whole number' when wait => 'use steps of 0.05 seconds',
    _ when wait && message.startsWith('must be') => 'use 0 to ${ticksToSeconds(routeMaxWait)} seconds',
    _ => message,
  };
  return text.isEmpty ? text : '${text[0].toUpperCase()}${text.substring(1)}.';
}
