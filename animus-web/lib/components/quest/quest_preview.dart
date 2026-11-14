/// What players see of a quest: the giver's offer in chat with its Accept
/// and Decline buttons, and the `/quests` journal entry at a stage or once
/// finished, picked in the frame's header. Drawn in the browser from the
/// document.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/quest/quest_model.dart';
import '../../model/animus_document.dart';
import '../../ui/mode.dart';
import '../../ui/stage_frame.dart';
import '../gui/gui_fields.dart';
import 'quest_fields.dart';
import '../select/animus_select.dart';
import '../gui/gui_stack.dart';
import '../../ui/stack.dart';

class QuestPreview extends StatefulWidget {
  const QuestPreview({required this.document, required this.npcs, this.band = false, super.key});

  final AnimusDocument document;
  final QuestNpcs npcs;

  /// The preview sits in the band above the form.
  final bool band;

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
    return StageFrame(
      title: 'Preview',
      free: true,
      fullscreen: false,
      collapsible: component.band,
      toggles: <Widget>[
        AnimusSelect.values(
          id: 'animus-quest-journal-state',
          hiddenLabel: 'Journal shows',
          value: '$shown',
          values: <String>[for (int index = 0; index <= stages; index++) '$index'],
          labels: <String, String>{for (int index = 0; index < stages; index++) '$index': 'Stage ${index + 1}', '$stages': 'Finished'},
          onChanged: (String? value) => setState(() => _stage = int.tryParse(value ?? '') ?? 0),
        ),
      ],
      status: givers.isEmpty ? 'No giver: starts by command or behavior.' : 'What $giver says, and the /quests entry.',
      statusTip: ModeScope.advanced(context)
          ? 'Behaviors read the stage with ${questStageExpression(component.document.id)}: 0 before it starts, 1 to $stages while a stage runs, ${stages + 1} once finished.'
          : null,
      child: dom.div(classes: 'animus-quest-preview', <Widget>[
        stack(<Widget>[
          groupHeading('Offer'),
          dom.ol(classes: 'animus-conv-chat animus-quest-offer', <Widget>[
            dom.li(classes: 'animus-conv-chat-npc', <Widget>[GuiStyledText('&6Quest &8» &r${asString(quest['title']) ?? ''}', defaultColor: null)]),
            dom.li(classes: 'animus-conv-chat-npc is-question', <Widget>[
              dom.span(classes: 'animus-conv-chat-name', <Widget>[Component.text('$giver »')]),
              GuiStyledText(offerLine(quest), defaultColor: null),
              const dom.span(classes: 'animus-conv-chat-answers', attributes: <String, String>{'aria-hidden': 'true'}, <Widget>[
                GuiStyledText('&a[Accept]', defaultColor: null),
                GuiStyledText('&c[Decline]', defaultColor: null),
              ]),
            ]),
          ]),
        ]),
        stack(<Widget>[
          groupHeading(finished ? 'Journal, finished' : 'Journal, stage ${shown + 1}'),
          dom.div(classes: 'animus-quest-entry', attributes: const <String, String>{'data-quest': 'journal-entry'}, <Widget>[
            dom.span(<Widget>[GuiStyledText(entry.title)]),
            for (final String line in entry.lore) dom.span(classes: 'animus-quest-entry-line', <Widget>[GuiStyledText(line)]),
          ]),
        ]),
      ]),
    );
  }
}
