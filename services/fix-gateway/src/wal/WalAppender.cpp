#include "WalAppender.h"

#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <sys/uio.h>

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <exception>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

namespace simplematch::fixgw::wal {

namespace {

enum class SyncMode {
  None,
  FDataSync,
  FSync,
};

constexpr SyncMode kDefaultSyncMode = SyncMode::FDataSync;
constexpr auto kDefaultFlushInterval = std::chrono::milliseconds(2);
constexpr size_t kDefaultMaxBatchBytes = 256 * 1024;
constexpr size_t kDefaultMaxBatchRecords = 4096;

void throwErrno(const char* what) {
  throw std::runtime_error(std::string(what) + ": " + std::strerror(errno));
}

void closeNoThrow(int& fd) noexcept {
  if (fd < 0) {
    return;
  }
  ::close(fd);
  fd = -1;
}

void writevAll(int fd, std::vector<iovec>& iovs) {
  size_t i = 0;
  while (i < iovs.size()) {
    const auto remaining = static_cast<int>(iovs.size() - i);
    const ssize_t n = ::writev(fd, iovs.data() + i, remaining);
    if (n < 0) {
      if (errno == EINTR) {
        continue;
      }
      throwErrno("writev WAL failed");
    }

    size_t written = static_cast<size_t>(n);
    while (written > 0 && i < iovs.size()) {
      if (written >= iovs[i].iov_len) {
        written -= iovs[i].iov_len;
        ++i;
        continue;
      }

      auto* base = static_cast<char*>(iovs[i].iov_base);
      base += written;
      iovs[i].iov_base = base;
      iovs[i].iov_len -= written;
      written = 0;
    }
  }
}

void syncFd(int fd, SyncMode mode) {
  switch (mode) {
  case SyncMode::None:
    return;
  case SyncMode::FDataSync:
    if (::fdatasync(fd) != 0) {
      throwErrno("fdatasync WAL failed");
    }
    return;
  case SyncMode::FSync:
    if (::fsync(fd) != 0) {
      throwErrno("fsync WAL failed");
    }
    return;
  }
}

} // namespace

struct WalAppender::State {
  struct Pending {
    uint64_t seq{};
    std::string record;
  };

  explicit State(std::filesystem::path walPath) : walPath_(std::move(walPath)) {
    if (walPath_.empty()) {
      throw std::invalid_argument("walPath is empty");
    }
    if (walPath_.has_parent_path()) {
      std::filesystem::create_directories(walPath_.parent_path());
    }

    fd_ = ::open(walPath_.c_str(), O_CREAT | O_WRONLY | O_APPEND | O_CLOEXEC, 0644);
    if (fd_ < 0) {
      throwErrno("open WAL failed");
    }

    worker_ = std::thread([this] { run(); });
  }

  ~State() {
    {
      std::lock_guard<std::mutex> lock(mu_);
      stopping_ = true;
    }
    cv_.notify_all();
    if (worker_.joinable()) {
      worker_.join();
    }
  }

  State(const State&) = delete;
  State& operator=(const State&) = delete;

  void appendAndFlush(std::string_view record) {
    uint64_t seq = 0;
    {
      std::lock_guard<std::mutex> lock(mu_);
      rethrowIfFailedLocked();

      seq = nextSeq_++;
      Pending p;
      p.seq = seq;
      p.record.assign(record.data(), record.size());
      queuedBytes_ += p.record.size() + 1; // + '\n'
      queue_.push_back(std::move(p));
    }
    cv_.notify_one();

    std::unique_lock<std::mutex> lock(mu_);
    cv_.wait(lock, [&] { return failed_ || durableSeq_ >= seq; });
    rethrowIfFailedLocked();
  }

private:
  void run() {
    try {
      runImpl();
    } catch (...) {
      {
        std::lock_guard<std::mutex> lock(mu_);
        failed_ = true;
        workerError_ = std::current_exception();
      }
      cv_.notify_all();
      closeNoThrow(fd_);
    }
  }

  void runImpl() {
    while (true) {
      std::deque<Pending> batch;
      uint64_t lastSeq = 0;

      {
        std::unique_lock<std::mutex> lock(mu_);

        cv_.wait(lock, [&] { return stopping_ || !queue_.empty(); });
        if (stopping_ && queue_.empty()) {
          break;
        }

        const auto deadline = std::chrono::steady_clock::now() + flushInterval_;
        while (!stopping_ && queuedBytes_ < maxBatchBytes_ && queue_.size() < maxBatchRecords_) {
          if (cv_.wait_until(lock, deadline, [&] {
                return stopping_ || queuedBytes_ >= maxBatchBytes_ || queue_.size() >= maxBatchRecords_;
              })) {
            break;
          }
        }

        size_t takingBytes = 0;
        while (!queue_.empty() && batch.size() < maxBatchRecords_) {
          const auto& front = queue_.front();
          const size_t bytes = front.record.size() + 1;
          if (!batch.empty() && (takingBytes + bytes) > maxBatchBytes_) {
            break;
          }
          takingBytes += bytes;
          lastSeq = front.seq;
          batch.push_back(std::move(queue_.front()));
          queue_.pop_front();
        }
        queuedBytes_ -= takingBytes;
      }

      if (batch.empty()) {
        continue;
      }

      writeBatch(batch);
      syncFd(fd_, syncMode_);

      {
        std::lock_guard<std::mutex> lock(mu_);
        durableSeq_ = std::max(durableSeq_, lastSeq);
      }
      cv_.notify_all();
    }

    std::deque<Pending> tail;
    uint64_t tailSeq = 0;
    {
      std::lock_guard<std::mutex> lock(mu_);
      tail = std::move(queue_);
      queue_.clear();
      queuedBytes_ = 0;
      if (!tail.empty()) {
        tailSeq = tail.back().seq;
      }
    }
    if (!tail.empty()) {
      writeBatch(tail);
      syncFd(fd_, syncMode_);
      {
        std::lock_guard<std::mutex> lock(mu_);
        durableSeq_ = std::max(durableSeq_, tailSeq);
      }
      cv_.notify_all();
    }

    closeNoThrow(fd_);
  }

  void writeBatch(const std::deque<Pending>& batch) {
    static constexpr char kNewline = '\n';

    // Each record is (data + newline) => 2 iovecs.
    constexpr size_t kMaxIovecsPerCall = 1024;

    std::vector<iovec> iovs;
    iovs.reserve(std::min(batch.size() * 2, kMaxIovecsPerCall));

    size_t iovCount = 0;
    for (const auto& p : batch) {
      iovec a;
      a.iov_base = const_cast<char*>(p.record.data());
      a.iov_len = p.record.size();
      iovec b;
      b.iov_base = const_cast<char*>(&kNewline);
      b.iov_len = 1;

      iovs.push_back(a);
      iovs.push_back(b);
      iovCount += 2;

      if (iovCount >= kMaxIovecsPerCall) {
        writevAll(fd_, iovs);
        iovs.clear();
        iovCount = 0;
      }
    }

    if (!iovs.empty()) {
      writevAll(fd_, iovs);
    }
  }

  void rethrowIfFailedLocked() {
    if (!failed_) {
      return;
    }
    if (workerError_) {
      std::rethrow_exception(workerError_);
    }
    throw std::runtime_error("WAL worker failed");
  }

  std::filesystem::path walPath_;
  int fd_{-1};

  std::mutex mu_;
  std::condition_variable cv_;
  std::deque<Pending> queue_;
  size_t queuedBytes_{0};

  uint64_t nextSeq_{1};
  uint64_t durableSeq_{0};

  bool stopping_{false};
  bool failed_{false};
  std::exception_ptr workerError_;

  SyncMode syncMode_{kDefaultSyncMode};
  std::chrono::milliseconds flushInterval_{kDefaultFlushInterval};
  size_t maxBatchBytes_{kDefaultMaxBatchBytes};
  size_t maxBatchRecords_{kDefaultMaxBatchRecords};

  std::thread worker_;
};

WalAppender::WalAppender(std::filesystem::path walPath) : state_(std::make_shared<State>(std::move(walPath))) {}

WalAppender::~WalAppender() = default;

void WalAppender::appendAndFlush(std::string_view record) {
  if (!state_) {
    throw std::runtime_error("WalAppender is not initialized");
  }
  state_->appendAndFlush(record);
}

} // namespace simplematch::fixgw::wal
