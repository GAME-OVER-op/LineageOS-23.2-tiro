/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include <private/android_filesystem_config.h>
#include <sys/types.h>

#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <optional>
#include <unordered_map>
#include <utility>
#include <vector>

namespace android {

struct FrameGenerationPolicy {
    static constexpr int32_t kModeOff = -1;
    static constexpr int32_t kModeNotSelected = 0;
    static constexpr int32_t kModeVideo = 1;
    static constexpr int32_t kModeAuto = 2;
    static constexpr size_t kMaxEntries = AID_APP_END - AID_APP_START + 1;

    bool enabled = false;
    int32_t currentUserId = 0;
    bool autoAll = false;
    int32_t maxFps = 0;

    static std::optional<FrameGenerationPolicy> fromWire(bool enabled, int32_t currentUserId,
                                                         bool autoAll, int32_t maxFps,
                                                         const std::vector<int32_t>& uids,
                                                         const std::vector<int32_t>& modes) {
        // Match UserHandle.MAX_SECONDARY_USER_ID to keep full UIDs in the signed int range.
        constexpr int32_t kMaxUserId = std::numeric_limits<int32_t>::max() / AID_USER_OFFSET - 1;
        if (currentUserId < 0 || currentUserId > kMaxUserId ||
            (maxFps != 0 && maxFps != 60 && maxFps != 120) || uids.size() != modes.size() ||
            uids.size() > kMaxEntries) {
            return std::nullopt;
        }

        FrameGenerationPolicy policy;
        policy.enabled = enabled;
        policy.currentUserId = currentUserId;
        policy.autoAll = autoAll;
        policy.maxFps = maxFps;
        if (uids.empty()) return policy;

        auto overrides = std::make_shared<UidModes>();
        overrides->reserve(uids.size());
        for (size_t i = 0; i < uids.size(); ++i) {
            const auto uid = static_cast<uid_t>(uids[i]);
            const int32_t mode = modes[i];
            if (uids[i] < 0 || !isAppUidForUser(uid, currentUserId) ||
                (mode != kModeOff && mode != kModeVideo && mode != kModeAuto) ||
                !overrides->try_emplace(uid, mode).second) {
                return std::nullopt;
            }
        }
        policy.mModes = std::move(overrides);
        return policy;
    }

    int32_t modeForUid(uid_t uid) const {
        if (!enabled || !isAppUidForUser(uid, currentUserId)) {
            return kModeOff;
        }
        if (mModes) {
            const auto it = mModes->find(uid);
            if (it != mModes->end()) return it->second;
        }
        return autoAll ? kModeAuto : kModeNotSelected;
    }

private:
    static bool isAppUidForUser(uid_t uid, int32_t userId) {
        const auto appId = uid % AID_USER_OFFSET;
        return uid / AID_USER_OFFSET == static_cast<uid_t>(userId) && appId >= AID_APP_START &&
                appId <= AID_APP_END;
    }

    using UidModes = std::unordered_map<uid_t, int32_t>;
    // The overrides remain immutable so copying a snapshot never copies the map.
    std::shared_ptr<const UidModes> mModes;
};

} // namespace android
