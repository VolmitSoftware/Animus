/// The Import wizard's Review step for a conversion made in this browser:
/// what it makes, in words, what could not be converted, the warnings, and
/// in Advanced the counts and tables behind them.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../config/docs_links.dart';
import '../../logic/citizens/citizens_review.dart';
import '../../logic/citizens/denizen_folder.dart';
import '../../logic/citizens/java_json.dart';
import '../../logic/denizen/denizen_converter.dart';
import '../../logic/plural.dart';
import '../../logic/shopkeepers/shopkeepers_converter.dart';
import '../../ui/alert.dart';
import '../../ui/mode.dart';
import '../../ui/section.dart';
import 'import_plan.dart';
import 'page_parts.dart';
import '../../ui/stack.dart';

/// A field status from a Citizens NPC report, in words.
String fieldStatusLabel(String status) => switch (status) {
      'CONVERTED' => 'Converted',
      'REQUIRES_REVIEW' => 'Converted, check it',
      'UNSUPPORTED_PRESERVED' => 'Kept, not run',
      'REQUIRES_INTEGRATION' => 'Needs another plugin',
      _ => status.toLowerCase().replaceAll('_', ' '),
    };

/// The longest list shown in full; a report.json holds the rest.
const int _listLimit = 500;

/// The line that says what a conversion makes.
Widget createdParagraph(Map<String, String> files) => dom.p(attributes: const <String, String>{'data-testid': 'import-created'}, <Widget>[
      Text(createdLine(kindCounts(files.keys)), weight: FontWeight.w500),
    ]);

/// A list of lines in a disclosure, open while it is short.
Widget lineList(String title, List<String> lines, {String? testId}) => AppSection(
      title: title,
      count: lines.length,
      open: lines.length <= 20,
      attributes: <String, String>{'data-testid': ?testId},
      children: <Widget>[
        rowList(<Widget>[
          for (final String line in lines.take(_listLimit)) listItem(Text(line, size: FontSize.sm)),
          if (lines.length > _listLimit) listItem(Text('${lines.length - _listLimit} more in report.json', size: FontSize.sm, color: TextColor.muted)),
        ]),
      ],
    );

/// Counts as a table, the non-zero ones only.
Widget countTable(List<(String, int)> counts, {String key = 'What', String value = 'Count'}) => StaticTable(
      headers: <String>[key, value],
      alignments: const <TextAlign>[TextAlign.left, TextAlign.right],
      rows: <List<Widget>>[
        for (final (String label, int count) in counts)
          if (count != 0) <Widget>[Component.text(label), Component.text('$count')],
      ],
    );

/// [counts] by value, largest first.
List<(String, int)> _sorted(Map<String, int> counts, String Function(String key) label) {
  final List<String> keys = counts.keys.toList()
    ..sort((String a, String b) => counts[b]!.compareTo(counts[a]!) != 0 ? counts[b]!.compareTo(counts[a]!) : a.compareTo(b));
  return <(String, int)>[for (final String key in keys) (label(key), counts[key]!)];
}

/// The import id, Advanced only.
Widget _importId(String id) => dom.p(<Widget>[
      const Component.text('Import '),
      dom.code(attributes: const <String, String>{'data-testid': 'import-id'}, <Widget>[Component.text(id)]),
    ]);

class CitizensImportReview extends StatelessWidget {
  const CitizensImportReview({required this.review, required this.names, super.key});

  final CitizensReview review;

  /// The converted NPCs' names by Citizens id, for the warnings.
  final Map<String, String> names;

  @override
  Widget build(BuildContext context) {
    final bool advanced = ModeScope.advanced(context);
    final List<String> notLoading = <String>[
      for (final String issue in review.issues) advanced ? plainWarning(issue, names) : simpleIssue(plainWarning(issue, names)),
    ];
    final List<String> warnings = <String>[for (final String warning in review.warnings) plainWarning(warning, names)];
    return stack(spacing: 16, <Widget>[
      createdParagraph(review.preview.files),
      if (notLoading.isEmpty && warnings.isEmpty) const AppAlert(tone: AlertTone.ok, message: 'Every NPC converted, with no warnings.'),
      if (notLoading.isNotEmpty) lineList('Not converted', notLoading, testId: 'import-not-converted'),
      if (warnings.isNotEmpty) lineList('Warnings', warnings, testId: 'import-warnings'),
      if (advanced) ...<Widget>[
        AppSection(title: 'Counts', open: false, children: <Widget>[
          countTable(<(String, int)>[
            ('Records', review.preview.records),
            ('Valid definitions', review.preview.valid),
            ('Unique skins', review.preview.skins),
            ('References resolved', review.resolvedReferences),
            ('References unresolved', review.unresolvedReferences),
            ('Locations remapped', review.remappedLocations),
            ('Patrol routes', review.conversions['routes'] ?? 0),
            ('Look-close', review.conversions['lookClose'] ?? 0),
            ('Text behaviors', review.conversions['textBehaviors'] ?? 0),
            ('Command behaviors', review.conversions['commandBehaviors'] ?? 0),
            ('Skins kept to check', review.conversions['skinsKept'] ?? 0),
          ]),
        ]),
        AppSection(title: 'Fields by status', open: false, tip: 'How each Citizens field carried over.', docs: Docs.citizensOutput, children: <Widget>[
          countTable(_sorted(review.classification, fieldStatusLabel), key: 'Status', value: 'Fields'),
        ]),
        AppSection(title: 'Traits', count: review.traits.length, open: false, children: <Widget>[
          countTable(_sorted(review.traits, (String key) => key), key: 'Trait', value: 'NPCs'),
        ]),
        AppSection(title: 'Body types', count: review.types.length, open: false, children: <Widget>[
          countTable(_sorted(review.types, (String key) => key), key: 'Type', value: 'NPCs'),
        ]),
        _importId(review.preview.id),
      ],
    ]);
  }
}

class ShopsImportReview extends StatelessWidget {
  const ShopsImportReview({required this.preview, required this.linked, super.key});

  final ShopkeepersPreview preview;

  /// A Citizens save was given, so links were checked.
  final bool linked;

  @override
  Widget build(BuildContext context) {
    final bool advanced = ModeScope.advanced(context);
    final List<String> issues = <String>[for (final JElement issue in (preview.summary.get('issues')! as JArray).elements) issue.asPrimitive.asString];
    final List<String> notes = <String>[
      if (preview.skippedOffers > 0) '${countOf(preview.skippedOffers, 'offer')} could not convert and stay in source.yml.',
      if (preview.linksMissing > 0) '${countOf(preview.linksMissing, 'shop')} point at a Citizens NPC that is not in the save.',
      if (!linked && preview.linksUnchecked > 0) '${countOf(preview.linksUnchecked, 'shop')} link to Citizens NPCs that were not checked.',
    ];
    return stack(spacing: 16, <Widget>[
      createdParagraph(preview.files),
      if (issues.isEmpty && notes.isEmpty) const AppAlert(tone: AlertTone.ok, message: 'Every shop converted.'),
      if (notes.isNotEmpty) rowList(<Widget>[for (final String note in notes) listItem(Text(note, size: FontSize.sm))]),
      if (issues.isNotEmpty) lineList('Not converted', issues, testId: 'import-not-converted'),
      if (advanced) ...<Widget>[
        AppSection(title: 'Counts', open: false, children: <Widget>[
          countTable(<(String, int)>[
            ('Shops', preview.shops),
            ('Trading offers', preview.tradeOffers),
            ('Shown only', preview.staticOffers),
            ('Kept in source.yml', preview.skippedOffers),
            ('New mob NPCs', preview.npcs),
            ('Citizens links found', preview.linksFound),
            ('Citizens links not found', preview.linksMissing),
            ('Citizens links unchecked', preview.linksUnchecked),
          ]),
        ]),
        _importId(preview.id),
      ],
    ]);
  }
}

class DenizenImportReview extends StatelessWidget {
  const DenizenImportReview({required this.folder, required this.preview, super.key});

  final DenizenFolder folder;
  final DenizenPreview preview;

  @override
  Widget build(BuildContext context) {
    final bool advanced = ModeScope.advanced(context);
    final List<String> issues = <String>[for (final JElement issue in (preview.report.get('issues')! as JArray).elements) issue.asPrimitive.asString];
    final List<String> notes = <String>[
      if (preview.preserved > 0) '${countOf(preview.preserved, 'script part')} kept but not run.',
      if (preview.flagsPreserved > 0) '${countOf(preview.flagsPreserved, 'flag')} kept but not run.',
      if (folder.skipped.isNotEmpty) '${countOf(folder.skipped.length, 'file')} in the folder not read.',
    ];
    return stack(spacing: 16, <Widget>[
      createdParagraph(preview.files),
      if (issues.isEmpty && notes.isEmpty) const AppAlert(tone: AlertTone.ok, message: 'Every script converted.'),
      if (notes.isNotEmpty) rowList(<Widget>[for (final String note in notes) listItem(Text(note, size: FontSize.sm))]),
      if (issues.isNotEmpty) lineList('Not converted', issues, testId: 'import-not-converted'),
      if (advanced) ...<Widget>[
        AppSection(title: 'Counts', open: false, children: <Widget>[
          countTable(<(String, int)>[
            ('Scripts read', folder.scripts),
            ('Behaviors', preview.behaviors),
            ('NPCs bound', preview.bindings),
            ('Flags imported', preview.flagsImported),
            ('Kept, not run', preview.preserved),
            ('Flags kept, not run', preview.flagsPreserved),
          ]),
        ]),
        if (folder.skipped.isNotEmpty)
          lineList('Files not read', folder.skipped, testId: 'denizen-skipped'),
        _importId(preview.id),
      ],
    ]);
  }
}
