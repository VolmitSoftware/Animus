/// The Visual view of `server-behaviors.json`: the server behaviors that
/// run, in order. Add from the workspace's server behaviors, remove and
/// reorder; ids the workspace does not hold, or that are not server
/// behaviors, are flagged but kept.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/inspect/server_behaviors.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../gui/gui_fields.dart';

class ServerBehaviorsEditor extends StatelessWidget {
  const ServerBehaviorsEditor({required this.document, required this.onJson, required this.workspaceDocuments, this.onOpen, this.diagnostics = const <Diagnostic>[], super.key});

  final AnimusDocument document;
  final JsonEdit onJson;
  final List<AnimusDocument> workspaceDocuments;
  final void Function(DocumentKind kind, String id)? onOpen;
  final List<Diagnostic> diagnostics;

  @override
  Widget build(BuildContext context) {
    final JsonMap json = asMap(document.json);
    final List<String> listed = stringList(json['behaviors']);
    final Map<String, bool> behaviors = workspaceBehaviors(workspaceDocuments);
    final List<String> addable = <String>[for (final MapEntry<String, bool> entry in behaviors.entries) if (entry.value && !listed.contains(entry.key)) entry.key];
    void put(List<String> next) => onJson(writeAt(json, const <JsonStep>[], <String, Object?>{...json, 'schemaVersion': 1, 'behaviors': next}), coalesce: 'server-behaviors');
    return dom.section(classes: 'animus-panel animus-inspector animus-server-behaviors', attributes: const <String, String>{'aria-label': 'Server behaviors list'}, <Widget>[
      const dom.h2(<Widget>[Component.text('Server behaviors that run')]),
      const dom.p(classes: 'animus-gui-help', <Widget>[
        Component.text('Only the behaviors listed here run on the server, in this order. A behavior must have the server switch on to be listed; '
            'a missing or broken list runs none. At most $serverBehaviorsMaximum.'),
      ]),
      for (final Diagnostic problem in diagnostics)
        dom.p(classes: 'animus-gui-problem is-${problem.severity.name}', attributes: const <String, String>{'role': 'note'}, <Widget>[Component.text('${problem.path}: ${problem.message}')]),
      if (listed.isEmpty)
        dom.p(classes: 'animus-muted', <Widget>[
          Component.text(addable.isEmpty
              ? 'The list is empty, so no server behavior runs. Turn on the server switch in a behavior\'s Document section, then add it here.'
              : 'The list is empty, so no server behavior runs. Add one below.'),
        ]),
      dom.ol(classes: 'animus-server-list', attributes: const <String, String>{'aria-label': 'Listed server behaviors'}, <Widget>[
        for (int index = 0; index < listed.length; index++)
          dom.li(key: ValueKey<String>('listed-$index'), classes: 'animus-server-item', attributes: <String, String>{'data-behavior': listed[index]}, <Widget>[
            dom.code(<Widget>[Component.text(listed[index])]),
            dom.span(classes: 'animus-muted', <Widget>[Component.text(serverBehaviorNote(listed[index], behaviors))]),
            dom.div(classes: 'animus-row', <Widget>[
              if (onOpen != null && behaviors.containsKey(listed[index]))
                Button.ghost(label: 'Open', size: ButtonSize.small, onPressed: () => onOpen!(DocumentKind.behavior, listed[index])),
              GuiIconButton(icon: ArcaneIcon.arrowUp(size: IconSize.sm), label: 'Move ${listed[index]} up', disabled: index == 0, onPressed: () => put(<String>[for (final Object? id in moveItem(listed, index, index - 1)) id! as String])),
              GuiIconButton(
                icon: ArcaneIcon.arrowDown(size: IconSize.sm),
                label: 'Move ${listed[index]} down',
                disabled: index == listed.length - 1,
                onPressed: () => put(<String>[for (final Object? id in moveItem(listed, index, index + 1)) id! as String]),
              ),
              GuiIconButton(icon: ArcaneIcon.x(size: IconSize.sm), label: 'Remove ${listed[index]} from the list', onPressed: () => put(<String>[...listed]..removeAt(index))),
            ]),
          ]),
      ]),
      if (listed.length >= serverBehaviorsMaximum)
        const dom.p(classes: 'animus-gui-problem is-warning', <Widget>[Component.text('The list is full; remove one to add another.')])
      else if (addable.isNotEmpty)
        GuiSelect(
          value: null,
          options: addable,
          noneLabel: 'Add a server behavior',
          label: 'Add a server behavior',
          onChanged: (String? id) {
            if (id != null) put(<String>[...listed, id]);
          },
        )
      else if (listed.isNotEmpty)
        const dom.p(classes: 'animus-muted', <Widget>[Component.text('Every server behavior in this workspace is listed.')]),
    ]);
  }
}
