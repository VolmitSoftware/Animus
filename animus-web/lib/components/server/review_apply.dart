/// Apply with its checks first: nothing goes while a change has problems or
/// was also changed on the server, the server validates the rest, then one
/// change set is sent. The Review sheet's Apply runs it; the save bar's
/// Apply should too, opening Review when it answers [ApplyOutcome.blocked].
library;

import '../../logic/change_sets.dart';
import '../../state/editor_store.dart';
import '../../state/live_session.dart';

enum ApplyOutcome {
  /// The server wrote every change.
  applied,

  /// Something in Review needs a decision first: a problem, a document
  /// changed on the server, a merge.
  blocked,

  /// Nothing reached the server (not connected, refused, no answer); the
  /// save bar says why.
  failed,

  /// No changes.
  nothing,
}

/// The changes that stop Apply: a source that is not JSON, problems found
/// here or by the server, or a document changed on the server too.
List<DraftChange> applyBlockers(LiveSession session) => <DraftChange>[
      for (final DraftChange draft in session.drafts)
        if (draft.problem != null ||
            session.stale.contains(draft.key) ||
            session.conflicts.containsKey(draft.key) ||
            (session.store.libraryEntry(draft.kind, draft.id)?.problems ?? 0) > 0)
          draft,
    ];

Future<ApplyOutcome> applyChecked(LiveSession session, {String? message}) async {
  if (session.busy != null) return ApplyOutcome.failed;
  if (session.drafts.isEmpty) return ApplyOutcome.nothing;
  if (applyBlockers(session).isNotEmpty || session.stale.isNotEmpty) return ApplyOutcome.blocked;
  final bool valid = await session.validate(quiet: true);
  if (!valid) return applyBlockers(session).isNotEmpty ? ApplyOutcome.blocked : ApplyOutcome.failed;
  if (await session.apply(message: message)) return ApplyOutcome.applied;
  return session.conflicts.isNotEmpty || session.stale.isNotEmpty || applyBlockers(session).isNotEmpty ? ApplyOutcome.blocked : ApplyOutcome.failed;
}
