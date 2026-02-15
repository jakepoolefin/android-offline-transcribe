#!/usr/bin/env bash
# Run end-to-end throughput evaluation for Android and update README charts/tables.
#
# Usage:
#   scripts/run-inference-benchmarks.sh
#
# Env:
#   TARGET_SECONDS (default: 30)  -> length of generated English fixture
#   RUN_ANDROID (default: 1)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

TARGET_SECONDS="${TARGET_SECONDS:-30}"
RUN_ANDROID="${RUN_ANDROID:-1}"

AUDIO_FIXTURE="$PROJECT_DIR/artifacts/benchmarks/long_en_eval.wav"

echo "=== Inference Benchmark Run ==="
echo "Project:        $PROJECT_DIR"
echo "Target seconds: $TARGET_SECONDS"
echo "Run Android:    $RUN_ANDROID"
echo ""

TARGET_SECONDS="$TARGET_SECONDS" "$SCRIPT_DIR/prepare-long-eval-audio.sh"

if [ "$RUN_ANDROID" = "1" ]; then
    echo ""
    echo "--- Running Android E2E per-model benchmark ---"
    EVAL_WAV_PATH="$AUDIO_FIXTURE" "$SCRIPT_DIR/android-e2e-test.sh"
fi

echo ""
echo "--- Generating charts + README benchmark section ---"
python3 "$SCRIPT_DIR/generate-inference-report.py" \
    --audio "$AUDIO_FIXTURE" \
    --update-readme

echo ""
echo "Benchmark run completed."
