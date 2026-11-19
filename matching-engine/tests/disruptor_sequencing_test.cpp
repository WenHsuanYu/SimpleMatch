#include <gtest/gtest.h>

#include <array>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <exception>
#include <future>
#include <limits>
#include <stdexcept>
#include <thread>
#include <vector>

#include "matching_test_support.hpp"
#include "simplematch/matching/disruptor/disruptor.hpp"
#include "simplematch/matching/runtime/matching_runtime.hpp"

namespace simplematch::matching::disruptor {
namespace {

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

}  // namespace
}  // namespace simplematch::matching::disruptor
