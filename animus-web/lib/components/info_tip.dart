/// Help on a label or heading: the text followed by the kit's square (i)
/// button ([TipLabel]), which shows the tip on hover or focus and pins it on
/// a click or tap, with a Docs link.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';

import '../config/docs_links.dart';
import '../ui/info_tip.dart';

class InfoTip extends StatelessWidget {
  const InfoTip({required this.tip, required this.child, this.docs, super.key});

  /// One or two short sentences; longer text is cut to two.
  final String tip;

  /// The visible text the tip explains.
  final Widget child;

  /// The wiki heading; null uses the surrounding [DocsScope].
  final DocLink? docs;

  @override
  Widget build(BuildContext context) => TipLabel(label: child, tip: tip, docs: docs);
}

/// [text] with its (i) tip, or [text] alone when there is no tip.
Widget withTip(Widget text, String? tip, {DocLink? docs}) => TipLabel(label: text, tip: tip, docs: docs);
