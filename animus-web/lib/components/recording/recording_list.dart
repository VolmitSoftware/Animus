/// Every recording of a workspace in one table: length, frames, events,
/// world and file size, each row linking to the recording.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/recording/recording_model.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../services/browser.dart' show mainHeadingId;
import '../../state/workspace_repository.dart';
import '../../state/workspace_route.dart';
import '../info_tip.dart';

class RecordingList extends StatelessWidget {
  const RecordingList({required this.workspace, super.key});

  final WorkspaceSnapshot workspace;

  static const List<String> _columns = <String>['Recording', 'Length', 'Frames', 'Events', 'World', 'Size'];

  @override
  Widget build(BuildContext context) {
    final List<AnimusDocument> recordings = <AnimusDocument>[
      for (final AnimusDocument document in workspace.documents)
        if (document.kind == DocumentKind.recording) document,
    ]..sort((AnimusDocument a, AnimusDocument b) => a.id.compareTo(b.id));
    return dom.section(classes: 'animus-panel animus-recording-list', attributes: const <String, String>{'aria-labelledby': mainHeadingId}, <Widget>[
      labelWithTip(
        dom.h1(id: mainHeadingId, attributes: const <String, String>{'tabindex': '-1'}, <Widget>[
          const Component.text('Recordings'),
          dom.span(classes: 'animus-count', <Widget>[Component.text('${recordings.length}')]),
        ]),
        'Made in game with /animus record. Only rename and delete apply here.',
        'Recordings',
        docs: Docs.recordings,
      ),
      if (recordings.isEmpty)
        const dom.p(classes: 'animus-muted', attributes: <String, String>{'data-recording': 'list-empty'}, <Widget>[
          Component.text('No recordings in this workspace. Record one in game with /animus record start <id>.'),
        ])
      else ...<Widget>[
        dom.div(classes: 'animus-recording-table-wrap', <Widget>[
          dom.table(classes: 'animus-recording-table', attributes: const <String, String>{'data-recording': 'list'}, <Widget>[
            dom.thead(<Widget>[
              dom.tr(<Widget>[
                for (final String column in _columns) dom.th(attributes: const <String, String>{'scope': 'col'}, <Widget>[Component.text(column)]),
              ]),
            ]),
            dom.tbody(<Widget>[for (final AnimusDocument document in recordings) _row(document)]),
          ]),
        ]),
      ],
    ]);
  }

  Widget _row(AnimusDocument document) {
    final Recording? recording = Recording.parse(document.json);
    final RecordingSummary? summary = recording == null ? null : RecordingSummary.of(recording, document.source);
    final String href = DocumentRoute(workspace.id, DocumentKind.recording, document.id).format();
    return dom.tr(attributes: <String, String>{'data-recording-id': document.id}, <Widget>[
      dom.th(attributes: const <String, String>{'scope': 'row'}, <Widget>[
        dom.a(href: href, <Widget>[Component.text(document.id)]),
        if (!document.valid) const dom.span(classes: 'animus-badge is-error', <Widget>[Component.text('invalid')]),
      ]),
      if (summary == null)
        const dom.td(attributes: <String, String>{'colspan': '5'}, <Widget>[Component.text('Not a JSON object; open it to see the problem.')])
      else ...<Widget>[
        dom.td(<Widget>[Component.text(formatRecordingLength(summary.durationTicks))]),
        dom.td(<Widget>[Component.text('${summary.frames}')]),
        dom.td(<Widget>[Component.text('${summary.events}')]),
        dom.td(<Widget>[Component.text(summary.world ?? '-')]),
        dom.td(<Widget>[Component.text(formatRecordingSize(summary.bytes))]),
      ],
    ]);
  }
}
