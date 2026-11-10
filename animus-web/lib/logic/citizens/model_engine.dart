/// Port of core's `ModelEngineMapping`: Model Engine's Citizens trait, `meg_model`, as an `animus:modelengine` module.
library;

import 'dart:convert';

import 'field_notes.dart';
import 'java_json.dart';
import 'java_lang.dart';

const String modelEngineTrait = 'meg_model';
const String modelEngineModule = 'animus:modelengine';
const String _path = '/traits/$modelEngineTrait';
const List<String> _idKeys = <String>['blueprint', 'model_id', 'modelId', 'model', 'id'];
const List<String> _visibleKeys = <String>['base_visible', 'isBaseEntityVisible'];
const List<String> _scaleKeys = <String>['render_scale', 'scale'];
const int _depth = 4;
const double _minimumScale = 0.0625, _maximumScale = 16;
final RegExp _modelId = RegExp(r'^[A-Za-z0-9_./-]{1,64}$');

/// Model Engine's default states Animus can rename, with the animation each plays by default.
const List<(String, String)> _defaultStates = <(String, String)>[('IDLE', 'idle'), ('WALK', 'walk'), ('DEATH', 'death')];

class ModelEngineResult {
  const ModelEngineResult(this.module, this.notes, this.warnings);

  final JObject? module;
  final Map<String, FieldNote> notes;
  final List<String> warnings;
}

ModelEngineResult mapModelEngine(JElement trait) {
  final JElement? models = _find(trait, 'models', _depth);
  final List<String> ids = <String>[];
  JObject? first;
  if (models is JArray) {
    for (final JElement model in models.elements) {
      final String? id = model is JObject ? _id(model) : null;
      if (id == null) continue;
      ids.add(id);
      first ??= model as JObject;
    }
  } else if (models is JObject) {
    for (final MapEntry<String, JElement> model in models.entries) {
      ids.add(model.key);
      first ??= model.value is JObject ? model.value as JObject : JObject();
    }
  } else if (trait is JObject) {
    final String? id = _id(trait);
    if (id != null) ids.add(id);
    first = trait;
  }
  if (ids.isEmpty) return _review('Model Engine data names no model this converter can read; kept in citizens:review');
  final String model = ids.first;
  if (!_modelId.hasMatch(model)) return _review("Model Engine model '$model' is not a valid Animus model id; kept in citizens:review");
  final double? read = _scale(first!);
  final double scale = read != null && read >= _minimumScale && read <= _maximumScale ? read : 1.0;
  bool visible = false;
  for (final String key in _visibleKeys) {
    final JElement? flag = _find(trait, key, _depth);
    if (flag is JBool) {
      visible = flag.value;
      break;
    }
  }
  final JObject module = JObject()..addString('model', model);
  if (scale != 1) module.addDouble('scale', scale);
  if (visible) module.addBool('showBody', true);
  final JObject states = _states(first);
  if (states.isNotEmpty) module.add('states', states);
  final List<String> warnings = ids.length > 1 ? <String>['Model Engine showed ${ids.length} models (${ids.join(', ')}); Animus shows the first, $model'] : <String>[];
  return ModelEngineResult(module, const <String, FieldNote>{_path: FieldNote(statusConverted, '')}, warnings);
}

ModelEngineResult _review(String warning) =>
    ModelEngineResult(null, <String, FieldNote>{_path: FieldNote(requiresReview, warning)}, <String>[warning]);

/// Model Engine 3's per-model `defaultStates`: the idle, walk and death animations it renamed, in Animus' state order.
JObject _states(JObject model) {
  final JObject states = JObject();
  final JElement? defaults = model.get('defaultStates');
  if (defaults is! JObject) return states;
  for (final (String state, String fallback) in _defaultStates) {
    final JElement? property = defaults.get(state);
    if (property is! JObject) continue;
    final JElement? name = property.get('stateId');
    if (name is JString && name.value != fallback) states.addString(fallback, name.value);
  }
  return states;
}

String? _id(JObject model) {
  for (final String key in _idKeys) {
    final JElement? value = model.get(key);
    if (value is JString && !javaIsBlank(value.value)) return value.value;
  }
  return null;
}

double? _scale(JObject model) {
  for (final String key in _scaleKeys) {
    JElement? value = model.get(key);
    if (value is JObject) value = value.get('x');
    if (value is JNumber) return value.asDouble;
  }
  return null;
}

/// The first value under [key], looking inside objects, arrays and strings that hold JSON.
JElement? _find(JElement element, String key, int depth) {
  if (depth < 0) return null;
  final JElement value = _unwrap(element);
  if (value is JObject) {
    if (value.has(key)) return _unwrap(value.get(key)!);
    for (final MapEntry<String, JElement> child in value.entries) {
      final JElement? found = _find(child.value, key, depth - 1);
      if (found != null) return found;
    }
  } else if (value is JArray) {
    for (final JElement child in value.elements) {
      final JElement? found = _find(child, key, depth - 1);
      if (found != null) return found;
    }
  }
  return null;
}

JElement _unwrap(JElement element) {
  if (element is! JString) return element;
  final String text = element.value.trim();
  if (!text.startsWith('{') && !text.startsWith('[')) return element;
  try {
    return _element(jsonDecode(text));
  } on FormatException {
    return element;
  }
}

JElement _element(Object? value) => switch (value) {
      null => JNull.instance,
      final bool flag => JBool(flag),
      final int number => JInt.of(number),
      final double number => JDouble(number),
      final String text => JString(text),
      final List<Object?> list => JArray(<JElement>[for (final Object? item in list) _element(item)]),
      final Map<String, Object?> map => JObject()..members.addAll(<String, JElement>{for (final MapEntry<String, Object?> entry in map.entries) entry.key: _element(entry.value)}),
      _ => JString(value.toString()),
    };
