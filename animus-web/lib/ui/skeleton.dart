/// Loading rows: grey bars where content will be, read as "Loading" by
/// screen readers. On shadcn's skeleton.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

class Skeleton extends StatelessWidget {
  const Skeleton({this.rows = 3, this.label = 'Loading', this.height, super.key});

  /// Bars to draw; with [height], one block of that height instead.
  final int rows;
  final String label;

  /// A CSS height for one block (a canvas placeholder).
  final String? height;

  @override
  Widget build(BuildContext context) => dom.div(
        classes: 'ui-skeleton',
        attributes: <String, String>{'role': 'status', 'aria-label': label},
        <Widget>[
          Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, spacing: 8, children: <Widget>[
            if (height != null)
              ArcaneSkeleton(width: '100%', height: height)
            else
              for (int row = 0; row < rows; row++) ArcaneSkeleton.line(width: row == rows - 1 && rows > 1 ? '60%' : '100%'),
          ]),
        ],
      );
}
