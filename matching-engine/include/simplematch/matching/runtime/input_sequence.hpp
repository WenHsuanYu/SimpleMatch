#pragma once

#include <cstdint>

namespace simplematch::matching {

/** Process-local sequence assigned to each accepted matching input. */
using InputSequence = std::uint64_t;

} // namespace simplematch::matching
