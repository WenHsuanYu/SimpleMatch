package com.simplematch.quickfixgateway.wal;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

public final class QuickFixWalReplayRunner implements ApplicationRunner {
  private final WalReplayService walReplayService;

  public QuickFixWalReplayRunner(WalReplayService walReplayService) {
    this.walReplayService = walReplayService;
  }

  @Override
  public void run(ApplicationArguments args) {
    walReplayService.replayAll();
  }
}