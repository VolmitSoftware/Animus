/// A behavior's Test, in its own preview frame: the header picks how it
/// starts (when there is more than one way) and Run plays it; the chat the
/// player would read is the frame's body, under the stage when an NPC runs
/// the behavior. Answer as the player and flip a check only a server could
/// answer; Advanced also carries the run on with another start. It all
/// runs in the browser (a dry run); nothing is sent.
library;

import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/gui/gui_text.dart';
import '../../logic/inspect/behavior_words.dart';
import '../../logic/offline/dry_run.dart';
import '../../logic/offline/new_documents.dart';
import '../../mc/npc/npc_look.dart';
import '../../mc/npc/npc_playground.dart';
import '../../mc/npc/npc_scene.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../logic/npc/placeholder_skins.dart';
import '../../services/skin_images.dart';
import '../../ui/action_row.dart';
import '../../ui/info_tip.dart';
import '../../ui/mode.dart';
import '../../ui/stage_frame.dart';
import '../gui/gui_fields.dart';
import '../mc/mc_stage.dart';
import '../select/animus_select.dart';
import '../select/picker_scope.dart';
import '../../ui/stack.dart';
import '../../ui/problem_text.dart';

/// How a test can start: its key (a trigger id, or [entryStart] for the
/// entry routine) and its words.
typedef TestStart = (String key, String label);

/// The key of the entry routine's start; no trigger id has a colon.
const String entryStart = ':entry';

/// The trigger id a start [key] runs, or null for the entry routine.
String? startTrigger(String key) => key == entryStart ? null : key;

/// The starts of [behavior] in words: each trigger as a player would do it,
/// the entry routine as "By command". Two starts that read the same are
/// numbered.
List<TestStart> testStarts(JsonMap behavior) {
  final Map<String, JsonMap> triggers = <String, JsonMap>{
    for (final Object? raw in asList(behavior['triggers']))
      if (asString(asMap(raw)['id']) case final String id) id: asMap(raw),
  };
  final List<TestStart> out = <TestStart>[];
  final Map<String, int> seen = <String, int>{};
  for (final DryRunStart start in dryRunStarts(behavior)) {
    final String words = start.id == null ? 'By command' : triggerWords(triggers[start.id] ?? const <String, Object?>{});
    final int count = seen[words] = (seen[words] ?? 0) + 1;
    out.add((start.id ?? entryStart, count == 1 ? words : '$words $count'));
  }
  return out;
}

final RegExp _answerTitle = RegExp(r'^Answer "(.*)"');

class DryRunPanel extends StatefulWidget {
  const DryRunPanel({
    required this.document,
    this.workspaceDocuments = const <AnimusDocument>[],
    this.resources = const <String, Uint8List>{},
    this.onOpen,
    this.band = false,
    super.key,
  });

  final AnimusDocument document;
  final List<AnimusDocument> workspaceDocuments;
  final Map<String, Uint8List> resources;
  final void Function(DocumentKind kind, String id)? onOpen;

  /// The Test sits in the band above the form.
  final bool band;

  @override
  State<DryRunPanel> createState() => _DryRunPanelState();
}

class _DryRunPanelState extends State<DryRunPanel> {
  final McStageController _stage = McStageController();
  DryRunInputs? _inputs;
  String? _choice;
  String? _npc;
  String? _staged;

  JsonMap get _behavior => asMap(component.document.json);

  /// NPCs in the workspace whose `behaviors` list this behavior.
  List<AnimusDocument> get _npcs => <AnimusDocument>[
        for (final AnimusDocument other in component.workspaceDocuments)
          if (other.kind == DocumentKind.npc && stringList(asMap(other.json)['behaviors']).contains(component.document.id)) other,
      ];

  AnimusDocument? _actor(List<AnimusDocument> npcs) {
    if (npcs.isEmpty) return null;
    return npcs.firstWhere((AnimusDocument npc) => npc.id == _npc, orElse: () => npcs.first);
  }

  String? _ranSource;
  DryRunInputs? _ranInputs;
  bool? _ranPlain;
  DryRunSession? _ran;

  /// The session for [inputs] on the current document, run once per change
  /// of either; a build reads it several times. [plain] words the steps for
  /// Simple.
  DryRunSession _run(DryRunInputs inputs, {required bool plain}) {
    final String source = component.document.source;
    final DryRunSession? cached = _ran;
    if (cached != null && identical(inputs, _ranInputs) && source == _ranSource && plain == _ranPlain) return cached;
    final DryRunSession session = dryRun(_behavior, inputs, plain: plain, names: (DocumentKind kind, String id) => PickerScope.nameIn(context, kind, id));
    _ranSource = source;
    _ranInputs = inputs;
    _ranPlain = plain;
    _ran = session;
    return session;
  }

  /// Runs the test from [key], from the start.
  void _play(String key) => setState(() => _inputs = DryRunInputs.start(startTrigger(key)));

  void _next(DryRunInputs inputs) => setState(() => _inputs = inputs);

  /// The line the NPC says last, for the stage.
  static String? _speech(DryRunSession? session) {
    if (session == null) return null;
    for (final DryRunRun run in session.runs.reversed) {
      for (final DryRunStep step in run.steps.reversed) {
        if (step.speech != null) return step.speech;
        if (step.prompt != null) return step.prompt;
      }
    }
    return null;
  }

  void _stageSync(AnimusDocument actor, DryRunSession? session) {
    final String? speech = _speech(session);
    final String signature = '${actor.source}|$speech';
    if (signature == _staged) return;
    final bool frame = _staged == null || !_staged!.startsWith(actor.source);
    _staged = signature;
    final NpcLook look = NpcLook.fromDefinition(asMap(actor.json));
    final LoadedSkin? skin = look.skin == null ? null : SkinImages.forKeyOrWorkspace(look.skin!, component.resources[skinImagePath(look.skin!)]);
    final NpcScene scene = npcPlaygroundScene(
      look,
      skin: NpcSkinChoice(textureId: skin?.textureId ?? npcPlaceholderSkin(actor.id, slim: false).textureId, slim: false),
      speech: speech,
    );
    _stage.show(scene.scene, scene.texts, yawDeg: look.yawDeg, frame: frame);
  }

  @override
  Widget build(BuildContext context) {
    final bool advanced = ModeScope.advanced(context);
    final List<TestStart> starts = testStarts(_behavior);
    final String? start = starts.any((TestStart one) => one.$1 == _choice) ? _choice : starts.firstOrNull?.$1;
    final DryRunSession? session = _inputs == null ? null : _run(_inputs!, plain: !advanced);
    final List<AnimusDocument> npcs = _npcs;
    final AnimusDocument? actor = _actor(npcs);
    if (actor != null) _stageSync(actor, session);
    final String name = actor == null ? 'NPC' : _plainName(actor);
    final List<Widget> summary = session == null ? const <Widget>[] : _summary(session, advanced);
    // The header holds the controls, so tests find them through the frame.
    return dom.div(attributes: const <String, String>{'data-testid': 'test-pane'}, <Widget>[
      DocsScope(
        link: Docs.dryRun,
        child: StageFrame(
          title: 'Test',
          free: true,
          fullscreen: false,
          collapsible: component.band,
          // The pickers shorten their text before they reach Run.
          toggles: <Widget>[
            if (starts.length > 1)
              Flexible(child: AnimusSelect.values(
                value: start,
                values: <String>[for (final (String key, String _) in starts) key],
                labels: <String, String>{for (final (String key, String words) in starts) key: words},
                hiddenLabel: 'Starts',
                onChanged: (String? key) => setState(() => _choice = key),
              )),
            if (actor != null && npcs.length > 1)
              Flexible(child: AnimusSelect.values(
                value: actor.id,
                values: <String>[for (final AnimusDocument npc in npcs) npc.id],
                labels: <String, String>{for (final AnimusDocument npc in npcs) npc.id: _plainName(npc)},
                hiddenLabel: 'NPC on the stage',
                onChanged: (String? value) => setState(() {
                  _npc = value;
                  _staged = null;
                }),
              )),
          ],
          primary: start == null ? null : RowAction('Run', () => _play(start), icon: ArcaneIcon.play()),
          status: testPaneNote(component.document, component.workspaceDocuments),
          statusTip: 'Only listed server behaviors run on the server. The Test runs here with a sample player.',
          child: dom.div(classes: 'animus-test', <Widget>[
            if (actor != null) McStage(controller: _stage, label: 'Test stage with $name'),
            if (session != null || actor == null) _chat(session, name, advanced ? starts : const <TestStart>[]),
            if (summary.isNotEmpty) Padding(padding: const EdgeInsets.all(12), child: stack(summary)),
          ]),
        ),
      ),
    ]);
  }

  static String _plainName(AnimusDocument npc) {
    final String plain = guiPlainText(npc.title).trim();
    return plain.isEmpty ? npc.id : plain;
  }

  /// The run as the player would see it in chat; once it ends, in Advanced,
  /// the [carryOn] starts that continue the same run.
  Widget _chat(DryRunSession? session, String name, List<TestStart> carryOn) {
    final List<Widget> lines = <Widget>[];
    if (session == null) {
      lines.add(const dom.li(classes: 'animus-conv-chat-note', <Widget>[Component.text('Not run yet.')]));
    } else {
      for (final DryRunRun run in session.runs) {
        final RegExpMatch? answer = _answerTitle.firstMatch(run.title);
        if (answer != null) {
          lines.add(dom.li(classes: 'animus-conv-chat-you', <Widget>[
            const dom.span(classes: 'animus-conv-chat-name', <Widget>[Component.text('You')]),
            GuiStyledText(answer.group(1)!, defaultColor: null),
          ]));
        }
        for (final DryRunStep step in run.steps) {
          lines.add(_line(step, name, open: identical(step, session.unanswered)));
        }
      }
      if (session.unanswered == null) {
        final bool stepped = session.runs.any((DryRunRun run) => run.steps.isNotEmpty);
        lines.add(dom.li(classes: 'animus-conv-chat-note', <Widget>[Component.text(stepped ? 'End' : 'No steps to run.')]));
        if (carryOn.isNotEmpty) {
          lines.add(dom.li(classes: 'animus-conv-chat-note', <Widget>[
            const Component.text('Run next'),
            dom.div(classes: 'animus-conv-chat-answers', attributes: const <String, String>{'role': 'group', 'aria-label': 'Run next'}, <Widget>[
              for (final (String key, String words) in carryOn)
                Button.outline(label: words, size: ButtonSize.sm, onPressed: () => _next(_inputs!.withTrigger(startTrigger(key)))),
            ]),
          ]));
        }
      }
    }
    return dom.ol(classes: 'animus-conv-chat', attributes: const <String, String>{'aria-label': 'Chat', 'aria-live': 'polite'}, lines);
  }

  Widget _line(DryRunStep step, String name, {required bool open}) {
    if (step.speech != null) {
      return dom.li(classes: 'animus-conv-chat-npc', <Widget>[
        dom.span(classes: 'animus-conv-chat-name', <Widget>[Component.text('$name »')]),
        GuiStyledText(step.speech!, defaultColor: null),
      ]);
    }
    if (step.prompt != null) {
      return dom.li(classes: 'animus-conv-chat-npc is-question', <Widget>[
        dom.span(classes: 'animus-conv-chat-name', <Widget>[Component.text('$name »')]),
        GuiStyledText(step.prompt!, defaultColor: null),
        dom.div(classes: 'animus-conv-chat-answers', attributes: const <String, String>{'role': 'group', 'aria-label': 'Answers'}, <Widget>[
          for (final DryRunOption option in step.options)
            Button.secondary(
              label: option.text.isEmpty ? '(empty)' : guiPlainText(option.text),
              size: ButtonSize.sm,
              disabled: !open,
              attributes: <String, String>{'data-answer': option.id},
              onPressed: () => _next(_inputs!.withAnswer(step.dialogue!, option.id)),
            ),
        ]),
      ]);
    }
    final bool? assumed = step.assumption;
    if (assumed != null) {
      return dom.li(classes: 'animus-conv-chat-note animus-test-assume', <Widget>[
        ArcaneToggleSwitch(
          value: assumed,
          label: _assumption(step.text),
          onChanged: (bool on) => _next(_inputs!.withAssumption(step.path, on)),
          size: ComponentSize.sm,
        ),
      ]);
    }
    return dom.li(
      classes: 'animus-conv-chat-note${step.outcome == DryRunOutcome.problem ? ' is-error' : ''}',
      attributes: <String, String>{'data-outcome': step.outcome.name, if (step.outcome == DryRunOutcome.problem) 'role': 'alert'},
      <Widget>[Component.text(step.outcome == DryRunOutcome.serverOnly ? 'On the server only: ${step.text}' : step.text)],
    );
  }

  /// "If flag met is true (assumed true)" reads "If flag met is true".
  static String _assumption(String text) => text.replaceFirst(RegExp(r'\s*\(assumed (true|false)\)$'), '');

  List<Widget> _summary(DryRunSession session, bool advanced) {
    final int server = session.serverOnly.length;
    return <Widget>[
      if (server > 0) noteLine('$server ${server == 1 ? 'step runs' : 'steps run'} on the server only.'),
      if (advanced && session.state.isNotEmpty)
        dom.dl(classes: 'ui-facts', attributes: const <String, String>{'aria-label': 'State after the test'}, <Widget>[
          for (final MapEntry<String, Object?> entry in session.state.entries) ...<Widget>[
            dom.dt(<Widget>[Component.text(entry.key)]),
            dom.dd(<Widget>[Component.text('${entry.value}')]),
          ],
        ]),
    ];
  }
}
