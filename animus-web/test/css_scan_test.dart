// The editor's stylesheets lay out its own pages and never restyle an
// arcane_jaspr component: no selector names a library class, the amount of
// custom CSS stays under a ceiling so it cannot creep back, and every
// stylesheet keeps the one standard format (tool/format_css.dart) so the
// files stay readable and the count cannot be lowered by packing lines.
import 'dart:io';

import 'package:animus_editor/config/theme_css.dart';
import 'package:test/test.dart';

import '../tool/format_css.dart';

/// Library class names: `arcane-*` (except the theme class on the root) and
/// the shadcn sidebar's own.
final RegExp libraryClass = RegExp(r'\.(arcane-(?!theme-)[a-z0-9-]+|sidebar-[a-z0-9-]+)');

/// Rule heads plus declarations of app CSS (web/styles and the theme
/// tokens), whatever the line breaks; lower it as screens move to the
/// library, never raise it.
const int cssCeiling = 1415;

/// The selectors of [css], comments dropped, one per rule head.
Iterable<String> selectors(String css) sync* {
  final String text = css.replaceAll(RegExp(r'/\*[\s\S]*?\*/'), '');
  for (final RegExpMatch match in RegExp(r'([^{}]+)\{').allMatches(text)) {
    final String head = match[1]!.trim();
    if (head.isEmpty || head.startsWith('@')) continue;
    yield head;
  }
}

void main() {
  test('no app stylesheet selects an arcane_jaspr component class', () {
    final List<String> found = <String>[
      for (final File file in stylesheets())
        for (final String selector in selectors(file.readAsStringSync()))
          if (libraryClass.hasMatch(selector)) '${file.path}: $selector',
      for (final String selector in selectors(themeCss()))
        if (libraryClass.hasMatch(selector)) 'theme_css.dart: $selector',
    ];
    expect(found, isEmpty, reason: 'Use the library component as it renders; fix a defect in arcane_jaspr.');
  });

  test('app CSS stays under its ceiling of rules and declarations', () {
    final List<CssSize> sizes = <CssSize>[for (final File file in stylesheets()) cssSize(file.readAsStringSync()), cssSize(themeCss())];
    final int rules = sizes.fold(0, (int sum, CssSize size) => sum + size.rules);
    final int declarations = sizes.fold(0, (int sum, CssSize size) => sum + size.declarations);
    expect(rules + declarations, lessThanOrEqualTo(cssCeiling), reason: '$rules rules and $declarations declarations of app CSS');
  });

  test('every stylesheet is in the standard format', () {
    final List<String> unformatted = <String>[
      for (final File file in stylesheets())
        if (formatCss(file.readAsStringSync()) != file.readAsStringSync()) file.path,
    ];
    expect(unformatted, isEmpty, reason: 'Run dart run tool/format_css.dart');
  });

  test('the scan catches what it looks for', () {
    expect(selectors('/* .arcane-button */ .ui-page > .arcane-button { color: red; }').single, '.ui-page > .arcane-button');
    expect(libraryClass.hasMatch('#arcane-root.arcane-theme-shadcn'), isFalse);
    expect(libraryClass.hasMatch('.ui-nav .sidebar-link'), isTrue);
    expect(libraryClass.hasMatch(':is(.arcane-tab-bar-item, .x)'), isTrue);
    expect(selectors('@media (max-width: 760px) { .a { b: c; } }').toList(), <String>['.a']);
  });

  test('the count ignores line breaks and comments, and the format puts one declaration on a line', () {
    const String packed = '/* A note. */ .a, .b:is(.c, .d) { color: red; margin: 0 } @media (max-width: 760px) { .a { color: blue; } }';
    expect(cssSize(packed), (rules: 3, declarations: 3));
    expect(cssSize(formatCss(packed)), cssSize(packed));
    expect(formatCss(packed), '/* A note. */\n.a,\n.b:is(.c, .d) {\n  color: red;\n  margin: 0;\n}\n\n@media (max-width: 760px) {\n  .a {\n    color: blue;\n  }\n}\n');
    expect(formatCss(formatCss(packed)), formatCss(packed));
  });
}
