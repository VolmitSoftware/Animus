/// Review: what goes to the server when you press Apply. The documents also
/// changed on the server (Keep mine, Take server, Compare), the changes that
/// stop Apply (with Fix), every changed document (what changed, Discard),
/// and Apply last in the footer. Advanced adds Validate, Preview in game,
/// a change note, the history of applies with Undo, and the last notices.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:arcane_jaspr/html.dart' show HeadingLevel;
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/change_sets.dart';
import '../../model/document_kind.dart';
import '../../model/server_binding.dart';
import '../../services/browser.dart';
import '../../services/server/connection_status.dart';
import '../../state/editor_store.dart';
import '../../state/live_session.dart';
import '../../state/workspace_route.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/badge.dart';
import '../../ui/confirm_dialog.dart';
import '../../ui/empty_state.dart';
import '../../ui/field.dart';
import '../../ui/menu.dart';
import '../../ui/mode.dart';
import '../../ui/tone.dart';
import '../list_page.dart' show listNoun;
import '../offline/page_parts.dart';
import 'connection_pill.dart';
import 'connection_words.dart';
import 'pair_link_form.dart';
import 'review_apply.dart';
import 'review_changes.dart';
import 'review_merge.dart';
import '../../ui/stack.dart';

/// The Review sheet's content (the shell hosts it in the sheet the save bar
/// opens).
class LiveBar extends StatefulWidget {
  const LiveBar({required this.session, required this.focus, required this.onOpenServer, super.key});

  final LiveSession session;

  /// The open document's key, previewed even when unchanged.
  final String? focus;

  /// Closes Review and shows the server's Home.
  final void Function() onOpenServer;

  @override
  State<LiveBar> createState() => _LiveBarState();
}

/// How many days before expiry Review starts saying so.
const int expiryNoticeDays = 3;

/// "Pairing ends in 2 days" while the pairing is close to its end.
String? expiryLine(Duration? left) {
  if (left == null || left.isNegative || left.inDays >= expiryNoticeDays) return null;
  if (left.inHours < 24) return 'Pairing ends in ${left.inHours < 1 ? 'under an hour' : '${left.inHours} hour${left.inHours == 1 ? '' : 's'}'} unless used.';
  final int days = (left.inHours / 24).ceil();
  return 'Pairing ends in $days day${days == 1 ? '' : 's'} unless used.';
}

/// The most notices Review keeps in Advanced.
const int activityLimit = 8;

class _LiveBarState extends State<LiveBar> {
  String _note = '';

  /// The document whose Compare dialog is open.
  String? _comparing;

  LiveSession get _session => component.session;

  static String _slug(String key) => key.replaceAll(RegExp(r'[^A-Za-z0-9_-]'), '_');
  EditorStore get _store => component.session.store;

  String _nameOf(String key) {
    final (DocumentKind, String)? parsed = parseDocumentKey(key);
    if (parsed == null) return key;
    return _store.libraryEntry(parsed.$1, parsed.$2)?.name ?? parsed.$2;
  }

  String? _href(DocumentKind kind, String id) {
    final String? workspace = _store.workspace?.id;
    if (workspace == null || _store.document(kind, id) == null) return null;
    return DocumentRoute(workspace, kind, id).format();
  }

  Future<void> _apply() async {
    final String note = _note.trim();
    final ApplyOutcome outcome = await applyChecked(_session, message: note.isEmpty ? null : note);
    if (outcome == ApplyOutcome.applied && mounted) setState(() => _note = '');
  }

  Future<void> _discard(DraftChange draft) async {
    final ServerBinding? binding = _session.binding;
    final List<DocumentChange>? changes = binding == null ? null : discardOf(draft, binding);
    if (changes == null) return;
    final String name = _nameOf(draft.key);
    final bool yes = await askToConfirm(
      title: 'Discard changes to $name?',
      message: draft.state == DraftState.created ? 'Removes it from this workspace. Undo brings it back.' : 'Puts it back as the server has it. Undo brings your changes back.',
      confirmLabel: 'Discard changes',
      destructive: true,
    );
    if (yes) await _store.commit(documents: changes, label: 'discard $name');
  }

  /// Builds the merge for a document changed on the server, if not yet.
  Future<ConflictView?> _merged(String key) async {
    if (!_session.conflicts.containsKey(key)) await _session.merge(key);
    return _session.conflicts[key];
  }

  Future<void> _keepMine(String key) async {
    if (await _merged(key) != null) await _session.resolve(key);
  }

  /// Asks first, while focus is still on the button that asked, then reads
  /// the server's copy and takes it.
  Future<void> _takeServer(String key) async {
    final ConflictView? known = _session.conflicts[key];
    final bool deleted = known != null && known.server == null;
    final String name = _nameOf(key);
    final bool yes = await askToConfirm(
      title: deleted ? 'Remove $name here too?' : 'Take the server version of $name?',
      message: deleted ? 'The server deleted it. Your copy goes too. Undo brings it back.' : 'Replaces your changes with the server\'s. Undo brings them back.',
      confirmLabel: deleted ? 'Remove my copy' : 'Take server version',
      destructive: true,
    );
    if (!yes) return;
    final ConflictView? view = await _merged(key);
    if (view == null) return;
    if (mounted) setState(() => _comparing = null);
    await _session.resolve(view.key, dropDraft: true);
  }

  Future<void> _compare(String key) async {
    if (await _merged(key) != null && mounted) setState(() => _comparing = key);
  }

  Future<void> _undo(AppliedChange change) async {
    final bool yes = await askToConfirm(
      title: 'Undo this apply on the server?',
      message: 'Puts ${change.documents.length == 1 ? '1 document' : '${change.documents.length} documents'} back as they were before it. Fails if they were changed since.',
      confirmLabel: 'Undo on the server',
      destructive: true,
    );
    if (yes) await _session.undo(change.changeId);
  }

  @override
  Widget build(BuildContext context) {
    final LiveSession session = _session;
    final bool advanced = ModeScope.advanced(context);
    final ConnectionStatus status = session.connection.status;
    final bool online = status.online && session.owned;
    final bool idle = session.busy == null;
    final List<DraftChange> drafts = session.drafts;
    final List<String> serverChanged = <String>{...session.stale, ...session.conflicts.keys}.toList();
    final List<DraftChange> blockers = <DraftChange>[
      for (final DraftChange draft in applyBlockers(session))
        if (!serverChanged.contains(draft.key)) draft,
    ];
    final List<DraftChange> plain = <DraftChange>[for (final DraftChange draft in drafts) if (!serverChanged.contains(draft.key)) draft];
    final PreviewState? preview = session.preview;
    final String? focus = component.focus;
    final bool focusIsNpc = focus != null && focus.startsWith('npc:');
    final ConflictView? comparing = _comparing == null ? null : session.conflicts[_comparing];
    return dom.div(
      classes: 'ui-review',
      attributes: const <String, String>{'data-testid': 'review'},
      <Widget>[
        stack(spacing: 16, <Widget>[
          ..._status(status),
          if (advanced) dom.div(<Widget>[ConnectionPill(status: status, hasToken: session.connection.token != null, elsewhere: session.waiting, detailed: true)]),
          if (session.busy != null) dom.div(attributes: const <String, String>{'role': 'status'}, <Widget>[mutedText('${session.busy}...')]),
          if (serverChanged.isNotEmpty) _serverChanged(serverChanged, online && idle),
          if (blockers.isNotEmpty) _blockers(blockers),
          // A draft changed on the server too is listed once, above.
          if (plain.isNotEmpty) _changes(plain, advanced) else if (serverChanged.isEmpty) const EmptyState(message: 'No changes to apply.'),
          if (advanced) ..._advanced(session, preview, online && idle, changes: drafts.isNotEmpty),
          // A tab that waits for another one has nothing to send: no footer.
          if (!session.waiting && (advanced || drafts.isNotEmpty))
            dom.div(classes: 'ui-review-footer', <Widget>[
              stack(spacing: 12, <Widget>[
                const ArcaneSeparator(),
                DialogFooter(
                  secondary: <RowAction>[
                    if (advanced) ...<RowAction>[
                      RowAction('Validate', online && idle ? () => session.validate(only: drafts.isEmpty && focus != null ? <String>{focus} : null) : null),
                      RowAction(
                        preview != null && preview.running ? 'Update preview' : 'Preview in game',
                        online && idle && (drafts.isNotEmpty || focusIsNpc) ? () => session.startPreview(focus: focusIsNpc ? <String>{focus} : null) : null,
                      ),
                      if (preview != null && preview.running) RowAction('End preview', online && idle ? session.endPreview : null),
                    ],
                  ],
                  // Nothing to apply: no Apply at all, rather than a disabled one.
                  primary: drafts.isEmpty ? null : RowAction('Apply ${drafts.length}', online && idle ? _apply : null, loading: session.busy == 'Applying', id: 'review-apply'),
                ),
              ]),
            ]),
        ]),
        MergeDialog(
          session: session,
          view: comparing,
          name: comparing == null ? '' : _nameOf(comparing.key),
          onClose: () => setState(() => _comparing = null),
          onTakeServer: (ConflictView view) => _takeServer(view.key),
        ),
      ],
    );
  }

  /// The connection when it is not simply online and owned here.
  List<Widget> _status(ConnectionStatus status) {
    final LiveSession session = _session;
    final bool hasToken = session.connection.token != null;
    final String? expiry = status.online ? expiryLine(session.connection.tokenLeft) : null;
    if (session.waiting) {
      return <Widget>[
        AppAlert(
          tone: AlertTone.info,
          title: 'Open in another tab',
          message: 'This tab waits while another one edits this server.',
          action: Button.outline(label: 'Take over', size: ButtonSize.md, onPressed: session.takeOver),
        ),
      ];
    }
    if (status.phase == ConnectionPhase.needsPairing || status.phase == ConnectionPhase.refused || !hasToken) {
      return <Widget>[
        dom.div(
          attributes: const <String, String>{'data-testid': 'review-pair'},
          <Widget>[
            AppAlert(tone: AlertTone.error, title: 'Pairing ended', message: status.message ?? 'Run /animus editor pair in game, then paste the new link.', live: true),
            PairLinkForm(id: 'review-pair-link', label: 'New link', onCode: (String code) => navigate(PairRoute(code).format())),
          ],
        ),
      ];
    }
    return <Widget>[
      if (status.phase == ConnectionPhase.reconnecting)
        AppAlert(
          tone: AlertTone.warning,
          title: 'Reconnecting',
          message: phaseHint(status, hasToken: true),
          action: Button.outline(label: 'Retry now', size: ButtonSize.md, onPressed: session.connection.retryNow),
        )
      else if (status.phase == ConnectionPhase.offline)
        AppAlert(
          tone: AlertTone.warning,
          title: 'Not connected',
          message: 'Your changes are kept here.',
          action: Button.outline(label: 'Connect', size: ButtonSize.md, onPressed: session.connection.connect),
        ),
      if (expiry != null)
        dom.div(
          attributes: const <String, String>{'data-testid': 'live-expiry'},
          <Widget>[AppAlert(tone: AlertTone.info, message: expiry)],
        ),
    ];
  }

  Widget _section(String id, String title, List<Widget> children, {String? tip, DocLink? docs}) => dom.section(
    attributes: <String, String>{'aria-labelledby': id},
    <Widget>[
      stack(spacing: 8, <Widget>[sectionHeading(title, id: id, level: HeadingLevel.h3, tip: tip, docs: docs), ...children]),
    ],
  );

  /// A row of a Review list: the name (a link to the document when it is
  /// here), a meta line, the row's actions beside it or, when [stacked],
  /// under it so a narrow sheet keeps the name readable.
  Widget _row({required String key, required String name, String? href, required List<Widget> meta, ActionRow? actions, bool stacked = false}) {
    final Widget title = Text.truncated(name, weight: FontWeight.w500, color: TextColor.primary);
    final Widget main = stack(spacing: 2, <Widget>[
      if (href == null) dom.span(attributes: <String, String>{'title': name}, <Widget>[title]) else dom.a(href: href, attributes: <String, String>{'title': name}, <Widget>[title]),
      Wrap(spacing: 8, runSpacing: 4, crossAxisAlignment: WrapCrossAlignment.center, children: meta),
    ]);
    return listItem(
      attributes: <String, String>{'data-key': key},
      Padding(padding: const EdgeInsets.symmetric(vertical: 8), child: stacked ? stack(spacing: 8, <Widget>[main, ?actions]) : splitRow(main, actions)),
    );
  }

  static Widget _meta(String text) => Text(text, size: FontSize.sm, color: TextColor.muted);

  Widget _serverChanged(List<String> keys, bool ready) => _section(
    'review-server',
    'Changed on the server',
    <Widget>[
      rowList(
        separated: true,
        attributes: const <String, String>{'data-testid': 'review-server-changed'},
        <Widget>[
          for (final String key in keys)
            if (parseDocumentKey(key) case (final DocumentKind kind, final String id))
              _row(
                key: key,
                name: _nameOf(key),
                href: _href(kind, id),
                meta: <Widget>[_meta(listNoun(kind))],
                stacked: true,
                actions: ActionRow(
                  dense: true,
                  label: _nameOf(key),
                  secondary: <RowAction>[
                    RowAction('Compare', ready ? () => _compare(key) : null, id: 'review-compare-${_slug(key)}'),
                    RowAction('Take server', ready ? () => _takeServer(key) : null, id: 'review-take-${_slug(key)}'),
                    RowAction('Keep mine', ready ? () => _keepMine(key) : null, id: 'review-keep-${_slug(key)}'),
                  ],
                ),
              ),
        ],
      ),
    ],
    tip: 'Someone changed these on the server after you did. Keep mine keeps your changes and takes theirs everywhere else.',
    docs: Docs.liveApply,
  );

  Widget _blockers(List<DraftChange> drafts) => _section('review-problems', 'Fix before Apply', <Widget>[
    rowList(
      separated: true,
      attributes: const <String, String>{'data-testid': 'review-problems'},
      <Widget>[
        for (final DraftChange draft in drafts)
          _row(
            key: draft.key,
            name: _nameOf(draft.key),
            meta: <Widget>[
              StatusBadge(_problemLabel(draft), tone: BadgeTone.error),
              _meta(draft.problem ?? listNoun(draft.kind)),
            ],
            actions: _href(draft.kind, draft.id) == null
                ? null
                : ActionRow(dense: true, label: _nameOf(draft.key), secondary: <RowAction>[RowAction('Fix', () => navigate(_href(draft.kind, draft.id)!))]),
          ),
      ],
    ),
  ]);

  String _problemLabel(DraftChange draft) {
    if (draft.problem != null) return 'Not valid JSON';
    final int count = _store.libraryEntry(draft.kind, draft.id)?.problems ?? 0;
    return count == 1 ? '1 problem' : '$count problems';
  }

  Widget _changes(List<DraftChange> drafts, bool advanced) {
    final ServerBinding? binding = _session.binding;
    return _section('review-changes', 'Changes (${drafts.length})', <Widget>[
      rowList(
        separated: true,
        attributes: const <String, String>{'data-testid': 'review-changes'},
        <Widget>[
          for (final DraftChange draft in drafts)
            _row(
              key: draft.key,
              name: _nameOf(draft.key),
              href: _href(draft.kind, draft.id),
              meta: <Widget>[
                _meta('${listNoun(draft.kind)}, ${changeLine(draft, base: binding?.bases[draft.key]?.source, mine: _store.document(draft.kind, draft.id)?.json)}'),
                if (advanced) dom.code(<Widget>[Component.text(draft.id)]),
              ],
              actions: ActionRow(
                dense: true,
                label: _nameOf(draft.key),
                moreId: 'review-more-${_slug(draft.key)}',
                more: <AppMenuEntry>[AppMenuEntry('Discard changes', binding == null || discardOf(draft, binding) == null ? null : () => _discard(draft), destructive: true)],
              ),
            ),
        ],
      ),
    ]);
  }

  /// Advanced's part of Review; the change note only while [changes] wait.
  List<Widget> _advanced(LiveSession session, PreviewState? preview, bool ready, {required bool changes}) {
    final ServerBinding? binding = session.binding;
    final List<AppliedChange> history = (binding?.history ?? const <AppliedChange>[]).reversed.take(10).toList();
    return <Widget>[
      if (binding != null && binding.pending.isNotEmpty && session.pendingProblem != null)
        AppAlert(
          tone: AlertTone.warning,
          title: 'An earlier apply has no answer',
          message: session.pendingProblem!.cause,
          action: ActionRow(
            dense: true,
            label: 'Earlier apply',
            secondary: <RowAction>[RowAction('Stop waiting', session.busy == null && session.owned ? session.forgetPending : null), RowAction('Check again', ready ? session.checkPending : null)],
          ),
        ),
      if (preview != null)
        stack(spacing: 4, <Widget>[
          dom.p(
            attributes: const <String, String>{'data-testid': 'preview-state'},
            <Widget>[
              Component.text(
                preview.running
                    ? 'Preview in game for your player only (${preview.npcs.map((String id) => _store.libraryEntry(DocumentKind.npc, id)?.name ?? id).join(', ')})'
                          '${preview.expiresAt == null ? '' : ', until ${preview.expiresAt!.toLocal().toString().substring(11, 16)}'}. Commands, combat and the economy are off.'
                    : 'The preview ${preview.stateLine}.',
              ),
            ],
          ),
          if (preview.hiddenLine != null) dom.p(attributes: const <String, String>{'data-testid': 'preview-hidden'}, <Widget>[Component.text(preview.hiddenLine!)]),
        ]),
      if (changes)
        Field(
          label: 'Change note',
          forId: 'review-note',
          tip: 'Saved with the change on the server, shown in its history.',
          docs: Docs.liveApply,
          child: TextInput(id: 'review-note', value: _note, placeholder: 'Optional', fullWidth: true, onChanged: (String value) => setState(() => _note = value)),
        ),
      if (history.isNotEmpty)
        _section('review-history', 'Applied', <Widget>[
          rowList(
            separated: true,
            attributes: const <String, String>{'data-testid': 'review-history'},
            <Widget>[
              for (final AppliedChange change in history)
                _row(
                  key: change.changeId,
                  name: historyLine(change, _nameOf),
                  meta: <Widget>[_meta(change.appliedAt.toLocal().toString().substring(0, 16)), if (change.undone) const StatusBadge('Undone')],
                  actions: change.undone || change.undoOf != null
                      ? null
                      : ActionRow(
                          dense: true,
                          label: 'Apply ${change.changeId}',
                          secondary: <RowAction>[RowAction('Undo', ready ? () => _undo(change) : null, id: 'review-undo-${_slug(change.changeId)}')],
                        ),
                ),
            ],
          ),
        ]),
      if (session.log.isNotEmpty)
        _section('review-activity', 'Activity', <Widget>[
          rowList(classes: 'ui-review-log', <Widget>[
            for (final LiveNotice notice in session.log.reversed.take(activityLimit))
              listItem(
                attributes: <String, String>{'data-at': '${notice.at.microsecondsSinceEpoch}', 'data-level': notice.level.name},
                Row(
                  spacing: 8,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    _meta(notice.at.toLocal().toString().substring(11, 19)),
                    Expanded(
                      child: Text(notice.message, size: FontSize.sm, color: notice.level == NoticeLevel.error ? TextColor.error : null),
                    ),
                  ],
                ),
              ),
          ]),
        ]),
    ];
  }
}
