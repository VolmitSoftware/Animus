/// The rig's named animations: one row per `animations` entry (the name,
/// the animation it plays, open, remove), `idle` and `walk` called out as
/// the names an NPC switches between on its own, and entries whose
/// animation is missing or belongs to another rig marked with a fix.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component, ValueKey;

import '../../config/docs_links.dart';
import '../../logic/rig_edit.dart';
import '../../ui/action_row.dart';
import '../../ui/alert.dart';
import '../../ui/badge.dart';
import '../../ui/confirm_dialog.dart';
import '../../ui/dialog.dart';
import '../../ui/field.dart';
import '../../ui/info_tip.dart';
import '../../ui/menu.dart';
import '../../ui/section.dart';
import '../../ui/tone.dart';
import 'rig_inspector.dart';
import '../select/animus_select.dart';
import '../npc/npc_layout.dart';

const String _namesTip = 'Behaviors and NPCs play this rig\'s animations by these names. idle plays while an NPC stands, walk while it walks.';

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

  /// Open at first when a name needs a fix; the reader decides after that.
  late final bool _openAtStart = animationNames(component.rig).values.any((Object? id) => _broken('$id'));

  String get _rigId => '${component.rig['id']}';

  List<String> get _own => <String>[
        for (final MapEntry<String, String?> entry in component.documents.entries)
          if (entry.value == _rigId) entry.key,
      ]..sort();

  static String? _role(String name) => switch (name) {
        'idle' => 'Plays while the NPC stands.',
        'walk' => 'Plays while the NPC walks.',
        _ => null,
      };

  /// This rig's animations go by what follows the rig's id ("walk" for
  /// town:golem/walk); others keep their whole id.
  String _shortId(String id) => id.startsWith('$_rigId/') ? id.substring(_rigId.length + 1) : id;

  Map<String, String> _short(List<String> ids) => <String, String>{for (final String id in ids) id: _shortId(id)};

  bool _broken(String id) => !component.documents.containsKey(id) || component.documents[id] != _rigId;

  void _rename(String from) {
    final String? to = _names.remove(from)?.trim();
    if (to == null || to.isEmpty || to == from) {
      setState(() {});
      return;
    }
    component.onEdit((JsonMap rig) => renameAnimationName(rig, from, to));
  }

  Future<void> _remove(String name) async {
    final bool yes = await askToConfirm(
      title: 'Remove the name $name?',
      message: 'The animation stays; behaviors that play $name stop finding it. Undo brings it back.',
      confirmLabel: 'Remove name',
      destructive: true,
    );
    if (yes) component.onEdit((JsonMap json) => removeAnimationName(json, name));
  }

  void _add() {
    final String name = _newName.trim();
    final String animation = _newAnimation.isEmpty && _own.isNotEmpty ? _own.first : _newAnimation;
    if (name.isEmpty || animation.isEmpty) return;
    if (animationNames(component.rig).containsKey(name)) {
      ArcaneSonner.warning('Name $name is taken.');
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
    final int broken = names.values.where((Object? id) => _broken('$id')).length;
    return AppSection(
      key: const ValueKey<String>('rig-animation-names'),
      open: _openAtStart,
      count: broken > 0 ? null : names.length,
      attributes: const <String, String>{'data-testid': 'rig-names'},
      summary: <Widget>[
        const dom.span(classes: 'ui-section-title', <Widget>[
          TipLabel(label: Component.text('Animation names'), tip: _namesTip, docs: Docs.animations),
        ]),
        if (broken > 0) StatusBadge('$broken to fix', tone: BadgeTone.warning),
      ],
      children: <Widget>[
        for (final String missing in navigationAnimations)
          if (!names.containsKey(missing))
            mutedLine(missing == 'idle' ? 'No idle: a standing NPC holds the rest pose.' : 'No walk: a walking NPC plays idle.'),
        for (final MapEntry<String, Object?> entry in names.entries) _row(entry.key, '${entry.value}', own),
        if (own.isEmpty)
          mutedLine('Make an animation first, then name it here.')
        else
          ActionRow(
            dense: true,
            leading: <Widget>[
              wrapItem('0 1 10rem', TextInput(
                  size: ComponentSize.sm,
                  placeholder: freeAnimationName(component.rig, names.containsKey('idle') ? 'walk' : 'idle'),
                  value: _newName,
                  attributes: const <String, String>{'aria-label': 'New name', 'autocomplete': 'off'},
                  onChanged: (String value) => setState(() => _newName = value),
                  onSubmitted: (String _) => _add(),
                ), minWidth: '5rem'),
              wrapItem('0 1 10rem', AnimusSelect.values(
                  value: _newAnimation.isEmpty ? own.first : _newAnimation,
                  values: own,
                  labels: _short(own),
                  hiddenLabel: 'Animation for the new name',
                  onChanged: (String? value) => setState(() => _newAnimation = value ?? ''),
                ), minWidth: '5rem'),
            ],
            secondary: <RowAction>[
              RowAction('Add name', _newName.trim().isEmpty ? null : _add, icon: ArcaneIcon.plus()),
            ],
          ),
      ],
    );
  }

  Widget _row(String name, String id, List<String> own) {
    final bool exists = component.documents.containsKey(id);
    final String? rig = component.documents[id];
    final bool foreign = exists && rig != _rigId;
    final String? role = _role(name);
    return dom.div(classes: 'animus-rig-name', styles: stackStyles(gap: Gap.xs), attributes: <String, String>{'data-name': name, if (!exists || foreign) 'data-broken': ''}, <Widget>[
      ActionRow(
        dense: true,
        leading: <Widget>[
          wrapItem('0 1 10rem', TextInput(
              size: ComponentSize.sm,
              value: _names[name] ?? name,
              attributes: <String, String>{'aria-label': 'Name $name', 'autocomplete': 'off'},
              onChanged: (String value) => _names[name] = value,
              onSubmitted: (String _) => _rename(name),
              onBlur: () => _rename(name),
            ), minWidth: '5rem'),
          wrapItem('0 1 10rem', AnimusSelect.values(
              key: ValueKey<String>('animation:$name:$id'),
              value: id,
              values: <String>[...own, if (!own.contains(id)) id],
              labels: <String, String>{..._short(own), if (!own.contains(id)) id: '${_shortId(id)} (${exists ? 'other rig' : 'missing'})'},
              hiddenLabel: 'Animation for $name',
              onChanged: (String? value) {
                if (value != null) component.onEdit((JsonMap json) => setAnimationName(json, name, value));
              },
            ), minWidth: '5rem'),
        ],
        secondary: <RowAction>[
          RowAction('Open', !exists || foreign ? null : () => component.onOpen(id), icon: ArcaneIcon.film(), attributes: <String, String>{'aria-label': 'Open $id'}),
        ],
        more: <AppMenuEntry>[AppMenuEntry('Remove name', () => _remove(name), destructive: true)],
      ),
      if (role != null) mutedLine(role),
      if (!exists)
        AppAlert(
          tone: AlertTone.warning,
          message: '$id is not in this workspace. The server skips $name.',
          action: documentIdPattern.hasMatch(id) ? Button.outline(label: 'Create $id', size: ButtonSize.sm, onPressed: () => component.onCreate(id)) : null,
        )
      else if (foreign)
        AppAlert(tone: AlertTone.warning, message: '$id belongs to $rig. Pick one of this rig\'s animations.'),
    ]);
  }
}

/// Asks for a new animation's name; Create makes `<rig>/<name>` and names
/// it in the rig.
class NewAnimationDialog extends StatelessWidget {
  const NewAnimationDialog({
    required this.open,
    required this.value,
    required this.suggested,
    required this.problem,
    required this.onChanged,
    required this.onCreate,
    required this.onClose,
    super.key,
  });

  final bool open;
  final String value;

  /// Used when the name is left empty.
  final String suggested;
  final String? problem;
  final void Function(String value) onChanged;
  final void Function() onCreate;
  final void Function() onClose;

  @override
  Widget build(BuildContext context) => AppDialog(
        open: open,
        id: 'rig-new-animation',
        title: 'New animation',
        onClose: onClose,
        footer: DialogFooter(cancel: RowAction('Cancel', onClose), primary: RowAction('Create', onCreate)),
        children: <Widget>[
          Field(
            label: 'Name',
            forId: 'rig-new-animation-name',
            hint: 'Behaviors play it by this name.',
            error: problem,
            child: TextInput(
              id: 'rig-new-animation-name',
              value: value,
              placeholder: suggested,
              attributes: <String, String>{'autocomplete': 'off', 'spellcheck': 'false', if (problem != null) 'aria-invalid': 'true'},
              onChanged: onChanged,
              onSubmitted: (String _) => onCreate(),
            ),
          ),
        ],
      );
}
