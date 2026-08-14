#pragma once

#include "simplematch/matching/runtime/matching_runtime.hpp"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <vector>

namespace simplematch::matching {

/** The bounded lifecycle state observed by readiness and the runtime supervisor. */
enum class MatchingRuntimeSupervisorState {
  kStarting,
  kParked,
  kRunning,
  kBackpressured,
  kQuiescing,
  kFailedClosed,
  kStopped
};

/** The deliberately small wait policy used by the single writer when its input ring is empty. */
enum class RuntimeWaitStrategy { kBusySpin, kYield, kBoundedBackoff };

/** Platform seam for the one thread that owns the mutable Matching core. */
class RuntimeCpuAffinity {
public:
  virtual ~RuntimeCpuAffinity() = default;

  /** Binds the calling writer thread and returns false when the requested binding is unavailable. */
  [[nodiscard]] virtual bool pin_current_thread() = 0;
};

/** Linux CPU-set adapter used by deployed native Matching processes. */
class RequestedCpuAffinity final : public RuntimeCpuAffinity {
public:
  explicit RequestedCpuAffinity(std::string cpu_set);

  [[nodiscard]] bool pin_current_thread() override;

private:
  std::vector<int> cpus_;
};

/** Pins the writer to the first CPU granted by the container's effective cgroup cpuset. */
class CgroupCpuAffinity final : public RuntimeCpuAffinity {
public:
  [[nodiscard]] bool pin_current_thread() override;
};

/** A test and local-profile adapter that deliberately performs no OS operation. */
class NoopRuntimeCpuAffinity final : public RuntimeCpuAffinity {
public:
  [[nodiscard]] bool pin_current_thread() override { return true; }
};

struct RuntimeSupervisorOptions {
  RuntimeWaitStrategy wait_strategy{RuntimeWaitStrategy::kBoundedBackoff};
  std::chrono::microseconds maximum_backoff{1000};
  std::chrono::milliseconds startup_timeout{5000};
  std::chrono::milliseconds output_drain_timeout{5000};
  std::chrono::milliseconds ownership_poll_interval{100};
  std::function<bool()> ownership_observer;
};

/**
 * Owns the native runtime thread topology around one MatchingRuntime.
 *
 * <p>The writer is started parked, pins itself before the startup gate can be released, and is the
 * only caller of MatchingRuntime::process_one(). Input submission and output consumption remain
 * explicit SPSC adapter operations. Terminal failure is an out-of-band control signal so a full
 * data ring cannot prevent every waiter from being woken.</p>
 */
class MatchingRuntimeSupervisor final {
public:
  MatchingRuntimeSupervisor(
      MatchingRuntime &runtime,
      std::shared_ptr<const PartitionOwnershipPermit> ownership_permit,
      std::unique_ptr<RuntimeCpuAffinity> cpu_affinity,
      RuntimeSupervisorOptions options = {});
  ~MatchingRuntimeSupervisor();

  MatchingRuntimeSupervisor(const MatchingRuntimeSupervisor &) = delete;
  MatchingRuntimeSupervisor &operator=(const MatchingRuntimeSupervisor &) = delete;

  /** Starts the writer and ownership observer while leaving the writer parked. */
  [[nodiscard]] bool start();

  /** Releases the startup gate after replay boundaries and identity checks have passed. */
  [[nodiscard]] bool release_startup();

  /** Submits one already-decoded command; nullopt is bounded backpressure or a terminal state. */
  [[nodiscard]] std::optional<InputSequence> submit(CoreCommand command);

  /** Reads the writer's output ring from its single publisher consumer. */
  [[nodiscard]] std::optional<RuntimeOutput> take_output();
  [[nodiscard]] std::size_t output_size() const;

  /** Wakes all waiters and records the first terminal failure reason. */
  void fail_closed(std::string reason);

  /** Quiesces ingress and drains only while ownership remains valid and the deadline permits. */
  void shutdown(std::chrono::milliseconds deadline);

  /** Waits for a terminal state and joins the owned threads when they have stopped. */
  [[nodiscard]] bool wait_until_stopped(std::chrono::milliseconds timeout);

  [[nodiscard]] MatchingRuntimeSupervisorState state() const noexcept;
  [[nodiscard]] std::string failure_reason() const;

private:
  void writer_loop();
  void ownership_loop();
  void wait_for_work(std::chrono::microseconds &backoff);
  void mark_writer_stopped();
  void notify_all();
  void join_threads();
  [[nodiscard]] bool stop_requested() const noexcept;

  MatchingRuntime &runtime_;
  std::shared_ptr<const PartitionOwnershipPermit> ownership_permit_;
  std::unique_ptr<RuntimeCpuAffinity> cpu_affinity_;
  RuntimeSupervisorOptions options_;

  std::atomic<MatchingRuntimeSupervisorState> state_{
      MatchingRuntimeSupervisorState::kStarting};
  std::atomic<bool> startup_released_{};
  std::atomic<bool> stop_requested_{};
  std::atomic<bool> writer_pinned_{};
  std::atomic<bool> writer_stopped_{};
  std::atomic<bool> ownership_stopped_{};
  mutable std::mutex control_mutex_;
  std::condition_variable control_condition_;
  std::string failure_reason_;
  std::optional<std::chrono::steady_clock::time_point> shutdown_deadline_;
  std::thread writer_thread_;
  std::thread ownership_thread_;
  bool started_{};
};

} // namespace simplematch::matching
