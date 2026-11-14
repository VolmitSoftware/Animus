/// What a stage shows (Terrain, Route, Nearby) and how far around (the
/// radius), for a stage header. The layers are toggles beside the title,
/// each with a check while on, as long as they fit; when they do not, the
/// stage lists them in its "..." menu instead, where the radius always is.
/// Shared by the NPC preview and the 3D route view.
library;

import 'dart:js_interop';

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;
import 'package:web/web.dart' as web;

import '../../ui/density.dart';
import '../../ui/menu.dart';
import '../../ui/toggle_choice.dart' show ChoiceOption;
import 'terrain_window.dart';

/// Width a check adds to a layer toggle that is off now: the icon and the
/// gap after it.
const double _checkPx = 24;

/// The layer toggles beside a stage's title. They stay laid out while
/// hidden, so the stage learns from [onFits] when they fit beside the title
/// again (counting the checks the layers that are off would add).
class StageShowControls extends StatefulWidget {
  const StageShowControls({required this.id, required this.options, required this.values, required this.onChanged, required this.onFits, this.fits = true, super.key});

  /// Prefixes the element ids.
  final String id;

  /// The layers that can be switched, such as Terrain and Route.
  final List<ChoiceOption<String>> options;
  final Set<String> values;
  final void Function(Set<String> values) onChanged;

  /// Whether the toggles show; false while the stage lists them in its menu.
  final bool fits;
  final void Function(bool fits) onFits;

  @override
  State<StageShowControls> createState() => _StageShowControlsState();
}

class _StageShowControlsState extends State<StageShowControls> {
  web.ResizeObserver? _observer;

  String get _rootId => '${component.id}-show-controls';

  @override
  void initState() {
    super.initState();
    context.binding.addPostFrameCallback(_observe);
  }

  @override
  void didUpdateWidget(StageShowControls oldComponent) {
    super.didUpdateWidget(oldComponent);
    // New choices may need another width.
    context.binding.addPostFrameCallback(_fit);
  }

  @override
  void dispose() {
    _observer?.disconnect();
    super.dispose();
  }

  void _observe() {
    if (!mounted || _observer != null) return;
    final web.Element? root = web.document.getElementById(_rootId);
    if (root == null) return;
    _observer = web.ResizeObserver(((JSArray<web.ResizeObserverEntry> _, web.ResizeObserver _) => _fit()).toJS);
    _observer!.observe(root);
    _fit();
  }

  void _fit() {
    if (!mounted) return;
    final web.Element? root = web.document.getElementById(_rootId);
    final web.Element? wide = root?.querySelector('.animus-stage-show-wide');
    if (root == null || wide == null) return;
    final int off = component.options.where((ChoiceOption<String> option) => !component.values.contains(option.value)).length;
    final bool fits = wide.scrollWidth + off * _checkPx <= root.clientWidth + 0.5;
    if (fits != component.fits) component.onFits(fits);
  }

  @override
  Widget build(BuildContext context) {
    final List<ChoiceOption<String>> options = component.options;
    final Set<String> values = component.values;
    final bool fits = component.fits;
    return dom.div(id: _rootId, classes: 'animus-stage-show${fits ? '' : ' is-folded'}', <Widget>[
      dom.div(
        classes: 'animus-stage-show-wide',
        attributes: <String, String>{'role': 'group', 'aria-label': 'Show', if (!fits) 'aria-hidden': 'true', if (!fits) 'inert': ''},
        <Widget>[
          ArcaneToggleGroup.multiple(
            items: <ToggleGroupItem>[
              for (int index = 0; index < options.length; index++)
                ToggleGroupItem(
                  value: '$index',
                  child: Component.fragment(<Widget>[
                    if (values.contains(options[index].value)) ArcaneIcon.check().sized(controlIconSize),
                    Component.text(options[index].label),
                  ]),
                ),
            ],
            values: <String>{
              for (int index = 0; index < options.length; index++)
                if (values.contains(options[index].value)) '$index',
            },
            variant: ToggleGroupVariant.outline,
            size: DensityScope.isDense(context) ? ToggleGroupSize.sm : ToggleGroupSize.md,
            onMultiChanged: (Set<String> picked) => component.onChanged(<String>{
              for (final String id in picked)
                if (int.tryParse(id) case final int index when index < options.length) options[index].value,
            }),
          ),
        ],
      ),
    ]);
  }
}

/// The stage's "..." entries: the layers while they do not fit beside the
/// title ([layers]), then the radius in blocks when one applies. A row says
/// "On" in words as well as with its check.
List<AppMenuEntry> stageShowEntries({
  required List<ChoiceOption<String>> options,
  required Set<String> values,
  required void Function(Set<String> values) onChanged,
  required bool layers,
  int? radius,
  void Function(int radius)? onRadius,
}) =>
    <AppMenuEntry>[
      if (layers)
        for (final ChoiceOption<String> option in options)
          AppMenuEntry(
            option.label,
            () => onChanged(values.contains(option.value) ? (Set<String>.of(values)..remove(option.value)) : <String>{...values, option.value}),
            hint: values.contains(option.value) ? 'On' : null,
            icon: values.contains(option.value) ? ArcaneIcon.check() : null,
          ),
      if (radius != null && onRadius != null) ...<AppMenuEntry>[
        if (layers && options.isNotEmpty) const AppMenuEntry.separator(),
        for (final int option in terrainRadii)
          AppMenuEntry('$option block radius', () => onRadius(option), hint: option == radius ? 'On' : null, icon: option == radius ? ArcaneIcon.check() : null),
      ],
    ];
