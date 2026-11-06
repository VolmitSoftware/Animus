import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

/// The Animus mark above the pairing page and the no-server states. The
/// brand lane ships the asset and its component; this is the one place to
/// swap in that component when it lands.
Component serverMark() => const dom.img(
      classes: 'animus-server-mark',
      src: '/assets/brand/animus-mark.svg',
      width: 48,
      height: 48,
      alt: '',
    );
