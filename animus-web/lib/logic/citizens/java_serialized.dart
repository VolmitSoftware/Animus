/// Port of core's `JavaSerialized`: reads the Java object stream Citizens writes for item meta without running it.
library;

import 'dart:typed_data';

import 'java_json.dart';

const String unknownClass = '?class';
const int _maxDepth = 64;
const int _maxHandles = 100000;
const int _maxItems = 100000;

class _Field {
  const _Field(this.type, this.name);

  final String type;
  final String name;
}

class _Descriptor {
  _Descriptor(this.name, this.flags, this.fields, this.parent);

  final String name;
  final int flags;
  final List<_Field> fields;
  final _Descriptor? parent;
}

class _Invalid implements Exception {
  const _Invalid();
}

/// The value of a base64 Java object stream, or null when it is not one this reader understands.
JElement? decodeJavaSerialized(String base64) {
  final Uint8List? data = javaBase64Decode(base64);
  if (data == null) return null;
  final _Reader reader = _Reader(data);
  try {
    if (reader.u2() != 0xACED || reader.u2() != 5) return null;
    final Object value = reader.content();
    if (reader.position != data.length) return null;
    return value is JElement ? value : JNull.instance;
  } on _Invalid {
    return null;
  } on RangeError {
    return null;
  }
}

/// `java.util.Base64.getDecoder().decode`: the basic alphabet, padding optional, nothing after it.
Uint8List? javaBase64Decode(String text) {
  const String alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  final List<int> out = <int>[];
  int bits = 0;
  int shift = 18;
  int index = 0;
  while (index < text.length) {
    final String char = text[index++];
    if (char == '=') {
      if (shift == 6 && (index == text.length || text[index++] != '=') || shift == 18) return null;
      break;
    }
    final int value = alphabet.indexOf(char);
    if (value < 0) return null;
    bits |= value << shift;
    shift -= 6;
    if (shift < 0) {
      out
        ..add((bits >> 16) & 0xFF)
        ..add((bits >> 8) & 0xFF)
        ..add(bits & 0xFF);
      shift = 18;
      bits = 0;
    }
  }
  if (shift == 6) {
    out.add((bits >> 16) & 0xFF);
  } else if (shift == 0) {
    out
      ..add((bits >> 16) & 0xFF)
      ..add((bits >> 8) & 0xFF);
  } else if (shift == 12) {
    return null;
  }
  if (index < text.length) return null;
  return Uint8List.fromList(out);
}

class _Reader {
  _Reader(this.bytes);

  final Uint8List bytes;
  int position = 0;
  int depth = 0;
  final List<Object?> handles = <Object?>[];

  int u1() {
    if (position >= bytes.length) throw const _Invalid();
    return bytes[position++];
  }

  int u2() => u1() << 8 | u1();

  int s4() => (u2() << 16 | u2()).toSigned(32);

  void skip(int count) {
    if (count < 0 || position + count > bytes.length) throw const _Invalid();
    position += count;
  }

  String utf(int length) {
    if (length < 0 || position + length > bytes.length) throw const _Invalid();
    final String text = modifiedUtf8(bytes, position, length);
    position += length;
    return text;
  }

  int handle(Object? value) {
    if (handles.length >= _maxHandles) throw const _Invalid();
    handles.add(value);
    return handles.length - 1;
  }

  Object content() {
    if (++depth > _maxDepth) throw const _Invalid();
    try {
      final int tag = u1();
      switch (tag) {
        case 0x70:
          return JNull.instance;
        case 0x71:
          final int index = s4() - 0x7E0000;
          if (index < 0 || index >= handles.length || handles[index] == null) throw const _Invalid();
          return handles[index]!;
        case 0x74:
          final JString text = JString(utf(u2()));
          handle(text);
          return text;
        case 0x7C:
          if (s4() != 0) throw const _Invalid();
          final JString text = JString(utf(s4()));
          handle(text);
          return text;
        case 0x72:
          return descriptor();
        case 0x73:
          return object();
        case 0x75:
          return array();
        case 0x7E:
          _descriptorOf(content());
          final int index = handle(null);
          final Object name = content();
          if (name is! JString) throw const _Invalid();
          handles[index] = name;
          return name;
        case 0x76:
          _descriptorOf(content());
          final JObject type = _unknown('java.lang.Class');
          handle(type);
          return type;
        default:
          throw const _Invalid();
      }
    } finally {
      depth--;
    }
  }

  _Descriptor descriptor() {
    final String name = utf(u2());
    skip(8);
    final int flags = u1();
    final int count = u2();
    final List<_Field> fields = <_Field>[];
    final int index = handle(null);
    for (int i = 0; i < count; i++) {
      final String type = String.fromCharCode(u1());
      final String field = utf(u2());
      if (type == 'L' || type == '[') {
        if (content() is! JPrimitive) throw const _Invalid();
      } else if (!'BCDFIJSZ'.contains(type)) {
        throw const _Invalid();
      }
      fields.add(_Field(type, field));
    }
    annotations();
    final _Descriptor descriptor = _Descriptor(name, flags, List<_Field>.unmodifiable(fields), _descriptorOf(content()));
    handles[index] = descriptor;
    return descriptor;
  }

  List<Object> annotations() {
    final List<Object> values = <Object>[];
    while (true) {
      if (position >= bytes.length) throw const _Invalid();
      final int tag = bytes[position];
      if (tag == 0x78) {
        position++;
        return values;
      }
      if (tag == 0x77) {
        position++;
        final int length = u1();
        final int start = position;
        skip(length);
        values.add(Uint8List.sublistView(bytes, start, start + length));
      } else {
        values.add(content());
      }
      if (values.length > _maxItems) throw const _Invalid();
    }
  }

  JElement primitive(String type) {
    switch (type) {
      case 'B':
        return JInt.of(u1().toSigned(8));
      case 'C':
        return JString(String.fromCharCode(u2()));
      case 'S':
        return JInt.of(u2().toSigned(16));
      case 'I':
        return JInt.of(s4());
      case 'J':
        final BigInt high = BigInt.from(s4() & 0xFFFFFFFF);
        final BigInt low = BigInt.from(s4() & 0xFFFFFFFF);
        final BigInt value = ((high << 32) | low).toSigned(64);
        return value.abs() <= BigInt.two.pow(53) ? JInt(value) : JString(value.toString());
      case 'F':
        final ByteData single = ByteData(4)..setInt32(0, s4());
        return JDouble(single.getFloat32(0));
      case 'D':
        final ByteData double = ByteData(8)
          ..setInt32(0, s4())
          ..setInt32(4, s4());
        return JDouble(double.getFloat64(0));
      case 'Z':
        return JBool(u1() != 0);
      default:
        throw const _Invalid();
    }
  }

  JElement object() {
    final _Descriptor? descriptor = _descriptorOf(content());
    if (descriptor == null) throw const _Invalid();
    final int index = handle(null);
    final List<_Descriptor> chain = <_Descriptor>[];
    for (_Descriptor? at = descriptor; at != null; at = at.parent) {
      chain.insert(0, at);
    }
    final Map<String, Object> fields = <String, Object>{};
    final List<Object> written = <Object>[];
    for (final _Descriptor at in chain) {
      if (at.flags & 0x04 != 0) throw const _Invalid();
      if (at.flags & 0x02 == 0) continue;
      for (final _Field field in at.fields) {
        fields[field.name] = field.type == 'L' || field.type == '[' ? content() : primitive(field.type);
      }
      if (at.flags & 0x01 != 0) written.addAll(annotations());
    }
    final JElement value = _interpret(descriptor.name, fields, written);
    handles[index] = value;
    return value;
  }

  JArray array() {
    final _Descriptor? descriptor = _descriptorOf(content());
    if (descriptor == null || descriptor.name.length < 2 || descriptor.name[0] != '[') throw const _Invalid();
    final int index = handle(null);
    final int length = s4();
    if (length < 0 || length > _maxItems || length > bytes.length - position) throw const _Invalid();
    final String type = descriptor.name[1];
    final JArray array = JArray();
    handles[index] = array;
    for (int i = 0; i < length; i++) {
      final Object value = type == 'L' || type == '[' ? content() : primitive(type);
      array.add(value is JElement ? value : _unknown('?'));
    }
    return array;
  }
}

/// Java's modified UTF-8: NUL as two bytes and supplementary characters as surrogate pairs.
String modifiedUtf8(Uint8List data, int start, int length) {
  final List<int> units = <int>[];
  int index = start;
  final int end = start + length;
  while (index < end) {
    final int a = data[index++];
    if (a < 0x80) {
      units.add(a);
    } else if (a & 0xE0 == 0xC0) {
      if (index >= end) throw const _Invalid();
      units.add((a & 0x1F) << 6 | data[index++] & 0x3F);
    } else if (a & 0xF0 == 0xE0) {
      if (index + 1 >= end) throw const _Invalid();
      final int b = data[index++] & 0x3F;
      final int c = data[index++] & 0x3F;
      units.add((a & 0x0F) << 12 | b << 6 | c);
    } else {
      throw const _Invalid();
    }
  }
  return String.fromCharCodes(units);
}

_Descriptor? _descriptorOf(Object value) {
  if (value is _Descriptor) return value;
  if (value == JNull.instance) return null;
  throw const _Invalid();
}

JObject _unknown(String name) => JObject()..addString(unknownClass, name);

JElement _element(Object? value) => value is JElement ? value : _unknown('?');

List<JElement> _objects(List<Object> written) => <JElement>[
      for (final Object value in written)
        if (value is! Uint8List) _element(value),
    ];

JElement _interpret(String name, Map<String, Object> fields, List<Object> written) {
  switch (name) {
    case 'java.lang.Boolean' || 'java.lang.Integer' || 'java.lang.Long' || 'java.lang.Short' || 'java.lang.Byte' || 'java.lang.Double' ||
          'java.lang.Float' || 'java.lang.Character':
      return _element(fields['value']);
    case 'org.bukkit.util.io.Wrapper':
      return _element(fields['map']);
    case 'java.util.ArrayList' || 'java.util.LinkedList':
      return JArray(_objects(written));
    case 'java.util.HashMap' || 'java.util.LinkedHashMap' || 'java.util.TreeMap':
      final List<JElement> values = _objects(written);
      if (values.length.isOdd) return _unknown(name);
      return _map(values, name);
  }
  if (name.startsWith('com.google.common.collect.Immutable') && name.endsWith(r'$SerializedForm')) {
    final Object? keys = fields['keys'];
    final Object? values = fields['values'];
    if (keys is JArray && values is JArray && keys.length == values.length) {
      return _map(<JElement>[
        for (int i = 0; i < keys.length; i++) ...<JElement>[keys[i], values[i]],
      ], name);
    }
    final Object? elements = fields['elements'];
    if (elements is JArray) return elements;
  }
  if (name == r'java.util.Arrays$ArrayList' && fields['a'] is JArray) return fields['a']! as JArray;
  if (name.startsWith(r'java.util.Collections$Unmodifiable') && name.endsWith('List') && fields['list'] is JArray) return fields['list']! as JArray;
  return _unknown(name);
}

JElement _map(List<JElement> pairs, String name) {
  final JObject object = JObject();
  for (int i = 0; i + 1 < pairs.length; i += 2) {
    final JElement key = pairs[i];
    if (key is! JPrimitive || object.has(key.asString)) return _unknown(name);
    object.add(key.asString, pairs[i + 1]);
  }
  return object;
}
