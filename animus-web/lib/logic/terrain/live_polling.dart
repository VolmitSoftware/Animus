/// How often a stage asks where its spawned NPC stands: every 1.5 s while
/// the NPC moves, then less often the longer it stands still (5 s after
/// 10 s, 15 s after a minute, 30 s after five), and not at all while the
/// stage is out of view or the tab has no focus. A move brings it back to
/// 1.5 s at the next answer.
library;

const Duration livePollPeriod = Duration(milliseconds: 1500);

/// How long the answer has stayed the same, and the wait from then on.
const List<(Duration, Duration)> _stillSteps = <(Duration, Duration)>[
  (Duration(minutes: 5), Duration(seconds: 30)),
  (Duration(minutes: 1), Duration(seconds: 15)),
  (Duration(seconds: 10), Duration(seconds: 5)),
];

/// The wait before the next question, or null to skip it: [seen] is false
/// while the stage is off screen or the tab is in the background;
/// [sinceChange] is how long the answer has stayed the same.
Duration? livePollDelay({required bool seen, required Duration sinceChange}) {
  if (!seen) return null;
  for (final (Duration still, Duration wait) in _stillSteps) {
    if (sinceChange >= still) return wait;
  }
  return livePollPeriod;
}
