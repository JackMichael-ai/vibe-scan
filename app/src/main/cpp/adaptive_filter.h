#pragma once
#include <cstdint>
#include <mutex>

namespace vibescan {

static constexpr int RAW_CAPACITY = 8192;

struct NotchState {
    float w1 = 0.0f;
    float w2 = 0.0f;
};

class AdaptiveFilter {
public:
    AdaptiveFilter();
    void  reset();
    void  setPowerlineHz(float hz);        // 50 (default) or 60
    void  pushRaw(float x, float y, float z, long long ts_ns);
    int   resampleUniform(float* out_x, float* out_y, float* out_z, int max_out) const;
    float getActualRate() const;
    float meanJitterMs() const;
    void  resetJitterStats();

private:
    float     applyNotch(float x, NotchState& state);

    float     raw_x_[RAW_CAPACITY];
    float     raw_y_[RAW_CAPACITY];
    float     raw_z_[RAW_CAPACITY];
    long long raw_ts_[RAW_CAPACITY];
    int       head_;
    int       count_;

    float     dc_x_, dc_y_, dc_z_;         // EMA DC offset
    NotchState notch_state_x_, notch_state_y_, notch_state_z_;

    float     jitter_accum_;
    int       jitter_count_;
    long long last_ts_ns_;
    float     powerline_hz_;

    mutable std::mutex mutex_;
};

} // namespace vibescan
