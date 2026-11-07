/// The Sync page's one main next step, the button shown as primary.
enum SyncStep { comparePush, validate, push, comparePull, pull, none }

/// What the Sync page should push next. A pull the user has started (server
/// documents picked, or a pull compared) leads; otherwise the push runs
/// compare, validate, push in that order.
SyncStep syncNextStep({
  required bool pushCompared,
  required int pushSelected,
  required bool validated,
  required int pullSelected,
  required bool pullCompared,
  required int pullWrites,
}) {
  if (pullCompared && pullWrites > 0) return SyncStep.pull;
  if (pullSelected > 0 && !pullCompared) return SyncStep.comparePull;
  if (!pushCompared) return SyncStep.comparePush;
  if (pushSelected == 0) return SyncStep.none;
  return validated ? SyncStep.push : SyncStep.validate;
}
