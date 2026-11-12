/// A chat-like playthrough beside the tree: the NPC's lines, its question
/// with clickable answers, and a switch on each condition that picks the
/// branch it takes. It runs in the browser; [onServerPreview], when a paired
/// server offers one, starts the server's own preview as well.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/conversation/conversation_model.dart';
import '../../logic/conversation/conversation_play.dart';
import '../../logic/plural.dart';
import '../gui/gui_fields.dart';
import '../info_tip.dart';
import 'conversation_fields.dart';

class ConversationPreview extends StatefulWidget {
  const ConversationPreview({required this.conversation, this.npcName = 'NPC', this.onServerPreview, super.key});

  final Conversation conversation;
  final String npcName;
  final void Function()? onServerPreview;

  @override
  State<ConversationPreview> createState() => _ConversationPreviewState();
}

class _ConversationPreviewState extends State<ConversationPreview> {
  /// The answer ids picked so far, in order.
  final List<String> _answers = <String>[];

  /// Branches flipped to No, by step key.
  final Set<String> _no = <String>{};

  bool _branch(StepAt at) => !_no.contains(at.key);

  @override
  Widget build(BuildContext context) {
    final Conversation conversation = component.conversation;
    final List<Widget> chat = <Widget>[];
    String block = conversation.entry;
    for (int turn = 0; turn <= _answers.length; turn++) {
      final List<PlayEntry> entries = playFrom(conversation, block, branch: _branch, npc: component.npcName);
      final PlayQuestion? asked = entries.whereType<PlayQuestion>().lastOrNull;
      final String? answer = turn < _answers.length ? _answers[turn] : null;
      final ConversationOption? picked = asked?.choices.options.where((ConversationOption option) => option.id == answer).firstOrNull;
      for (final PlayEntry entry in entries) {
        chat.add(_entry(entry, live: picked == null && identical(entry, asked)));
      }
      if (picked == null) {
        if (asked == null) chat.add(const dom.li(classes: 'animus-conv-chat-end', <Widget>[Component.text('End of conversation')]));
        break;
      }
      chat.add(dom.li(classes: 'animus-conv-chat-you', <Widget>[
        const dom.span(classes: 'animus-conv-chat-name', <Widget>[Component.text('You')]),
        GuiStyledText(picked.text),
      ]));
      if (picked.next == null) {
        chat.add(const dom.li(classes: 'animus-conv-chat-end', <Widget>[Component.text('End of conversation')]));
        break;
      }
      block = picked.next!;
    }
    return dom.section(classes: 'animus-conv-preview', attributes: const <String, String>{'aria-label': 'Preview'}, <Widget>[
      dom.div(classes: 'animus-conv-preview-head', <Widget>[
        dom.h3(<Widget>[
          withTip(const Component.text('Preview'), 'Plays in the browser. Each condition has a switch for the branch it takes.', docs: conversationDocs),
        ]),
        Button.ghost(
          label: 'Restart',
          size: ButtonSize.small,
          disabled: _answers.isEmpty,
          onPressed: () => setState(_answers.clear),
        ),
        if (component.onServerPreview != null)
          Button.ghost(label: 'Preview on server', size: ButtonSize.small, onPressed: component.onServerPreview),
      ]),
      dom.ol(classes: 'animus-conv-chat', attributes: const <String, String>{'aria-live': 'polite'}, chat),
    ]);
  }

  Widget _entry(PlayEntry entry, {required bool live}) => switch (entry) {
        PlayLine(:final String text, :final int? delayTicks) => dom.li(classes: 'animus-conv-chat-npc', <Widget>[
            dom.span(classes: 'animus-conv-chat-name', <Widget>[Component.text('${component.npcName} »')]),
            GuiStyledText(text),
            if (delayTicks != null) dom.span(classes: 'animus-muted animus-conv-chat-note', <Widget>[Component.text('after ${countOf(delayTicks, 'tick')}')]),
          ]),
        PlayQuestion(:final ConversationChoices choices) => dom.li(classes: 'animus-conv-chat-npc is-question', <Widget>[
            dom.span(classes: 'animus-conv-chat-name', <Widget>[Component.text('${component.npcName} »')]),
            GuiStyledText(choices.prompt),
            dom.div(classes: 'animus-conv-chat-answers', attributes: const <String, String>{'role': 'group', 'aria-label': 'Answers'}, <Widget>[
              for (final ConversationOption option in choices.options)
                Button.secondary(
                  label: option.text.isEmpty ? '(empty)' : option.text,
                  size: ButtonSize.small,
                  disabled: !live,
                  attributes: <String, String>{if (option.hover != null) 'title': option.hover!, 'data-answer': option.id},
                  onPressed: () => setState(() => _answers.add(option.id)),
                ),
            ]),
          ]),
        PlayCondition(:final StepAt at, :final String label, :final bool taken) => dom.li(classes: 'animus-conv-chat-if', <Widget>[
            GuiSwitch(
              value: taken,
              label: 'If $label',
              onChanged: (bool on) => setState(() => on ? _no.remove(at.key) : _no.add(at.key)),
            ),
          ]),
        PlayEffect(:final String text) => dom.li(classes: 'animus-conv-chat-note', <Widget>[Component.text(text)]),
        PlayStop(:final String text) => dom.li(classes: 'animus-conv-chat-end', <Widget>[Component.text(text)]),
      };
}
