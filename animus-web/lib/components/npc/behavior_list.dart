/// An NPC's (or group's) `behaviors` list: each entry and the add row are
/// pickers over the workspace's NPC behaviors and the connected server's.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../logic/gui/gui_json.dart';
import '../../logic/npc/npc_catalog.dart';
import '../../logic/npc/reference_options.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../gui/gui_fields.dart';
import '../../config/docs_links.dart';
import '../../ui/action_row.dart';
import '../../ui/field.dart';
import '../../ui/menu.dart';
import 'npc_parts.dart';
import '../select/animus_select.dart';
import 'npc_layout.dart';
import '../../ui/problem_text.dart';

class NpcBehaviorList extends StatelessWidget {
  const NpcBehaviorList({
    required this.listed,
    required this.documents,
    required this.onChanged,
    this.serverBehaviors = const <String>[],
    this.diagnostics = const <Diagnostic>[],
    this.help,
    this.onOpen,
    this.onNewConversation,
    super.key,
  });

  final List<String> listed;

  /// The workspace.
  final List<AnimusDocument> documents;

  /// Behavior ids in the connected server's catalog.
  final List<String> serverBehaviors;

  /// The new list; empty removes the field.
  final void Function(List<String> next) onChanged;
  final List<Diagnostic> diagnostics;
  final String? help;
  final void Function(DocumentKind kind, String id)? onOpen;

  /// Starts a new conversation; null leaves the button out.
  final void Function()? onNewConversation;

  @override
  Widget build(BuildContext context) {
    final List<String> behaviorIds = npcBehaviorIds(documents);
    final Set<String> workspace = <String>{
      for (final AnimusDocument document in documents)
        if (document.kind == DocumentKind.behavior) document.id,
    };
    final PickerOptions available = behaviorPickerOptions(documents, listed: listed, server: serverBehaviors);
    const String addId = 'animus-npc-add-behavior';
    return Field(
      label: 'Behaviors',
      forId: addId,
      tip: help ?? 'What the NPC does when something happens: a click, a player nearby, a timer. Conversations are behaviors that talk.',
      docs: Docs.behaviors,
      child: dom.div(
        styles: stackStyles(),
        attributes: const <String, String>{'role': 'group', 'aria-label': 'Behaviors'},
        <Widget>[
          for (int index = 0; index < listed.length; index++)
            dom.div(key: ValueKey<String>('behavior-$index'), classes: 'animus-npc-behavior', <Widget>[
              ActionRow(
                label: 'Behavior ${index + 1}',
                leading: <Widget>[
                  NpcPick(child: AnimusSelect.values(
                    references: DocumentKind.behavior,
                    value: listed[index].isEmpty ? null : listed[index],
                    values: <String>[listed[index], ...available.ids],
                    groups: available.groups,
                    noneLabel: 'Pick a behavior',
                    hiddenLabel: 'Behavior ${index + 1}',
                    onChanged: (String? value) {
                      if (value != null) onChanged(<String>[...listed]..[index] = value);
                    },
                  )),
                ],
                // One menu per row, so every select ends on the same line.
                more: <AppMenuEntry>[
                  if (onOpen != null && workspace.contains(listed[index])) AppMenuEntry('Open', () => onOpen!(DocumentKind.behavior, listed[index])),
                  AppMenuEntry('Move up', index == 0 ? null : () => onChanged(<String>[for (final Object? id in moveItem(listed, index, index - 1)) id! as String])),
                  AppMenuEntry('Move down', index == listed.length - 1 ? null : () => onChanged(<String>[for (final Object? id in moveItem(listed, index, index + 1)) id! as String])),
                  const AppMenuEntry.separator(),
                  AppMenuEntry('Remove', () => onChanged(<String>[...listed]..removeAt(index)), destructive: true),
                ],
              ),
              if (workspace.contains(listed[index]) && !behaviorIds.contains(listed[index]))
                problemText('A server-wide behavior. List it in the server behaviors list instead.')
              else if (!workspace.contains(listed[index]) && !serverBehaviors.contains(listed[index]))
                problemText('Not in this workspace; the server may have it.', severity: DiagnosticSeverity.warning),
              for (final Diagnostic problem in diagnosticsAt(diagnostics, '\$.behaviors[$index]'))
                problemText(problem.message, severity: problem.severity),
            ]),
          ActionRow(
            label: 'Add behavior',
            leading: <Widget>[
              NpcPick(child: AnimusSelect.values(
                references: DocumentKind.behavior,
                id: addId,
                value: null,
                values: available.ids,
                groups: available.groups,
                noneLabel: listed.isEmpty ? 'Add a behavior' : 'Add another',
                onChanged: (String? id) {
                  if (id != null) onChanged(<String>[...listed, id]);
                },
              )),
            ],
            secondary: <RowAction>[
              if (onNewConversation != null)
                RowAction('New conversation', onNewConversation, icon: ArcaneIcon.plus(), iconOnly: true, attributes: const <String, String>{'data-npc': 'new-conversation'}),
            ],
          ),
        ],
      ),
    );
  }
}
