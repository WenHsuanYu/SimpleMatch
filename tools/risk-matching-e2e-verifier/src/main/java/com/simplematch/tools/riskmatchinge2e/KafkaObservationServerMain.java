package com.simplematch.tools.riskmatchinge2e;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Starts the in-cluster Kafka observation adapter used by failure certification. */
public final class KafkaObservationServerMain {
  private static final int DEFAULT_QUERY_TIMEOUT_MILLIS = 2000;

  private KafkaObservationServerMain() {}

  /** Starts one warm Kafka observation process and serves requests until shutdown. */
  public static void main(String[] args) throws InterruptedException {
    final Map<String, String> options = parseOptions(args);
    final String bootstrap = required(options, "--bootstrap");
    final String commandsTopic = required(options, "--commands-topic");
    final String eventsTopic = required(options, "--events-topic");
    final int port = positiveInt(required(options, "--port"), "--port");
    final String queryTimeoutOption = "--query-timeout-millis";
    final int queryTimeoutMillis =
        options.containsKey(queryTimeoutOption)
            ? positiveInt(options.get(queryTimeoutOption), queryTimeoutOption)
            : DEFAULT_QUERY_TIMEOUT_MILLIS;

    final CountDownLatch shutdown = new CountDownLatch(1);
    Runtime.getRuntime()
        .addShutdownHook(new Thread(shutdown::countDown, "kafka-observer-shutdown"));

    try (var session =
            new AdminKafkaObservationSession(
                bootstrap, commandsTopic, eventsTopic, queryTimeoutMillis);
        var server = new KafkaObservationHttpServer(session, port)) {
      server.start();
      shutdown.await();
    }
  }

  private static Map<String, String> parseOptions(String[] args) {
    if (args.length % 2 != 0) {
      throw new IllegalArgumentException("options must be supplied as name/value pairs");
    }
    final Map<String, String> options = new LinkedHashMap<>();
    for (int index = 0; index < args.length; index += 2) {
      if (options.put(args[index], args[index + 1]) != null) {
        throw new IllegalArgumentException("duplicate option: " + args[index]);
      }
    }
    return options;
  }

  private static String required(Map<String, String> options, String name) {
    final String value = options.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }

  private static int positiveInt(String value, String name) {
    try {
      final int parsed = Integer.parseInt(value);
      if (parsed <= 0) {
        throw new IllegalArgumentException(name + " must be positive");
      }
      return parsed;
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException(name + " must be an integer", invalid);
    }
  }
}
