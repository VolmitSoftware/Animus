/// The code font of the behavior and conversation editors' code fields.
library;

import 'package:jaspr/dom.dart' as dom;

/// Monospace for a code field or its box: the theme gives a native field the
/// font it sits in, and an inline font beats that.
const dom.Styles codeFont = dom.Styles(raw: <String, String>{'font-family': 'var(--font-mono, monospace)'});
