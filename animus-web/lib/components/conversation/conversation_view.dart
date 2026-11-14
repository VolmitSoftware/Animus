/// A behavior as a conversation: how it starts, the NPCs that run it, and
/// its steps as cards. Every edit rewrites the behavior's routines and
/// triggers through [writeConversation]. The frame shows it for behaviors
/// that fit; reached in Advanced for one that does not, it says why.
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
import '../../model/document_summary.dart';
import '../../state/library.dart';
import '../../ui/alert.dart';
import '../../ui/density.dart';
import '../../ui/field.dart';
import '../../ui/info_tip.dart';
import '../../ui/mode.dart';
import '../gui/gui_fields.dart';
import '../pending_field.dart';
import 'conversation_attach.dart';
import 'conversation_fields.dart';
import 'conversation_input.dart';
import 'conversation_picker.dart';
import 'conversation_tree.dart';
import '../../ui/number_input.dart';
import '../../ui/stack.dart';

class ConversationView extends StatefulWidget {
  const ConversationView({
    required this.document,
    required this.onJson,
    required this.catalog,
    this.workspaceDocuments = const <AnimusDocument>[],
    this.library,
    this.load,
    this.onCommit,
    super.key,
  });

  final AnimusDocument document;
  final JsonEdit onJson;
  final CompletionCatalog catalog;
  final List<AnimusDocument> workspaceDocuments;

  /// The workspace library, for the NPC picker; null lists the documents read.
  final LibraryOf? library;

  /// Reads a picked NPC that is not here yet.
  final LoadDocuments? load;

  /// Writes several documents as one change; null hides the NPC picker.
  final ConversationCommit? onCommit;

  @override
  State<ConversationView> createState() => _ConversationViewState();
}

class _ConversationViewState extends State<ConversationView> {
  final Set<String> _expanded = <String>{};

  void _edit(void Function(Conversation conversation) change, {String? coalesce, String? focus, String? focusValue}) {
    // A field added empty to be typed in is pending until it loses focus.
    if (focus != null && focusValue == '') holdField(focus);
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

  /// Entries for the documents read, when the frame gives no library.
  List<LibraryEntry> _libraryOfRead(DocumentKind kind) => <LibraryEntry>[
        for (final AnimusDocument document in _of(kind))
          LibraryEntry(kind: kind, id: document.id, path: document.path, name: guiPlainText(document.title).trim(), summary: DocumentSummary.empty),
      ];

  String get _namespace => component.document.id.split(':').first;

  List<AnimusDocument> _of(DocumentKind kind) => <AnimusDocument>[
        for (final AnimusDocument document in component.workspaceDocuments)
          if (document.kind == kind) document,
      ];

  @override
  Widget build(BuildContext context) {
    final bool advanced = ModeScope.advanced(context);
    final ConversationRead read = readConversation(asMap(component.document.json));
    return dom.div(classes: 'animus-conv', attributes: const <String, String>{'aria-label': 'Conversation'}, <Widget>[
      switch (read) {
        ConversationMisfit(:final String path, :final String reason) => AppAlert(
            tone: AlertTone.warning,
            title: 'Not a conversation',
            message: advanced ? '$reason ($path) Edit it in Steps.' : '$reason Edit it in Advanced.',
          ),
        ConversationFits(:final Conversation conversation) => _editor(conversation, advanced),
      },
    ]);
  }

  Widget _editor(Conversation conversation, bool advanced) {
    final List<Object?> namespace = <Object?>[
      for (final AnimusDocument behavior in _of(DocumentKind.behavior))
        if (behavior.id.split(':').first == _namespace) behavior.json,
    ];
    final String? pending = pendingProblems.field;
    final List<ConversationIssue> issues = <ConversationIssue>[
      for (final ConversationIssue issue in checkConversation(conversation, names: knownNames(namespace)))
        if (pending == null || !_pendingIssue(issue, pending)) issue,
    ];
    final ConversationScope scope = ConversationScope(
      conversation: conversation,
      issues: issues,
      catalog: component.catalog,
      edit: _edit,
      expanded: _expanded,
      toggle: _toggle,
      advanced: advanced,
    );
    final List<ConversationIssue> general = <ConversationIssue>[
      for (final ConversationIssue issue in issues)
        if (issue.at == null && issue.block == null) issue,
    ];
    return stack(spacing: 16, <Widget>[
      for (final ConversationIssue issue in general) AppAlert(tone: issue.error ? AlertTone.error : AlertTone.warning, message: issue.message),
      ..._start(conversation, advanced),
      if (component.onCommit != null)
        ConversationAttach(
          behavior: component.document.id,
          documents: component.workspaceDocuments,
          library: component.library ?? _libraryOfRead,
          load: component.load,
          onCommit: component.onCommit!,
          advanced: advanced,
        ),
      dom.h2(<Widget>[
        TipLabel(label: const Component.text('Steps'), tip: conversationKeys(), docs: conversationDocs),
      ]),
      DenseBox(children: <Widget>[ConversationTree(scope: scope)]),
    ]);
  }

  /// Whether [issue] belongs to the field [pending] the user is filling.
  static bool _pendingIssue(ConversationIssue issue, String pending) {
    final StepAt? at = issue.at;
    if (at == null) return false;
    final int? option = issue.option;
    return option == null ? pending == lineId(at) || pending == promptId(at) : pending == optionId(at, option);
  }

  List<Widget> _start(Conversation conversation, bool advanced) {
    final ConversationStart start = conversation.start;
    return <Widget>[
      Field(
        label: 'Starts',
        forId: 'conv-start',
        tip: 'What a player does to start it with the NPCs below.',
        docs: conversationDocs,
        hint: advanced && start.kind == ConversationStartKind.manual ? '/animus behaviors run <npc> ${component.document.id}' : null,
        child: ConversationPicker(
          id: 'conv-start',
          label: 'Starts',
          value: start.kind.name,
          options: <PickOption>[for (final ConversationStartKind kind in ConversationStartKind.values) PickOption(kind.name, kind.label, hint: kind.line)],
          onPick: (String value) => _edit((Conversation c) => setStart(c, ConversationStartKind.values.byName(value))),
        ),
      ),
      if (start.kind == ConversationStartKind.proximity)
        Field(
          label: 'Radius',
          forId: 'conv-radius',
          hint: advanced ? 'Blocks, 1 to 64' : 'Blocks',
          tip: 'How near a player comes before it starts: 1 to 64 blocks.',
          child: NumberInput(
            id: 'conv-radius',
            value: start.radius,
            integer: false,
            label: 'Radius in blocks',
            digits: 5,
            onChanged: (num? value) {
              if (value != null) _edit((Conversation c) => c.start.radius = value, coalesce: 'radius');
            },
          ),
        ),
    ];
  }
}
