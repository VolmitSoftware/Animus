/// An NPC's (or group's) `behaviors` list: each entry and the add row are
/// pickers over the workspace's NPC behaviors and the connected server's.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/npc/npc_catalog.dart';
import '../../logic/npc/reference_options.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../gui/gui_fields.dart';
import 'npc_section.dart';

class NpcBehaviorList extends StatelessWidget {
  const NpcBehaviorList({
    required this.listed,
    required this.documents,
    required this.onChanged,
    this.serverBehaviors = const <String>[],
    this.diagnostics = const <Diagnostic>[],
    this.help,
    this.onOpen,
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

  @override
  Widget build(BuildContext context) {
    final List<String> behaviorIds = npcBehaviorIds(documents);
    final Set<String> workspace = <String>{
      for (final AnimusDocument document in documents)
        if (document.kind == DocumentKind.behavior) document.id,
    };
    final PickerOptions available = behaviorPickerOptions(documents, listed: listed, server: serverBehaviors);
    const String addId = 'animus-npc-add-behavior';
    return dom.div(
      classes: 'animus-npc-behaviors',
      attributes: const <String, String>{'role': 'group', 'aria-label': 'Behaviors'},
      <Widget>[
        NpcSubhead('Behaviors', help: help),
        if (listed.isEmpty) const dom.p(classes: 'animus-muted', <Widget>[Component.text('No behaviors. Add one below.')]),
        for (int index = 0; index < listed.length; index++)
          dom.div(key: ValueKey<String>('behavior-$index'), classes: 'animus-inspect-item', <Widget>[
            dom.div(classes: 'animus-gui-line', <Widget>[
              GuiSelect(
                references: DocumentKind.behavior,
                value: listed[index].isEmpty ? null : listed[index],
                options: <String>[listed[index], ...available.ids],
                groups: available.groups,
                noneLabel: 'Pick a behavior',
                hiddenLabel: 'Behavior ${index + 1}',
                onChanged: (String? value) {
                  if (value != null) onChanged(<String>[...listed]..[index] = value);
                },
              ),
              if (onOpen != null && workspace.contains(listed[index]))
                Button.ghost(label: 'Open', size: ButtonSize.small, onPressed: () => onOpen!(DocumentKind.behavior, listed[index])),
              GuiIconButton(
                icon: ArcaneIcon.arrowUp(size: IconSize.sm),
                label: 'Move behavior ${index + 1} up',
                disabled: index == 0,
                onPressed: () => onChanged(<String>[for (final Object? id in moveItem(listed, index, index - 1)) id! as String]),
              ),
              GuiIconButton(
                icon: ArcaneIcon.x(size: IconSize.sm),
                label: 'Remove behavior ${index + 1}',
                onPressed: () => onChanged(<String>[...listed]..removeAt(index)),
              ),
            ]),
            if (workspace.contains(listed[index]) && !behaviorIds.contains(listed[index]))
              const dom.span(classes: 'animus-gui-problem is-error', <Widget>[
                Component.text('Server-wide behavior. List it in server-behaviors.json.'),
              ])
            else if (!workspace.contains(listed[index]) && !serverBehaviors.contains(listed[index]))
              const dom.span(classes: 'animus-gui-problem is-warning', <Widget>[Component.text('Not in this workspace; may be on the server.')]),
            for (final Diagnostic problem in diagnosticsAt(diagnostics, '\$.behaviors[$index]'))
              dom.span(classes: 'animus-gui-problem is-${problem.severity.name}', <Widget>[Component.text(problem.message)]),
          ]),
        dom.div(classes: 'animus-row', <Widget>[
          const dom.label(classes: 'animus-sr-only', attributes: <String, String>{'for': addId}, <Widget>[Component.text('Add a behavior')]),
          GuiSelect(
            references: DocumentKind.behavior,
            id: addId,
            value: null,
            options: available.ids,
            groups: available.groups,
            noneLabel: 'Add a behavior',
            onChanged: (String? id) {
              if (id != null) onChanged(<String>[...listed, id]);
            },
          ),
        ]),
      ],
    );
  }
}
