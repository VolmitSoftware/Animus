/// Which theme the editor opens in. Pure Dart; web/bootstrap.js makes the
/// same choice before paint.
library;

/// The theme picked in the overflow menu ([stored] is `light` or `dark`)
/// wins; until one is picked the editor follows the system.
bool opensLight({required String? stored, required bool systemLight}) => switch (stored) {
      'light' => true,
      'dark' => false,
      _ => systemLight,
    };
