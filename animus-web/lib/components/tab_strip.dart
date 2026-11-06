/// A row of tabs with the tablist keyboard pattern: one Tab stop, Left and
/// Right (or Home and End) move and select. The selected tab is a raised
/// neutral fill with a full pink border (`.animus-tabs`), so pink fills stay
/// for actions.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

/// One tab: its value and visible label.
class TabOption<T> {
  const TabOption(this.value, this.label);

  final T value;
  final String label;
}

class TabStrip<T> extends StatelessWidget {
  const TabStrip({
    required this.idPrefix,
    required this.label,
    required this.options,
    required this.selected,
    required this.panelId,
    required this.onSelect,
    super.key,
  });

  /// Tabs get the ids `<idPrefix>-<index>`.
  final String idPrefix;

  /// The tablist's accessible name.
  final String label;
  final List<TabOption<T>> options;
  final T selected;

  /// The id of the element the tabs control (`role="tabpanel"`).
  final String panelId;
  final void Function(T value) onSelect;

  /// The id of the selected tab, for the panel's `aria-labelledby`.
  static String tabId(String idPrefix, int index) => '$idPrefix-$index';

  void _move(web.Event raw, int from) {
    final web.KeyboardEvent event = raw as web.KeyboardEvent;
    final int count = options.length;
    final int? to = switch (event.key) {
      'ArrowRight' => (from + 1) % count,
      'ArrowLeft' => (from - 1 + count) % count,
      'Home' => 0,
      'End' => count - 1,
      _ => null,
    };
    if (to == null) return;
    event.preventDefault();
    onSelect(options[to].value);
    (web.document.getElementById(tabId(idPrefix, to)) as web.HTMLElement?)?.focus();
  }

  @override
  Widget build(BuildContext context) => dom.div(
        classes: 'animus-tabs',
        attributes: <String, String>{'role': 'tablist', 'aria-label': label},
        <Widget>[
          for (int index = 0; index < options.length; index++)
            dom.button(
              id: tabId(idPrefix, index),
              classes: 'animus-tab',
              attributes: <String, String>{
                'type': 'button',
                'role': 'tab',
                'aria-selected': '${options[index].value == selected}',
                'aria-controls': panelId,
                'tabindex': options[index].value == selected ? '0' : '-1',
              },
              events: <String, void Function(web.Event)>{
                'click': (web.Event _) => onSelect(options[index].value),
                'keydown': (web.Event event) => _move(event, index),
              },
              <Widget>[Component.text(options[index].label)],
            ),
        ],
      );
}
