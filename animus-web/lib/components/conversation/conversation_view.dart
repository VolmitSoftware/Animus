/// The Conversation tab of a behavior document: the start, the tree of
/// lines, questions, conditions and actions, a playthrough, and the NPCs
/// that run it. Every edit rewrites the behavior's routines and triggers
/// through [writeConversation]; a behavior that does not fit shows why and
/// stays in the Visual editor.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/conversation/conversation_check.dart';
import '../../logic/conversation/conversation_edit.dart';
import '../../logic/conversation/conversation_json.dart';
import '../../logic/conversation/conversation_model.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_text.dart';
import '../../logic/inspect/completion_catalog.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../gui/gui_fields.dart';
import '../info_tip.dart';
import 'conversation_attach.dart';
import 'conversation_fields.dart';
import 'conversation_input.dart';
import 'conversation_picker.dart';
import 'conversation_preview.dart';
import 'conversation_tree.dart';

class ConversationView extends StatefulWidget {
  const ConversationView({
    required this.document,
    required this.onJson,
    required this.catalog,
    this.workspaceDocuments = const <AnimusDocument>[],
    this.onCommit,
    this.onServerPreview,
    this.onOpenVisual,
    this.newHref,
    super.key,
  });

  final AnimusDocument document;
  final JsonEdit onJson;
  final CompletionCatalog catalog;
  final List<AnimusDocument> workspaceDocuments;

  /// Writes several documents as one change; null hides the NPC list.
  final ConversationCommit? onCommit;

  /// Starts the paired server's preview; null when there is none.
  final void Function()? onServerPreview;

  /// Switches to the Visual tab.
  final void Function()? onOpenVisual;

  /// Where "New conversation" leads.
  final String? newHref;

  @override
  State<ConversationView> createState() => _ConversationViewState();
}

class _ConversationViewState extends State<ConversationView> {
  final Set<String> _expanded = <String>{};

  void _edit(void Function(Conversation conversation) change, {String? coalesce, String? focus, String? focusValue}) {
    component.onJson((Object? latest) {
      final JsonMap json = asMap(latest);
      final Conversation? conversation = conversationOf(json);
      if (conversation == null) return latest;
      change(conversation);
      return writeConversation(conversation, json);
    }, coalesce: coalesce == null ? null : 'conversation:$coalesce');
    if (focus != null) focusField(focus, value: focusValue);
  }

  void _toggle(String key) => setState(() => _expanded.contains(key) ? _expanded.remove(key) : _expanded.add(key));

  String get _namespace => component.document.id.split(':').first;

  List<AnimusDocument> _of(DocumentKind kind) => <AnimusDocument>[
        for (final AnimusDocument document in component.workspaceDocuments)
          if (document.kind == kind) document,
      ];

  /// The name of the first NPC that lists this behavior, for the preview.
  String get _npcName {
    for (final AnimusDocument npc in _of(DocumentKind.npc)) {
      if (listedBehaviors(npc).contains(component.document.id)) return guiPlainText(asString(asMap(npc.json)['name']) ?? npc.id);
    }
    return 'NPC';
  }

  @override
  Widget build(BuildContext context) {
    final ConversationRead read = readConversation(asMap(component.document.json));
    return dom.section(classes: 'animus-panel animus-conv', attributes: const <String, String>{'aria-label': 'Conversation'}, <Widget>[
      switch (read) {
        ConversationMisfit(:final String path, :final String reason) => _misfit(path, reason),
        ConversationFits(:final Conversation conversation) => _editor(conversation),
      },
    ]);
  }

  Widget _misfit(String path, String reason) => dom.div(classes: 'animus-conv-misfit', attributes: const <String, String>{'role': 'note'}, <Widget>[
        conversationKeysTip('Conversation'),
        dom.p(<Widget>[
          const Component.text('Not a conversation: '),
          Component.text(reason),
          const Component.text(' '),
          dom.code(<Widget>[Component.text(path)]),
        ]),
        dom.div(classes: 'animus-row', <Widget>[
          if (component.onOpenVisual != null) Button.secondary(label: 'Edit in Visual', size: ButtonSize.small, onPressed: component.onOpenVisual),
          if (component.newHref != null) Button.ghost(label: 'New conversation', size: ButtonSize.small, href: component.newHref),
        ]),
      ]);

  Widget _editor(Conversation conversation) {
    final List<Object?> namespace = <Object?>[
      for (final AnimusDocument behavior in _of(DocumentKind.behavior))
        if (behavior.id.split(':').first == _namespace) behavior.json,
    ];
    final List<ConversationIssue> issues = checkConversation(conversation, names: knownNames(namespace));
    final ConversationScope scope = ConversationScope(
      conversation: conversation,
      issues: issues,
      catalog: component.catalog,
      edit: _edit,
      expanded: _expanded,
      toggle: _toggle,
      suggestions: ConversationSuggestions(
        animations: <String>[for (final AnimusDocument animation in _of(DocumentKind.animation)) animation.id],
        shops: <String>[for (final AnimusDocument shop in _of(DocumentKind.shop)) shop.id],
      ),
    );
    final int errors = issues.where((ConversationIssue issue) => issue.error).length;
    return dom.div(classes: 'animus-conv-layout', <Widget>[
      dom.div(classes: 'animus-conv-main', <Widget>[
        dom.div(classes: 'animus-conv-toolbar', <Widget>[
          conversationKeysTip('Conversation'),
          if (errors > 0) dom.span(classes: 'animus-conv-errors', attributes: <String, String>{'title': 'Problems', 'data-conv-errors': '$errors'}, <Widget>[Component.text('$errors')]),
          if (component.newHref != null)
            Button.ghost(label: 'New conversation', icon: ArcaneIcon.plus(size: IconSize.sm), size: ButtonSize.small, href: component.newHref),
        ]),
        _start(conversation),
        for (final ConversationIssue issue in issues)
          if (issue.at == null && issue.block == null)
            dom.p(classes: 'animus-gui-problem is-${issue.error ? 'error' : 'warning'}', attributes: const <String, String>{'role': 'note'}, <Widget>[Component.text(issue.message)]),
        ConversationTree(scope: scope),
      ]),
      dom.div(classes: 'animus-conv-side', <Widget>[
        ConversationPreview(key: ValueKey<String>('conv-preview:${component.document.id}'), conversation: conversation, npcName: _npcName, onServerPreview: component.onServerPreview),
        if (component.onCommit != null)
          ConversationAttach(
            key: ValueKey<String>('conv-attach:${component.document.id}'),
            behavior: component.document.id,
            documents: component.workspaceDocuments,
            onCommit: component.onCommit!,
          ),
      ]),
    ]);
  }

  Widget _start(Conversation conversation) {
    final ConversationStart start = conversation.start;
    const String startId = 'conv-start';
    return dom.div(classes: 'animus-conv-start', <Widget>[
      dom.label(classes: 'animus-gui-label', attributes: const <String, String>{'for': startId}, <Widget>[
        withTip(
          const Component.text('Start'),
          'What starts it for the NPCs below. Command means /animus behaviors run <npc> <behavior>, which needs animus.admin.',
          docs: conversationDocs,
        ),
      ]),
      ConversationPicker(
        id: startId,
        label: 'Start',
        value: start.kind.name,
        options: <PickOption>[for (final ConversationStartKind kind in ConversationStartKind.values) PickOption(kind.name, kind.label)],
        onPick: (String value) => _edit((Conversation c) => setStart(c, ConversationStartKind.values.byName(value))),
      ),
      if (start.kind == ConversationStartKind.proximity)
        GuiNumberInput(
          id: 'conv-radius',
          value: start.radius,
          integer: false,
          label: 'Radius in blocks',
          digits: 5,
          onChanged: (num? value) {
            if (value != null) _edit((Conversation c) => c.start.radius = value, coalesce: 'radius');
          },
        ),
      if (start.kind == ConversationStartKind.proximity) const dom.span(classes: 'animus-muted', <Widget>[Component.text('blocks, 1 to 64')]),
      if (start.kind == ConversationStartKind.manual)
        dom.code(classes: 'animus-muted', <Widget>[Component.text('/animus behaviors run <npc> ${component.document.id}')]),
    ]);
  }
}
