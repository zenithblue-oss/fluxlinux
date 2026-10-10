#!/bin/bash
# scripts/common/launch_qwen25.sh
# Launch Qwen2.5-1.5B-Instruct via llama-vulkan

handle_error() {
    echo ""
    echo "❌ FluxLinux Error: $1"
    echo "---------------------------------------------------"
    read -p "Press Enter to exit..."
    exit 1
}

MODEL_PATH="/root/models/Qwen2.5-1.5B-Instruct-Q4_0.gguf"

echo "FluxLinux: Running Qwen2.5-1.5B-Instruct..."
echo ""

# --- Check model ---
if [ ! -f "$MODEL_PATH" ]; then
    echo " [❌] Model not found: $MODEL_PATH"
    echo "      Install 'Qwen2.5-1.5B Model' from distro settings first."
    handle_error "Model not found"
fi
FILE_SIZE=$(stat -c%s "$MODEL_PATH" 2>/dev/null || echo "0")
if [ "$FILE_SIZE" -lt 900000000 ]; then
    echo " [❌] Model file appears incomplete ($FILE_SIZE bytes)."
    echo "      Re-install the model from distro settings."
    handle_error "Model incomplete"
fi
echo " [✅] Model found: $(du -h "$MODEL_PATH" | cut -f1)"

# --- Check llama.cpp ---
if command -v llama-vulkan &>/dev/null; then
    LAUNCHER="llama-vulkan llama-cli"
elif command -v llama-cli &>/dev/null; then
    LAUNCHER="llama-cli"
else
    echo " [❌] llama.cpp not found."
    echo "      Install 'Vulkan Llama.cpp' from distro settings first."
    handle_error "llama.cpp not installed"
fi
echo " [✅] llama.cpp found."

# --- Self-test helper ---
# Newer llama.cpp builds make llama-cli chat-only (it never exits on its own), so
# the one-shot self-tests use llama-completion with a raw ChatML prompt.
# The test asks a question with one deterministic answer (temp 0) so that neither
# a chatty-but-valid reply fails it nor corrupted output passes it by chance.
# The prompt is deliberately longer than 32 tokens: on Adreno/Turnip, llama.cpp's
# Vulkan batched matmul returns garbage for micro-batches over 32 tokens, so the
# test has to exercise the same -ub 32 chunking as the chat session below.
if command -v llama-completion &>/dev/null; then
    TEST_BIN="llama-completion"
else
    TEST_BIN="llama-cli"
fi
TEST_PROMPT=$'<|im_start|>system\nYou are Qwen, created by Alibaba Cloud. You are a helpful assistant.<|im_end|>\n<|im_start|>user\nWhat is 2+2? Answer with just the number.<|im_end|>\n<|im_start|>assistant\n'

run_self_test() {
    # $1 = optional wrapper (llama-vulkan), $2 = -ngl value
    # Prints only the model's reply: llama-vulkan echoes its own (multi-line) command
    # line first, so keep just the last non-empty line of output.
    $1 $TEST_BIN -m "$MODEL_PATH" -ngl "$2" -ub 32 -p "$TEST_PROMPT" -n 8 --temp 0 \
        -no-cnv --no-display-prompt </dev/null 2>/dev/null \
        | tr -cd '[:print:][:space:]' | sed 's/\[end of text\]//' \
        | awk 'NF { last = $0 } END { print last }'
}

self_test_passed() {
    echo "$1" | grep -qiE '^[[:space:]]*(4|four)\b'
}

# --- Test CPU first to verify model integrity ---
echo ""
echo "Testing model with CPU (verifying file integrity)..."
CPU_OUTPUT=$(run_self_test "" 0)
if self_test_passed "$CPU_OUTPUT"; then
    echo " [✅] CPU test passed. Model file is valid."
else
    echo " [❌] CPU test failed. Model output is corrupted."
    echo "      Output: $CPU_OUTPUT"
    echo ""
    echo "      The model file may be damaged. Please:"
    echo "      1. Delete the model: rm $MODEL_PATH"
    echo "      2. Re-install from distro settings"
    handle_error "Model corrupted"
fi

# --- Test GPU ---
echo ""
echo "Testing GPU inference..."
GPU_OUTPUT=$(run_self_test llama-vulkan 99)
if self_test_passed "$GPU_OUTPUT"; then
    echo " [✅] GPU test passed. Using GPU acceleration."
    GPU_LAYERS=99
else
    echo " [⚠️] GPU test failed (produced garbled output)."
    echo "      GPU output: $GPU_OUTPUT"
    echo ""
    echo "      This usually means:"
    echo "      - Turnip driver compute shaders have issues with this model"
    echo "      - The Vulkan backend doesn't fully support this quantization"
    echo ""
    echo "      Falling back to CPU mode (-ngl 0)."
    echo "      CPU will be slower but should work correctly."
    GPU_LAYERS=0
fi

# --- Launch ---
echo ""
echo "Starting Qwen2.5-1.5B ($([ "$GPU_LAYERS" -gt 0 ] && echo "GPU" || echo "CPU"))..."
echo "Type your prompt and press Enter. Ctrl+C to exit."
echo "-------------------------------------------"
echo ""

# -ub 32: on Adreno/Turnip, llama.cpp's Vulkan batched matmul returns garbage for
# micro-batches over 32 tokens, so any longer prompt or message has to be chunked.
# </dev/tty: the app runs this script as `... | base64 -d | bash`, so stdin is the
# script pipe; without reattaching the terminal, llama-cli hits EOF and spins on '>'.
exec llama-vulkan llama-cli -m "$MODEL_PATH" \
    -ngl $GPU_LAYERS \
    -ub 32 \
    -c 4096 \
    --temp 0.7 \
    -n 512 \
    --no-display-prompt \
    -p "You are Qwen, a helpful assistant. User: Hello! Assistant:" </dev/tty
