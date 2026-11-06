import 'dart:io';

import 'package:animus_editor/config/docs_links.dart';
import 'package:animus_editor/logic/help_text.dart';
import 'package:animus_editor/model/document_kind.dart';
import 'package:test/test.dart';

/// The wiki's animus folder in a docs checkout beside this repository, or null.
Directory? _wiki() {
  Directory current = Directory.current.absolute;
  while (true) {
    final Directory candidate = Directory('${current.parent.path}/docs/animus');
    if (File('${candidate.path}/14-web-editor.md').existsSync()) return candidate;
    if (current.parent.path == current.path) return null;
    current = current.parent;
  }
}

/// Wiki.js heading slugs: lowercase, punctuation dropped, spaces as dashes.
String _slug(String heading) => heading.toLowerCase().replaceAll(RegExp(r'[^a-z0-9 -]'), '').trim().replaceAll(RegExp(r'\s+'), '-');

void main() {
  group('help text', () {
    test('test_splitHelp_shortText_staysInline', () {
      expect(splitHelp('1 to 72000'), (hint: '1 to 72000', tip: null));
    });

    test('test_splitHelp_longText_movesToTip', () {
      const String long = 'The folder under npcs/, empty for npcs/ itself. Animus sets it from where the file is.';
      expect(splitHelp(long), (hint: null, tip: long));
    });

    test('test_splitHelp_blank_isNothing', () {
      expect(splitHelp('  '), (hint: null, tip: null));
      expect(splitHelp(null), (hint: null, tip: null));
    });

    test('test_tipText_cutsToTwoSentences', () {
      expect(tipText('One. Two! Three? Four.'), 'One. Two!');
      expect(tipText('Only one sentence'), 'Only one sentence');
    });
  });

  group('docs links', () {
    test('test_docLink_url_pointsAtTheAnimusWiki', () {
      expect(Docs.groupMerge.url, 'https://docs.volmitsoftware.com/animus/18-trait-groups#merge-rules');
      expect(const DocLink('14-web-editor').url, 'https://docs.volmitsoftware.com/animus/14-web-editor');
    });

    test('test_forKind_coversEveryKind', () {
      for (final DocumentKind kind in DocumentKind.values) {
        expect(Docs.forKind(kind).page, isNotEmpty, reason: kind.name);
      }
    });

    test('test_everyLink_namesAHeadingOfItsPage', () {
      final Directory? wiki = _wiki();
      if (wiki == null) {
        markTestSkipped('no docs checkout beside the repository');
        return;
      }
      final Uri source = Uri.file('${Directory.current.path}/lib/config/docs_links.dart');
      final String code = File.fromUri(source).readAsStringSync();
      final Iterable<RegExpMatch> links = RegExp(r"DocLink\('([^']+)'(?:, '([^']+)')?\)").allMatches(code);
      expect(links, isNotEmpty);
      for (final RegExpMatch link in links) {
        final File page = File('${wiki.path}/${link.group(1)}.md');
        expect(page.existsSync(), isTrue, reason: '${link.group(1)} is not a wiki page');
        final String? anchor = link.group(2);
        if (anchor == null) continue;
        final Set<String> slugs = <String>{
          for (final String line in page.readAsLinesSync())
            if (line.startsWith('#')) _slug(line.replaceFirst(RegExp(r'^#+\s*'), '')),
        };
        expect(slugs, contains(anchor), reason: '${link.group(1)}#$anchor');
      }
    });
  });
}
