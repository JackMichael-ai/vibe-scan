/**
 * VibeScan Engine v4.1 — Production Industrial Instrument + Mounting Detection
 * Northmark Intelligence
 *
 * Features: Per-axis FFT, parabolic interpolation, configurable BPFO/BPFI,
 *           wider bearing bands, mounting detection with quality score.
 */

#include <jni.h>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <pthread.h>
#include <sched.h>
#include <sys/resource.h>
#include <android/log.h>
#include <mutex>
#include "kissfft/kiss_fft.h"
#include "adaptive_filter.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace vibescan {

// ─── Constants ────────────────────────────────────────────────────────────────
static constexpr int NOMINAL_RATE = 44100;
static constexpr int WINDOW_SIZE = 4096;
static constexpr int RING_CAPACITY = 8192;
static constexpr int BASELINE_FRAMES = 60;
static constexpr int SPECTRUM_HALF = WINDOW_SIZE / 2;
static constexpr float AUDIO_MAX_VALUE = 32768.0f;
static constexpr float NOISE_FLOOR_PCM = 0.00001f;
static constexpr float BAND_WIDTH_HZ = 50.0f;

static constexpr float G_MS2 = 9.80665f;
static constexpr float GRAVITY_ALPHA = 0.8f;

// ─── Mounting Detection Constants ─────────────────────────────────────────────
static constexpr int MOUNTING_HISTORY = 30;           // frames for stability stats
[[maybe_unused]] static constexpr float GOOD_RMS_MIN_MS2 = 0.15f;      // below this → probably not on machine
[[maybe_unused]] static constexpr float POOR_RMS_MAX_MS2 = 0.08f;      // very low = table/loose
[[maybe_unused]] static constexpr float MIN_PLAUSIBLE_1X = 8.0f;       // Hz (480 RPM min reasonable)

// ─── ISO 10816-3 ──────────────────────────────────────────────────────────────
struct ISO10816Class { float a, b, c; };
static const ISO10816Class ISO_CLASSES[4] = {
    {0.71f, 1.8f, 4.5f}, {1.12f, 2.8f, 7.1f},
    {1.8f, 4.5f, 11.2f}, {2.8f, 7.1f, 18.0f}
};

// ─── Data Structures ──────────────────────────────────────────────────────────
struct AudioFrame {
    float sample;
};

struct SensorFrame {
    float x, y, z;
    long long timestamp_ns;
};

struct AxisFeatures {
    float rms_val = 0.f;
    float crest = 1.0f;
    float dominant_hz = 0.f;
    float kurtosis = 3.f;
    float bpfo_energy = 0.f;
    float bpfi_energy = 0.f;
    float bpfo_h2_energy = 0.f;
};

struct Features {
    float rms_raw = 0.f;
    float rms_val = 0.f;
    float crest = 0.f;
    float kurtosis = 3.f;
    float dominant_hz = 0.f;
    float bpfo_energy = 0.f;
    float bpfi_energy = 0.f;
    float bpfo_harmonic_ratio = 0.f;
    float harmonic_confidence = 0.f;
    float actual_sample_rate = (float)NOMINAL_RATE;
    float signal_confidence = 0.f;
    int worst_axis = 0;
    AxisFeatures axis[3]; // X, Y, Z

    struct MountingStats {
        float quality = 0.f;        // 0-100
        bool is_mounted = false;
        int status_level = 0;       // 0=poor/red, 1=fair/yellow, 2=good, 3=excellent/green
        float signal_stability = 0.f;
        float rms_stability = 0.f;
        float freq_consistency = 0.f;
        float signal_strength = 0.f;
    } mounting;
};

struct Baseline {
    float rms_mean = 0.f, rms_std = 0.001f;
    float kurtosis_mean = 3.f, kurtosis_std = 0.001f;
    bool ready = false;
    int frame_count = 0;
    float rms_accum[BASELINE_FRAMES] = {};
    float kurtosis_accum[BASELINE_FRAMES] = {};
};

// ─── Ring Buffer & Helpers ────────────────────────────────────────────────────
class RingBuffer {
public:
    void push(float s) {
        std::lock_guard<std::mutex> lock(mtx);
        buf[head] = {s};
        head = (head + 1) % RING_CAPACITY;
        if (count < RING_CAPACITY) count++;
    }
    int getCount() const {
        // Simple atomic-like read if we don't want to lock here,
        // but for safety with head/count consistency, we lock.
        return count;
    }
    void readLast(AudioFrame* out, int n) const {
        std::lock_guard<std::mutex> lock(mtx);
        int actual = std::min(n, count);
        int start = (head - actual + RING_CAPACITY) % RING_CAPACITY;
        for (int i = 0; i < actual; i++)
            out[i] = buf[(start + i) % RING_CAPACITY];
    }
    void reset() {
        std::lock_guard<std::mutex> lock(mtx);
        head = 0;
        count = 0;
    }
private:
    AudioFrame buf[8192] = {}; // Explicit size for safety
    int head = 0, count = 0;
    mutable std::mutex mtx;
};

// ─── Static State ─────────────────────────────────────────────────────────────
static RingBuffer g_ring;
static Baseline g_baseline;
static kiss_fft_cfg g_fft_cfg = nullptr;
static float g_hann[WINDOW_SIZE];
static bool g_hann_ready = false;
static int g_machine_class = 0;

static float g_grav_x = 0.f, g_grav_y = 0.f, g_grav_z = -G_MS2;
static AdaptiveFilter g_filter;
static AdaptiveFilter g_mag_filter;
static float g_last_spectrum[4][SPECTRUM_HALF] = {}; // 0=X, 1=Y, 2=Z, 3=Mag
static float g_peak_spectrum[4][SPECTRUM_HALF] = {}; // Peak Hold for Bump Tests
static float g_audio_spectrum[SPECTRUM_HALF] = {};
static float g_audio_peak_spectrum[SPECTRUM_HALF] = {};

// Mounting history
static float g_rms_history[MOUNTING_HISTORY] = {};
static float g_domhz_history[MOUNTING_HISTORY] = {};
static int g_mount_idx = 0;
static int g_mount_count = 0;
static int g_worst_axis = 0;

// ─── Thread Priority ──────────────────────────────────────────────────────────
static void set_realtime_priority() {
    struct sched_param p = {1};
    if (pthread_setschedparam(pthread_self(), SCHED_FIFO, &p) != 0)
        setpriority(PRIO_PROCESS, 0, -10);
}

// ─── Hann Window ──────────────────────────────────────────────────────────────
static void init_hann() {
    if (g_hann_ready) return;
    for (int i = 0; i < WINDOW_SIZE; i++)
        g_hann[i] = 0.5f * (1.f - cosf(2.f * (float)M_PI * (float)i / (float)(WINDOW_SIZE - 1)));
    g_hann_ready = true;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────
static inline float mag3(float x, float y, float z) {
    return sqrtf(x*x + y*y + z*z);
}

[[maybe_unused]] static void remove_gravity(float rx, float ry, float rz, float& lx, float& ly, float& lz) {
    g_grav_x = GRAVITY_ALPHA * g_grav_x + (1.f - GRAVITY_ALPHA) * rx;
    g_grav_y = GRAVITY_ALPHA * g_grav_y + (1.f - GRAVITY_ALPHA) * ry;
    g_grav_z = GRAVITY_ALPHA * g_grav_z + (1.f - GRAVITY_ALPHA) * rz;
    lx = rx - g_grav_x; ly = ry - g_grav_y; lz = rz - g_grav_z;
}

[[maybe_unused]] static float compute_actual_rate(const SensorFrame* frames, int n) {
    if (n < 10) return (float)NOMINAL_RATE;
    long long span = frames[n-1].timestamp_ns - frames[0].timestamp_ns;
    if (span <= 0) return (float)NOMINAL_RATE;
    float rate = (float)(n - 1) / ((float)span * 1e-9f);
    return (rate < 50.f || rate > 800.f) ? (float)NOMINAL_RATE : rate;
}

// ─── FFT ──────────────────────────────────────────────────────────────────────
static void ensure_fft() {
    if (!g_fft_cfg) g_fft_cfg = kiss_fft_alloc(WINDOW_SIZE, 0, nullptr, nullptr);
}

static void run_fft(const float* signal, float* spectrum, float actual_hz, bool integrate_to_velocity) {
    ensure_fft(); init_hann();
    float raw[WINDOW_SIZE];
    float mean = 0.f;
    for (int i = 0; i < WINDOW_SIZE; i++) { raw[i] = signal[i]; mean += raw[i]; }
    mean /= WINDOW_SIZE;
    for (int i = 0; i < WINDOW_SIZE; i++) raw[i] -= mean;

    kiss_fft_cpx in_buf[WINDOW_SIZE], out_buf[WINDOW_SIZE];
    for (int i = 0; i < WINDOW_SIZE; i++) {
        in_buf[i].r = raw[i] * g_hann[i]; in_buf[i].i = 0.f;
    }

    kiss_fft(g_fft_cfg, in_buf, out_buf);

    float bin_hz = actual_hz / WINDOW_SIZE;
    for (int i = 0; i < SPECTRUM_HALF; i++) {
        float re = out_buf[i].r, im = out_buf[i].i;
        float mag = sqrtf(re * re + im * im) / (float)WINDOW_SIZE;

        if (integrate_to_velocity && i > 0) {
            // v = a / omega (where omega = 2 * pi * f)
            // conversion to mm/s: mag * 1000 / (2 * pi * frequency)
            float freq = (float)i * bin_hz;
            if (freq > 2.0f) { // High-pass at 2Hz
                mag = (mag * 1000.0f) / (2.0f * (float)M_PI * freq);
            } else {
                mag = 0.0f;
            }
        }
        spectrum[i] = mag;
    }
}

static void update_mounting_history(float rms, float dom_hz) {
    g_rms_history[g_mount_idx] = rms;
    g_domhz_history[g_mount_idx] = dom_hz;
    g_mount_idx = (g_mount_idx + 1) % MOUNTING_HISTORY;
    if (g_mount_count < MOUNTING_HISTORY) g_mount_count++;
}

static float get_dominant_hz_interp(const float* spectrum, float actual_hz, int& peak_bin_out) {
    float bin_hz = actual_hz / WINDOW_SIZE;
    int skip = (int)(2.0f / bin_hz) + 1;
    int peak_bin = skip;
    for (int i = skip + 1; i < SPECTRUM_HALF; i++)
        if (spectrum[i] > spectrum[peak_bin]) peak_bin = i;

    peak_bin_out = peak_bin;
    if (spectrum[peak_bin] < 0.001f) return 0.f;

    // Parabolic interpolation for sub-bin precision
    if (peak_bin > 0 && peak_bin < SPECTRUM_HALF - 1) {
        float alpha = spectrum[peak_bin - 1], beta = spectrum[peak_bin], gamma = spectrum[peak_bin + 1];
        float p = 0.5f * (alpha - gamma) / (alpha - 2.f * beta + gamma);
        return ((float)peak_bin + p) * bin_hz;
    }
    return (float)peak_bin * bin_hz;
}

static float get_band_energy(const float* spectrum, float target_hz, float actual_hz, float width_hz = BAND_WIDTH_HZ) {
    float bin_hz = actual_hz / WINDOW_SIZE;
    int lo = std::max(1, (int)((target_hz - width_hz/2.f) / bin_hz));
    int hi = std::min(SPECTRUM_HALF - 1, (int)((target_hz + width_hz/2.f) / bin_hz));
    float sum_sq = 0.f;
    for (int i = lo; i <= hi; i++) sum_sq += spectrum[i] * spectrum[i];
    return sqrtf(sum_sq);
}

static Features::MountingStats compute_mounting_quality(const Features& feat, float shaft_hz) {
    Features::MountingStats m;
    if (feat.rms_raw < NOISE_FLOOR_PCM * 2.f) {
        m.quality = 5.f;
        m.is_mounted = false;
        return m;
    }

    // 1. RMS level check (Signal strength)
    float rms_score = 0.f;
    // Lowered thresholds for stationary detection sensitivity
    if (feat.rms_raw < 0.005f) rms_score = 10.f;
    else if (feat.rms_raw < 0.02f) rms_score = 40.f;
    else if (feat.rms_raw < 0.05f) rms_score = 80.f;
    else rms_score = 90.f + std::min(10.f, (feat.rms_raw - 0.05f) * 20.f);

    // 2. Frequency consistency (if we have history)
    float freq_score = 0.f; // Default to 0, only increase if we see stable peaks
    if (g_mount_count > 8) {
        float sum = 0.f, sumsq = 0.f;
        int valid = 0;
        for (int i = 0; i < g_mount_count; i++) {
            if (g_domhz_history[i] > 10.f) {
                sum += g_domhz_history[i];
                sumsq += g_domhz_history[i] * g_domhz_history[i];
                valid++;
            }
        }
        if (valid > 5) {
            float mean = sum / (float)valid;
            float var = (sumsq / (float)valid) - mean * mean;
            float jitter = sqrtf(var);
            freq_score = 100.f - std::min(80.f, jitter * 5.f);
        }
    }

    // Combine
    // If signal is very weak, significantly penalize quality
    if (feat.rms_raw < 0.005f) {
        m.quality = rms_score * 0.2f;
    } else {
        m.quality = (rms_score * 0.4f + freq_score * 0.6f);
    }
    m.quality = std::max(5.f, std::min(100.f, m.quality));

    m.is_mounted = m.quality >= 40.f;
    m.status_level = m.quality > 80.f ? 3 : m.quality > 60.f ? 2 : m.quality > 40.f ? 1 : 0;

    m.signal_stability = freq_score;
    m.rms_stability = rms_score;
    m.freq_consistency = freq_score;
    m.signal_strength = rms_score;

    return m;
}

static char iso_zone(float rms_mms, int cls) {
    cls = std::max(0, std::min(3, cls));
    const ISO10816Class& c = ISO_CLASSES[cls];
    if (rms_mms <= c.a) return 'A';
    if (rms_mms <= c.b) return 'B';
    if (rms_mms <= c.c) return 'C';
    return 'D';
}

static Features extract_features(const AudioFrame* frames, int n, float shaft_rpm, float min_hz, float max_hz, float bpfo_f, float bpfi_f) {
    Features f = {};
    f.actual_sample_rate = g_filter.getActualRate();
    float shaft_hz = shaft_rpm / 60.f;
    __android_log_print(ANDROID_LOG_DEBUG, "VibeScan-JNI", "extract_features: shaft_hz=%.2f, actual_rate=%.2f", shaft_hz, f.actual_sample_rate);

    float res_x[WINDOW_SIZE] = {0}, res_y[WINDOW_SIZE] = {0}, res_z[WINDOW_SIZE] = {0};
    int count = g_filter.resampleUniform(res_x, res_y, res_z, WINDOW_SIZE);

    // Audio Analysis (Mic) — ALWAYS PROCESS THIS
    float audio_sig[WINDOW_SIZE] = {0};
    for (int i = 0; i < WINDOW_SIZE; i++) {
        audio_sig[i] = (i < n) ? frames[i].sample : 0.0f;
    }

    float audio_spectrum[SPECTRUM_HALF] = {0};
    run_fft(audio_sig, audio_spectrum, (float)NOMINAL_RATE, false); // 44.1kHz mic
    memcpy(g_audio_spectrum, audio_spectrum, sizeof(audio_spectrum));
    for (int i=0; i<SPECTRUM_HALF; i++) {
        if (audio_spectrum[i] > g_audio_peak_spectrum[i]) g_audio_peak_spectrum[i] = audio_spectrum[i];
    }

    int audio_peak_bin;
    float audio_dom = get_dominant_hz_interp(audio_spectrum, (float)NOMINAL_RATE, audio_peak_bin);
    if (audio_dom > 0) {
        __android_log_print(ANDROID_LOG_DEBUG, "VibeScan-JNI", "Audio analysis: dominant frequency at %.1f Hz", audio_dom);
    }

    // Constant rate for resampled vibration data (1000Hz to support up to 500Hz Nyquist)
    const float VIBRATION_RATE = 1000.0f;

    for (int a = 0; a < 3; a++) {
        float* sig = (a == 0) ? res_x : (a == 1) ? res_y : res_z;
        float spectrum[SPECTRUM_HALF] = {0};
        run_fft(sig, spectrum, VIBRATION_RATE, true); // Integrate to velocity (mm/s)

        // Store and Update Peak Hold
        memcpy(g_last_spectrum[a], spectrum, sizeof(spectrum));
        for (int i=0; i<SPECTRUM_HALF; i++) {
            if (spectrum[i] > g_peak_spectrum[a][i]) g_peak_spectrum[a][i] = spectrum[i];
        }

        const float* active_spec = g_last_spectrum[a];
        float pwr_sq = 0.f;
        for(int i=1; i<SPECTRUM_HALF; i++) {
            pwr_sq += active_spec[i]*active_spec[i];
        }
        f.axis[a].rms_val = sqrtf(pwr_sq / (float)SPECTRUM_HALF);
        if (a == 2) {
            float rms_display = f.axis[a].rms_val;
            // STATIONARY DETECTION: If device is stationary, RMS will be very low (noise).
            // A real machine typically shows > 0.05 mm/s.
            __android_log_print(ANDROID_LOG_DEBUG, "VibeScan-JNI", "Z-axis RMS: %.4f (Stationary Threshold: 0.02)", rms_display);
        }

        int pb;
        f.axis[a].dominant_hz = get_dominant_hz_interp(active_spec, VIBRATION_RATE, pb);
        if (a == 2) {
            __android_log_print(ANDROID_LOG_DEBUG, "VibeScan-JNI", "Z-axis Peak: %.2f Hz (val: %.4f)", f.axis[a].dominant_hz, active_spec[pb]);
        }

        float mean = 0.f; for(int i=0; i<count; i++) mean += sig[i]; mean /= (float)std::max(1, count);
        float var = 0.f, k4 = 0.f;
        for(int i=0; i<count; i++) { float d = sig[i]-mean; float d2 = d*d; var += d2; k4 += d2*d2; }
        var /= (float)std::max(1, count); f.axis[a].kurtosis = (var > 1e-12f) ? (k4/(float)std::max(1, count))/(var*var) : 3.f;

        // --- Crest Factor Calculation (Peak / RMS) ---
        float peak = 0.f;
        for (int i = 0; i < count; i++) {
            float abs_val = fabsf(sig[i] - mean);
            if (abs_val > peak) peak = abs_val;
        }
        float axis_rms = sqrtf(var);
        f.axis[a].crest = (axis_rms > 1e-6f) ? (peak / axis_rms) : 1.0f;

        f.axis[a].bpfo_energy = get_band_energy(active_spec, shaft_hz * bpfo_f, VIBRATION_RATE);
        f.axis[a].bpfi_energy = get_band_energy(active_spec, shaft_hz * bpfi_f, VIBRATION_RATE);
        f.axis[a].bpfo_h2_energy = get_band_energy(active_spec, shaft_hz * bpfo_f * 2.f, VIBRATION_RATE);
    }

    f.rms_raw = mag3(f.axis[0].rms_val, f.axis[1].rms_val, f.axis[2].rms_val);

    // --- Magnetometer (EMF) Analysis ---
    float mag_x[WINDOW_SIZE] = {0}, mag_y[WINDOW_SIZE] = {0}, mag_z[WINDOW_SIZE] = {0};
    int mag_count = g_mag_filter.resampleUniform(mag_x, mag_y, mag_z, WINDOW_SIZE);
    float mag_combined[WINDOW_SIZE] = {0};
    for(int i=0; i<WINDOW_SIZE; i++) mag_combined[i] = mag3(mag_x[i], mag_y[i], mag_z[i]);

    float mag_spec[SPECTRUM_HALF] = {0};
    run_fft(mag_combined, mag_spec, VIBRATION_RATE, false); // No integration for EMF
    memcpy(g_last_spectrum[3], mag_spec, sizeof(mag_spec));
    for (int i=0; i<SPECTRUM_HALF; i++) {
        if (mag_spec[i] > g_peak_spectrum[3][i]) g_peak_spectrum[3][i] = mag_spec[i];
    }

    if (f.rms_raw < NOISE_FLOOR_PCM) {
        // Log low signal to help debugging stationary devices
        if (f.rms_raw > 0) {
            __android_log_print(ANDROID_LOG_VERBOSE, "VibeScan-JNI", "Signal below noise floor: %.6f", f.rms_raw);
        }
        f.kurtosis = 3.f; f.signal_confidence = 0.f; return f;
    }

    f.signal_confidence = std::max(0.f, std::min(100.f, f.rms_raw * 500.f));

    int worst = 0;
    for (int a = 1; a < 3; a++) if (f.axis[a].rms_val > f.axis[worst].rms_val) worst = a;
    f.worst_axis = worst;
    g_worst_axis = worst;

    f.rms_val = f.axis[worst].rms_val;
    f.dominant_hz = f.axis[worst].dominant_hz;
    f.kurtosis = f.axis[worst].kurtosis;
    f.crest = f.axis[worst].crest;
    f.bpfo_energy = f.axis[worst].bpfo_energy;
    f.bpfi_energy = f.axis[worst].bpfi_energy;
    f.bpfo_harmonic_ratio = (f.axis[worst].bpfo_energy > 0.001f) ? f.axis[worst].bpfo_h2_energy / f.axis[worst].bpfo_energy : 0.f;

    // Harmonic confidence (1X, 2X, 3X)
    int visible = 0;
    float h1 = get_band_energy(g_last_spectrum[worst], shaft_hz, VIBRATION_RATE);
    float h2 = get_band_energy(g_last_spectrum[worst], shaft_hz * 2.0f, VIBRATION_RATE);
    float h3 = get_band_energy(g_last_spectrum[worst], shaft_hz * 3.0f, VIBRATION_RATE);

    float floor = f.rms_val * 0.15f;
    if (h1 > floor) visible++;
    if (h2 > floor) visible++;
    if (h3 > floor) visible++;

    f.harmonic_confidence = ((float)visible / 3.0f) * 100.0f;

    update_mounting_history(f.rms_raw, f.dominant_hz);
    f.mounting = compute_mounting_quality(f, shaft_hz);

    return f;
}

static void update_baseline(const Features& feat) {
    if (g_baseline.ready || feat.rms_raw < NOISE_FLOOR_PCM) return;
    int idx = g_baseline.frame_count % BASELINE_FRAMES;
    g_baseline.rms_accum[idx] = feat.rms_val;
    g_baseline.kurtosis_accum[idx] = feat.kurtosis;
    g_baseline.frame_count++;
    if (g_baseline.frame_count >= BASELINE_FRAMES) {
        float rs = 0.f, ks = 0.f;
        for(int i=0; i<BASELINE_FRAMES; i++) { rs += g_baseline.rms_accum[i]; ks += g_baseline.kurtosis_accum[i]; }
        g_baseline.rms_mean = rs/BASELINE_FRAMES; g_baseline.kurtosis_mean = ks/BASELINE_FRAMES;
        g_baseline.ready = true;
    }
}

// ─── Main JNI Entry Points ────────────────────────────────────────────────────
extern "C" {

JNIEXPORT void JNICALL Java_com_northmark_vibescan_engine_VibeScanEngine_nativeSetThreadPriority(JNIEnv*, jobject) {
    set_realtime_priority();
}

JNIEXPORT void JNICALL Java_com_northmark_vibescan_engine_VibeScanEngine_nativePushAudio(
        JNIEnv* env, jobject, jshortArray data, jint len) {
    jshort* buf = env->GetShortArrayElements(data, nullptr);
    for (int i = 0; i < len; i++) {
        g_ring.push((float)buf[i] / AUDIO_MAX_VALUE);
    }
    env->ReleaseShortArrayElements(data, buf, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_com_northmark_vibescan_engine_VibeScanEngine_nativePushSensor(
        JNIEnv*, jobject, jfloat x, jfloat y, jfloat z, jlong ts_ns) {
    static int push_count = 0;
    if (++push_count % 200 == 0) {
        __android_log_print(ANDROID_LOG_DEBUG, "VibeScan-JNI", "Pushing sensor data: sample %d", push_count);
    }
    g_filter.pushRaw(x, y, z, ts_ns);
}

JNIEXPORT void JNICALL Java_com_northmark_vibescan_engine_VibeScanEngine_nativePushMagnet(
        JNIEnv*, jobject, jfloat x, jfloat y, jfloat z, jlong ts_ns) {
    g_mag_filter.pushRaw(x, y, z, ts_ns);
}

JNIEXPORT jfloatArray JNICALL Java_com_northmark_vibescan_engine_VibeScanEngine_nativeAnalyse(
        JNIEnv* env, jobject, jfloat shaft_rpm, jfloat min_hz, jfloat max_hz,
        jfloat bpfo_factor, jfloat bpfi_factor) {

    __android_log_print(ANDROID_LOG_DEBUG, "VibeScan-JNI", "Starting analysis...");
    AudioFrame frames[WINDOW_SIZE];
    int n = g_ring.getCount();
    g_ring.readLast(frames, std::min(n, WINDOW_SIZE));

    auto f = extract_features(frames, std::min(n, WINDOW_SIZE), shaft_rpm, min_hz, max_hz, bpfo_factor, bpfi_factor);
    update_baseline(f);

    __android_log_print(ANDROID_LOG_DEBUG, "VibeScan-JNI",
        "Diag: rms=%.4f, domHz=%.2f, conf=%.1f", f.rms_val, f.dominant_hz, f.signal_confidence);

    float z_rms = g_baseline.ready ? (f.rms_val - g_baseline.rms_mean)/0.05f : 0.f;
    int health = (int)std::max(0.f, std::min(100.f, 100.f - z_rms*10.f));

    int fault = 0;
    if (f.signal_confidence > 10.f) {
        if (health < 30) fault = 5;
        else if (f.bpfo_energy > f.bpfi_energy*1.5f) fault = 2;
        else if (f.bpfi_energy > f.bpfo_energy*1.5f) fault = 3;
        else if (f.kurtosis > 5.f) fault = 1;
        else if (health < 70) fault = 4;
    }

    float r[29] = {
        (float)health, (float)fault, f.rms_raw, f.rms_val, f.kurtosis, f.crest, f.dominant_hz,
        f.bpfo_energy, f.bpfi_energy, f.actual_sample_rate, f.signal_confidence,
        g_baseline.ready ? 1.f : 0.f, (float)g_baseline.frame_count * 100.0f / (float)BASELINE_FRAMES, (float)iso_zone(f.rms_val, g_machine_class),
        f.axis[0].rms_val, f.axis[1].rms_val, f.axis[2].rms_val,
        f.axis[0].dominant_hz, f.axis[1].dominant_hz, f.axis[2].dominant_hz,
        f.axis[0].kurtosis, f.axis[1].kurtosis, f.axis[2].kurtosis,
        f.bpfo_harmonic_ratio, (float)f.worst_axis, f.harmonic_confidence,
        f.mounting.quality, f.mounting.is_mounted?1.f:0.f, (float)f.mounting.status_level
    };

    jfloatArray arr = env->NewFloatArray(29);
    env->SetFloatArrayRegion(arr, 0, 29, r);
    return arr;
}

JNIEXPORT jfloatArray JNICALL Java_com_northmark_vibescan_engine_VibeScanEngine_nativeGetSpectrum(JNIEnv* env, jobject) {
    jfloatArray arr = env->NewFloatArray(SPECTRUM_HALF);
    env->SetFloatArrayRegion(arr, 0, SPECTRUM_HALF, g_last_spectrum[g_worst_axis]);
    return arr;
}

JNIEXPORT jfloatArray JNICALL Java_com_northmark_vibescan_engine_VibeScanEngine_nativeGetAxisSpectrum(JNIEnv* env, jobject, jint axis) {
    jfloatArray arr = env->NewFloatArray(SPECTRUM_HALF);
    if (axis == 4) { // Audio
        env->SetFloatArrayRegion(arr, 0, SPECTRUM_HALF, g_audio_spectrum);
    } else {
        axis = std::max(0, std::min(3, axis)); // 0-2: Accel, 3: Mag
        env->SetFloatArrayRegion(arr, 0, SPECTRUM_HALF, g_last_spectrum[axis]);
    }
    return arr;
}

JNIEXPORT jfloatArray JNICALL Java_com_northmark_vibescan_engine_VibeScanEngine_nativeGetAudioSpectrum(JNIEnv* env, jobject) {
    jfloatArray arr = env->NewFloatArray(SPECTRUM_HALF);
    env->SetFloatArrayRegion(arr, 0, SPECTRUM_HALF, g_audio_spectrum);
    return arr;
}

JNIEXPORT jfloatArray JNICALL Java_com_northmark_vibescan_engine_VibeScanEngine_nativeGetPeakSpectrum(JNIEnv* env, jobject, jint axis) {
    jfloatArray arr = env->NewFloatArray(SPECTRUM_HALF);
    if (axis == 4) { // Audio
        env->SetFloatArrayRegion(arr, 0, SPECTRUM_HALF, g_audio_peak_spectrum);
    } else if (axis == 3) { // Mag
        env->SetFloatArrayRegion(arr, 0, SPECTRUM_HALF, g_peak_spectrum[3]);
    } else {
        axis = std::max(0, std::min(2, axis));
        env->SetFloatArrayRegion(arr, 0, SPECTRUM_HALF, g_peak_spectrum[axis]);
    }
    return arr;
}

JNIEXPORT void JNICALL Java_com_northmark_vibescan_engine_VibeScanEngine_nativeResetPeakHold(JNIEnv*, jobject) {
    memset(g_peak_spectrum, 0, sizeof(g_peak_spectrum));
    memset(g_audio_peak_spectrum, 0, sizeof(g_audio_peak_spectrum));
}

JNIEXPORT void JNICALL Java_com_northmark_vibescan_engine_VibeScanEngine_nativeSetMachineClass(JNIEnv*, jobject, jint cls) {
    g_machine_class = cls;
}

JNIEXPORT void JNICALL Java_com_northmark_vibescan_engine_VibeScanEngine_nativeResetBaseline(JNIEnv*, jobject) {
    g_baseline = {};
}

JNIEXPORT void JNICALL Java_com_northmark_vibescan_engine_VibeScanEngine_nativeReset(JNIEnv*, jobject) {
    g_ring.reset();
    g_baseline = {};
    g_mount_count = 0;
    g_filter.reset();
}

} // extern "C"
} // namespace vibescan
