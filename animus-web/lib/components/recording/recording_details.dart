/// The editable part of a recording's Visual view: its id (moved through the
/// document rename, which carries the NPCs and routes that play it), and the
/// description, tags, default playback and speed the server stores with it.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;
import 'package:jaspr/jaspr.dart' show Component;

import '../../logic/gui/gui_json.dart';
import '../../logic/select/select_model.dart' show withDefault;
import '../../logic/recording/recording_edit.dart';
import '../../logic/suggest/field_profile.dart';
import '../../model/animus_document.dart';
import '../gui/gui_fields.dart';
import '../select/suggest_input.dart';

const String recordingIdInputId = 'animus-recording-id';
const String recordingDescriptionId = 'animus-recording-description';
const String recordingTagInputId = 'animus-recording-tag';
const String recordingPlaybackId = 'animus-recording-playback';
const String recordingSpeedId = 'animus-recording-speed';
const String recordingRouteInputId = 'animus-recording-route';

class RecordingDetailsForm extends StatefulWidget {
  const RecordingDetailsForm({required this.document, required this.onJson, this.onRename, this.onMakeRoute, this.knownTags = const <String>[], super.key});

  final AnimusDocument document;
  final JsonEdit onJson;

  /// Moves the recording to a new id; returns a problem to show, or null.
  final Future<String?> Function(String id)? onRename;

  /// Makes a route under an id that follows the recording; returns a problem to show, or null.
  final Future<String?> Function(String id)? onMakeRoute;

  /// Tags other recordings use, offered while typing one.
  final List<String> knownTags;

  @override
  State<RecordingDetailsForm> createState() => _RecordingDetailsFormState();
}

class _RecordingDetailsFormState extends State<RecordingDetailsForm> {
  String? _id;
  String? _idProblem;
  String _newTag = '';
  String? _speedProblem;
  String? _routeId;
  String? _routeProblem;

  JsonMap get _json => asMap(component.document.json);
  RecordingDetails get _details => RecordingDetails.read(_json);

  void _write(RecordingDetails details, String field) {
    final JsonMap shown = _json;
    component.onJson(writeAt(shown, const <JsonStep>[], details.writeTo(shown)), coalesce: 'recording:$field');
  }

  Future<void> _rename() async {
    final String? next = _id?.trim();
    if (next == null || next == component.document.id) return;
    final String? problem = await component.onRename!(next);
    if (!mounted) return;
    setState(() {
      _idProblem = problem;
      if (problem == null) _id = null;
    });
  }

  Future<void> _makeRoute(String id) async {
    final String? problem = await component.onMakeRoute!(id);
    if (!mounted) return;
    setState(() {
      _routeProblem = problem;
      if (problem == null) _routeId = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final RecordingDetails details = _details;
    final String routeId = (_routeId ?? component.document.id).trim();
    final bool routeValid = recordingIdPattern.hasMatch(routeId);
    final List<Diagnostic> diagnostics = component.document.diagnostics;
    final String id = _id ?? component.document.id;
    final bool idChanged = id.trim() != component.document.id;
    final bool idValid = recordingIdPattern.hasMatch(id.trim());
    final String adding = _newTag;
    final bool tagValid = recordingTagPattern.hasMatch(adding) && !details.tags.contains(adding) && details.tags.length < recordingTagLimit;
    void addTag() {
      _write(details.copyWith(tags: <String>[...details.tags, adding]), 'tags');
      setState(() => _newTag = '');
    }

    return dom.div(
      classes: 'animus-recording-details',
      attributes: const <String, String>{'data-recording': 'details'},
      <Widget>[
        if (component.onRename != null)
          GuiRow(
            label: 'Id',
            forId: recordingIdInputId,
            tip: 'Renaming moves the file and updates the NPCs and routes that play it.',
            problems: diagnosticsAt(diagnostics, r'$.id'),
            child: dom.div(classes: 'animus-gui-line', <Widget>[
              GuiTextInput(
                id: recordingIdInputId,
                value: id,
                onChanged: (String value) => setState(() {
                  _id = value;
                  _idProblem = null;
                }),
              ),
              Button.secondary(id: 'animus-recording-rename', label: 'Rename', size: ButtonSize.small, disabled: !idChanged || !idValid, onPressed: _rename),
              if (_idProblem != null || (idChanged && !idValid))
                dom.span(classes: 'animus-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[Component.text(_idProblem ?? 'Use 1-64 lowercase letters, digits, _ or -.')]),
            ]),
          ),
        if (component.onMakeRoute != null)
          GuiRow(
            label: 'Route',
            forId: recordingRouteInputId,
            tip: 'A route along the recorded path. Each leg keeps the style recorded on it: sneak, sprint, swim or glide.',
            child: dom.div(classes: 'animus-gui-line', <Widget>[
              GuiTextInput(
                id: recordingRouteInputId,
                value: routeId,
                onChanged: (String value) => setState(() {
                  _routeId = value;
                  _routeProblem = null;
                }),
              ),
              Button.secondary(id: 'animus-recording-make-route', label: 'Make route', size: ButtonSize.small, disabled: !routeValid, onPressed: () => _makeRoute(routeId)),
              if (_routeProblem != null || !routeValid)
                dom.span(classes: 'animus-error', attributes: const <String, String>{'role': 'alert'}, <Widget>[Component.text(_routeProblem ?? 'Use 1-64 lowercase letters, digits, _ or -.')]),
            ]),
          ),
        GuiRow(
          label: 'Description',
          forId: recordingDescriptionId,
          help: '${details.description.length} / $recordingDescriptionLimit',
          problems: diagnosticsAt(diagnostics, r'$.description'),
          child: GuiTextInput(
            id: recordingDescriptionId,
            value: details.description,
            profile: FieldProfile.richText,
            placeholder: 'What the NPC acts out',
            onChanged: (String value) {
              if (value.length <= recordingDescriptionLimit) _write(details.copyWith(description: value), 'description');
            },
          ),
        ),
        GuiRow(
          label: 'Tags',
          forId: recordingTagInputId,
          help: 'a-z, 0-9, _ and -',
          problems: diagnosticsAt(diagnostics, r'$.tags', nested: true),
          child: dom.div(classes: 'animus-tag-editor', <Widget>[
            if (details.tags.isNotEmpty)
              dom.div(classes: 'animus-npc-tag-cloud', <Widget>[
                for (final String tag in details.tags)
                  dom.span(
                    classes: 'animus-npc-tag',
                    attributes: <String, String>{'data-tag': tag},
                    <Widget>[
                      Component.text(tag),
                      GuiIconButton(
                        icon: ArcaneIcon.x(size: IconSize.sm),
                        label: 'Remove tag $tag',
                        onPressed: () => _write(details.copyWith(tags: <String>[...details.tags]..remove(tag)), 'tags'),
                      ),
                    ],
                  ),
              ]),
            dom.div(classes: 'animus-gui-line', <Widget>[
              SuggestInput(
                // A fresh field per added tag, so the typed text clears.
                key: ValueKey<int>(details.tags.length),
                id: recordingTagInputId,
                value: adding,
                label: 'New tag',
                placeholder: 'market',
                profile: FieldProfile(options: <ValueOption>[
                  for (final String tag in component.knownTags)
                    if (!details.tags.contains(tag)) ValueOption(tag, detail: 'tag'),
                ]),
                onChanged: (String value) => setState(() => _newTag = value.trim().toLowerCase()),
                onSubmit: tagValid ? addTag : null,
              ),
              Button.ghost(id: 'animus-recording-add-tag', label: 'Add tag', size: ButtonSize.small, disabled: !tagValid, onPressed: addTag),
            ]),
          ]),
        ),
        GuiRow(
          label: 'Playback',
          forId: recordingPlaybackId,
          tip: 'Used when the NPC or play-recording action names no playback.',
          problems: diagnosticsAt(diagnostics, r'$.playback'),
          child: GuiSelect(
            id: recordingPlaybackId,
            value: withDefault(details.playback, recordingPlaybacks, 'loop').value,
            options: withDefault(details.playback, recordingPlaybacks, 'loop').options,
            allowNone: true,
            noneLabel: 'loop (default)',
            onChanged: (String? next) => _write(details.copyWith(playback: () => next), 'playback'),
          ),
        ),
        GuiRow(
          label: 'Speed',
          forId: recordingSpeedId,
          tip: 'Recorded ticks per game tick. 2 plays twice as fast.',
          help: _speedProblem ?? '0.25 to 4',
          problems: diagnosticsAt(diagnostics, r'$.speed'),
          child: GuiNumberInput(
            id: recordingSpeedId,
            value: details.speed,
            integer: false,
            placeholder: '1',
            onChanged: (num? value) {
              final double speed = value?.toDouble() ?? 1;
              final bool valid = speed >= recordingMinimumSpeed && speed <= recordingMaximumSpeed;
              setState(() => _speedProblem = valid ? null : 'Use 0.25 to 4.');
              if (valid) _write(details.copyWith(speed: speed), 'speed');
            },
          ),
        ),
      ],
    );
  }
}
