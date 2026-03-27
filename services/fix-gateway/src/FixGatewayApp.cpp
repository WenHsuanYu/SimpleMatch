#include "FixGatewayApp.h"

#include "ExecutionReportBuilder.h"

#include <quickfix/Exceptions.h>
#include <quickfix/FieldMap.h>
#include <quickfix/Session.h>

#include <quickfix/fix44/ExecutionReport.h>
#include <quickfix/fix44/NewOrderSingle.h>

#include <chrono>
#include <iostream>
#include <sstream>
#include <stdexcept>

namespace simplematch::fixgw {

namespace {
std::string nowUnixMs() {
  using namespace std::chrono;
  const auto ms = duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();
  return std::to_string(ms);
}

// Minimal “escape” so the WAL stays one-record-per-line.
std::string sanitizeForSingleLine(std::string s) {
  for (char& c : s) {
    if (c == '\n' || c == '\r') {
      c = ' ';
    }
  }
  return s;
}
} // namespace

FixGatewayApp::FixGatewayApp(simplematch::fixgw::wal::WalAppender wal) : wal_(std::move(wal)) {}

void FixGatewayApp::onCreate(const FIX::SessionID& sessionID) {
  std::cout << "[fix-gateway] session created: " << sessionID.toString() << "\n";
}

void FixGatewayApp::onLogon(const FIX::SessionID& sessionID) {
  std::cout << "[fix-gateway] logon: " << sessionID.toString() << "\n";
}

void FixGatewayApp::onLogout(const FIX::SessionID& sessionID) {
  std::cout << "[fix-gateway] logout: " << sessionID.toString() << "\n";
}

void FixGatewayApp::toAdmin(FIX::Message& message, const FIX::SessionID&) {
  (void)message;
}

void FixGatewayApp::toApp(FIX::Message& message, const FIX::SessionID&) {
  (void)message;
  // If you later implement resend customization, this is where PossDupFlag is visible.
}

void FixGatewayApp::fromAdmin(const FIX::Message& message, const FIX::SessionID&) {
  (void)message;
}

void FixGatewayApp::fromApp(const FIX::Message& message, const FIX::SessionID& sessionID) {
  // WAL-first: persist the inbound app message before any business effects.
  // Record format is intentionally simple text for now.
  const std::string raw = sanitizeForSingleLine(message.toString());
  wal_.appendAndFlush("ts_unix_ms=" + nowUnixMs() + " session=" + sessionID.toString() + " msg=" + raw);

  if (!message.getHeader().isSetField(FIX::FIELD::MsgType)) {
    throw FIX::UnsupportedMessageType();
  }

  FIX::MsgType mt;
  message.getHeader().getField(mt);

  if (mt.getValue() == FIX44::NewOrderSingle::MsgType().getValue()) {
    handleNewOrderSingle(message, sessionID);
    return;
  }

  throw FIX::UnsupportedMessageType();
}

std::string FixGatewayApp::nextExecId() {
  const uint64_t seq = execSeq_.fetch_add(1, std::memory_order_relaxed) + 1;
  std::ostringstream oss;
  oss << "E" << seq;
  return oss.str();
}

void FixGatewayApp::handleNewOrderSingle(const FIX::Message& message, const FIX::SessionID& sessionID) {
  // Parse only the minimum needed fields to produce a conventional PendingNew ack.
  FIX::ClOrdID clOrdId;
  FIX::Symbol symbol;
  FIX::Side side;
  FIX::OrderQty orderQty;

  message.getField(clOrdId);
  message.getField(symbol);
  message.getField(side);
  message.getField(orderQty);

  // ExecutionReport fields (minimal set).
  const FIX::OrderID orderId("O-" + clOrdId.getValue());
  const FIX::ExecID execId(nextExecId());

  FIX44::ExecutionReport er = BuildPendingNewExecutionReport(orderId, execId, clOrdId, symbol, side, orderQty);

  // Best-effort: send to the same session.
  if (!FIX::Session::sendToTarget(er, sessionID)) {
    throw std::runtime_error("sendToTarget(ExecutionReport) failed");
  }
}

} // namespace simplematch::fixgw
