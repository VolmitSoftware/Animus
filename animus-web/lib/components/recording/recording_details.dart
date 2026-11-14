/// The editable part of a recording: its description, tags, default
/// playback and speed, which the server stores beside the frames, and Make
/// route. The page header renames the recording.
library;

import 'package:arcane_jaspr/arcane_jaspr.dart';
import 'package:jaspr/dom.dart' as dom;

import '../../config/docs_links.dart';
import '../../logic/gui/gui_json.dart';
import '../../logic/recording/recording_edit.dart';
import '../../logic/recording/recording_facts.dart';
import '../../model/animus_document.dart';
import '../../services/browser.dart' show focusWhenShown;
import '../../ui/action_row.dart';
import '../../ui/dialog.dart';
import '../../ui/field.dart';
import '../../ui/toggle_choice.dart';
import '../gui/gui_fields.dart';
import '../../logic/suggest/field_profile.dart';
import '../suggest/autocomplete_input.dart';
import '../../ui/number_input.dart';
import '../npc/npc_layout.dart';

const String recordingDescriptionId = 'animus-recording-description';
const String recordingTagsId = 'animus-recording-tags';
const String recordingSpeedId = 'animus-recording-speed';
const String recordingRouteInputId = 'animus-recording-route';

class RecordingDetailsForm extends StatefulWidget {
  const RecordingDetailsForm({required this.document, required this.onJson, this.onMakeRoute, this.knownTags = const <String>[], super.key});

  final AnimusDocument document;
  final JsonEdit onJson;

  /// Makes a route under an id that follows the recording; returns a problem to show, or null.
  final Future<String?> Function(String id)? onMakeRoute;

  /// Tags other recordings use, offered in the tip.
  final List<String> knownTags;

  @override
  State<RecordingDetailsForm> createState() => _RecordingDetailsFormState();
}

class _RecordingDetailsFormState extends State<RecordingDetailsForm> {
  /// The tags as typed while the box is in use.
  String? _tagsTyped;
  String? _tagsProblem;
  String? _speedProblem;
  bool _routeOpen = false;
  String _routeId = '';
  String? _routeProblem;

  JsonMap get _json => asMap(component.document.json);
  RecordingDetails get _details => RecordingDetails.read(_json);

  void _write(RecordingDetails details, String field) {
    final JsonMap shown = _json;
    component.onJson(writeAt(shown, const <JsonStep>[], details.writeTo(shown)), coalesce: 'recording:$field');
  }

  void _tags(String text) {
    final List<String> tags = parseRecordingTags(text);
    final String? problem = recordingTagsProblem(tags);
    setState(() {
      _tagsTyped = text;
      _tagsProblem = problem;
    });
    if (problem == null && tags.join(',') != _details.tags.join(',')) _write(_details.copyWith(tags: tags), 'tags');
  }

  void _openRoute() {
    setState(() {
      _routeOpen = true;
      _routeId = component.document.id;
      _routeProblem = null;
    });
    focusWhenShown(recordingRouteInputId);
  }

  void _closeRoute() => setState(() => _routeOpen = false);

  Future<void> _makeRoute() async {
    final String id = _routeId.trim();
    if (!recordingIdPattern.hasMatch(id)) {
      setState(() => _routeProblem = 'Use 1-64 lowercase letters, digits, _ or -.');
      return;
    }
    final String? problem = await component.onMakeRoute!(id);
    if (!mounted) return;
    setState(() {
      _routeProblem = problem;
      if (problem == null) _routeOpen = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final RecordingDetails details = _details;
    final List<Diagnostic> diagnostics = component.document.diagnostics;
    final List<String> unused = <String>[for (final String tag in component.knownTags) if (!details.tags.contains(tag)) tag];
    return dom.div(
      classes: 'animus-recording-details',
      attributes: const <String, String>{'data-recording': 'details'},
      <Widget>[
        Field(
          label: 'Description',
          forId: recordingDescriptionId,
          hint: '${details.description.length} of $recordingDescriptionLimit',
          problems: diagnosticsAt(diagnostics, r'$.description'),
          child: AutocompleteInput(
            id: recordingDescriptionId,
            value: details.description,
            placeholder: 'What the NPC acts out',
            onChanged: (String value) {
              if (value.length <= recordingDescriptionLimit) _write(details.copyWith(description: value), 'description');
            },
            profile: FieldProfile.none,
          ),
        ),
        Field(
          label: 'Tags',
          forId: recordingTagsId,
          hint: 'Separate with commas',
          tip: unused.isEmpty ? 'Words to find the recording by.' : 'Words to find the recording by. Others in use: ${unused.take(8).join(', ')}.',
          error: _tagsProblem,
          problems: diagnosticsAt(diagnostics, r'$.tags', nested: true),
          child: TextInput(
            id: recordingTagsId,
            value: _tagsTyped ?? details.tags.join(', '),
            onChanged: _tags,
            onBlur: () => setState(() => _tagsTyped = null),
          ),
        ),
        Field(
          label: 'Playback',
          tip: 'How an NPC plays it when the NPC or the action names no playback.',
          docs: Docs.recordingPlayback,
          problems: diagnosticsAt(diagnostics, r'$.playback'),
          child: ToggleChoice<String>(
            label: 'Playback',
            options: <ChoiceOption<String>>[for (final MapEntry<String, String> entry in playbackLabels.entries) ChoiceOption<String>(entry.key, entry.value)],
            value: details.playback ?? 'loop',
            onChanged: (String next) => _write(details.copyWith(playback: () => next == 'loop' ? null : next), 'playback'),
          ),
        ),
        Field(
          label: 'Speed',
          forId: recordingSpeedId,
          hint: _speedProblem == null ? '0.25 to 4 times' : null,
          tip: 'How fast an NPC plays it: 1 as recorded, 2 twice as fast, 0.5 at half speed.',
          error: _speedProblem,
          problems: diagnosticsAt(diagnostics, r'$.speed'),
          child: NumberInput(
            id: recordingSpeedId,
            value: details.speed,
            integer: false,
            digits: 4,
            placeholder: '1',
            onChanged: (num? value) {
              final double speed = value?.toDouble() ?? 1;
              final bool valid = speed >= recordingMinimumSpeed && speed <= recordingMaximumSpeed;
              setState(() => _speedProblem = valid ? null : 'Use 0.25 to 4.');
              if (valid) _write(details.copyWith(speed: speed), 'speed');
            },
          ),
        ),
        if (component.onMakeRoute != null) ...<Widget>[
          ActionRow(
            label: 'Recording actions',
            secondary: <RowAction>[RowAction('Make route', _openRoute, icon: ArcaneIcon.route(), id: 'animus-recording-make-route')],
          ),
          if (_routeOpen) _routeDialog(),
        ],
      ],
    );
  }

  Widget _routeDialog() => AppDialog(
        open: true,
        id: 'make-route',
        title: 'Make route',
        onClose: _closeRoute,
        footer: DialogFooter(cancel: RowAction('Cancel', _closeRoute), primary: RowAction('Make route', _makeRoute)),
        children: <Widget>[
          mutedLine('A route along the recorded steps. Each leg keeps how it was walked: sneak, sprint, swim or glide.'),
          Field(
            label: 'Route name',
            forId: recordingRouteInputId,
            required: true,
            error: _routeProblem,
            child: TextInput(
              id: recordingRouteInputId,
              value: _routeId,
              onChanged: (String value) => setState(() {
                _routeId = value;
                _routeProblem = null;
              }),
              onSubmitted: (String _) => _makeRoute(),
            ),
          ),
        ],
      );
}
