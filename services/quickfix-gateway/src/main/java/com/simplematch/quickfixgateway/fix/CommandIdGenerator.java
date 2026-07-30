package com.simplematch.quickfixgateway.fix;

import com.simplematch.config.SimpleMatchUuids;

final class CommandIdGenerator {

  String nextCommandId() {
    return SimpleMatchUuids.uuidV7().toString();
  }
}
