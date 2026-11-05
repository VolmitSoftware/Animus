import 'dart:convert';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/change_sets.dart';
import '../../logic/json_merge.dart';
import '../../model/document_kind.dart';
import '../../model/server_binding.dart';
import '../../services/server/server_connection.dart';
import '../../state/live_session.dart';
import 'connection_pill.dart';

/// The live editing bar of a server workspace: connection, changed drafts,
/// validate, preview, apply, and the applied history with undo.
class LiveBar extends StatefulWidget {
  const LiveBar({required this.session, required this.focus, required this.onOpenServer, super.key});

  final LiveSession session;

  /// The open document's key, previewed even when unchanged.
  final String? focus;
  final void Function() onOpenServer;

  @override
  State<LiveBar> createState() => _LiveBarState();
}

class _LiveBarState extends State<LiveBar> {
  String _message = '';

  @override
  Widget build(BuildContext context) {
    final LiveSession session = component.session;
    final ConnectionStatus status = session.connection.status;
    final List<DraftChange> drafts = session.drafts;
    final bool online = status.online;
    final bool idle = session.busy == null;
    final ServerBinding? binding = session.binding;
    final PreviewState? preview = session.preview;
    final String? focus = component.focus;
    final bool focusIsNpc = focus != null && focus.startsWith('npc:');
    final List<AppliedChange> undoable = <AppliedChange>[
      for (final AppliedChange change in (binding?.history ?? const <AppliedChange>[]).reversed)
        if (!change.undone && change.undoOf == null) change,
    ];
    return dom.section(classes: 'animus-live', attributes: const <String, String>{'aria-label': 'Live editing'}, <Widget>[
      dom.div(classes: 'animus-live-head', <Widget>[
        ConnectionPill(status: status, hasToken: session.connection.token != null),
        if (status.phase == ConnectionPhase.reconnecting)
          Button.ghost(label: 'Retry now', size: ButtonSize.small, onPressed: session.connection.retryNow),
        Button.ghost(label: 'Server', size: ButtonSize.small, onPressed: component.onOpenServer),
        dom.span(classes: 'animus-live-count', attributes: const <String, String>{'data-testid': 'draft-count'}, <Widget>[
          Component.text(drafts.isEmpty ? 'No changes' : '${drafts.length} changed'),
        ]),
        if (binding != null && binding.pending.isNotEmpty)
          dom.span(classes: 'animus-badge', <Widget>[Component.text('${binding.pending.length} awaiting the server')]),
        if (session.busy != null) dom.span(classes: 'animus-muted', <Widget>[Component.text('${session.busy}...')]),
      ]),
      dom.div(classes: 'animus-live-actions', <Widget>[
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
        TextInput(
          placeholder: 'Change note (optional)',
          value: _message,
          size: ComponentSize.sm,
          attributes: const <String, String>{'aria-label': 'Change note'},
          onChanged: (String value) => setState(() => _message = value),
        ),
        Button.primary(
          label: drafts.isEmpty ? 'Apply' : 'Apply ${drafts.length}',
          size: ButtonSize.small,
          disabled: !online || !idle || drafts.isEmpty,
          onPressed: () async {
            await session.apply(message: _message.trim().isEmpty ? null : _message.trim());
            if (mounted) setState(() => _message = '');
          },
        ),
        if (undoable.isNotEmpty)
          Button.ghost(
            label: 'Undo last apply',
            size: ButtonSize.small,
            disabled: !online || !idle,
            attributes: <String, String>{'title': 'Undo change ${undoable.first.changeId} on the server'},
            onPressed: () => session.undo(undoable.first.changeId),
          ),
      ]),
      if (preview != null)
        dom.p(classes: 'animus-muted animus-live-preview', attributes: const <String, String>{'data-testid': 'preview-state'}, <Widget>[
          Component.text(preview.running
              ? 'Preview ${preview.id} running for your player only (${preview.npcs.join(', ')})'
                  '${preview.expiresAt == null ? '' : ', until ${preview.expiresAt!.toLocal().toString().substring(11, 16)}'}. Commands, combat and economy are refused.'
              : 'Preview ${preview.state}.'),
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
class ConflictPanel extends StatelessWidget {
  const ConflictPanel({required this.session, super.key});

  final LiveSession session;

  @override
  Widget build(BuildContext context) {
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
                    dom.td(<Widget>[dom.code(<Widget>[Component.text(conflict.path)])]),
                    dom.td(<Widget>[dom.code(<Widget>[Component.text(_short(conflict.base))])]),
                    dom.td(<Widget>[dom.code(<Widget>[Component.text(_short(conflict.mine))])]),
                    dom.td(<Widget>[dom.code(<Widget>[Component.text(_short(conflict.server))])]),
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
          dom.div(classes: 'animus-row', <Widget>[
            Button.primary(
              label: view.server == null ? 'Keep mine (create it again)' : 'Use merge',
              size: ButtonSize.small,
              onPressed: () => session.resolve(view.key),
            ),
            Button.secondary(
              label: view.server == null ? 'Accept the deletion' : 'Take server version',
              size: ButtonSize.small,
              onPressed: () => session.resolve(view.key, dropDraft: true),
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
