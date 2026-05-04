/**
 * VibeScan Adaptive Filter — Android Jitter Compensation
 * Northmark Intelligence
 *
 * THE PROBLEM:
 * Android is not a real-time OS. The SensorManager delivers samples with
 * irregular, jittery timestamps even when SENSOR_DELAY_FASTEST is set.
 * On a 2014 Galaxy S4:
 *   - Nominal interval: 1ms (1000Hz)
 *   - Actual interval: 2ms–18ms (wildly variable)
 *
 * This jitter is fatal for FFT accuracy. If the FFT assumes uniform 5ms
 * spacing but samples actually arrived at 2ms, 8ms, 3ms, 14ms... the
 * frequency bins are wrong, and BPFO peaks appear at incorrect frequencies.
 *
 * THE FIX:
 * 1. Timestamp-aware cubic spline resampling → uniform 5ms grid
 * 2. Exponential moving average (EMA) for DC offset removal
 * 3. Adaptive notch filter for powerline interference (50Hz / 60Hz)
 * 4. Jitter severity metric → reported to dashboard for mounting QA
 *
 * This runs in C++ because doing it in Kotlin/Java would add GC pauses
 * that make the jitter worse, not better.
 */

#include "adaptive_filter.h"
#include <cmath>
#include <cstring>
#include <algorithm>
#include <mutex>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace vibescan {

// ─── Constants ────────────────────────────────────────────────────────────────

static constexpr float TARGET_DT_S    = 1.0f / 1000.0f;  // 1ms — target sample interval (1000Hz)
static constexpr float NS_TO_S        = 1e-9f;
static constexpr float EMA_ALPHA      = 0.05f;            // DC removal: ~5Hz cutoff at 1000Hz
static constexpr float NOTCH_BW       = 2.0f;             // Hz — notch bandwidth
static constexpr int   INTERP_HISTORY = 4;                // cubic spline needs 4 points

// ─── AdaptiveFilter implementation ───────────────────────────────────────────

AdaptiveFilter::AdaptiveFilter() {
    reset();
}

void AdaptiveFilter::reset() {
    std::lock_guard<std::mutex> lock(mutex_);
    memset(raw_x_,  0, sizeof(raw_x_));
    memset(raw_y_,  0, sizeof(raw_y_));
    memset(raw_z_,  0, sizeof(raw_z_));
    memset(raw_ts_, 0, sizeof(raw_ts_));
    head_           = 0;
    count_          = 0;
    dc_x_ = dc_y_ = dc_z_ = 0.0f;
    notch_state_x_  = {};
    notch_state_y_  = {};
    notch_state_z_  = {};
    jitter_accum_   = 0.0f;
    jitter_count_   = 0;
    last_ts_ns_     = 0;
    powerline_hz_   = 50.0f;   // default — Kenya/UK/Europe; set to 60 for US
}

void AdaptiveFilter::setPowerlineHz(float hz) {
    powerline_hz_ = hz;
    // Recompute notch coefficients
    notch_state_x_ = {};
    notch_state_y_ = {};
    notch_state_z_ = {};
}

/**
 * Push a raw sample with its hardware timestamp (nanoseconds).
 * The filter accumulates raw samples and resamples them to a uniform grid
 * on demand when resampleUniform() is called.
 */
void AdaptiveFilter::pushRaw(float x, float y, float z, long long ts_ns) {
    std::lock_guard<std::mutex> lock(mutex_);
    // Track jitter
    if (last_ts_ns_ > 0) {
        float dt_s   = (ts_ns - last_ts_ns_) * NS_TO_S;
        float jitter = fabsf(dt_s - TARGET_DT_S);
        jitter_accum_ += jitter;
        jitter_count_++;
    }
    last_ts_ns_ = ts_ns;

    // Remove DC offset (EMA high-pass)
    dc_x_ = dc_x_ + EMA_ALPHA * (x - dc_x_);
    dc_y_ = dc_y_ + EMA_ALPHA * (y - dc_y_);
    dc_z_ = dc_z_ + EMA_ALPHA * (z - dc_z_);

    float xf = x - dc_x_;
    float yf = y - dc_y_;
    float zf = z - dc_z_;

    // Apply notch filter for powerline interference
    xf = applyNotch(xf, notch_state_x_);
    yf = applyNotch(yf, notch_state_y_);
    zf = applyNotch(zf, notch_state_z_);

    raw_x_[head_]  = xf;
    raw_y_[head_]  = yf;
    raw_z_[head_]  = zf;
    raw_ts_[head_] = ts_ns;
    head_          = (head_ + 1) % RAW_CAPACITY;
    if (count_ < RAW_CAPACITY) count_++;
}

/**
 * Catmull-Rom cubic spline interpolation.
 * Given four control points p0..p3 at equal spacing, interpolate at t ∈ [0,1]
 * between p1 and p2. Produces smooth, accurate intermediate values.
 */
static float catmullRom(float p0, float p1, float p2, float p3, float t) {
    float t2 = t * t;
    float t3 = t2 * t;
    return 0.5f * (
        (2.0f * p1) +
        (-p0 + p2) * t +
        (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3) * t2 +
        (-p0 + 3.0f * p1 - 3.0f * p2 + p3) * t3
    );
}

/**
 * Resample the accumulated raw samples onto a uniform TARGET_DT_S grid.
 * Output is written to out_x/y/z, returns number of uniform samples written.
 *
 * Uses Catmull-Rom spline — smooth, causal, no ringing.
 */
int AdaptiveFilter::resampleUniform(float* out_x, float* out_y, float* out_z,
                                     int max_out) const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (count_ < INTERP_HISTORY) return 0;

    // Build chronological view of raw buffer
    int n = std::min(count_, RAW_CAPACITY);
    long long ts_buf[RAW_CAPACITY];
    float     x_buf[RAW_CAPACITY], y_buf[RAW_CAPACITY], z_buf[RAW_CAPACITY];

    int start = (head_ - n + RAW_CAPACITY) % RAW_CAPACITY;
    for (int i = 0; i < n; i++) {
        int idx     = (start + i) % RAW_CAPACITY;
        ts_buf[i]   = raw_ts_[idx];
        x_buf[i]    = raw_x_[idx];
        y_buf[i]    = raw_y_[idx];
        z_buf[i]    = raw_z_[idx];
    }

    long long target_dt_ns = (long long)(TARGET_DT_S * 1e9f);
    long long t_end_ns   = ts_buf[n - 2]; // skip last (no p3)

    // FIX: Calculate start time to get the LATEST max_out samples.
    // This removes the massive lag when the buffer is full.
    long long t_start_ns = t_end_ns - (long long)((max_out - 1) * target_dt_ns);
    if (t_start_ns < ts_buf[1]) t_start_ns = ts_buf[1];

    int out_count = 0;
    long long t_ns = t_start_ns;

    while (t_ns <= t_end_ns && out_count < max_out) {
        // Find surrounding raw samples (binary search would be faster but n is small)
        int j = 1;
        while (j < n - 2 && ts_buf[j + 1] < t_ns) j++;

        if (j < 1 || j >= n - 1) { t_ns += target_dt_ns; continue; }

        float span = (float)(ts_buf[j + 1] - ts_buf[j]);
        float tt   = (span > 0) ? (float)(t_ns - ts_buf[j]) / span : 0.0f;
        tt = std::max(0.0f, std::min(1.0f, tt));

        out_x[out_count] = catmullRom(x_buf[j-1], x_buf[j], x_buf[j+1], x_buf[j+2], tt);
        out_y[out_count] = catmullRom(y_buf[j-1], y_buf[j], y_buf[j+1], y_buf[j+2], tt);
        out_z[out_count] = catmullRom(z_buf[j-1], z_buf[j], z_buf[j+1], z_buf[j+2], tt);

        out_count++;
        t_ns += target_dt_ns;
    }

    return out_count;
}

/**
 * Mean jitter in milliseconds since last reset.
 * Exposed to the dashboard as a mounting quality indicator:
 *   < 1ms  = excellent (rigid metal mount)
 *   1–3ms  = acceptable (magnetic mount)
 *   > 3ms  = poor (hand-held or loose)
 */
float AdaptiveFilter::meanJitterMs() const {
    if (jitter_count_ == 0) return 0.0f;
    return (jitter_accum_ / jitter_count_) * 1000.0f;
}

void AdaptiveFilter::resetJitterStats() {
    jitter_accum_ = 0.0f;
    jitter_count_ = 0;
}

float AdaptiveFilter::getActualRate() const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (count_ < 20) return 1000.0f;

    // Calculate rate over the last 100 samples or all if less
    int n = std::min(count_, 100);
    int last_idx = (head_ - 1 + RAW_CAPACITY) % RAW_CAPACITY;
    int first_idx = (head_ - n + RAW_CAPACITY) % RAW_CAPACITY;

    long long span = raw_ts_[last_idx] - raw_ts_[first_idx];
    if (span <= 0) return 1000.0f;

    float rate = (float)(n - 1) / (span * 1e-9f);
    // Sanity check: accelerometer usually runs between 50Hz and 1000Hz on most phones
    return (rate > 10.0f && rate < 2000.0f) ? rate : 1000.0f;
}

// ─── Notch filter (biquad IIR) ────────────────────────────────────────────────

/**
 * Second-order IIR notch filter.
 * Attenuates powerline frequency (50 or 60Hz) which bleeds into the
 * accelerometer on devices with poor shielding (common in 2012–2016 phones).
 *
 * Coefficients computed from bilinear transform of analog notch prototype.
 */
float AdaptiveFilter::applyNotch(float x, NotchState& state) {
    float fs  = 1.0f / TARGET_DT_S;
    float f0  = powerline_hz_;
    float bw  = NOTCH_BW;
    float w0  = 2.0f * M_PI * f0 / fs;
    float r   = 1.0f - M_PI * bw / fs;

    // Biquad direct form II
    float b0  =  1.0f;
    float b1  = -2.0f * cosf(w0);
    float b2  =  1.0f;
    float a1  = -2.0f * r * cosf(w0);
    float a2  =  r * r;

    float w   = x - a1 * state.w1 - a2 * state.w2;
    float y   = b0 * w + b1 * state.w1 + b2 * state.w2;
    state.w2  = state.w1;
    state.w1  = w;
    return y;
}

} // namespace vibescan
