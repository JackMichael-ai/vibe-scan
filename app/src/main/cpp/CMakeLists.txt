cmake_minimum_required(VERSION 3.18.1)
project(vibescan)

# ── kissfft (BSD licensed, tiny, runs on single-core 1GHz) ──────────────────
# Drop the kissfft source files into app/src/main/cpp/kissfft/
# Download from: https://github.com/mborgerding/kissfft (tag 131.1.0)
add_library(kissfft STATIC
    kissfft/kiss_fft.c
    kissfft/kiss_fft.h
)
target_include_directories(kissfft PUBLIC kissfft/)

# ── VibeScan engine ──────────────────────────────────────────────────────────
add_library(
    vibescan_engine
    SHARED
    vibescan_engine.cpp
    adaptive_filter.cpp
)

target_include_directories(vibescan_engine PRIVATE kissfft/)

target_link_libraries(
    vibescan_engine
    kissfft
    android
    log
    m          # math — sqrtf, cosf, etc.
)

# ── Optimisation flags ───────────────────────────────────────────────────────
# -O2 is safe for API 16. -O3 can cause issues on very old ARMv6 chips.
# -ffast-math: safe here — we don't need IEEE 754 strictness for vibration.
# -DNDEBUG:   strips all assert() calls from kissfft in release builds.
target_compile_options(vibescan_engine PRIVATE
    -O2
    -ffast-math
    -DNDEBUG
    -Wall
)

target_compile_options(kissfft PRIVATE
    -O2
    -ffast-math
    -DNDEBUG
)
