/// The rig's named animations: one row per `animations` entry (the name,
/// the animation it plays, open, rename, remove), `idle` and `walk` called
/// out as the names navigation switches between, and entries whose
/// animation is missing or belongs to another rig marked with a fix.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/rig_edit.dart';
import 'rig_inspector.dart';

class RigAnimationsPanel extends StatefulWidget {
  const RigAnimationsPanel({
    required this.rig,
    required this.documents,
    required this.onEdit,
    required this.onOpen,
    required this.onCreate,
    super.key,
  });

  final JsonMap rig;

  /// Every animation document in the workspace, by id, with the rig it names.
  final Map<String, String?> documents;
  final RigChange onEdit;

  /// Opens an animation on the timeline.
  final void Function(String id) onOpen;

  /// Creates an empty animation [id] for this rig.
  final void Function(String id) onCreate;

  @override
  State<RigAnimationsPanel> createState() => _RigAnimationsPanelState();
}

class _RigAnimationsPanelState extends State<RigAnimationsPanel> {
  final Map<String, String> _names = <String, String>{};
  String _newName = '';
  String _newAnimation = '';

  String get _rigId => '${component.rig['id']}';

  List<String> get _own => <String>[
    for (final MapEntry<String, String?> entry in component.documents.entries)
      if (entry.value == _rigId) entry.key,
  ]..sort();

  static String? _role(String name) => switch (name) {
    'idle' => 'Navigation plays this while the NPC stands.',
    'walk' => 'Navigation plays this while the NPC walks.',
    _ => null,
  };

  void _rename(String from) {
    final String? to = _names.remove(from)?.trim();
    if (to == null || to.isEmpty || to == from) {
      setState(() {});
      return;
    }
    component.onEdit((JsonMap rig) => renameAnimationName(rig, from, to));
  }

  void _add() {
    final String name = _newName.trim();
    final String animation = _newAnimation.isEmpty && _own.isNotEmpty ? _own.first : _newAnimation;
    if (name.isEmpty || animation.isEmpty) return;
    if (animationNames(component.rig).containsKey(name)) {
      ArcaneSonner.warning('The name $name is taken. Rename or remove that row first.');
      return;
    }
    component.onEdit((JsonMap rig) => setAnimationName(rig, name, animation));
    setState(() {
      _newName = '';
      _newAnimation = '';
    });
  }

  @override
  Widget build(BuildContext context) {
    final JsonMap names = animationNames(component.rig);
    final List<String> own = _own;
    return dom.div(classes: 'animus-rig-names', attributes: const <String, String>{'aria-label': 'Named animations'}, <Widget>[
      const dom.p(classes: 'animus-muted', <Widget>[
        Component.text('Behaviors play an animation by its name. idle and walk are the two names navigation switches between on its own.'),
      ]),
      for (final String missing in navigationAnimations)
        if (!names.containsKey(missing))
          dom.p(classes: 'animus-muted', <Widget>[
            Component.text(missing == 'idle'
                ? 'No idle name: a standing NPC holds the rest pose.'
                : 'No walk name: a walking NPC plays idle, or holds the rest pose.'),
          ]),
      if (names.isNotEmpty)
        dom.ul(classes: 'animus-rig-name-list', <Widget>[
          for (final MapEntry<String, Object?> entry in names.entries) _row(entry.key, '${entry.value}', own),
        ]),
      dom.div(classes: 'animus-rig-name-add', <Widget>[
        TextInput(
          size: ComponentSize.sm,
          placeholder: own.isEmpty ? 'idle' : freeAnimationName(component.rig, names.containsKey('idle') ? 'walk' : 'idle'),
          value: _newName,
          attributes: const <String, String>{'aria-label': 'New animation name'},
          onChanged: (String value) => setState(() => _newName = value),
          onSubmitted: (String _) => _add(),
        ),
        if (own.isNotEmpty)
          dom.label(classes: 'animus-rig-field', <Widget>[
            const dom.span(classes: 'animus-sr-only', <Widget>[Component.text('Animation for the new name')]),
            ArcaneSelect(
              size: ComponentSize.sm,
              value: _newAnimation.isEmpty ? own.first : _newAnimation,
              options: <ArcaneSelectOption>[for (final String id in own) ArcaneSelectOption(label: id, value: id)],
              onChanged: (String value) => setState(() => _newAnimation = value),
            ),
          ]),
        Button.secondary(
          label: 'Add name',
          icon: ArcaneIcon.plus(),
          size: ButtonSize.small,
          disabled: own.isEmpty || _newName.trim().isEmpty,
          onPressed: _add,
        ),
      ]),
      if (own.isEmpty) const dom.p(classes: 'animus-muted', <Widget>[Component.text('Create an animation on the timeline first, then name it here.')]),
    ]);
  }

  Widget _row(String name, String id, List<String> own) {
    final bool exists = component.documents.containsKey(id);
    final String? rig = component.documents[id];
    final bool foreign = exists && rig != _rigId;
    final String? role = _role(name);
    final String draft = _names[name] ?? name;
    return dom.li(classes: 'animus-rig-name${exists && !foreign ? '' : ' is-broken'}', attributes: <String, String>{'data-name': name}, <Widget>[
      dom.div(classes: 'animus-rig-name-head', <Widget>[
        TextInput(
          size: ComponentSize.sm,
          value: draft,
          attributes: <String, String>{'aria-label': 'Name $name'},
          onChanged: (String value) => _names[name] = value,
          onSubmitted: (String _) => _rename(name),
          onBlur: () => _rename(name),
        ),
        dom.label(classes: 'animus-rig-field', <Widget>[
          dom.span(classes: 'animus-sr-only', <Widget>[Component.text('Animation for $name')]),
          ArcaneSelect(
            size: ComponentSize.sm,
            value: id,
            options: <ArcaneSelectOption>[
              for (final String option in own) ArcaneSelectOption(label: option, value: option),
              if (!own.contains(id)) ArcaneSelectOption(label: '$id (${exists ? 'other rig' : 'missing'})', value: id),
            ],
            onChanged: (String value) => component.onEdit((JsonMap json) => setAnimationName(json, name, value)),
          ),
        ]),
        Button.ghost(
          label: 'Open',
          size: ButtonSize.small,
          disabled: !exists || foreign,
          attributes: <String, String>{'aria-label': 'Open $id'},
          onPressed: () => component.onOpen(id),
        ),
        Button.ghost(
          label: 'Remove',
          size: ButtonSize.small,
          attributes: <String, String>{'aria-label': 'Remove the name $name'},
          onPressed: () => component.onEdit((JsonMap json) => removeAnimationName(json, name)),
        ),
      ]),
      if (role != null) dom.p(classes: 'animus-rig-name-role', <Widget>[Component.text(role)]),
      if (!exists)
        dom.div(classes: 'animus-rig-name-fix', attributes: const <String, String>{'role': 'status'}, <Widget>[
          dom.span(<Widget>[Component.text('$id is not in this workspace, so the server skips $name.')]),
          if (documentIdPattern.hasMatch(id))
            Button.secondary(label: 'Create $id', size: ButtonSize.small, onPressed: () => component.onCreate(id)),
        ])
      else if (foreign)
        dom.div(classes: 'animus-rig-name-fix', attributes: const <String, String>{'role': 'status'}, <Widget>[
          dom.span(<Widget>[Component.text('$id animates $rig, so the server skips $name. Pick one of this rig\'s animations.')]),
        ]),
    ]);
  }
}
