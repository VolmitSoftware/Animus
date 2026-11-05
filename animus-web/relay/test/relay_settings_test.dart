import 'package:animus_relay/animus_relay.dart';
import 'package:test/test.dart';

void main() {
  test('should default to the hosted editor origin and port 8080', () {
    final RelaySettings settings = RelaySettings.fromEnvironment(
      <String, String>{},
    );
    expect(settings.port, 8080);
    expect(settings.address, '0.0.0.0');
    expect(settings.allowedAppOrigins, <String>[
      'https://animus-editor.web.app',
    ]);
    expect(settings.limits.maxFrameBytes, 1024 * 1024);
    expect(settings.limits.requestTimeout, const Duration(seconds: 10));
  });

  test('should read exact origins and limits from the environment', () {
    final RelaySettings settings = RelaySettings.fromEnvironment(
      <String, String>{
        'PORT': '9000',
        'ALLOWED_APP_ORIGINS':
            'https://Animus-Editor.web.app, http://127.0.0.1:8098',
        'MAX_PENDING_PER_APP': '8',
        'REQUEST_TIMEOUT_SECONDS': '20',
      },
    );
    expect(settings.port, 9000);
    expect(settings.allowedAppOrigins, <String>[
      'https://animus-editor.web.app',
      'http://127.0.0.1:8098',
    ]);
    expect(settings.limits.maxPendingPerApp, 8);
    expect(settings.limits.requestTimeout, const Duration(seconds: 20));
  });

  test('should refuse wildcard, path and malformed origins', () {
    for (final String origin in <String>[
      '*',
      'https://*.web.app',
      'https://a.example/path',
      'ftp://a.example',
      'a.example',
    ]) {
      expect(
        () => RelaySettings.fromEnvironment(<String, String>{
          'ALLOWED_APP_ORIGINS': origin,
        }),
        throwsArgumentError,
        reason: origin,
      );
    }
  });

  test('should refuse limits outside their ranges', () {
    expect(
      () => RelaySettings.fromEnvironment(<String, String>{
        'MAX_FRAME_BYTES': '10',
      }),
      throwsFormatException,
    );
    expect(
      () => RelaySettings.fromEnvironment(<String, String>{'PORT': 'x'}),
      throwsFormatException,
    );
  });
}
