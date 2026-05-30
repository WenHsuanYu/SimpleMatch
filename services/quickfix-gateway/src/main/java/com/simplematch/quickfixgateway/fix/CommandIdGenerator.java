package com.simplematch.quickfixgateway.fix;

import com.github.f4b6a3.uuid.UuidCreator;

final class CommandIdGenerator {

  String nextCommandId() {
    return UuidCreator.getTimeOrderedEpoch().toString();
  }
}