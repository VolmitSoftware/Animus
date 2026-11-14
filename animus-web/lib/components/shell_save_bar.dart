/// The save bar wired to the open workspace: a local workspace reports its
/// browser save, a server workspace its unapplied changes and Apply. The
/// bar's Apply, Retry and Mod+S run [SaveFlow.apply].
library;

import 'dart:async';

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../model/server_binding.dart';
import '../services/server/connection_status.dart';
import '../state/editor_store.dart';
import '../state/document_loader.dart';
import '../state/live_session.dart';
import '../state/save_state.dart';
import '../state/workspace_repository.dart';
import '../ui/confirm_dialog.dart';
import '../ui/save_bar.dart';
import '../ui/save_state.dart';
import 'rig/rig_actions.dart' show exportWorkspace;
import 'server/review_apply.dart';

/// What the bar remembers between rebuilds: a browser save in progress and
/// the "Saved" flash after Mod+S. A server workspace's apply state lives in
/// the session ([LiveSession.saveState]).
class SaveFlow {
  SaveFlow(this._changed);

  final void Function() _changed;
  bool saving = false;
  bool flashed = false;
  Timer? _flash;
  Timer? _slow;
  bool _settling = false;

  void dispose() {
    _flash?.cancel();
    _slow?.cancel();
  }

  /// After any store change: shows "Saving..." only when the browser's save
  /// takes longer than a blink.
  void watch(EditorStore store) {
    if (_settling) return;
    _settling = true;
    _slow?.cancel();
    _slow = Timer(const Duration(milliseconds: 150), () {
      if (_settling && !saving) {
        saving = true;
        _changed();
      }
    });
    store.settled().whenComplete(() {
      _settling = false;
      _slow?.cancel();
      if (saving) {
        saving = false;
        _changed();
      }
    });
  }

  /// Mod+S in a local workspace: the edit is already kept, so it says so.
  void flash() {
    flashed = true;
    _flash?.cancel();
    _flash = Timer(const Duration(milliseconds: 1600), () {
      flashed = false;
      _changed();
    });
    _changed();
  }

  /// Sends every change to the server as one change set (the bar's Apply,
  /// Retry and Mod+S). In Simple it checks first ([applyChecked]): while a
  /// change has a problem or was changed on the server too, nothing is sent
  /// and [onBlocked] opens Review.
  Future<void> apply(LiveSession session, {required bool simple, required void Function() onBlocked}) async {
    if (session.busy != null) return;
    if (session.drafts.isEmpty) {
      ArcaneSonner.info('Nothing to apply.');
      return;
    }
    if (simple) {
      if (await applyChecked(session) == ApplyOutcome.blocked) onBlocked();
    } else {
      await session.apply();
    }
    _changed();
  }
}

/// The server link as the save bar names it.
ServerLink linkOf(LiveSession session) {
  if (session.waiting) return ServerLink.otherTab;
  return switch (session.connection.status.phase) {
    ConnectionPhase.online => ServerLink.online,
    ConnectionPhase.connecting => ServerLink.connecting,
    ConnectionPhase.reconnecting => ServerLink.reconnecting,
    ConnectionPhase.needsPairing || ConnectionPhase.refused => ServerLink.needsPairing,
    ConnectionPhase.offline => ServerLink.offline,
  };
}

class ShellSaveBar extends StatelessWidget {
  const ShellSaveBar({
    required this.store,
    required this.workspace,
    required this.flow,
    required this.advanced,
    required this.onReview,
    required this.onReconnect,
    required this.onSend,
    this.session,
    super.key,
  });

  final EditorStore store;
  final WorkspaceSnapshot workspace;
  final SaveFlow flow;
  final bool advanced;
  final LiveSession? session;
  final void Function() onReview;
  final void Function() onReconnect;

  /// Opens Send to server for a local workspace; null on that page.
  final void Function()? onSend;

  SaveBarState _state() {
    final LiveSession? session = this.session;
    final SaveState state = saveStateOf(store, session);
    if (workspace.server == null || session == null || state.phase == SavePhase.notSaved) {
      return localSaveState(
        saving: flow.saving,
        unsaved: state.phase == SavePhase.notSaved,
        problem: state.reason,
        canSend: advanced && workspace.server == null && onSend != null,
        flashed: flow.flashed,
      );
    }
    final LoadProgress? loading = session.loading;
    return serverSaveState(
      link: linkOf(session),
      changes: session.drafts.length,
      applying: state.phase == SavePhase.applying,
      failure: state.phase == SavePhase.failed ? state.reason : null,
      applied: state.phase == SavePhase.applied ? state.count : null,
      undone: state.phase == SavePhase.undone ? state.count : null,
      stale: session.stale.length,
      reading: session.reading,
      catalogFailed: !session.catalogLoaded && session.catalogProblem != null,
      linkMessage: session.connection.status.message,
      loadDone: loading?.done ?? 0,
      loadTotal: loading?.total ?? 0,
      loadFailed: session.loadReport?.failed.length ?? 0,
      history: advanced,
      retry: state.retry,
    );
  }

  Future<void> _undoApply(LiveSession session) async {
    final String? changeId = session.undoableChange;
    final List<AppliedChange> history = session.binding?.history ?? const <AppliedChange>[];
    final AppliedChange? last = history.where((AppliedChange change) => change.changeId == changeId).firstOrNull;
    if (changeId == null || last == null) return;
    final bool yes = await askToConfirm(
      title: 'Undo the last apply?',
      message: 'Puts ${last.documents.length == 1 ? '1 document' : '${last.documents.length} documents'} on the server back. Fails if edited since.',
      confirmLabel: 'Undo on the server',
      destructive: true,
    );
    if (yes) await session.undo(last.changeId);
  }

  void _act(SaveAction action) {
    final LiveSession? session = this.session;
    switch (action) {
      case SaveAction.apply || SaveAction.retry:
        if (session != null) flow.apply(session, simple: !advanced, onBlocked: onReview);
      case SaveAction.review:
        onReview();
      case SaveAction.reconnect:
        onReconnect();
      case SaveAction.saveAgain:
        store.saveAgain();
      case SaveAction.exportZip:
        exportWorkspace(workspace);
      case SaveAction.sendToServer:
        onSend?.call();
      case SaveAction.undoApply:
        if (session != null) _undoApply(session);
      case SaveAction.takeOver:
        session?.takeOver();
      case SaveAction.retryLoads:
        session?.retryFailedLoads();
      case SaveAction.retryCatalog:
        session?.loadCatalog();
      case SaveAction.pairAgain:
        onReview();
    }
  }

  @override
  Widget build(BuildContext context) => SaveBar(
        state: _state(),
        busyAction: session?.busy == 'Applying' ? SaveAction.apply : null,
        onAction: _act,
      );
}
