package com.simplematch.quickfixgateway.wal;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Owns the v1 WAL compatibility seam while delegating focused encode and decode work. */
final class WalRecordJsonCodec {
  private final WalRecordJsonEncoder encoder;
  private final WalRecordJsonDecoder decoder;

  WalRecordJsonCodec(ObjectMapper objectMapper) {
    this.encoder = new WalRecordJsonEncoder(objectMapper);
    this.decoder = new WalRecordJsonDecoder(objectMapper);
  }

  String encode(WalRecord record) {
    return encoder.encode(record);
  }

  WalRecord decode(String line) {
    return decoder.decode(line);
  }
}
