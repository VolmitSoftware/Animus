/// Which NPCs and groups run the conversation: a checklist of every NPC and
/// group in the workspace, ticked where its `behaviors` list names the
/// behavior. Attach writes every changed list in one undoable change.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_text.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../state/editor_store.dart';
import '../gui/gui_fields.dart';
import '../info_tip.dart';
import 'conversation_fields.dart';

/// Writes [changes] as one undoable change named [label].
typedef ConversationCommit = Future<void> Function(List<DocumentChange> changes, String label);

/// The `behaviors` list of an NPC, or of a group's `values`.
List<String> listedBehaviors(AnimusDocument document) {
  final JsonMap json = asMap(document.json);
  return stringList(document.kind == DocumentKind.group ? asMap(json['values'])['behaviors'] : json['behaviors']);
}

/// [json] of an NPC or group with [behavior] added to or removed from its
/// `behaviors` list. An emptied list is removed.
JsonMap withBehavior(DocumentKind kind, JsonMap json, String behavior, {required bool listed}) {
  JsonMap put(JsonMap holder) {
    final List<String> current = stringList(holder['behaviors']);
    final List<String> next = listed ? <String>{...current, behavior}.toList() : <String>[for (final String id in current) if (id != behavior) id];
    final JsonMap out = <String, Object?>{...holder, 'behaviors': next};
    if (next.isEmpty) out.remove('behaviors');
    return out;
  }

  if (kind == DocumentKind.group) return <String, Object?>{...json, 'values': put(asMap(json['values']))};
  return put(json);
}

class ConversationAttach extends StatefulWidget {
  const ConversationAttach({required this.behavior, required this.documents, required this.onCommit, super.key});

  final String behavior;

  /// The workspace; its NPCs and groups are listed.
  final List<AnimusDocument> documents;
  final ConversationCommit onCommit;

  @override
  State<ConversationAttach> createState() => _ConversationAttachState();
}

class _ConversationAttachState extends State<ConversationAttach> {
  /// Ticks the author changed and has not saved, by `kind:id`.
  final Map<String, bool> _pending = <String, bool>{};
  bool _busy = false;

  static String _key(AnimusDocument document) => '${document.kind.wireName}:${document.id}';

  bool _listed(AnimusDocument document) => listedBehaviors(document).contains(component.behavior);

  Future<void> _save(List<AnimusDocument> targets) async {
    final List<DocumentChange> changes = <DocumentChange>[
      for (final AnimusDocument document in targets)
        if (_pending[_key(document)] case final bool listed when listed != _listed(document))
          DocumentChange.update(document.kind, document.id, (Object? latest) => withBehavior(document.kind, asMap(latest), component.behavior, listed: listed)),
    ];
    if (changes.isEmpty) return;
    setState(() => _busy = true);
    try {
      await component.onCommit(changes, 'attach ${component.behavior}');
      _pending.clear();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final List<AnimusDocument> targets = <AnimusDocument>[
      for (final AnimusDocument document in component.documents)
        if ((document.kind == DocumentKind.npc || document.kind == DocumentKind.group) && document.json is Map<String, Object?>) document,
    ];
    final int changed = targets.where((AnimusDocument document) => _pending.containsKey(_key(document)) && _pending[_key(document)] != _listed(document)).length;
    final int using = targets.where(_listed).length;
    return dom.section(classes: 'animus-conv-attach', attributes: const <String, String>{'aria-label': 'NPCs'}, <Widget>[
      dom.div(classes: 'animus-conv-preview-head', <Widget>[
        labelWithTip(
          dom.h3(<Widget>[Component.text(using == 0 ? 'NPCs' : 'NPCs ($using)')]),
          'NPCs and groups that list this behavior run its start. Tick them, then Attach.',
          'NPCs',
          docs: conversationDocs,
        ),
        Button.primary(
          label: changed == 0 ? 'Attach' : 'Attach ($changed)',
          size: ButtonSize.small,
          disabled: changed == 0 || _busy,
          onPressed: () => _save(targets),
        ),
      ]),
      if (targets.isEmpty)
        const dom.p(classes: 'animus-muted', <Widget>[Component.text('No NPCs or groups yet.')])
      else
        dom.ul(classes: 'animus-conv-targets', <Widget>[
          for (final AnimusDocument document in targets)
            dom.li(attributes: <String, String>{'data-target': _key(document)}, <Widget>[
              GuiSwitch(
                value: _pending[_key(document)] ?? _listed(document),
                label: '${document.kind == DocumentKind.group ? 'Group ' : ''}${guiPlainText(document.title)}${document.title == document.id ? '' : ' (${document.id})'}',
                onChanged: (bool on) => setState(() => _pending[_key(document)] = on),
              ),
            ]),
        ]),
    ]);
  }
}
