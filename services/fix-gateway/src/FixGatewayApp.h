#pragma once

#include "wal/WalAppender.h"

#include <quickfix/Application.h>
#include <quickfix/SessionID.h>

#include <atomic>
#include <string>

namespace simplematch::fixgw {

class FixGatewayApp final : public FIX::Application {
public:
  explicit FixGatewayApp(simplematch::fixgw::wal::WalAppender wal);

  void onCreate(const FIX::SessionID& sessionID) override;
  void onLogon(const FIX::SessionID& sessionID) override;
  void onLogout(const FIX::SessionID& sessionID) override;

  void toAdmin(FIX::Message& message, const FIX::SessionID& sessionID) override;
  void toApp(FIX::Message& message, const FIX::SessionID& sessionID) override;

  void fromAdmin(const FIX::Message& message, const FIX::SessionID& sessionID) override;
  void fromApp(const FIX::Message& message, const FIX::SessionID& sessionID) override;

private:
  simplematch::fixgw::wal::WalAppender wal_;
  std::atomic<uint64_t> execSeq_{0};

  std::string nextExecId();
  void handleNewOrderSingle(const FIX::Message& message, const FIX::SessionID& sessionID);
};

} // namespace simplematch::fixgw
