#include "simplematch/matching/runtime/matching_runtime_supervisor.hpp"

#include <algorithm>
#include <charconv>
#include <fstream>
#include <limits>
#include <stdexcept>
#include <string_view>

#ifdef __linux__
#include <pthread.h>
#include <sched.h>
#endif

namespace simplematch::matching {
namespace {

std::vector<int> parse_cpu_set(std::string_view encoded) {
  if (encoded.empty()) {
    throw std::invalid_argument("Matching CPU set must not be empty");
  }
  std::vector<int> cpus;
  std::size_t token_start = 0;
  while (token_start < encoded.size()) {
    const auto comma = encoded.find(',', token_start);
    const auto token_end = comma == std::string_view::npos ? encoded.size() : comma;
    const auto dash = encoded.find('-', token_start);
    const bool dash_in_token = dash != std::string_view::npos && dash < token_end;
    const auto parse_cpu = [](std::string_view text) {
      if (text.empty()) {
        throw std::invalid_argument("Matching CPU set contains an empty CPU");
      }
      int value{};
      const auto parsed = std::from_chars(text.data(), text.data() + text.size(), value);
      if (parsed.ec != std::errc{} || parsed.ptr != text.data() + text.size() || value < 0) {
        throw std::invalid_argument("Matching CPU set contains an invalid CPU");
      }
      return value;
    };
    if (!dash_in_token) {
      cpus.push_back(parse_cpu(encoded.substr(token_start, token_end - token_start)));
    } else {
      const int first = parse_cpu(encoded.substr(token_start, dash - token_start));
      const int last = parse_cpu(encoded.substr(dash + 1, token_end - dash - 1));
      if (last < first) {
        throw std::invalid_argument("Matching CPU set range is reversed");
      }
      for (int cpu = first; cpu <= last; ++cpu) {
        cpus.push_back(cpu);
        if (cpu == std::numeric_limits<int>::max()) {
          break;
        }
      }
    }
    if (comma == std::string_view::npos) {
      break;
    }
    token_start = comma + 1;
  }
  std::sort(cpus.begin(), cpus.end());
  cpus.erase(std::unique(cpus.begin(), cpus.end()), cpus.end());
  if (cpus.empty()) {
    throw std::invalid_argument("Matching CPU set must contain one CPU");
  }
  return cpus;
}

bool pin_cpu_set(const std::vector<int> &cpus) {
#ifdef __linux__
  cpu_set_t set;
  CPU_ZERO(&set);
  for (const int cpu : cpus) {
    if (cpu >= CPU_SETSIZE) {
      return false;
    }
    CPU_SET(cpu, &set);
  }
  return sched_setaffinity(0, sizeof(set), &set) == 0;
#else
  static_cast<void>(cpus);
  return false;
#endif
}

} // namespace

RequestedCpuAffinity::RequestedCpuAffinity(std::string cpu_set) : cpus_(parse_cpu_set(cpu_set)) {}

bool RequestedCpuAffinity::pin_current_thread() {
  return pin_cpu_set(cpus_);
}

bool CgroupCpuAffinity::pin_current_thread() {
#ifdef __linux__
  constexpr std::string_view kCpuSetPaths[] = {
      "/sys/fs/cgroup/cpuset.cpus.effective",
      "/sys/fs/cgroup/cpuset/cpuset.cpus.effective",
      "/sys/fs/cgroup/cpuset/cpuset.cpus"};
  for (const auto path : kCpuSetPaths) {
    std::ifstream input{std::string(path)};
    std::string encoded;
    if (!input || !std::getline(input, encoded) || encoded.empty()) {
      continue;
    }
    try {
      const auto cpus = parse_cpu_set(encoded);
      return pin_cpu_set(std::vector<int>{cpus.front()});
    } catch (const std::invalid_argument &) {
      return false;
    }
  }
#endif
  return false;
}

MatchingRuntimeSupervisor::MatchingRuntimeSupervisor(
    MatchingRuntime &runtime,
    std::shared_ptr<const PartitionOwnershipPermit> ownership_permit,
    std::unique_ptr<RuntimeCpuAffinity> cpu_affinity,
    RuntimeSupervisorOptions options)
    : runtime_(runtime),
      ownership_permit_(std::move(ownership_permit)),
      cpu_affinity_(std::move(cpu_affinity)),
      options_(std::move(options)) {
  if (ownership_permit_ == nullptr || cpu_affinity_ == nullptr ||
      options_.maximum_backoff <= std::chrono::microseconds::zero() ||
      options_.startup_timeout <= std::chrono::milliseconds::zero() ||
      options_.output_drain_timeout <= std::chrono::milliseconds::zero() ||
      options_.ownership_poll_interval <= std::chrono::milliseconds::zero()) {
    throw std::invalid_argument("invalid Matching runtime supervisor configuration");
  }
}

MatchingRuntimeSupervisor::~MatchingRuntimeSupervisor() {
  shutdown(std::chrono::milliseconds(5000));
}

bool MatchingRuntimeSupervisor::start() {
  if (started_) {
    return false;
  }
  started_ = true;
  state_.store(MatchingRuntimeSupervisorState::kStarting, std::memory_order_release);
  writer_thread_ = std::thread(&MatchingRuntimeSupervisor::writer_loop, this);
  if (options_.ownership_observer) {
    ownership_thread_ = std::thread(&MatchingRuntimeSupervisor::ownership_loop, this);
  } else {
    ownership_stopped_.store(true, std::memory_order_release);
  }

  std::unique_lock lock(control_mutex_);
  const bool ready = control_condition_.wait_for(
      lock, options_.startup_timeout, [this] {
        return writer_pinned_.load(std::memory_order_acquire) ||
               state_.load(std::memory_order_acquire) ==
                   MatchingRuntimeSupervisorState::kFailedClosed;
      });
  lock.unlock();
  if (!ready || !writer_pinned_.load(std::memory_order_acquire) ||
      state_.load(std::memory_order_acquire) == MatchingRuntimeSupervisorState::kFailedClosed) {
    fail_closed("MATCHING_WRITER_STARTUP_TIMEOUT");
    join_threads();
    return false;
  }
  return state_.load(std::memory_order_acquire) !=
         MatchingRuntimeSupervisorState::kFailedClosed;
}

bool MatchingRuntimeSupervisor::release_startup() {
  bool accepted = false;
  {
    std::lock_guard lock(control_mutex_);
    accepted = writer_pinned_.load(std::memory_order_acquire) &&
               state_.load(std::memory_order_acquire) == MatchingRuntimeSupervisorState::kParked &&
               ownership_permit_->allows_processing();
    if (accepted) {
      startup_released_.store(true, std::memory_order_release);
      state_.store(MatchingRuntimeSupervisorState::kRunning, std::memory_order_release);
    }
  }
  if (!accepted) {
    fail_closed("MATCHING_STARTUP_GATE_REJECTED");
    return false;
  }
  notify_all();
  return true;
}

std::optional<InputSequence> MatchingRuntimeSupervisor::submit(CoreCommand command) {
  if (state_.load(std::memory_order_acquire) != MatchingRuntimeSupervisorState::kRunning) {
    return std::nullopt;
  }
  if (!ownership_permit_->allows_processing()) {
    fail_closed("MATCHING_OWNERSHIP_LOST");
    return std::nullopt;
  }
  const auto sequence = runtime_.submit(std::move(command));
  if (!sequence.has_value() && ownership_permit_->allows_processing()) {
    state_.store(MatchingRuntimeSupervisorState::kBackpressured, std::memory_order_release);
  }
  return sequence;
}

std::optional<RuntimeOutput> MatchingRuntimeSupervisor::take_output() {
  return runtime_.take_output();
}

std::size_t MatchingRuntimeSupervisor::output_size() const {
  return runtime_.output_size();
}

void MatchingRuntimeSupervisor::fail_closed(std::string reason) {
  {
    std::lock_guard lock(control_mutex_);
    const auto current = state_.load(std::memory_order_acquire);
    if (current == MatchingRuntimeSupervisorState::kFailedClosed ||
        current == MatchingRuntimeSupervisorState::kStopped) {
      return;
    }
    state_.store(MatchingRuntimeSupervisorState::kFailedClosed, std::memory_order_release);
    failure_reason_ = std::move(reason);
  }
  stop_requested_.store(true, std::memory_order_release);
  notify_all();
}

void MatchingRuntimeSupervisor::shutdown(std::chrono::milliseconds deadline) {
  if (!started_) {
    return;
  }
  bool quiescing_requested = false;
  {
    std::lock_guard lock(control_mutex_);
    const auto current = state_.load(std::memory_order_acquire);
    if (current != MatchingRuntimeSupervisorState::kFailedClosed &&
        current != MatchingRuntimeSupervisorState::kStopped) {
      shutdown_deadline_ = std::chrono::steady_clock::now() + deadline;
      state_.store(MatchingRuntimeSupervisorState::kQuiescing, std::memory_order_release);
      startup_released_.store(true, std::memory_order_release);
      quiescing_requested = true;
    }
  }
  if (quiescing_requested) {
    notify_all();
  }
  if (!wait_until_stopped(deadline)) {
    fail_closed("MATCHING_SHUTDOWN_DEADLINE_EXCEEDED");
    join_threads();
  }
}

bool MatchingRuntimeSupervisor::wait_until_stopped(std::chrono::milliseconds timeout) {
  if (!started_) {
    return true;
  }
  std::unique_lock lock(control_mutex_);
  const bool stopped = control_condition_.wait_for(
      lock, timeout, [this] { return writer_stopped_.load(std::memory_order_acquire); });
  lock.unlock();
  if (stopped) {
    join_threads();
  }
  return stopped;
}

MatchingRuntimeSupervisorState MatchingRuntimeSupervisor::state() const noexcept {
  return state_.load(std::memory_order_acquire);
}

std::string MatchingRuntimeSupervisor::failure_reason() const {
  std::lock_guard lock(control_mutex_);
  return failure_reason_;
}

void MatchingRuntimeSupervisor::writer_loop() {
  try {
#ifdef __linux__
    static_cast<void>(pthread_setname_np(pthread_self(), "matching-writer"));
#endif
    if (!cpu_affinity_->pin_current_thread()) {
      fail_closed("MATCHING_WRITER_CPU_AFFINITY_FAILED");
      mark_writer_stopped();
      return;
    }
    {
      std::lock_guard lock(control_mutex_);
      writer_pinned_.store(true, std::memory_order_release);
      if (state_.load(std::memory_order_acquire) == MatchingRuntimeSupervisorState::kStarting) {
        state_.store(MatchingRuntimeSupervisorState::kParked, std::memory_order_release);
      }
    }
    notify_all();

    std::unique_lock lock(control_mutex_);
    control_condition_.wait(lock, [this] {
      return startup_released_.load(std::memory_order_acquire) ||
             stop_requested_.load(std::memory_order_acquire);
    });
    lock.unlock();

    std::chrono::microseconds backoff{1};
    while (!stop_requested_.load(std::memory_order_acquire)) {
      if (state() == MatchingRuntimeSupervisorState::kQuiescing) {
        bool deadline_expired = false;
        {
          std::lock_guard deadline_lock(control_mutex_);
          deadline_expired = shutdown_deadline_.has_value() &&
                             std::chrono::steady_clock::now() >= *shutdown_deadline_;
        }
        if (deadline_expired) {
          fail_closed("MATCHING_SHUTDOWN_DEADLINE_EXCEEDED");
          break;
        }
      }
      if (!ownership_permit_->allows_processing()) {
        fail_closed("MATCHING_OWNERSHIP_LOST");
        break;
      }
      const auto step = runtime_.process_one();
      if (step == MatchingRuntimeStep::kProcessed) {
        backoff = std::chrono::microseconds(1);
        if (state() == MatchingRuntimeSupervisorState::kBackpressured) {
          state_.store(MatchingRuntimeSupervisorState::kRunning, std::memory_order_release);
        }
        continue;
      }
      if (step == MatchingRuntimeStep::kOwnershipDenied ||
          step == MatchingRuntimeStep::kCoreRejected) {
        fail_closed("MATCHING_WRITER_PROCESSING_FAILED");
        break;
      }
      if (stop_requested() || state() == MatchingRuntimeSupervisorState::kFailedClosed) {
        break;
      }
      if (state() != MatchingRuntimeSupervisorState::kQuiescing) {
        state_.store(
            step == MatchingRuntimeStep::kOutputBackpressured
                ? MatchingRuntimeSupervisorState::kBackpressured
                : MatchingRuntimeSupervisorState::kRunning,
            std::memory_order_release);
      }
      if (state() == MatchingRuntimeSupervisorState::kQuiescing &&
          runtime_.input_size() == 0) {
        break;
      }
      wait_for_work(backoff);
    }
  } catch (const std::exception &failure) {
    fail_closed(std::string("MATCHING_WRITER_EXCEPTION:") + failure.what());
  } catch (...) {
    fail_closed("MATCHING_WRITER_EXCEPTION");
  }
  mark_writer_stopped();
}

void MatchingRuntimeSupervisor::ownership_loop() {
  try {
    while (!stop_requested_.load(std::memory_order_acquire)) {
      static_cast<void>(options_.ownership_observer());
      if (!ownership_permit_->allows_processing()) {
        fail_closed("MATCHING_OWNERSHIP_LOST");
        break;
      }
      std::unique_lock lock(control_mutex_);
      control_condition_.wait_for(lock, options_.ownership_poll_interval, [this] {
        return stop_requested_.load(std::memory_order_acquire);
      });
    }
  } catch (const std::exception &failure) {
    fail_closed(std::string("MATCHING_OWNERSHIP_OBSERVER_EXCEPTION:") + failure.what());
  } catch (...) {
    fail_closed("MATCHING_OWNERSHIP_OBSERVER_EXCEPTION");
  }
  ownership_stopped_.store(true, std::memory_order_release);
  notify_all();
}

void MatchingRuntimeSupervisor::wait_for_work(std::chrono::microseconds &backoff) {
  switch (options_.wait_strategy) {
    case RuntimeWaitStrategy::kBusySpin:
      return;
    case RuntimeWaitStrategy::kYield:
      std::this_thread::yield();
      return;
    case RuntimeWaitStrategy::kBoundedBackoff:
      std::this_thread::sleep_for(backoff);
      backoff = std::min(backoff * 2, options_.maximum_backoff);
      return;
  }
}

void MatchingRuntimeSupervisor::mark_writer_stopped() {
  {
    std::lock_guard lock(control_mutex_);
    writer_stopped_.store(true, std::memory_order_release);
  }
  notify_all();
}

void MatchingRuntimeSupervisor::notify_all() {
  control_condition_.notify_all();
}

void MatchingRuntimeSupervisor::join_threads() {
  stop_requested_.store(true, std::memory_order_release);
  notify_all();
  if (writer_thread_.joinable()) {
    writer_thread_.join();
  }
  if (ownership_thread_.joinable()) {
    ownership_thread_.join();
  }
  {
    std::lock_guard lock(control_mutex_);
    if (writer_stopped_.load(std::memory_order_acquire) &&
        state_.load(std::memory_order_acquire) != MatchingRuntimeSupervisorState::kFailedClosed) {
      state_.store(MatchingRuntimeSupervisorState::kStopped, std::memory_order_release);
    }
  }
}

bool MatchingRuntimeSupervisor::stop_requested() const noexcept {
  return stop_requested_.load(std::memory_order_acquire);
}

} // namespace simplematch::matching
