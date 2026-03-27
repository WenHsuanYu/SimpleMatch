#pragma once

#include <filesystem>
#include <memory>
#include <string_view>

namespace simplematch::fixgw::wal {

class WalAppender {
public:
  explicit WalAppender(std::filesystem::path walPath);
  ~WalAppender();

  WalAppender(const WalAppender&) = default;
  WalAppender& operator=(const WalAppender&) = default;
  WalAppender(WalAppender&&) noexcept = default;
  WalAppender& operator=(WalAppender&&) noexcept = default;

  // record may be any string‑like object; we do not take ownership.
  void appendAndFlush(std::string_view record);

private:
  struct State;
  std::shared_ptr<State> state_;
};

} // namespace simplematch::fixgw::wal
