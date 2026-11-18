/// Apply with its checks first: nothing goes while a change has problems or
/// was also changed on the server, the server validates the rest, then one
/// change set is sent. The Review sheet's Apply runs it; the save bar's
/// Apply in Simple too, opening Review when it answers
/// [ApplyOutcome.blocked].
library;

import '../../logic/change_sets.dart';
import '../../logic/inspect/command_scope.dart';
import '../../logic/inspect/inspect_schemas.dart';
import '../../model/document_kind.dart';
import '../../services/server/server_profiles.dart';
import '../../logic/npc/npc_lookups.dart';
import '../../logic/suggest/completion_sources.dart';
import '../../model/animus_document.dart';
import '../../state/editor_store.dart';
import '../../state/live_session.dart';
import '../../ui/save_state.dart' show applyBlockedReason;

enum ApplyOutcome {
  /// The server wrote every change.
  applied,

  /// Something in Review needs a decision first: a problem, a file changed
  /// on the server, a merge.
  blocked,

  /// Nothing reached the server (not connected, refused, no answer); the
  /// save bar says why.
  failed,

  /// No changes.
  nothing,
}

/// The changes that stop Apply: a source that is not JSON, problems found
/// here or by the server, an id the server's lists lack, a file changed
/// on the server too, or a behavior that needs Run commands when the
/// pairing does not have it.
List<DraftChange> applyBlockers(LiveSession session) => <DraftChange>[
      for (final DraftChange draft in session.drafts)
        if (draft.problem != null ||
            session.stale.contains(draft.key) ||
            session.conflicts.containsKey(draft.key) ||
            (session.store.libraryEntry(draft.kind, draft.id)?.problems ?? 0) > 0 ||
            commandsBlock(session, draft) != null ||
            lookupProblems(session, draft) > 0)
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

/// What stops Apply, counted as Review lists it: files also changed on the
/// server, the other changed files with problems, and behaviors whose only
/// block is the Run commands the pairing lacks.
({int problems, int serverChanged, int commands}) applyBlocks(LiveSession session) {
  final Set<String> serverChanged = <String>{...session.stale, ...session.conflicts.keys};
  int problems = 0, commands = 0;
  for (final DraftChange draft in applyBlockers(session)) {
    if (serverChanged.contains(draft.key)) continue;
    final bool broken = draft.problem != null || (session.store.libraryEntry(draft.kind, draft.id)?.problems ?? 0) > 0 || lookupProblems(session, draft) > 0;
    if (broken) {
      problems++;
    } else {
      commands++;
    }
  }
  return (problems: problems, serverChanged: serverChanged.length, commands: commands);
}

/// Why Apply cannot go yet, in the words the save bar and Review share;
/// null when it can.
String? applyBlockedOf(LiveSession session) {
  final ({int problems, int serverChanged, int commands}) blocks = applyBlocks(session);
  return applyBlockedReason(problems: blocks.problems, serverChanged: blocks.serverChanged, commands: blocks.commands);
}

/// How many ids in [draft] the paired server's lists lack: a body, world or
/// mob type the server does not have.
int lookupProblems(LiveSession session, DraftChange draft) {
  final ServerLookups? lookups = session.lookups;
  final AnimusDocument? document = session.store.document(draft.kind, draft.id);
  if (lookups == null || document == null) return 0;
  return lookupDiagnostics(document, lookups.over(CompletionSources.empty)).length;
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
