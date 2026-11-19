#include <gtest/gtest.h>

#include <array>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <exception>
#include <future>
#include <limits>
#include <optional>
#include <stdexcept>
#include <string_view>
#include <thread>
#include <vector>

#include "matching_test_support.hpp"
#include "simplematch/matching/disruptor/disruptor.hpp"
#include "simplematch/matching/disruptor/spsc_channel.hpp"
#include "simplematch/matching/runtime/matching_runtime.hpp"

namespace simplematch::matching::disruptor {
namespace {

TEST(DisruptorSequencingTest, SpscChannelPreservesWrapAndCapacity) {
  DisruptorSpscChannel<int> channel(4);
  EXPECT_EQ(channel.capacity(), 4U);
  EXPECT_EQ(channel.size(), 0U);
  EXPECT_EQ(channel.available_to_write(), 4U);
  for (int cycle = 0; cycle < 100; ++cycle) {
    for (int index = 0; index < 4; ++index) {
      ASSERT_TRUE(channel.try_push(cycle * 4 + index));
    }
    EXPECT_EQ(channel.available_to_write(), 0U);
    EXPECT_FALSE(channel.try_push(-1));
    for (int index = 0; index < 4; ++index) {
      ASSERT_EQ(channel.try_pop(), cycle * 4 + index);
    }
    EXPECT_EQ(channel.size(), 0U);
    EXPECT_EQ(channel.available_to_write(), 4U);
  }
  EXPECT_FALSE(channel.try_pop().has_value());
}

TEST(DisruptorSequencingTest, SpscChannelPublishesCompletedObjects) {
  DisruptorSpscChannel<std::array<int, 3>> channel(1);
  std::array<int, 3> observed{};
  std::atomic<bool> completed{};
  std::jthread consumer([&](std::stop_token stop) {
    while (!stop.stop_requested()) {
      if (auto value = channel.try_pop()) {
        observed = *value;
        completed.store(true, std::memory_order_release);
        return;
      }
      std::this_thread::yield();
    }
  });
  ASSERT_TRUE(channel.try_push({1, 2, 3}));
  const auto deadline =
      std::chrono::steady_clock::now() + std::chrono::seconds(5);
  while (!completed.load(std::memory_order_acquire) &&
         std::chrono::steady_clock::now() < deadline) {
    std::this_thread::yield();
  }
  consumer.request_stop();
  consumer.join();
  ASSERT_TRUE(completed.load());
  EXPECT_EQ(observed, (std::array<int, 3>{1, 2, 3}));
}

TEST(DisruptorSequencingTest, TerminalConsumerClearsSlotBeforeReleasingIt) {
  Sequence consumed;
  const std::array<const Sequence*, 1> gates{&consumed};
  RingBuffer<int, SingleProducerSequencer> ring(1, gates);
  ASSERT_TRUE(ring.try_publish(42));
  const auto value = ring.take_for_consumer(0);
  ASSERT_TRUE(value.has_value());
  EXPECT_EQ(*value, 42);
  EXPECT_FALSE(ring.has_event(0));
  consumed.store(0);
  EXPECT_TRUE(ring.try_publish(43));
}

TEST(DisruptorSequencingTest,
     TwoRingChannelsPreserveZeroOneManyFramesConcurrently) {
  struct Input {
    int sequence;
    int output_count;
  };
  struct Output {
    int sequence;
    int index;
    bool end;
  };
  constexpr int kCommands = 300;
  DisruptorSpscChannel<Input> input(2);
  DisruptorSpscChannel<Output> output(4);
  std::atomic<bool> failed{};
  const auto deadline =
      std::chrono::steady_clock::now() + std::chrono::seconds(5);
  const auto should_abort = [&] {
    return failed.load(std::memory_order_acquire) ||
           std::chrono::steady_clock::now() >= deadline;
  };

  std::jthread writer([&](std::stop_token stop) {
    for (int expected = 0; expected < kCommands && !stop.stop_requested();) {
      // Maximum two business events plus one terminal marker per command.
      if (output.available_to_write() < 3) {
        if (should_abort()) {
          failed.store(true);
          return;
        }
        std::this_thread::yield();
        continue;
      }
      auto command = input.try_pop();
      if (!command) {
        if (should_abort()) {
          failed.store(true);
          return;
        }
        std::this_thread::yield();
        continue;
      }
      if (command->sequence != expected ||
          command->output_count != expected % 3) {
        failed.store(true);
        return;
      }
      for (int index = 0; index < command->output_count; ++index) {
        if (!output.try_push(Output{expected, index, false})) {
          failed.store(true);
          return;
        }
      }
      if (!output.try_push(Output{expected, command->output_count, true})) {
        failed.store(true);
        return;
      }
      ++expected;
    }
  });

  std::jthread publisher([&](std::stop_token stop) {
    int expected_command = 0;
    int expected_index = 0;
    while (expected_command < kCommands && !stop.stop_requested()) {
      auto next = output.try_pop();
      if (!next) {
        if (should_abort()) {
          failed.store(true);
          return;
        }
        std::this_thread::yield();
        continue;
      }
      if (next->sequence != expected_command || next->index != expected_index) {
        failed.store(true);
        return;
      }
      if (next->end) {
        if (expected_index != expected_command % 3) {
          failed.store(true);
          return;
        }
        ++expected_command;
        expected_index = 0;
      } else {
        ++expected_index;
      }
    }
  });

  for (int sequence = 0; sequence < kCommands; ++sequence) {
    while (!input.try_push(Input{sequence, sequence % 3})) {
      if (should_abort()) {
        failed.store(true);
        break;
      }
      std::this_thread::yield();
    }
    if (failed.load()) {
      break;
    }
  }
  writer.join();
  publisher.join();
  EXPECT_FALSE(failed.load());
  EXPECT_EQ(input.size(), 0U);
  EXPECT_EQ(output.size(), 0U);
}

TEST(DisruptorSequencingTest, SingleProducerRespectsSlowestGatingConsumer) {
  Sequence fast;
  Sequence slow;
  const std::array<const Sequence*, 2> gates{&fast, &slow};
  RingBuffer<int, SingleProducerSequencer> ring(4, gates);

  for (int value = 0; value != 4; ++value) {
    ASSERT_TRUE(ring.try_publish(value));
  }
  EXPECT_FALSE(ring.try_publish(4));
  EXPECT_EQ(ring.sequencer().highest_contiguous_published(0), 3);
  for (std::int64_t sequence = 0; sequence != 4; ++sequence) {
    EXPECT_EQ(ring.at(sequence), sequence);
  }

  fast.store(3);
  EXPECT_FALSE(ring.try_publish(4));
  slow.store(0);
  EXPECT_TRUE(ring.try_publish(4));
  EXPECT_EQ(ring.at(4), 4);
  EXPECT_EQ(ring.sequencer().highest_contiguous_published(1), 4);
}

TEST(DisruptorSequencingTest, OutputCapacityPreflightTracksSlowConsumer) {
  Sequence consumer;
  const std::array<const Sequence*, 1> gates{&consumer};
  RingBuffer<int, SingleProducerSequencer> ring(4, gates);
  EXPECT_EQ(ring.available_to_write(), 4U);
  ASSERT_TRUE(ring.try_publish(10));
  ASSERT_TRUE(ring.try_publish(11));
  EXPECT_EQ(ring.available_to_write(), 2U);
  ASSERT_TRUE(ring.try_publish(12));
  ASSERT_TRUE(ring.try_publish(13));
  EXPECT_EQ(ring.available_to_write(), 0U);
  consumer.store(1);
  EXPECT_EQ(ring.available_to_write(), 2U);
  ASSERT_TRUE(ring.try_publish(14));
  EXPECT_EQ(ring.available_to_write(), 1U);
}

TEST(DisruptorSequencingTest, CapacityPreflightStopsAtSequenceExhaustion) {
  constexpr auto maximum = std::numeric_limits<SequenceNumber>::max();
  EXPECT_EQ(detail::remaining_capacity(-1, -1, 4), 4U);
  EXPECT_EQ(detail::remaining_capacity(maximum - 2, maximum - 2, 4), 2U);
  EXPECT_EQ(detail::remaining_capacity(maximum - 1, maximum - 1, 4), 1U);
  EXPECT_EQ(detail::remaining_capacity(maximum, maximum, 4), 0U);
}

TEST(DisruptorSequencingTest, OutOfOrderMultiProducerPublishCannotExposeAHole) {
  Sequence consumer;
  const std::array<const Sequence*, 1> gates{&consumer};
  RingBuffer<int, MultiProducerSequencer> ring(4, gates);
  auto first = ring.try_claim();
  auto second = ring.try_claim();
  ASSERT_TRUE(first.has_value());
  ASSERT_TRUE(second.has_value());
  ASSERT_EQ(first->sequence(), 0);
  ASSERT_EQ(second->sequence(), 1);

  second->publish(11);
  EXPECT_EQ(ring.sequencer().highest_contiguous_published(0), -1);
  first->publish(10);
  EXPECT_EQ(ring.sequencer().highest_contiguous_published(0), 1);
  EXPECT_EQ(ring.at(0), 10);
  EXPECT_EQ(ring.at(1), 11);
}

TEST(DisruptorSequencingTest, MultiProducerNeverOverwritesUnconsumedSlots) {
  Sequence consumer;
  const std::array<const Sequence*, 1> gates{&consumer};
  RingBuffer<int, MultiProducerSequencer> ring(8, gates);
  constexpr int kThreads = 4;
  constexpr int kEventsPerThread = 50;
  std::vector<std::jthread> producers;
  producers.reserve(kThreads);
  for (int thread = 0; thread < kThreads; ++thread) {
    producers.emplace_back([&ring, thread](std::stop_token stop) {
      for (int count = 0; count < kEventsPerThread; ++count) {
        while (!ring.try_publish(thread * kEventsPerThread + count)) {
          if (stop.stop_requested()) {
            return;
          }
          std::this_thread::yield();
        }
      }
    });
  }

  std::array<bool, kThreads * kEventsPerThread> seen{};
  bool completed = true;
  const auto deadline =
      std::chrono::steady_clock::now() + std::chrono::seconds(10);
  for (int count = 0; count < kThreads * kEventsPerThread; ++count) {
    while (ring.sequencer().highest_contiguous_published(count) < count) {
      if (std::chrono::steady_clock::now() >= deadline) {
        completed = false;
        break;
      }
      std::this_thread::yield();
    }
    if (!completed) {
      break;
    }
    const int value = ring.at(count);
    if (value < 0 || value >= static_cast<int>(seen.size())) {
      ADD_FAILURE() << "Out-of-range multi-producer payload: " << value;
      completed = false;
      break;
    }
    EXPECT_FALSE(seen[value]);
    seen[value] = true;
    consumer.store(count);
  }
  for (auto& producer : producers) {
    producer.request_stop();
    producer.join();
  }
  ASSERT_TRUE(completed) << "Multi-producer publication stalled";
  for (bool value : seen) {
    EXPECT_TRUE(value);
  }
}

TEST(DisruptorSequencingTest, DependentProcessorCannotOvertakeItsPredecessor) {
  Sequence first_done;
  Sequence second_done;
  const std::array<const Sequence*, 1> gates{&second_done};
  RingBuffer<int, SingleProducerSequencer> ring(4, gates);
  const std::array<const Sequence*, 1> dependencies{&first_done};
  SequenceBarrier first_barrier(ring.sequencer(), {});
  SequenceBarrier second_barrier(ring.sequencer(), dependencies);
  std::vector<int> seen_first;
  std::vector<int> seen_second;
  BatchEventProcessor first(
      ring, first_barrier, first_done,
      [&](const int& value, SequenceNumber) { seen_first.push_back(value); });
  BatchEventProcessor second(
      ring, second_barrier, second_done,
      [&](const int& value, SequenceNumber) { seen_second.push_back(value); });
  ASSERT_TRUE(ring.try_publish(10));
  ASSERT_TRUE(ring.try_publish(11));
  EXPECT_EQ(second.poll(4), 0U);
  EXPECT_EQ(first.poll(4), 2U);
  EXPECT_EQ(second.poll(4), 2U);
  EXPECT_EQ(seen_first, (std::vector<int>{10, 11}));
  EXPECT_EQ(seen_second, (std::vector<int>{10, 11}));
  EXPECT_EQ(second_done.load(), 1);
}

TEST(DisruptorSequencingTest,
     ConcurrentSingleRingStagesPublishOnlyCompletedResults) {
  struct StageFrame {
    int input;
    int result;
  };
  constexpr int kEvents = 200;
  Sequence matched;
  Sequence published;
  const std::array<const Sequence*, 1> gates{&published};
  const std::array<const Sequence*, 1> dependencies{&matched};
  RingBuffer<StageFrame, SingleProducerSequencer> ring(4, gates);
  PipelineFailure failure;
  SequenceBarrier matching_barrier(ring.sequencer(), {}, &failure);
  SequenceBarrier publishing_barrier(ring.sequencer(), dependencies, &failure);
  BatchEventProcessor matching(
      ring, matching_barrier, matched,
      [&](const StageFrame& frame, SequenceNumber sequence) {
        ring.at_for_stage(sequence).result = frame.input * 2;
      },
      &failure);
  std::vector<int> observed;
  observed.reserve(kEvents);
  BatchEventProcessor publisher(
      ring, publishing_barrier, published,
      [&](const StageFrame& frame, SequenceNumber) {
        observed.push_back(frame.result);
      },
      &failure);

  std::jthread matching_thread([&](std::stop_token stop) {
    matching.run(stop, WaitStrategy::kBlocking, 4);
  });
  std::jthread publishing_thread([&](std::stop_token stop) {
    publisher.run(stop, WaitStrategy::kBlocking, 4);
  });
  const auto deadline =
      std::chrono::steady_clock::now() + std::chrono::seconds(5);
  bool submitted = true;
  for (int value = 0; value < kEvents; ++value) {
    while (!ring.try_publish(StageFrame{value, -1})) {
      if (std::chrono::steady_clock::now() >= deadline || failure.failed()) {
        submitted = false;
        break;
      }
      std::this_thread::yield();
    }
    if (!submitted) {
      break;
    }
  }
  while (submitted && published.load() != kEvents - 1 &&
         std::chrono::steady_clock::now() < deadline && !failure.failed()) {
    std::this_thread::yield();
  }
  const bool drained = published.load() == kEvents - 1;
  matching_thread.request_stop();
  publishing_thread.request_stop();
  matching_barrier.alert();
  publishing_barrier.alert();
  matching_thread.join();
  publishing_thread.join();
  EXPECT_TRUE(submitted) << "Single-ring producer stalled";
  EXPECT_TRUE(drained) << "Single-ring publisher stalled";
  EXPECT_FALSE(failure.failed());
  ASSERT_EQ(observed.size(), static_cast<std::size_t>(kEvents));
  for (int value = 0; value < kEvents; ++value) {
    EXPECT_EQ(observed[static_cast<std::size_t>(value)], value * 2);
  }
}

TEST(DisruptorSequencingTest, FanOutWaitsForBothIndependentConsumers) {
  Sequence first_done;
  Sequence second_done;
  const std::array<const Sequence*, 2> gates{&first_done, &second_done};
  RingBuffer<int, SingleProducerSequencer> ring(2, gates);
  SequenceBarrier first_barrier(ring.sequencer(), {});
  SequenceBarrier second_barrier(ring.sequencer(), {});
  std::vector<int> first_values;
  std::vector<int> second_values;
  BatchEventProcessor first(
      ring, first_barrier, first_done,
      [&](const int& value, SequenceNumber) { first_values.push_back(value); });
  BatchEventProcessor second(ring, second_barrier, second_done,
                             [&](const int& value, SequenceNumber) {
                               second_values.push_back(value);
                             });
  ASSERT_TRUE(ring.try_publish(17));
  ASSERT_TRUE(ring.try_publish(18));
  EXPECT_FALSE(ring.try_publish(19));
  EXPECT_EQ(first.poll(2), 2U);
  EXPECT_FALSE(ring.try_publish(19));
  EXPECT_EQ(second.poll(1), 1U);
  EXPECT_TRUE(ring.try_publish(19));
  EXPECT_EQ(first.poll(1), 1U);
  EXPECT_EQ(second.poll(2), 2U);
  EXPECT_EQ(first_values, (std::vector<int>{17, 18, 19}));
  EXPECT_EQ(second_values, (std::vector<int>{17, 18, 19}));
}

TEST(DisruptorSequencingTest, HandlerFailureDoesNotAcknowledgeItsEvent) {
  Sequence consumed;
  const std::array<const Sequence*, 1> gates{&consumed};
  RingBuffer<int, SingleProducerSequencer> ring(2, gates);
  SequenceBarrier barrier(ring.sequencer(), {});
  BatchEventProcessor processor(ring, barrier, consumed,
                                [](const int&, SequenceNumber) {
                                  throw std::runtime_error("handler failed");
                                });
  ASSERT_TRUE(ring.try_publish(1));
  EXPECT_EQ(processor.poll(1), 0U);
  EXPECT_EQ(consumed.load(), kInitialSequence);
  EXPECT_TRUE(processor.failed());
  EXPECT_TRUE(processor.failure() != nullptr);
  EXPECT_EQ(processor.poll(1), 0U);
}

TEST(DisruptorSequencingTest,
     SharedPipelineFailureDuringHandlerDoesNotAcknowledgeEvent) {
  Sequence consumed;
  const std::array<const Sequence*, 1> gates{&consumed};
  RingBuffer<int, SingleProducerSequencer> ring(2, gates);
  PipelineFailure failure;
  SequenceBarrier barrier(ring.sequencer(), {}, &failure);
  BatchEventProcessor processor(
      ring, barrier, consumed,
      [&](const int&, SequenceNumber) {
        failure.fail(std::make_exception_ptr(
            std::runtime_error("upstream stopped during processing")));
      },
      &failure);
  ASSERT_TRUE(ring.try_publish(17));
  EXPECT_EQ(processor.poll(1), 0U);
  EXPECT_EQ(consumed.load(), kInitialSequence);
  EXPECT_TRUE(failure.failed());
  EXPECT_EQ(processor.poll(1), 0U);
}

TEST(DisruptorSequencingTest,
     AbandonedClaimFailsClosedWithoutHangingFollowers) {
  Sequence consumed;
  const std::array<const Sequence*, 1> gates{&consumed};
  RingBuffer<int, MultiProducerSequencer> ring(4, gates);
  {
    auto abandoned = ring.try_claim();
    ASSERT_TRUE(abandoned.has_value());
    EXPECT_EQ(abandoned->sequence(), 0);
  }
  ASSERT_TRUE(ring.try_publish(42));
  SequenceBarrier barrier(ring.sequencer(), {});
  BatchEventProcessor processor(ring, barrier, consumed,
                                [](const int&, SequenceNumber) {
                                  FAIL() << "Abandoned claim was visible";
                                });
  EXPECT_EQ(ring.sequencer().highest_contiguous_published(0), 1);
  EXPECT_THROW(static_cast<void>(ring.at(0)), std::logic_error);
  EXPECT_EQ(processor.poll(4), 0U);
  EXPECT_EQ(consumed.load(), kInitialSequence);
  EXPECT_TRUE(processor.failed());
}

TEST(DisruptorSequencingTest, SingleProducerCannotPublishClaimsOutOfOrder) {
  Sequence consumed;
  const std::array<const Sequence*, 1> gates{&consumed};
  RingBuffer<int, SingleProducerSequencer> ring(4, gates);
  auto first = ring.try_claim();
  ASSERT_TRUE(first.has_value());
  EXPECT_FALSE(ring.try_claim().has_value());
  first->publish(10);
  auto second = ring.try_claim();
  ASSERT_TRUE(second.has_value());
  EXPECT_EQ(second->sequence(), 1);
  second->publish(11);
  EXPECT_EQ(ring.sequencer().highest_contiguous_published(0), 1);
}

TEST(DisruptorSequencingTest, UpstreamFailureStopsDependentConsumer) {
  Sequence upstream_done;
  Sequence downstream_done;
  const std::array<const Sequence*, 1> gates{&downstream_done};
  RingBuffer<int, SingleProducerSequencer> ring(2, gates);
  PipelineFailure failure;
  const std::array<const Sequence*, 1> dependencies{&upstream_done};
  SequenceBarrier upstream_barrier(ring.sequencer(), {}, &failure);
  SequenceBarrier downstream_barrier(ring.sequencer(), dependencies, &failure);
  BatchEventProcessor upstream(
      ring, upstream_barrier, upstream_done,
      [](const int&, SequenceNumber) {
        throw std::runtime_error("upstream failure");
      },
      &failure);
  BatchEventProcessor downstream(
      ring, downstream_barrier, downstream_done,
      [](const int&, SequenceNumber) {
        FAIL() << "Must not consume failed event";
      },
      &failure);
  ASSERT_TRUE(ring.try_publish(7));
  EXPECT_EQ(downstream.poll(1), 0U);
  EXPECT_EQ(upstream.poll(1), 0U);
  EXPECT_TRUE(failure.failed());
  EXPECT_FALSE(
      downstream_barrier.wait_for(0, WaitStrategy::kBusySpin).has_value());
  EXPECT_EQ(downstream.poll(1), 0U);
  EXPECT_EQ(upstream_done.load(), kInitialSequence);
  EXPECT_EQ(downstream_done.load(), kInitialSequence);
}

TEST(DisruptorSequencingTest,
     FailedSingleRingMutationNeverReachesDependentPublisher) {
  struct StageFrame {
    int input;
    int output;
  };
  Sequence matched;
  Sequence published;
  const std::array<const Sequence*, 1> gates{&published};
  const std::array<const Sequence*, 1> dependencies{&matched};
  RingBuffer<StageFrame, SingleProducerSequencer> ring(2, gates);
  PipelineFailure failure;
  SequenceBarrier matching_barrier(ring.sequencer(), {}, &failure);
  SequenceBarrier publishing_barrier(ring.sequencer(), dependencies, &failure);
  BatchEventProcessor matching(
      ring, matching_barrier, matched,
      [&](const StageFrame&, SequenceNumber sequence) {
        ring.at_for_stage(sequence).output = 42;
        throw std::runtime_error(
            "Matching stage failed after partial mutation");
      },
      &failure);
  std::size_t publisher_calls = 0;
  BatchEventProcessor publisher(
      ring, publishing_barrier, published,
      [&](const StageFrame&, SequenceNumber) { ++publisher_calls; }, &failure);
  ASSERT_TRUE(ring.try_publish(StageFrame{7, -1}));
  EXPECT_EQ(publisher.poll(1), 0U);
  EXPECT_EQ(matching.poll(1), 0U);
  EXPECT_TRUE(failure.failed());
  EXPECT_EQ(matched.load(), kInitialSequence);
  EXPECT_EQ(published.load(), kInitialSequence);
  EXPECT_FALSE(
      publishing_barrier.wait_for(0, WaitStrategy::kBlocking).has_value());
  EXPECT_EQ(publisher.poll(1), 0U);
  EXPECT_EQ(publisher_calls, 0U);
}

TEST(DisruptorSequencingTest, BarrierCanWaitAndHaltWithoutPublishing) {
  Sequence consumer;
  const std::array<const Sequence*, 1> gates{&consumer};
  RingBuffer<int, SingleProducerSequencer> ring(4, gates);
  SequenceBarrier barrier(ring.sequencer(), {});
  std::promise<bool> stopped;
  auto future = stopped.get_future();
  std::jthread waiter([&](std::stop_token stop) {
    stopped.set_value(
        !barrier.wait_for(0, WaitStrategy::kBlocking, stop).has_value());
  });
  barrier.alert();
  const auto ready = future.wait_for(std::chrono::seconds(3));
  if (ready != std::future_status::ready) {
    waiter.request_stop();
  }
  waiter.join();
  ASSERT_EQ(ready, std::future_status::ready);
  EXPECT_TRUE(future.get());
  EXPECT_FALSE(barrier.wait_for(0, WaitStrategy::kBusySpin).has_value());
}

TEST(DisruptorSequencingTest, BlockingBarrierWakesForPublishedInput) {
  Sequence consumer;
  const std::array<const Sequence*, 1> gates{&consumer};
  RingBuffer<int, SingleProducerSequencer> ring(4, gates);
  SequenceBarrier barrier(ring.sequencer(), {});
  std::promise<std::optional<SequenceNumber>> result;
  auto future = result.get_future();
  std::jthread waiter([&](std::stop_token stop) {
    result.set_value(barrier.wait_for(0, WaitStrategy::kBlocking, stop));
  });
  EXPECT_TRUE(ring.try_publish(7));
  const auto ready = future.wait_for(std::chrono::seconds(3));
  if (ready != std::future_status::ready) {
    barrier.alert();
    waiter.request_stop();
  }
  waiter.join();
  ASSERT_EQ(ready, std::future_status::ready);
  EXPECT_EQ(future.get(), std::optional<SequenceNumber>{0});
}

TEST(DisruptorSequencingTest, RejectsInvalidCapacityAndMissingGates) {
  Sequence consumer;
  const std::array<const Sequence*, 1> gates{&consumer};
  EXPECT_THROW((RingBuffer<int, SingleProducerSequencer>(3, gates)),
               std::invalid_argument);
  EXPECT_THROW((RingBuffer<int, MultiProducerSequencer>(0, gates)),
               std::invalid_argument);
  EXPECT_THROW((RingBuffer<int, SingleProducerSequencer>(4, {})),
               std::invalid_argument);
}

TEST(DisruptorSequencingTest, TwoDirectionalRingsFrameOneMatchingCommand) {
  // MatchingRuntime's RuntimeInput is private. Model its transport shape here
  // without widening the production API or replacing the existing SPSC rings.
  struct InputFrame {
    InputSequence sequence;
    CoreCommand command;
  };
  Sequence input_consumed;
  Sequence output_consumed;
  const std::array<const Sequence*, 1> input_gates{&input_consumed};
  const std::array<const Sequence*, 1> output_gates{&output_consumed};
  RingBuffer<InputFrame, SingleProducerSequencer> input(4, input_gates);
  RingBuffer<RuntimeOutput, SingleProducerSequencer> output(4, output_gates);
  SequenceBarrier input_barrier(input.sequencer(), {});
  SequenceBarrier output_barrier(output.sequencer(), {});
  DeterministicMatchingCore core({test_support::instrument()}, 1, 0);

  BatchEventProcessor writer(
      input, input_barrier, input_consumed,
      [&](const InputFrame& next, SequenceNumber) {
        // There is one output producer. Consumer progress can only increase
        // available space, so this preflight covers the entire event frame.
        if (output.available_to_write() < core.maximum_output_events() + 1) {
          throw std::runtime_error(
              "Output lacks a complete event-frame capacity");
        }
        if (core.process(next.command) != MatchingProcessResult::kApplied) {
          throw std::runtime_error("Core rejected the test command");
        }
        std::size_t index = 0;
        for (const CoreEvent& event : core.events()) {
          if (!output.try_publish(
                  RuntimeEventOutput{next.sequence, index++, event})) {
            throw std::runtime_error("Output ring backpressured");
          }
        }
        if (!output.try_publish(RuntimeEndOfInput{next.sequence, index})) {
          throw std::runtime_error("Output end marker backpressured");
        }
      });
  std::vector<RuntimeOutput> published;
  BatchEventProcessor publisher(
      output, output_barrier, output_consumed,
      [&](const RuntimeOutput& frame, SequenceNumber) {
        published.push_back(frame);
      });

  const auto command = CoreCommand::new_order(
      test_support::context(),
      test_support::uuid("0198a001-0000-7000-8000-000000000091"),
      test_support::uuid("0198a001-0000-7000-8000-000000000092"),
      test_support::uuid("0198a001-0000-7000-8000-000000000093"),
      test_support::instrument(), CoreSide::kBuy, ShareQuantity(10),
      FixedPrice(1'000'000), CoreOrderType::kLimit, CoreTimeInForce::kRod);
  ASSERT_TRUE(input.try_publish(InputFrame{42, command}));
  ASSERT_EQ(writer.poll(1), 1U);
  ASSERT_EQ(publisher.poll(2), 2U);
  ASSERT_EQ(published.size(), 2U);
  const auto& rested = std::get<RuntimeEventOutput>(published.front());
  const auto& end = std::get<RuntimeEndOfInput>(published.back());
  EXPECT_EQ(rested.input_sequence, 42U);
  EXPECT_EQ(rested.event.type, CoreEventType::kOrderRested);
  EXPECT_EQ(rested.event.order_id, command.new_order().order_id);
  EXPECT_EQ(end.input_sequence, 42U);
  EXPECT_EQ(end.output_count, 1U);
  EXPECT_EQ(core.resting_order_count(test_support::instrument()), 1U);
}

TEST(DisruptorSequencingTest, OneRingMatchingPipelineGatesOnSlowPublisher) {
  // An event stays in the same ring across two dependent consumers. This is
  // intentionally a small, bounded topology example, not a Kafka adapter.
  constexpr std::size_t kMaximumFrames = 8;
  struct MatchingFrame {
    InputSequence input_sequence;
    CoreCommand command;
    std::array<std::optional<RuntimeOutput>, kMaximumFrames> outputs{};
    std::size_t output_size{};
  };

  Sequence matched;
  Sequence published;
  const std::array<const Sequence*, 1> gates{&published};
  const std::array<const Sequence*, 1> dependencies{&matched};
  RingBuffer<MatchingFrame, SingleProducerSequencer> ring(2, gates);
  PipelineFailure failure;
  SequenceBarrier matching_barrier(ring.sequencer(), {}, &failure);
  SequenceBarrier publishing_barrier(ring.sequencer(), dependencies, &failure);
  DeterministicMatchingCore core({test_support::instrument()}, 3, 0);
  ASSERT_LE(core.maximum_output_events() + 1, kMaximumFrames);

  BatchEventProcessor matching(
      ring, matching_barrier, matched,
      [&](const MatchingFrame&, SequenceNumber sequence) {
        MatchingFrame& frame = ring.at_for_stage(sequence);
        if (core.process(frame.command) != MatchingProcessResult::kApplied) {
          throw std::runtime_error("Single-ring Matching rejected command");
        }
        for (const CoreEvent& event : core.events()) {
          if (frame.output_size == frame.outputs.size()) {
            throw std::logic_error(
                "Single-ring output batch exceeds slot bound");
          }
          const auto index = frame.output_size++;
          frame.outputs[index].emplace(
              RuntimeEventOutput{frame.input_sequence, index, event});
        }
        if (frame.output_size == frame.outputs.size()) {
          throw std::logic_error("Single-ring output end marker exceeds bound");
        }
        frame.outputs[frame.output_size++].emplace(
            RuntimeEndOfInput{frame.input_sequence, core.events().size()});
      },
      &failure);
  std::vector<RuntimeOutput> delivered;
  BatchEventProcessor publisher(
      ring, publishing_barrier, published,
      [&](const MatchingFrame& frame, SequenceNumber) {
        for (std::size_t index = 0; index < frame.output_size; ++index) {
          delivered.push_back(frame.outputs[index].value());
        }
      },
      &failure);

  const auto make_order = [](std::string_view command_id,
                             std::string_view order_id, CoreSide side,
                             std::int64_t quantity, CoreTimeInForce tif) {
    return CoreCommand::new_order(
        test_support::context(), test_support::uuid(command_id),
        test_support::uuid(order_id),
        test_support::uuid("0198a001-0000-7000-8000-0000000000aa"),
        test_support::instrument(), side, ShareQuantity(quantity),
        FixedPrice(1'000'000), CoreOrderType::kLimit, tif);
  };
  const auto resting_first =
      make_order("0198a001-0000-7000-8000-000000000091",
                 "0198a001-0000-7000-8000-000000000092", CoreSide::kBuy, 10,
                 CoreTimeInForce::kRod);
  const auto resting_second =
      make_order("0198a001-0000-7000-8000-000000000093",
                 "0198a001-0000-7000-8000-000000000094", CoreSide::kBuy, 10,
                 CoreTimeInForce::kRod);
  const auto crossing = make_order("0198a001-0000-7000-8000-000000000095",
                                   "0198a001-0000-7000-8000-000000000096",
                                   CoreSide::kSell, 30, CoreTimeInForce::kIoc);
  ASSERT_TRUE(ring.try_publish(MatchingFrame{42, resting_first}));
  ASSERT_TRUE(ring.try_publish(MatchingFrame{43, resting_second}));
  EXPECT_EQ(publisher.poll(2), 0U);
  EXPECT_FALSE(ring.try_publish(MatchingFrame{44, crossing}));
  ASSERT_EQ(matching.poll(2), 2U);
  EXPECT_EQ(matched.load(), 1);
  EXPECT_FALSE(ring.try_publish(MatchingFrame{44, crossing}));
  ASSERT_EQ(publisher.poll(1), 1U);
  ASSERT_TRUE(ring.try_publish(MatchingFrame{44, crossing}));
  EXPECT_EQ(publisher.poll(2), 1U);
  ASSERT_EQ(matching.poll(1), 1U);
  ASSERT_EQ(publisher.poll(1), 1U);
  ASSERT_EQ(delivered.size(), 8U);
  for (std::size_t index = 0; index < 2; ++index) {
    const auto& event = std::get<RuntimeEventOutput>(delivered[index * 2]);
    const auto& end = std::get<RuntimeEndOfInput>(delivered[index * 2 + 1]);
    EXPECT_EQ(event.input_sequence, 42U + index);
    EXPECT_EQ(event.output_index, 0U);
    EXPECT_EQ(event.event.type, CoreEventType::kOrderRested);
    EXPECT_EQ(end.input_sequence, 42U + index);
    EXPECT_EQ(end.output_count, 1U);
  }
  for (std::size_t index = 0; index < 3; ++index) {
    const auto& event = std::get<RuntimeEventOutput>(delivered[4 + index]);
    EXPECT_EQ(event.input_sequence, 44U);
    EXPECT_EQ(event.output_index, index);
    EXPECT_EQ(event.event.type, index < 2 ? CoreEventType::kTradeExecuted
                                          : CoreEventType::kOrderCancelled);
  }
  const auto& crossing_end = std::get<RuntimeEndOfInput>(delivered[7]);
  EXPECT_EQ(crossing_end.input_sequence, 44U);
  EXPECT_EQ(crossing_end.output_count, 3U);
  EXPECT_EQ(core.resting_order_count(test_support::instrument()), 0U);

  const auto close = CoreCommand::close(
      test_support::context(),
      test_support::uuid("0198a001-0000-7000-8000-000000000097"));
  ASSERT_TRUE(ring.try_publish(MatchingFrame{45, close}));
  ASSERT_EQ(matching.poll(1), 1U);
  ASSERT_EQ(publisher.poll(1), 1U);
  ASSERT_EQ(delivered.size(), 9U);
  const auto& empty_end = std::get<RuntimeEndOfInput>(delivered.back());
  EXPECT_EQ(empty_end.input_sequence, 45U);
  EXPECT_EQ(empty_end.output_count, 0U);
  EXPECT_EQ(published.load(), 3);
  EXPECT_FALSE(failure.failed());
}

}  // namespace
}  // namespace simplematch::matching::disruptor
