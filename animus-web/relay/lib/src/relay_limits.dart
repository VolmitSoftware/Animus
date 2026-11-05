/// Bounds for one relay process. Defaults follow `limits` in
/// `schema/editor-v1.endpoints.json`.
final class RelayLimits {
  static const int defaultMaxFrameBytes = 1024 * 1024;

  /// Largest UTF-8 text frame accepted from or forwarded to either side.
  final int maxFrameBytes;

  /// Frames one browser connection may send per [messageRateWindow].
  final int maxAppMessagesPerWindow;

  /// Frames one server connection may send per [messageRateWindow]. A server
  /// answers many browsers at once, so this is higher than the browser bound.
  final int maxAgentMessagesPerWindow;
  final Duration messageRateWindow;

  /// Requests one browser connection may have waiting for an answer.
  final int maxPendingPerApp;

  /// Open event streams per browser connection.
  final int maxStreamsPerApp;

  /// Requests waiting for an answer across the process.
  final int maxPendingGlobal;

  /// Browser connections subscribed to one server.
  final int maxAppsPerServer;
  final int maxAgents;
  final int maxApps;

  /// Frames queued for sending on one browser connection before it is closed.
  final int maxAppOutboundQueue;

  /// Frames queued for sending on one server connection before it is closed.
  final int maxAgentOutboundQueue;
  final Duration handshakeTimeout;
  final Duration subscriptionTimeout;
  final Duration requestTimeout;
  final Duration pingInterval;

  const RelayLimits({
    this.maxFrameBytes = defaultMaxFrameBytes,
    this.maxAppMessagesPerWindow = 120,
    this.maxAgentMessagesPerWindow = 2000,
    this.messageRateWindow = const Duration(seconds: 1),
    this.maxPendingPerApp = 64,
    this.maxStreamsPerApp = 4,
    this.maxPendingGlobal = 4096,
    this.maxAppsPerServer = 64,
    this.maxAgents = 1000,
    this.maxApps = 4096,
    this.maxAppOutboundQueue = 256,
    this.maxAgentOutboundQueue = 1024,
    this.handshakeTimeout = const Duration(seconds: 10),
    this.subscriptionTimeout = const Duration(seconds: 10),
    this.requestTimeout = const Duration(seconds: 10),
    this.pingInterval = const Duration(seconds: 30),
  });

  /// Throws [ArgumentError] naming the first bound outside its range.
  void validate() {
    _range(maxFrameBytes, 1024, 16 * 1024 * 1024, 'maxFrameBytes');
    _range(maxAppMessagesPerWindow, 1, 10000, 'maxAppMessagesPerWindow');
    _range(maxAgentMessagesPerWindow, 1, 100000, 'maxAgentMessagesPerWindow');
    if (messageRateWindow <= Duration.zero ||
        messageRateWindow > const Duration(minutes: 1)) {
      throw ArgumentError.value(messageRateWindow, 'messageRateWindow');
    }
    _range(maxPendingPerApp, 1, 4096, 'maxPendingPerApp');
    _range(maxStreamsPerApp, 1, 64, 'maxStreamsPerApp');
    _range(maxPendingGlobal, maxPendingPerApp, 100000, 'maxPendingGlobal');
    _range(maxAppsPerServer, 1, 4096, 'maxAppsPerServer');
    _range(maxAgents, 1, 100000, 'maxAgents');
    _range(maxApps, 1, 100000, 'maxApps');
    _range(maxAppOutboundQueue, 1, 65536, 'maxAppOutboundQueue');
    _range(maxAgentOutboundQueue, 1, 65536, 'maxAgentOutboundQueue');
    _duration(handshakeTimeout, 'handshakeTimeout');
    _duration(subscriptionTimeout, 'subscriptionTimeout');
    _duration(requestTimeout, 'requestTimeout');
    _duration(pingInterval, 'pingInterval');
  }

  static void _range(int value, int minimum, int maximum, String name) {
    if (value < minimum || value > maximum) {
      throw ArgumentError.value(
        value,
        name,
        'must be from $minimum through $maximum',
      );
    }
  }

  static void _duration(Duration value, String name) {
    if (value <= Duration.zero || value > const Duration(hours: 1)) {
      throw ArgumentError.value(value, name);
    }
  }
}
