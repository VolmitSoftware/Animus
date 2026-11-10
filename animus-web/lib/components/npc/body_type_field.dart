/// An NPC's body `type`. While the NPC wears a skin (its own or one its
/// groups give), a type other than `minecraft:player` is held back: the
/// server refuses a skin on any other body (`DefinitionSpec.activation`). A
/// known body type, or Enter, asks whether to remove the skin and change the
/// type in one step, or keep the type.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/inspect/schema_fields.dart';
import '../../mc/rigs/mc_rigs.dart';
import '../offline/offline_fields.dart';
import '../select/suggest_input.dart';

class BodyTypeField extends StatefulWidget {
  const BodyTypeField({
    required this.value,
    required this.onChanged,
    required this.onChangeWithoutSkin,
    this.skinned = false,
    this.skinGroups = const <String>[],
    this.otherMembers = 0,
    super.key,
  });

  final String value;
  final void Function(String type) onChanged;

  /// Changes the type and removes the skin, as one undo step.
  final void Function(String type) onChangeWithoutSkin;

  /// Whether the NPC sets a skin itself.
  final bool skinned;

  /// The groups that give it a skin.
  final List<String> skinGroups;

  /// Other NPCs in [skinGroups], who lose that skin too.
  final int otherMembers;

  @override
  State<BodyTypeField> createState() => _BodyTypeFieldState();
}

const String _player = 'minecraft:player';

class _BodyTypeFieldState extends State<BodyTypeField> {
  /// A type held back while the question shows.
  String? _asking;

  /// The type the reader declined, for the line that says why.
  String? _kept;

  /// The text in the field, which may be ahead of [BodyTypeField.value].
  String? _draft;

  bool get _skinned => component.skinned || component.skinGroups.isNotEmpty;

  void _typed(String text) {
    final String type = text.trim();
    _draft = text;
    setState(() => _kept = null);
    if (!_skinned || type == _player || type.isEmpty) {
      component.onChanged(text);
      return;
    }
    if (mcPresentedBodyTypes.contains(type)) setState(() => _asking = type);
  }

  void _submit(String text) {
    final String type = text.trim();
    if (_skinned && type.isNotEmpty && type != _player) setState(() => _asking = type);
  }

  String get _detail {
    final List<String> groups = component.skinGroups;
    if (groups.isEmpty) return 'Undo restores both.';
    final String named = groups.length == 1 ? 'group ${groups.single}' : 'groups ${groups.join(', ')}';
    final int others = component.otherMembers;
    return 'Also removes it from $named${others == 0 ? '' : ', which $others other NPC${others == 1 ? '' : 's'} use${others == 1 ? 's' : ''}'}. Undo restores all.';
  }

  @override
  Widget build(BuildContext context) {
    final String? asking = _asking;
    return dom.div(classes: 'animus-body-type', <Widget>[
      SuggestInput(
        value: component.value,
        label: 'Body type',
        suggestions: <FieldOption>[for (final String body in mcPresentedBodyTypes) FieldOption(body)],
        onChanged: _typed,
        onSubmit: () => _submit(_draft ?? component.value),
      ),
      if (asking != null)
        ConfirmRow(
          question: 'A skin needs a player body. Remove the skin and use $asking?',
          detail: _detail,
          confirmLabel: 'Remove skin',
          onConfirm: () {
            setState(() => _asking = null);
            component.onChangeWithoutSkin(asking);
          },
          onCancel: () => setState(() {
            _asking = null;
            _kept = asking;
          }),
        )
      else if (_kept != null)
        dom.p(classes: 'animus-gui-problem is-warning', attributes: const <String, String>{'role': 'status'}, <Widget>[
          Component.text('Kept ${component.value}: $_kept cannot wear a skin.'),
        ]),
    ]);
  }
}
