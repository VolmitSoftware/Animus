import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

/// Where the Animus mark is served; `firebase.json` caches it for a year,
/// so a changed mark gets a new file name.
const String brandMarkPath = '/assets/brand/animus-mark.svg';

/// The Animus mark (the grey triangle with the pink stripe and diamond).
/// Decorative: the text beside it names the place.
class BrandMark extends StatelessWidget {
  const BrandMark({this.size = 28, this.classes, super.key});

  final int size;
  final String? classes;

  @override
  Widget build(BuildContext context) => dom.img(
        src: brandMarkPath,
        alt: '',
        width: size,
        height: size,
        classes: 'animus-mark${classes == null ? '' : ' $classes'}',
      );
}
