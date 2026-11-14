/// The one state the save bar shows, for a local workspace (kept in this
/// browser) and a server workspace (changes applied to the server).
library;

import '../logic/plural.dart';
import 'editor_store.dart';
import 'live_session.dart';

enum SavePhase {
  /// Local: every edit is stored in this browser.
  saved,

  /// Local: an edit is being stored.
  saving,

  /// Local or server: the browser refused to store an edit ([SaveState.reason]).
  notSaved,

  /// Server: nothing waits to be applied.
  upToDate,

  /// Server: [SaveState.count] changes wait to be applied.
  changes,

  /// Server: an apply or undo is on its way.
  applying,

  /// Server: the last apply wrote [SaveState.count] documents.
  applied,

  /// Server: the last undo put [SaveState.count] documents back.
  undone,

  /// Server: the last apply did not go through ([SaveState.reason]).
  failed,

  /// Server: not connected; [SaveState.count] changes are kept.
  offline,
}

class SaveState {
  const SaveState(this.phase, {this.count = 0, this.reason, this.retry = true});

  final SavePhase phase;
  final int count;
  final String? reason;

  /// A failed apply that sending again could fix; false for a refusal that
  /// needs something else first, such as a new pairing.
  final bool retry;

  /// A server workspace's state (apply), not a local one's (save).
  bool get server => phase.index >= SavePhase.upToDate.index;

  /// Something is not where the user expects it: an edit not stored, an
  /// apply that failed.
  bool get problem => phase == SavePhase.notSaved || phase == SavePhase.failed;

  /// The save bar's line.
  String get text => switch (phase) {
        SavePhase.saved => 'Saved in this browser',
        SavePhase.saving => 'Saving...',
        SavePhase.notSaved => 'Not saved: ${reason ?? 'the browser refused'}',
        SavePhase.upToDate => 'Up to date',
        SavePhase.changes => '${countOf(count, 'change')} not applied',
        SavePhase.applying => 'Applying...',
        SavePhase.applied => 'Applied ${countOf(count, 'change')}',
        SavePhase.undone => 'Undid ${countOf(count, 'change')}',
        SavePhase.failed => 'Apply failed: ${reason ?? 'the server refused'}',
        SavePhase.offline => 'Server offline. Changes are kept.',
      };

  @override
  bool operator ==(Object other) => other is SaveState && other.phase == phase && other.count == count && other.reason == reason && other.retry == retry;

  @override
  int get hashCode => Object.hash(phase, count, reason, retry);
}

/// The save state of the open workspace: [session] decides for a server
/// workspace, the store for a local one; an edit the browser refused to
/// store comes first either way.
SaveState saveStateOf(EditorStore store, LiveSession? session) {
  if (store.hasUnsavedEdit) return SaveState(SavePhase.notSaved, reason: store.unsavedReason);
  if (session != null && store.workspace?.server != null) return session.saveState;
  return store.saving ? const SaveState(SavePhase.saving) : const SaveState(SavePhase.saved);
}
