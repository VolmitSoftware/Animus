/// Apply with its checks first: nothing goes while a change has problems or
/// was also changed on the server, the server validates the rest, then one
/// change set is sent. The Review sheet's Apply runs it; the save bar's
/// Apply should too, opening Review when it answers [ApplyOutcome.blocked].
library;

import '../../logic/change_sets.dart';
import '../../logic/inspect/command_scope.dart';
import '../../logic/inspect/inspect_schemas.dart';
import '../../model/document_kind.dart';
import '../../services/server/server_profiles.dart';
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
/// here or by the server, a document changed on the server too, or a
/// behavior that needs Run commands when the pairing does not have it.
List<DraftChange> applyBlockers(LiveSession session) => <DraftChange>[
      for (final DraftChange draft in session.drafts)
        if (draft.problem != null ||
            session.stale.contains(draft.key) ||
            session.conflicts.containsKey(draft.key) ||
            (session.store.libraryEntry(draft.kind, draft.id)?.problems ?? 0) > 0 ||
            commandsBlock(session, draft) != null)
          draft,
    ];

/// Why [draft] cannot be applied with this pairing: a behavior that runs
/// commands, gives items or pays quest rewards needs Run commands
/// (`apply:commands`). Null when the pairing has it or the server cannot say.
String? commandsBlock(LiveSession session, DraftChange draft) {
  final ServerToken? token = session.connection.token;
  if (draft.kind != DocumentKind.behavior || token == null || token.has('apply:commands')) return null;
  final String? why = behaviorNeedsCommands(session.store.document(draft.kind, draft.id)?.json, privilegedCapabilities(InspectSchemas.from(session.schemas).catalog));
  return why == null ? null : 'It $why; applying it needs Run commands (apply:commands).';
}

Future<ApplyOutcome> applyChecked(LiveSession session, {String? message}) async {
  if (session.busy != null) return ApplyOutcome.failed;
  if (session.drafts.isEmpty) return ApplyOutcome.nothing;
  if (applyBlockers(session).isNotEmpty || session.stale.isNotEmpty) return ApplyOutcome.blocked;
  final bool valid = await session.validate(quiet: true);
  if (!valid) return applyBlockers(session).isNotEmpty ? ApplyOutcome.blocked : ApplyOutcome.failed;
  if (await session.apply(message: message)) return ApplyOutcome.applied;
  return session.conflicts.isNotEmpty || session.stale.isNotEmpty || applyBlockers(session).isNotEmpty ? ApplyOutcome.blocked : ApplyOutcome.failed;
}
