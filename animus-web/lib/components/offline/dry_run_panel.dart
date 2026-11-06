/// A behavior's Preview: a dry run in the browser. Pick what starts it, step
/// through what the NPC would say, ask and walk to, answer its prompts, flip
/// what only a server could know, and see which actions a server would run
/// (commands, combat, shop trades) listed as not run. An NPC that runs the
/// behavior stands on the stage and speaks the current line.
library;

import 'dart:typed_data';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/offline/dry_run.dart';
import '../../logic/offline/new_documents.dart';
import '../../mc/npc/npc_look.dart';
import '../../mc/npc/npc_playground.dart';
import '../../mc/npc/npc_scene.dart';
import '../../model/animus_document.dart';
import '../../model/document_kind.dart';
import '../../services/skin_images.dart';
import '../gui/gui_fields.dart';
import '../info_tip.dart';
import '../mc/mc_stage.dart';

class DryRunPanel extends StatefulWidget {
  const DryRunPanel({
    required this.document,
    this.workspaceDocuments = const <AnimusDocument>[],
    this.resources = const <String, Uint8List>{},
    this.onOpen,
    super.key,
  });

  final AnimusDocument document;
  final List<AnimusDocument> workspaceDocuments;
  final Map<String, Uint8List> resources;
  final void Function(DocumentKind kind, String id)? onOpen;

  @override
  State<DryRunPanel> createState() => _DryRunPanelState();
}

class _DryRunPanelState extends State<DryRunPanel> {
  final McStageController _stage = McStageController();
  DryRunInputs? _inputs;

  /// Steps shown so far; the rest wait for Step.
  int _shown = 0;
  String? _npc;
  String? _staged;

  JsonMap get _behavior => asMap(component.document.json);

  /// NPCs in the workspace whose `behaviors` list this behavior.
  List<AnimusDocument> get _npcs => <AnimusDocument>[
        for (final AnimusDocument other in component.workspaceDocuments)
          if (other.kind == DocumentKind.npc && stringList(asMap(other.json)['behaviors']).contains(component.document.id)) other,
      ];

  AnimusDocument? get _actor {
    final List<AnimusDocument> npcs = _npcs;
    if (npcs.isEmpty) return null;
    return npcs.firstWhere((AnimusDocument npc) => npc.id == _npc, orElse: () => npcs.first);
  }

  String? _ranSource;
  DryRunInputs? _ranInputs;
  DryRunSession? _ran;

  /// The session for [inputs] on the current document, run once per
  /// change of either; a build reads it several times.
  DryRunSession _run(DryRunInputs inputs) {
    final String source = component.document.source;
    final DryRunSession? cached = _ran;
    if (cached != null && identical(inputs, _ranInputs) && source == _ranSource) return cached;
    final DryRunSession session = dryRun(_behavior, inputs);
    _ranSource = source;
    _ranInputs = inputs;
    _ran = session;
    return session;
  }

  DryRunSession? get _session => _inputs == null ? null : _run(_inputs!);

  List<DryRunStep> _steps(DryRunSession session) => <DryRunStep>[for (final DryRunRun run in session.runs) ...run.steps];

  void _start(DryRunInputs inputs, {required bool keepShown}) {
    final DryRunSession? before = _session;
    setState(() {
      _inputs = inputs;
      final int total = _run(inputs).stepCount;
      _shown = keepShown && before != null ? (before.stepCount + 1).clamp(0, total) : (total == 0 ? 0 : 1);
    });
  }

  @override
  void didUpdateWidget(DryRunPanel oldComponent) {
    super.didUpdateWidget(oldComponent);
    if (oldComponent.document.source != component.document.source && _inputs != null) {
      _shown = _shown.clamp(0, _run(_inputs!).stepCount);
    }
  }

  /// The line the NPC says at the current step, for the stage.
  String? _speech(DryRunSession? session) {
    if (session == null) return null;
    final List<DryRunStep> steps = _steps(session).take(_shown).toList();
    for (final DryRunStep step in steps.reversed) {
      if (step.speech != null) return step.speech;
      if (step.prompt != null) return step.prompt;
    }
    return null;
  }

  void _stageSync(DryRunSession? session) {
    final AnimusDocument? actor = _actor;
    if (actor == null) return;
    final String? speech = _speech(session);
    final String signature = '${actor.source}|$speech';
    if (signature == _staged) return;
    final bool frame = _staged == null || !_staged!.startsWith(actor.source);
    _staged = signature;
    final NpcLook look = NpcLook.fromDefinition(asMap(actor.json));
    final LoadedSkin? skin = look.skin == null ? null : SkinImages.forKeyOrWorkspace(look.skin!, component.resources[skinImagePath(look.skin!)]);
    final NpcScene scene = npcPlaygroundScene(
      look,
      skin: NpcSkinChoice(textureId: skin?.textureId ?? SkinImages.placeholderFor(actor.id, slim: false).textureId, slim: false),
      speech: speech,
    );
    _stage.show(scene.scene, scene.texts, yawDeg: look.yawDeg, frame: frame);
  }

  @override
  Widget build(BuildContext context) {
    final List<DryRunStart> starts = dryRunStarts(_behavior);
    final DryRunSession? session = _session;
    _stageSync(session);
    final List<DryRunStep> steps = session == null ? const <DryRunStep>[] : _steps(session);
    final bool finished = session != null && _shown >= steps.length;
    final AnimusDocument? actor = _actor;
    return dom.section(classes: 'animus-panel animus-dryrun', attributes: const <String, String>{'aria-label': 'Dry run'}, <Widget>[
      const dom.h3(<Widget>[
        InfoTip(
          tip: 'Runs in this browser with sample player and NPC facts. Server-only steps are listed, not run.',
          docs: Docs.dryRun,
          child: Component.text('Dry run'),
        ),
      ]),
      dom.div(classes: 'animus-dryrun-layout${actor == null ? '' : ' has-stage'}', <Widget>[
        if (actor != null)
          dom.div(classes: 'animus-dryrun-stage', <Widget>[
            McStage(controller: _stage, label: 'Dry run stage with ${actor.title}'),
            if (_npcs.length > 1)
              GuiSelect(
                value: actor.id,
                options: <String>[for (final AnimusDocument npc in _npcs) npc.id],
                labels: <String, String>{for (final AnimusDocument npc in _npcs) npc.id: npc.title},
                onChanged: (String? value) => setState(() {
                  _npc = value;
                  _staged = null;
                }),
              )
            else
              dom.p(classes: 'animus-muted', <Widget>[Component.text('${NpcLook.legacy(actor.title).replaceAll(RegExp('§.'), '')} runs this behavior.')]),
          ])
        else
          dom.p(classes: 'animus-muted animus-dryrun-nostage', <Widget>[
            const Component.text('No NPC uses this behavior. '),
            if (component.onOpen != null)
              const Component.text('Add it to one to see it speak.'),
          ]),
        dom.div(classes: 'animus-dryrun-trace', <Widget>[
          dom.div(classes: 'animus-row', attributes: <String, String>{'role': 'group', 'aria-label': session == null ? 'Start the dry run' : 'Next trigger'}, <Widget>[
            dom.span(classes: 'animus-muted', <Widget>[Component.text(session == null ? 'Start with:' : 'Then:')]),
            for (final DryRunStart start in starts)
              Button.secondary(
                label: start.label,
                size: ButtonSize.small,
                disabled: session != null && !finished,
                onPressed: () => _start(session == null ? DryRunInputs.start(start.id) : _inputs!.withTrigger(start.id), keepShown: session != null),
              ),
          ]),
          if (session != null) ...<Widget>[
            dom.div(classes: 'animus-row', attributes: const <String, String>{'role': 'toolbar', 'aria-label': 'Dry run controls'}, <Widget>[
              Button.primary(label: 'Step', size: ButtonSize.small, disabled: finished, onPressed: () => setState(() => _shown++)),
              Button.ghost(label: 'Show all', size: ButtonSize.small, disabled: finished, onPressed: () => setState(() => _shown = steps.length)),
              Button.ghost(
                label: 'Back one run',
                size: ButtonSize.small,
                disabled: _inputs!.events.length < 2,
                onPressed: () => _start(_inputs!.withoutLast(), keepShown: false),
              ),
              Button.ghost(
                label: 'Reset',
                size: ButtonSize.small,
                onPressed: () => setState(() {
                  _inputs = null;
                  _shown = 0;
                }),
              ),
              dom.span(classes: 'animus-muted', attributes: const <String, String>{'aria-live': 'polite'}, <Widget>[
                Component.text('Step ${_shown.clamp(0, steps.length)} of ${steps.length}'),
              ]),
            ]),
            _trace(session),
            if (finished && session.unanswered != null) _answers(session.unanswered!),
            _summary(session),
          ],
        ]),
      ]),
    ]);
  }

  Widget _trace(DryRunSession session) {
    int index = 0;
    final List<Widget> runs = <Widget>[];
    for (final DryRunRun run in session.runs) {
      final List<Widget> items = <Widget>[];
      for (final DryRunStep step in run.steps) {
        if (index >= _shown) break;
        items.add(_step(step, current: index == _shown - 1));
        index++;
      }
      if (items.isEmpty) break;
      runs.add(dom.li(classes: 'animus-dryrun-run', <Widget>[
        dom.h4(<Widget>[Component.text(run.title)]),
        dom.ol(classes: 'animus-dryrun-steps', items),
      ]));
    }
    return dom.ol(classes: 'animus-dryrun-runs', attributes: const <String, String>{'aria-label': 'Steps'}, runs);
  }

  static const Map<DryRunOutcome, String> _tags = <DryRunOutcome, String>{
    DryRunOutcome.previewed: 'shown',
    DryRunOutcome.assumed: 'assumed',
    DryRunOutcome.serverOnly: 'server only',
    DryRunOutcome.problem: 'stopped',
  };

  Widget _step(DryRunStep step, {required bool current}) => dom.li(
        classes: 'animus-dryrun-step is-${step.outcome.name}${current ? ' is-current' : ''}',
        styles: dom.Styles(raw: <String, String>{'--depth': '${step.depth}'}),
        attributes: <String, String>{if (current) 'aria-current': 'step'},
        <Widget>[
          dom.span(classes: 'animus-dryrun-tag', <Widget>[Component.text(_tags[step.outcome]!)]),
          dom.span(classes: 'animus-dryrun-text', <Widget>[
            Component.text(step.text),
            if (step.speech != null) dom.span(classes: 'animus-dryrun-speech', <Widget>[GuiStyledText(step.speech!)]),
            if (step.prompt != null) ...<Widget>[
              dom.span(classes: 'animus-dryrun-speech', <Widget>[GuiStyledText(step.prompt!)]),
              dom.span(classes: 'animus-muted', <Widget>[
                Component.text('Options: ${step.options.map((DryRunOption option) => option.text).join(', ')}'),
              ]),
            ],
          ]),
          if (step.assumption != null)
            Button.ghost(
              label: 'Assume ${!step.assumption!}',
              size: ButtonSize.small,
              onPressed: () => _start(_inputs!.withAssumption(step.path, !step.assumption!), keepShown: false),
            ),
        ],
      );

  Widget _answers(DryRunStep prompt) => dom.div(classes: 'animus-dryrun-answers', attributes: <String, String>{'role': 'group', 'aria-label': 'Answer ${prompt.dialogue}'}, <Widget>[
        const dom.span(<Widget>[Component.text('Answer as the player:')]),
        for (final DryRunOption option in prompt.options)
          Button.secondary(
            label: option.text,
            size: ButtonSize.small,
            onPressed: () => _start(_inputs!.withAnswer(prompt.dialogue!, option.id), keepShown: true),
          ),
      ]);

  Widget _summary(DryRunSession session) {
    final List<DryRunStep> serverOnly = session.serverOnly;
    return dom.div(classes: 'animus-dryrun-summary', <Widget>[
      if (session.state.isNotEmpty)
        dom.dl(classes: 'animus-facts', <Widget>[
          for (final MapEntry<String, Object?> entry in session.state.entries) ...<Widget>[
            dom.dt(<Widget>[Component.text(entry.key)]),
            dom.dd(<Widget>[Component.text('${entry.value}')]),
          ],
        ]),
      dom.p(classes: 'animus-muted', <Widget>[
        Component.text(serverOnly.isEmpty
            ? 'Nothing in this session needs a server.'
            : '${serverOnly.length} server-only step${serverOnly.length == 1 ? '' : 's'} not run.'),
      ]),
    ]);
  }
}
