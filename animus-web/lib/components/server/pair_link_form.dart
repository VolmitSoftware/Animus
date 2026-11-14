/// The field a pairing link is pasted into, with Pair. Used on the start
/// page, on the pair page when a link is spent, and in Review when the
/// pairing ends.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../services/server/pairing.dart';
import '../../ui/action_row.dart';
import '../../ui/field.dart';
import 'pair_copy.dart';

class PairLinkForm extends StatefulWidget {
  const PairLinkForm({required this.onCode, this.id = 'pair-link', this.label = 'Pairing link', this.hint, super.key});

  /// Called with the `ANM1.` code from what was pasted.
  final void Function(String code) onCode;

  /// The text box's id; the label names it.
  final String id;
  final String label;
  final String? hint;

  @override
  State<PairLinkForm> createState() => _PairLinkFormState();
}

class _PairLinkFormState extends State<PairLinkForm> {
  String _text = '';
  String? _error;

  void _submit() {
    final String code = extractPairingCode(_text);
    if (!code.startsWith(pairingPrefix)) {
      setState(() => _error = _text.trim().isEmpty ? 'Paste the link from the chat.' : notALink.title);
      return;
    }
    component.onCode(code);
  }

  @override
  Widget build(BuildContext context) => dom.div(<Widget>[
        Field(
          label: component.label,
          forId: component.id,
          hint: component.hint,
          error: _error,
          child: TextInput(
            id: component.id,
            value: _text,
            placeholder: 'https://.../#/pair/ANM1...',
            fullWidth: true,
            attributes: const <String, String>{'autocomplete': 'off', 'spellcheck': 'false'},
            onChanged: (String value) => setState(() {
              _text = value;
              _error = null;
            }),
            onSubmitted: (String _) => _submit(),
          ),
        ),
        // It opens the pair page, whose Pair is the one that pairs.
        FormFooter(primary: RowAction('Open link', _submit, attributes: <String, String>{'data-testid': '${component.id}-submit'})),
      ]);
}
