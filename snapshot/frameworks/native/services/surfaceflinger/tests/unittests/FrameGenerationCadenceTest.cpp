/* SPDX-License-Identifier: Apache-2.0 */

#include <gtest/gtest.h>

#include "FrameGenerationCadence.h"

namespace android::surfaceflinger {
namespace {

constexpr int64_t k60Hz = 16'666'667;
constexpr int64_t k120Hz = 8'333'333;

FrameGenerationCadence stableCadence(int64_t period, int64_t slots, int64_t lastReal) {
    FrameGenerationCadence cadence;
    for (int64_t i = 0; i < 5; ++i) {
        cadence.observe(static_cast<uint64_t>(i + 1), lastReal - (4 - i) * period * slots, period);
    }
    return cadence;
}

TEST(FrameGenerationCadenceTest, InsertsMidpointFor60To120) {
    constexpr int64_t last = 1'000'000'000;
    auto cadence = stableCadence(k120Hz, 2, last);
    EXPECT_FLOAT_EQ(.5f, cadence.fraction(last + k120Hz, last + 2 * k120Hz, k120Hz, 0));
    EXPECT_FLOAT_EQ(0.f, cadence.fraction(last + k120Hz, last + 2 * k120Hz, k120Hz, 60));
    EXPECT_FLOAT_EQ(0.f, cadence.fraction(last + 2 * k120Hz, last + 2 * k120Hz, k120Hz, 0));
}

TEST(FrameGenerationCadenceTest, PreservesEvenSlotsForVideo) {
    constexpr int64_t last = 1'000'000'000;
    const auto thirty = stableCadence(k60Hz, 2, last);
    EXPECT_FLOAT_EQ(.5f, thirty.fraction(last + k60Hz, last + 2 * k60Hz, k60Hz, 60));
    const auto thirty120 = stableCadence(k120Hz, 4, last);
    EXPECT_FLOAT_EQ(0.f, thirty120.fraction(last + k120Hz, last + 4 * k120Hz, k120Hz, 60));
    EXPECT_FLOAT_EQ(.5f, thirty120.fraction(last + 2 * k120Hz, last + 4 * k120Hz, k120Hz, 60));
    const auto twentyFour = stableCadence(k120Hz, 5, last);
    EXPECT_FLOAT_EQ(.2f, twentyFour.fraction(last + k120Hz, last + 5 * k120Hz, k120Hz, 120));
    EXPECT_FLOAT_EQ(.8f, twentyFour.fraction(last + 4 * k120Hz, last + 5 * k120Hz, k120Hz, 120));
    EXPECT_FLOAT_EQ(0.f, twentyFour.fraction(last + 2 * k120Hz, last + 5 * k120Hz, k120Hz, 60));
}

TEST(FrameGenerationCadenceTest, SkipsSeekAndRefreshChange) {
    constexpr int64_t last = 1'000'000'000;
    auto cadence = stableCadence(k120Hz, 2, last);
    EXPECT_FLOAT_EQ(0.f, cadence.fraction(last + k120Hz, last + 90'000'000, k120Hz, 0));
    EXPECT_FLOAT_EQ(0.f, cadence.fraction(last + k60Hz, last + 2 * k60Hz, k60Hz, 0));
    cadence.observe(6, last + 180'000'000, k120Hz);
    EXPECT_EQ(0, cadence.predictedNext());
}

TEST(FrameGenerationCadenceTest, DoesNotGenerateAtPanelRate) {
    constexpr int64_t last = 1'000'000'000;
    const auto cadence = stableCadence(k120Hz, 1, last);
    EXPECT_FLOAT_EQ(0.f, cadence.fraction(last + k120Hz, last + k120Hz, k120Hz, 0));
}

} // namespace
} // namespace android::surfaceflinger
