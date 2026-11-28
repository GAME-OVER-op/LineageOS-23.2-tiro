/* SPDX-License-Identifier: Apache-2.0 */
#pragma once

#include <algorithm>
#include <cstdint>
#include <cstdlib>

namespace android::surfaceflinger {

// One cadence per layer. Observe original presentations, never synthesized frames.
class FrameGenerationCadence {
public:
    void observe(uint64_t frame, int64_t present, int64_t period) {
        if (frame == mFrame && mPresent > 0 && period == mPeriod) return;
        const int64_t delta = present - mPresent;
        const bool valid = mPresent > 0 && frame > mFrame && period == mPeriod &&
                delta >= period && delta <= 85'000'000;
        if (valid && mDelta > 0 && std::abs(delta - mDelta) <= period / 3) {
            mStable = std::min(mStable + 1, 4);
        } else {
            mStable = 0;
        }
        mDelta = valid ? delta : 0;
        mFrame = frame;
        mPresent = present;
        mPeriod = period;
    }

    float fraction(int64_t present, int64_t next, int64_t period, int maxFps) const {
        if (mStable < 2 || period <= 0 || period != mPeriod || present <= mPresent) return 0.f;
        const int64_t nextDelta = next - mPresent;
        if (std::abs(nextDelta - mDelta) > period / 2) return 0.f;
        const auto slots = (nextDelta + period / 2) / period;
        const auto step = (present - mPresent + period / 2) / period;
        if (slots < 2 || slots > 6 || step < 1 || step >= slots ||
            std::abs(present - mPresent - step * period) > period / 3) {
            return 0.f;
        }
        // Use only even divisions of the source interval: e.g. at 120 Hz the
        // 60 cap permits 30 -> 60, but not an uneven 24 -> 48 cadence.
        const int64_t capPeriod = maxFps > 0 ? period * maxFps : 1'000'000'000;
        const int64_t minStep = (980'000'000 + capPeriod - 1) / capPeriod;
        for (int64_t stride = minStep; stride < slots; ++stride) {
            if (slots % stride == 0) {
                return step % stride == 0 ? static_cast<float>(step) / static_cast<float>(slots)
                                          : 0.f;
            }
        }
        return 0.f;
    }

    int64_t predictedNext() const { return mStable >= 2 ? mPresent + mDelta : 0; }

private:
    uint64_t mFrame = 0;
    int64_t mPresent = 0;
    int64_t mPeriod = 0;
    int64_t mDelta = 0;
    int mStable = 0;
};

} // namespace android::surfaceflinger
