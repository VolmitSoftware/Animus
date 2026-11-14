/// The parts every Home shares, a server's or a workspace's: a link card per
/// job with its count, the documents that need attention, and the first
/// steps checklist until it is hidden.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../model/document_kind.dart';
import '../../state/workspace_route.dart';
import '../../ui/badge.dart';
import '../../ui/editor_mode.dart';
import '../../ui/icon_button.dart';
import '../../ui/mode.dart';
import '../../ui/recent.dart';
import '../../ui/tone.dart';
import '../list_page.dart' show listNoun;
import '../shell_sidebar.dart';
import 'page_parts.dart';
import 'start_rows.dart';
import '../../ui/stack.dart';

/// A link card per job of the nav (Simple's jobs, all in Advanced) with how
/// many documents it holds.
class HomeCards extends StatelessWidget {
  const HomeCards({required this.workspaceId, required this.counts, required this.server, this.ownRoutes = 0, super.key});

  final String workspaceId;
  final Map<DocumentKind, int> counts;

  /// NPCs with a route of their own, counted under Movement.
  final int ownRoutes;
  final bool server;

  @override
  Widget build(BuildContext context) {
    final EditorMode mode = ModeScope.of(context);
    return dom.nav(attributes: const <String, String>{'aria-label': 'Library'}, <Widget>[
      Carpet(minItemWidth: 160, gap: 12, children: <Widget>[
        for (final NavJob job in visibleJobs(mode, server: server))
          if (job.kinds.isNotEmpty)
            Card(
              href: jobRoute(job, workspaceId, counts: counts).format(),
              classes: 'ui-home-card',
              padding: const EdgeInsets.all(16),
              attributes: <String, String>{'data-job': job.id},
              // The label over the number, so a narrow card never cuts the
              // label short beside a short number.
              children: <Widget>[
                Row(spacing: 8, children: <Widget>[
                  job.icon().sized(IconSize.sm),
                  Expanded(child: Text.truncated(job.label, weight: FontWeight.w500)),
                ]),
                // The nav's count.
                dom.span(attributes: const <String, String>{'data-testid': 'home-count'}, <Widget>[
                  Text(
                    '${jobCount(job, counts, mode, ownRoutes: ownRoutes)}',
                    size: FontSize.xl,
                    weight: FontWeight.w600,
                  ),
                ]),
              ],
            ),
      ]),
    ]);
  }
}

/// A document that needs a look: problems, or changed on the server while
/// it is changed here too.
class AttentionRow {
  const AttentionRow({required this.kind, required this.id, required this.name, this.problems = 0, this.stale = false});

  final DocumentKind kind;
  final String id;
  final String name;
  final int problems;
  final bool stale;
}

/// The most rows Needs attention lists; the rest are counted.
const int attentionLimit = 20;

class NeedsAttention extends StatelessWidget {
  const NeedsAttention({required this.workspaceId, required this.rows, super.key});

  final String workspaceId;
  final List<AttentionRow> rows;

  @override
  Widget build(BuildContext context) {
    final EditorMode mode = ModeScope.of(context);
    // Simple lists its own kinds; a change waiting on a conflict shows in
    // either mode, as Changed does.
    final List<AttentionRow> rows = <AttentionRow>[
      for (final AttentionRow row in this.rows)
        if (row.stale || kindShown(row.kind, mode)) row,
    ];
    if (rows.isEmpty) return const Component.fragment(<Widget>[]);
    return dom.section(attributes: const <String, String>{'aria-labelledby': 'home-attention'}, <Widget>[
      stack(spacing: 12, <Widget>[
        sectionHeading('Needs attention (${rows.length})', id: 'home-attention'),
        Card(
          variant: CardVariant.outlined,
          padding: const EdgeInsets.all(4),
          child: rowList(<Widget>[
            for (final AttentionRow row in rows.take(attentionLimit))
              listItem(
                Card(
                  href: DocumentRoute(workspaceId, row.kind, row.id).format(),
                  variant: CardVariant.ghost,
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                  classes: 'ui-attention-row',
                  children: <Widget>[
                    Wrap(spacing: 8, runSpacing: 4, crossAxisAlignment: WrapCrossAlignment.center, children: <Widget>[
                      Text(row.name, weight: FontWeight.w500),
                      Text(listNoun(row.kind), size: FontSize.sm, color: TextColor.muted),
                      if (row.stale) const StatusBadge('Changed on server', tone: BadgeTone.warning),
                      if (row.problems > 0) StatusBadge(row.problems == 1 ? '1 problem' : '${row.problems} problems', tone: BadgeTone.error),
                    ]),
                  ],
                ),
              ),
          ], spacing: 0),
        ),
        if (rows.length > attentionLimit) mutedText('${rows.length - attentionLimit} more. Filter a list by Has problems to see them.'),
      ]),
    ]);
  }
}

/// Whether this browser hid the first steps.
bool firstStepsHidden() {
  try {
    return web.window.localStorage.getItem(firstStepsHiddenKey) == '1';
  } on Object {
    return false;
  }
}

/// Whether an NPC of [workspaceId] was opened (it is in Recent).
bool openedAnNpc(String workspaceId) {
  try {
    return decodeRecent(web.window.localStorage.getItem(recentStorageKey(workspaceId))).any((String key) => key.startsWith('${DocumentKind.npc.wireName}:'));
  } on Object {
    return false;
  }
}

/// The first steps, ticking themselves, with a button that hides them for
/// good.
class FirstSteps extends StatefulWidget {
  const FirstSteps({required this.steps, super.key});

  final List<FirstStep> steps;

  @override
  State<FirstSteps> createState() => _FirstStepsState();
}

class _FirstStepsState extends State<FirstSteps> {
  bool _hidden = firstStepsHidden();

  void _hide() {
    try {
      web.window.localStorage.setItem(firstStepsHiddenKey, '1');
    } on Object {
      // Storage blocked: hidden for this page.
    }
    setState(() => _hidden = true);
  }

  @override
  Widget build(BuildContext context) {
    if (_hidden) return const Component.fragment(<Widget>[]);
    final int done = component.steps.where((FirstStep step) => step.done).length;
    return Card(
      attributes: const <String, String>{'data-testid': 'first-steps'},
      children: <Widget>[
        stack(spacing: 12, <Widget>[
          splitRow(
            sectionHeading('First steps ($done of ${component.steps.length})'),
            AppIconButton(icon: ArcaneIcon.x(), label: 'Hide first steps', onPressed: _hide),
          ),
          rowList(label: 'First steps', spacing: 8, <Widget>[
            for (final FirstStep step in component.steps)
              listItem(
                attributes: <String, String>{'data-done': '${step.done}'},
                Row(spacing: 8, children: <Widget>[
                  (step.done ? ArcaneIcon.circleCheck() : ArcaneIcon.circle()).sized(IconSize.sm),
                  Wrap(spacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: <Widget>[
                    Text(
                      step.label,
                      color: step.done ? TextColor.muted : null,
                      decoration: step.done ? TextDecoration.lineThrough : null,
                    ),
                    Text(step.done ? 'Done' : step.hint, size: FontSize.sm, color: TextColor.muted),
                  ]),
                ]),
              ),
          ]),
        ]),
      ],
    );
  }
}
