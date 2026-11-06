import 'dart:convert';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/change_sets.dart';
import '../../logic/json_merge.dart';
import '../../model/document_kind.dart';
import '../../model/server_binding.dart';
import '../../services/browser.dart';
import '../../services/server/pairing.dart';
import '../../services/server/server_connection.dart';
import '../../state/live_session.dart';
import '../../state/workspace_route.dart';
import 'connection_pill.dart';
import 'live_confirm.dart';

/// The live editing bar of a server workspace, where the connection and the
/// drafts meet: the connection state in words, the changed drafts, Validate,
/// Preview and Apply, and the applied history with undo.
class LiveBar extends StatefulWidget {
  const LiveBar({required this.session, required this.focus, required this.onOpenServer, super.key});

  final LiveSession session;

  /// The open document's key, previewed even when unchanged.
  final String? focus;
  final void Function() onOpenServer;

  @override
  State<LiveBar> createState() => _LiveBarState();
}

/// How many days before expiry the bar starts saying so.
const int expiryNoticeDays = 3;

/// "Pairing expires in 2 days" while the token is close to its end.
String? expiryLine(Duration? left) {
  if (left == null || left.isNegative || left.inDays >= expiryNoticeDays) return null;
  if (left.inHours < 24) return 'The pairing for this browser expires in ${left.inHours < 1 ? 'under an hour' : '${left.inHours} hour${left.inHours == 1 ? '' : 's'}'}. Pair again before then to keep editing.';
  final int days = (left.inHours / 24).ceil();
  return 'The pairing for this browser expires in $days day${days == 1 ? '' : 's'}. Pair again before then to keep editing.';
}

class _LiveBarState extends State<LiveBar> {
  String _message = '';
  bool _confirmUndo = false;
  bool _pairing = false;
  String _code = '';

  static const String _undoId = 'animus-live-undo';
  static const String _pairId = 'animus-live-pair';

  void _pairWith() {
    final String code = extractPairingCode(_code);
    if (!code.startsWith(pairingPrefix)) {
      ArcaneSonner.warning('Paste the link or ANM1. code that /animus editor pair prints on the server.');
      return;
    }
    navigate(PairRoute(code).format());
  }

  @override
  Widget build(BuildContext context) {
    final LiveSession session = component.session;
    final ConnectionStatus status = session.connection.status;
    final List<DraftChange> drafts = session.drafts;
    final bool owned = session.owned;
    final bool online = status.online && owned;
    final bool idle = session.busy == null;
    final ServerBinding? binding = session.binding;
    final PreviewState? preview = session.preview;
    final String? focus = component.focus;
    final bool focusIsNpc = focus != null && focus.startsWith('npc:');
    final List<AppliedChange> undoable = <AppliedChange>[
      for (final AppliedChange change in (binding?.history ?? const <AppliedChange>[]).reversed)
        if (!change.undone && change.undoOf == null) change,
    ];
    final String? cause = session.waiting ? LiveSession.otherTab : (status.online ? null : phaseHint(status, hasToken: session.connection.token != null));
    final String? expiry = status.online ? expiryLine(session.connection.tokenLeft) : null;
    final bool pairAgain = status.phase == ConnectionPhase.needsPairing || (status.phase == ConnectionPhase.offline && session.connection.token == null);
    return dom.section(classes: 'animus-live', attributes: const <String, String>{'aria-label': 'Live editing'}, <Widget>[
      dom.div(classes: 'animus-live-head', <Widget>[
        ConnectionPill(status: status, hasToken: session.connection.token != null),
        dom.span(classes: 'animus-live-count', attributes: const <String, String>{'data-testid': 'draft-count'}, <Widget>[
          Component.text(drafts.isEmpty ? 'No changed drafts' : '${drafts.length} changed draft${drafts.length == 1 ? '' : 's'}'),
        ]),
        if (binding != null && binding.pending.isNotEmpty)
          dom.span(classes: 'animus-badge', attributes: const <String, String>{'data-testid': 'pending-count'}, <Widget>[
            Component.text('${binding.pending.length} awaiting the server'),
          ]),
        if (session.busy != null) dom.span(classes: 'animus-muted', attributes: const <String, String>{'role': 'status'}, <Widget>[Component.text('${session.busy}...')]),
        dom.div(classes: 'animus-live-links', <Widget>[
          if (status.phase == ConnectionPhase.reconnecting && owned)
            Button.ghost(label: 'Retry now', size: ButtonSize.small, onPressed: session.connection.retryNow),
          if (pairAgain && !_pairing)
            Button.secondary(
              label: 'Pair again',
              size: ButtonSize.small,
              attributes: const <String, String>{'id': _pairId},
              onPressed: () {
                setState(() => _pairing = true);
                focusSoon('animus-live-pair-code');
              },
            ),
          Button.ghost(label: 'Server page', size: ButtonSize.small, onPressed: component.onOpenServer),
        ]),
      ]),
      if (cause != null && cause.isNotEmpty)
        dom.p(classes: 'animus-live-cause', attributes: const <String, String>{'data-testid': 'live-cause', 'role': 'status'}, <Widget>[Component.text(cause)]),
      if (expiry != null) dom.p(classes: 'animus-live-cause', attributes: const <String, String>{'data-testid': 'live-expiry'}, <Widget>[Component.text(expiry)]),
      if (_pairing)
        dom.div(classes: 'animus-row animus-live-pair', <Widget>[
          TextInput(
            placeholder: 'Pairing link or ANM1. code',
            value: _code,
            size: ComponentSize.sm,
            attributes: const <String, String>{'aria-label': 'New pairing link', 'id': 'animus-live-pair-code'},
            onChanged: (String value) => setState(() => _code = value),
            onSubmitted: (String _) => _pairWith(),
          ),
          Button.primary(label: 'Pair', size: ButtonSize.small, onPressed: _pairWith),
          Button.ghost(
            label: 'Cancel',
            size: ButtonSize.small,
            onPressed: () {
              setState(() => _pairing = false);
              focusSoon(_pairId);
            },
          ),
          const dom.span(classes: 'animus-muted', <Widget>[Component.text('Run /animus editor pair on the server, then paste the link it prints.')]),
        ]),
      if (binding != null && binding.pending.isNotEmpty && session.pendingProblem != null)
        dom.div(classes: 'animus-row', <Widget>[
          dom.span(classes: 'animus-muted', <Widget>[Component.text('The server has not said what became of an earlier change set. ${session.pendingProblem!.cause}')]),
          Button.ghost(label: 'Check again', size: ButtonSize.small, disabled: !online || !idle, onPressed: session.checkPending),
          Button.ghost(label: 'Stop waiting', size: ButtonSize.small, disabled: !idle || !owned, onPressed: session.forgetPending),
        ]),
      dom.div(classes: 'animus-live-actions', <Widget>[
        dom.div(classes: 'animus-live-group', attributes: const <String, String>{'role': 'group', 'aria-label': 'Send drafts to the server'}, <Widget>[
          Button.secondary(
            label: 'Validate',
            size: ButtonSize.small,
            disabled: !online || !idle,
            onPressed: () => session.validate(only: drafts.isEmpty && focus != null ? <String>{focus} : null),
          ),
          Button.secondary(
            label: preview != null && preview.running ? 'Update preview' : 'Preview',
            size: ButtonSize.small,
            disabled: !online || !idle || (drafts.isEmpty && !focusIsNpc),
            onPressed: () => session.startPreview(focus: focusIsNpc ? <String>{focus} : null),
          ),
          if (preview != null && preview.running)
            Button.ghost(label: 'End preview', size: ButtonSize.small, disabled: !online || !idle, onPressed: session.endPreview),
          Button.primary(
            label: drafts.isEmpty ? 'Apply' : 'Apply ${drafts.length}',
            size: ButtonSize.small,
            disabled: !online || !idle || drafts.isEmpty,
            onPressed: () async {
              final bool applied = await session.apply(message: _message.trim().isEmpty ? null : _message.trim());
              // A refused or unanswered apply keeps the note for the retry.
              if (applied && mounted) setState(() => _message = '');
            },
          ),
        ]),
        TextInput(
          placeholder: 'Change note (optional)',
          value: _message,
          size: ComponentSize.sm,
          attributes: const <String, String>{'aria-label': 'Change note'},
          onChanged: (String value) => setState(() => _message = value),
        ),
        if (undoable.isNotEmpty && !_confirmUndo)
          Button.ghost(
            label: 'Undo last apply',
            size: ButtonSize.small,
            disabled: !online || !idle,
            attributes: <String, String>{'id': _undoId, 'title': 'Undo change ${undoable.first.changeId} on the server'},
            onPressed: () => setState(() => _confirmUndo = true),
          ),
      ]),
      if (undoable.isNotEmpty && _confirmUndo)
        LiveConfirm(
          question: 'Undo the last apply on the server?',
          detail: 'The server puts back ${undoable.first.documents.length} document${undoable.first.documents.length == 1 ? '' : 's'} as they were before change ${undoable.first.changeId}. '
              'It refuses if someone edited them since.',
          confirmLabel: 'Undo on the server',
          returnFocus: _undoId,
          onConfirm: () {
            setState(() => _confirmUndo = false);
            session.undo(undoable.first.changeId);
          },
          onCancel: () => setState(() => _confirmUndo = false),
        ),
      if (preview != null)
        dom.p(classes: 'animus-muted animus-live-preview', attributes: const <String, String>{'data-testid': 'preview-state'}, <Widget>[
          Component.text(preview.running
              ? 'Preview running for your player only (${preview.npcs.join(', ')})'
                  '${preview.expiresAt == null ? '' : ', until ${preview.expiresAt!.toLocal().toString().substring(11, 16)}'}. Commands, combat and the economy stay off.'
              : 'The preview ${preview.stateLine}.'),
        ]),
      if (preview != null && preview.hiddenLine != null)
        dom.p(classes: 'animus-muted animus-live-preview', attributes: const <String, String>{'data-testid': 'preview-hidden'}, <Widget>[
          Component.text(preview.hiddenLine!),
        ]),
      if (drafts.isNotEmpty)
        dom.ul(classes: 'animus-live-drafts', <Widget>[
          for (final DraftChange draft in drafts)
            dom.li(<Widget>[
              dom.span(classes: 'animus-badge is-${draft.state.name}', <Widget>[Component.text(_stateLabel(draft.state))]),
              dom.code(<Widget>[Component.text('${draft.kind.wireName} ${draft.id}')]),
              if (session.stale.contains(draft.key)) const dom.span(classes: 'animus-badge is-error', <Widget>[Component.text('changed on server')]),
              if (session.stale.contains(draft.key))
                Button.ghost(label: 'Merge', size: ButtonSize.small, disabled: !online || !idle, onPressed: () => session.merge(draft.key)),
              if (draft.problem != null) dom.span(classes: 'animus-error', <Widget>[Component.text(draft.problem!)]),
            ]),
        ]),
      if (session.log.isNotEmpty)
        dom.details(classes: 'animus-live-log', <Widget>[
          dom.summary(attributes: const <String, String>{'data-testid': 'live-last'}, <Widget>[Component.text(session.log.last.message)]),
          dom.ul(<Widget>[
            for (final LiveNotice notice in session.log.reversed.take(12))
              dom.li(classes: 'is-${notice.level.name}', <Widget>[
                dom.span(classes: 'animus-muted', <Widget>[Component.text(notice.at.toLocal().toString().substring(11, 19))]),
                Component.text(' ${notice.message}'),
              ]),
          ]),
        ]),
      if (session.conflicts.isNotEmpty) ConflictPanel(session: session),
    ]);
  }
}

String _stateLabel(DraftState state) => switch (state) {
      DraftState.modified => 'edited',
      DraftState.created => 'new',
      DraftState.deleted => 'deleted',
      DraftState.clean => 'same',
    };

String _short(Object? value) {
  if (isAbsent(value)) return '(not present)';
  final String text = jsonEncode(value);
  return text.length > 160 ? '${text.substring(0, 157)}...' : text;
}

/// Three-way merges for documents whose server version moved: each
/// conflicting field with base, mine and server, and a side to keep.
class ConflictPanel extends StatefulWidget {
  const ConflictPanel({required this.session, super.key});

  final LiveSession session;

  @override
  State<ConflictPanel> createState() => _ConflictPanelState();
}

class _ConflictPanelState extends State<ConflictPanel> {
  /// The conflict whose "take the server's" answer waits for a confirm.
  String? _confirming;

  static String _takeId(String key) => 'animus-take-${key.replaceAll(RegExp('[^A-Za-z0-9_-]'), '-')}';

  @override
  Widget build(BuildContext context) {
    final LiveSession session = component.session;
    return dom.div(classes: 'animus-conflicts', attributes: const <String, String>{'role': 'region', 'aria-label': 'Conflicts'}, <Widget>[
      for (final ConflictView view in session.conflicts.values)
        dom.section(classes: 'animus-panel animus-conflict', attributes: <String, String>{'data-key': view.key}, <Widget>[
          dom.h3(<Widget>[
            Component.text('${view.kind.label}: ${view.id}'),
          ]),
          dom.p(classes: 'animus-muted', <Widget>[
            Component.text(view.server == null
                ? 'The server deleted this document after you opened it.'
                : 'The server is at revision ${view.server!.revision}; you started from ${view.base == null ? 'nothing' : 'revision ${view.base!.revision}'}. '
                    '${view.merge.clean ? 'Your edits and the server\'s touch different fields, so they merge cleanly.' : '${view.merge.conflicts.length} field${view.merge.conflicts.length == 1 ? '' : 's'} changed on both sides.'}'),
          ]),
          if (view.merge.conflicts.isNotEmpty)
            dom.table(classes: 'animus-merge-table', <Widget>[
              const dom.thead(<Widget>[
                dom.tr(<Widget>[
                  dom.th(<Widget>[Component.text('Field')]),
                  dom.th(<Widget>[Component.text('Base')]),
                  dom.th(<Widget>[Component.text('Mine')]),
                  dom.th(<Widget>[Component.text('Server')]),
                  dom.th(<Widget>[Component.text('Keep')]),
                ]),
              ]),
              dom.tbody(<Widget>[
                for (final MergeConflict conflict in view.merge.conflicts)
                  dom.tr(<Widget>[
                    dom.td(attributes: const <String, String>{'data-label': 'Field'}, <Widget>[dom.code(<Widget>[Component.text(conflict.path)])]),
                    dom.td(attributes: const <String, String>{'data-label': 'Base'}, <Widget>[dom.code(<Widget>[Component.text(_short(conflict.base))])]),
                    dom.td(attributes: const <String, String>{'data-label': 'Mine'}, <Widget>[dom.code(<Widget>[Component.text(_short(conflict.mine))])]),
                    dom.td(attributes: const <String, String>{'data-label': 'Server'}, <Widget>[dom.code(<Widget>[Component.text(_short(conflict.server))])]),
                    dom.td(<Widget>[
                      dom.div(classes: 'animus-segmented', attributes: <String, String>{'role': 'radiogroup', 'aria-label': 'Keep for ${conflict.path}'}, <Widget>[
                        for (final MergeSide side in MergeSide.values)
                          Button(
                            label: side == MergeSide.mine ? 'Mine' : 'Server',
                            size: ButtonSize.small,
                            variant: (view.choices[conflict.path] ?? MergeSide.mine) == side ? ButtonVariant.primary : ButtonVariant.ghost,
                            attributes: <String, String>{'role': 'radio', 'aria-checked': '${(view.choices[conflict.path] ?? MergeSide.mine) == side}'},
                            onPressed: () => session.choose(view.key, conflict.path, side),
                          ),
                      ]),
                    ]),
                  ]),
              ]),
            ]),
          if (_confirming == view.key)
            LiveConfirm(
              question: view.server == null ? 'Accept the deletion of ${view.id}?' : 'Take the server version of ${view.id}?',
              detail: view.server == null
                  ? 'Your draft of it is removed from this workspace, as the server already did.'
                  : 'Your edits to it are replaced by the server\'s revision ${view.server!.revision}. Undo in this workspace brings them back.',
              confirmLabel: view.server == null ? 'Remove my draft' : 'Replace my draft',
              destructive: true,
              returnFocus: _takeId(view.key),
              onConfirm: () {
                setState(() => _confirming = null);
                session.resolve(view.key, dropDraft: true);
              },
              onCancel: () => setState(() => _confirming = null),
            )
          else
            dom.div(classes: 'animus-row', <Widget>[
              Button.primary(
                label: view.server == null ? 'Keep mine (create it again)' : 'Use merge',
                size: ButtonSize.small,
                onPressed: () => session.resolve(view.key),
              ),
              Button.secondary(
                label: view.server == null ? 'Accept the deletion' : 'Take server version',
                size: ButtonSize.small,
                attributes: <String, String>{'id': _takeId(view.key)},
                onPressed: () => setState(() => _confirming = view.key),
              ),
            ]),
        ]),
    ]);
  }
}

/// The badge text the sidebar shows for a document of a server workspace.
String? draftBadge(LiveSession session, DocumentKind kind, String id) {
  final String key = documentKey(kind, id);
  if (session.conflicts.containsKey(key)) return 'conflict';
  if (session.stale.contains(key)) return 'stale';
  return switch (session.stateOf(kind, id)) {
    DraftState.modified => 'edited',
    DraftState.created => 'new',
    _ => null,
  };
}
