/// What the save bar says and offers, worked out from plain facts about the
/// workspace. Pure Dart, so every state is unit tested; the shell feeds it
/// from the store and the live session.
library;

/// How the line reads: quiet (nothing to do), busy (working), attention
/// (something waits for you), failed, done (just finished).
enum SaveTone { quiet, busy, attention, failed, done }

/// The buttons the bar can show.
enum SaveAction {
  apply('Apply'),
  review('Review'),
  retry('Retry'),
  reconnect('Reconnect'),
  saveAgain('Save again'),
  exportZip('Export zip'),
  sendToServer('Send to server'),
  undoApply('Undo'),
  takeOver('Take over'),
  retryLoads('Retry'),
  retryCatalog('Retry'),
  pairAgain('Pair again');

  const SaveAction(this.label);

  final String label;
}

class SaveBarState {
  const SaveBarState(this.message, this.tone, {this.primary, this.secondary = const <SaveAction>[]});

  final String message;
  final SaveTone tone;

  /// The one filled button, last in the row.
  final SaveAction? primary;
  final List<SaveAction> secondary;

  @override
  bool operator ==(Object other) =>
      other is SaveBarState &&
      other.message == message &&
      other.tone == tone &&
      other.primary == primary &&
      other.secondary.length == secondary.length &&
      <int>[for (int index = 0; index < secondary.length; index++) index].every((int index) => other.secondary[index] == secondary[index]);

  @override
  int get hashCode => Object.hash(message, tone, primary, Object.hashAll(secondary));

  @override
  String toString() => '$message ($tone, $primary, $secondary)';
}

String _changes(int count) => count == 1 ? '1 change' : '$count changes';

/// A workspace kept in this browser. [flashed] is the moment after Mod+S.
SaveBarState localSaveState({required bool saving, required bool unsaved, String? problem, bool canSend = false, bool flashed = false}) {
  if (unsaved) {
    final String reason = problem == null || problem.trim().isEmpty ? 'the browser refused the edit' : problem.trim();
    return SaveBarState('Not saved: $reason', SaveTone.failed, primary: SaveAction.saveAgain, secondary: const <SaveAction>[SaveAction.exportZip]);
  }
  if (saving) return const SaveBarState('Saving...', SaveTone.busy);
  final List<SaveAction> send = canSend ? const <SaveAction>[SaveAction.sendToServer] : const <SaveAction>[];
  if (flashed) return SaveBarState('Saved', SaveTone.done, secondary: send);
  return SaveBarState('Saved in this browser', SaveTone.quiet, secondary: send);
}

/// The link to a paired server, as the save bar sees it.
enum ServerLink { online, connecting, reconnecting, offline, needsPairing, otherTab }

/// A paired server's workspace: [changes] drafts not applied, an apply in
/// progress, the reason the last one failed, how many the last one applied
/// or [undone], documents changed both here and on the server ([stale]),
/// the catalog not read yet ([reading]) or not readable ([catalogFailed]),
/// the background read of documents ([loadDone] of [loadTotal]) and the
/// documents the last read could not get ([loadFailed]). [linkMessage] says
/// why the link is down ("Server restarting. Reconnecting."). Without
/// [retry] a failure is one a retry cannot fix, so only Review is offered.
SaveBarState serverSaveState({
  required ServerLink link,
  required int changes,
  bool applying = false,
  String? failure,
  int? applied,
  int? undone,
  int stale = 0,
  bool reading = false,
  bool catalogFailed = false,
  String? linkMessage,
  int loadDone = 0,
  int loadTotal = 0,
  int loadFailed = 0,
  bool history = false,
  bool retry = true,
}) {
  final String kept = changes == 0 ? '' : ' ${_changes(changes)} kept.';
  // Review opens only when it has something in it: changes, a failure, a
  // conflict, or in Advanced ([history]) the apply history, the log and
  // Validate. Simple up to date shows none, as a browser workspace does.
  final List<SaveAction> review = changes > 0 || history ? const <SaveAction>[SaveAction.review] : const <SaveAction>[];
  switch (link) {
    case ServerLink.otherTab:
      return const SaveBarState('Open in another tab.', SaveTone.quiet, primary: SaveAction.takeOver);
    case ServerLink.needsPairing:
      return SaveBarState('Pairing ended.$kept', SaveTone.failed, primary: SaveAction.pairAgain);
    case ServerLink.connecting:
      return SaveBarState('Connecting...$kept', SaveTone.busy, secondary: review);
    case ServerLink.reconnecting:
      return SaveBarState('${linkMessage ?? 'Reconnecting.'}$kept', SaveTone.busy, secondary: review);
    case ServerLink.offline:
      return SaveBarState('Server offline. Changes are kept.', SaveTone.attention, primary: SaveAction.reconnect, secondary: review);
    case ServerLink.online:
      break;
  }
  if (applying) return const SaveBarState('Applying...', SaveTone.busy);
  if (failure != null && changes > 0) {
    return SaveBarState('Apply failed: $failure', SaveTone.failed, primary: retry ? SaveAction.retry : null, secondary: review);
  }
  if (stale > 0) {
    return SaveBarState('${_changes(stale)} ${stale == 1 ? 'conflicts' : 'conflict'} with the server', SaveTone.attention, primary: SaveAction.review);
  }
  final bool loading = loadTotal > 0 && loadDone < loadTotal;
  final String progress = 'Loading $loadDone of $loadTotal';
  if (changes > 0) {
    return SaveBarState('${_changes(changes)} not applied${loading ? '. $progress' : ''}', SaveTone.attention, primary: SaveAction.apply, secondary: review);
  }
  if (catalogFailed) return SaveBarState('Could not read the server', SaveTone.failed, primary: SaveAction.retryCatalog, secondary: review);
  if (reading) return SaveBarState('Reading the server...', SaveTone.busy, secondary: review);
  if (loadFailed > 0) {
    return SaveBarState('${loadFailed == 1 ? '1 document' : '$loadFailed documents'} could not load', SaveTone.failed, primary: SaveAction.retryLoads, secondary: review);
  }
  if (loading) return SaveBarState(progress, SaveTone.busy, secondary: review);
  if (applied != null && applied > 0) {
    return SaveBarState('Applied ${_changes(applied)}', SaveTone.done, secondary: <SaveAction>[SaveAction.undoApply, ...review]);
  }
  if (undone != null && undone > 0) return SaveBarState('Undid ${_changes(undone)}', SaveTone.done, secondary: review);
  return SaveBarState('Up to date', SaveTone.quiet, secondary: review);
}
