#include "FixGatewayApp.h"
#include "FixGatewayConfig.h"

#include <quickfix/FileLog.h>
#include <quickfix/FileStore.h>
#include <quickfix/SessionSettings.h>
#include <quickfix/SocketAcceptor.h>

#include <atomic>
#include <csignal>
#include <chrono>
#include <iostream>
#include <string>
#include <thread>

namespace {
std::atomic<bool> g_shouldStop{false};

void handleSignal(int) {
  g_shouldStop.store(true);
}
} // namespace

int main(int argc, char** argv) {
  std::signal(SIGINT, handleSignal);
  std::signal(SIGTERM, handleSignal);

  try {
    const auto cfg = simplematch::fixgw::LoadFixGatewayConfig(argc, argv);

    FIX::SessionSettings settings(cfg.quickfixConfigPath);

    // WAL path (keep it simple: local file).
    simplematch::fixgw::wal::WalAppender wal(cfg.walPath);

    simplematch::fixgw::FixGatewayApp application(std::move(wal));
    FIX::FileStoreFactory storeFactory(settings);
    FIX::FileLogFactory logFactory(settings);

    FIX::SocketAcceptor acceptor(application, storeFactory, settings, logFactory);

    acceptor.start();
    std::cout << "[fix-gateway] acceptor started"
              << " env=" << cfg.env
              << " quickfix_cfg=" << cfg.quickfixConfigPath
              << " wal=" << cfg.walPath;
    if (!cfg.appConfigPath.empty()) {
      std::cout << " app_config=" << cfg.appConfigPath;
    }
    std::cout << "\n";

    while (!g_shouldStop.load()) {
      std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }

    std::cout << "[fix-gateway] stopping...\n";
    acceptor.stop();
    return 0;
  } catch (const std::exception& e) {
    std::cerr << "[fix-gateway] fatal: " << e.what() << "\n";
    return 1;
  }
}
