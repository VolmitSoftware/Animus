/// What players see of a quest: the giver's offer in chat with its Accept
/// and Decline buttons, and the `/quests` journal entry at each stage and
/// once finished. Drawn in the browser from the document.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/quest/quest_model.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import '../info_tip.dart';
import 'quest_fields.dart';

class QuestPreview extends StatefulWidget {
  const QuestPreview({required this.document, required this.npcs, super.key});

  final AnimusDocument document;
  final QuestNpcs npcs;

  @override
  State<QuestPreview> createState() => _QuestPreviewState();
}

class _QuestPreviewState extends State<QuestPreview> {
  /// The stage shown (0-based); the stage count means finished.
  int _stage = 0;

  @override
  Widget build(BuildContext context) {
    final JsonMap quest = asMap(component.document.json);
    final int stages = asList(quest['stages']).length;
    final int shown = _stage.clamp(0, stages);
    final bool finished = shown == stages;
    final QuestJournalEntry entry = journalEntry(quest, stage: shown, finished: finished, npcName: component.npcs.nameOf);
    final List<String> givers = stringList(quest['givers']);
    final String giver = givers.isEmpty ? 'NPC' : component.npcs.nameOf(givers.first);
    return dom.section(classes: 'animus-panel animus-quest-preview', attributes: const <String, String>{'aria-label': 'Quest preview'}, <Widget>[
      dom.h2(classes: 'animus-quest-panel-title', <Widget>[
        withTip(const Component.text('Offer'), givers.isEmpty ? 'Without givers the quest starts only by command or behavior.' : 'What $giver says on right click.'),
      ]),
      dom.ol(classes: 'animus-conv-chat animus-quest-offer', <Widget>[
        dom.li(classes: 'animus-conv-chat-npc', <Widget>[GuiStyledText('&6Quest &8» &r${asString(quest['title']) ?? ''}')]),
        dom.li(classes: 'animus-conv-chat-npc is-question', <Widget>[
          dom.span(classes: 'animus-conv-chat-name', <Widget>[Component.text('$giver »')]),
          GuiStyledText(offerLine(quest)),
          const dom.div(classes: 'animus-quest-offer-buttons', attributes: <String, String>{'aria-hidden': 'true'}, <Widget>[
            dom.span(classes: 'animus-quest-chat-button is-accept', <Widget>[Component.text('[Accept]')]),
            dom.span(classes: 'animus-quest-chat-button is-decline', <Widget>[Component.text('[Decline]')]),
          ]),
        ]),
      ]),
      dom.h2(classes: 'animus-quest-panel-title', <Widget>[
        withTip(const Component.text('Journal'), 'The entry in /quests, with nothing counted yet.'),
      ]),
      GuiTabs(
        label: 'Journal state',
        tabs: <String>[for (int index = 0; index < stages; index++) 'Stage ${index + 1}', 'Finished'],
        selected: shown,
        onSelect: (int index) => setState(() => _stage = index),
      ),
      dom.div(classes: 'animus-quest-entry', attributes: const <String, String>{'data-quest': 'journal-entry'}, <Widget>[
        dom.span(classes: 'animus-quest-entry-name', <Widget>[GuiStyledText(entry.title)]),
        for (final String line in entry.lore) dom.span(classes: 'animus-quest-entry-line', <Widget>[GuiStyledText(line)]),
      ]),
      dom.p(classes: 'animus-muted animus-quest-state', <Widget>[
        withTip(Component.text('Behaviors read the stage as ${questStageExpression(component.document.id)}.'),
            '0 before it starts, 1 to $stages while a stage is active, ${stages + 1} once finished.'),
      ]),
    ]);
  }
}
