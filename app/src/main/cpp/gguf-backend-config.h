#pragma once

#include <vector>
#include <string>
#include <sstream>
#include <cstring>
#include <algorithm>

#include "llama.h"
#include "llama-context.h"
#include "llama-model.h"
#include "ggml.h"
#include "ggml-backend.h"

#ifdef GGML_USE_VULKAN
#include "ggml-vulkan.h"
#endif

// Backend Policy Version for trustworthy metadata and receipt validation
static constexpr int GGUF_BACKEND_POLICY_VERSION = 1;

// Stable lifetime empty device array for CPU execution.
// A non-null, empty device list disables GPU-device selection in this vendored llama.cpp version.
// devices = nullptr selects all available devices.
static ggml_backend_dev_t s_cpu_only_devices[] = { nullptr };

struct GgufBackendInspection {
    std::vector<std::string> compute_backend_names;
    size_t gpu_compute_buffer_bytes = 0;
    bool effective_offload_kqv = false;
    bool is_cpu_isolated = false;
    bool has_gpu_compute_backend = false;
    int backend_policy_version = GGUF_BACKEND_POLICY_VERSION;
};

// Configures llama_model_params and llama_context_params strictly for true CPU execution
inline void configure_cpu_backend_params(llama_model_params & mparams, llama_context_params & cparams) {
    mparams.devices = s_cpu_only_devices;
    mparams.n_gpu_layers = 0;
    mparams.split_mode = LLAMA_SPLIT_MODE_LAYER;
    cparams.offload_kqv = false;
}

// Retrieves the vendored Vulkan device if available
inline ggml_backend_dev_t get_vulkan_device_explicit() {
#ifdef GGML_USE_VULKAN
    ggml_backend_reg_t reg = ggml_backend_reg_by_name("Vulkan");
    if (!reg) {
        reg = ggml_backend_vk_reg();
    }
    if (reg && ggml_backend_reg_dev_count(reg) > 0) {
        return ggml_backend_reg_dev_get(reg, 0);
    }
#endif
    return nullptr;
}

// Configures llama_model_params and llama_context_params for Vulkan execution.
// gpu_devices_storage must have lifetime at least matching the model.
inline bool configure_vulkan_backend_params(
    llama_model_params & mparams,
    llama_context_params & cparams,
    std::vector<ggml_backend_dev_t> & gpu_devices_storage,
    int requested_gpu_layers = 99
) {
    ggml_backend_dev_t vk_dev = get_vulkan_device_explicit();
    if (!vk_dev) {
        return false;
    }
    gpu_devices_storage = { vk_dev, nullptr };
    mparams.devices = gpu_devices_storage.data();
    mparams.n_gpu_layers = (requested_gpu_layers > 0) ? requested_gpu_layers : 99;
    mparams.split_mode = LLAMA_SPLIT_MODE_LAYER;
    cparams.offload_kqv = true;
    return true;
}

// Inspects actual compute backends and buffer allocation from llama_context
inline GgufBackendInspection inspect_gguf_backend(const llama_context * ctx) {
    GgufBackendInspection result;
    if (!ctx) return result;

    result.effective_offload_kqv = ctx->cparams.offload_kqv;

    if (ctx->sched) {
        ggml_backend_sched_t sched = ctx->sched.get();
        int n_backends = ggml_backend_sched_get_n_backends(sched);
        for (int i = 0; i < n_backends; ++i) {
            ggml_backend_t b = ggml_backend_sched_get_backend(sched, i);
            if (!b) continue;
            const char * name = ggml_backend_name(b);
            std::string name_str = name ? name : "Unknown";
            result.compute_backend_names.push_back(name_str);

            size_t buf_size = ggml_backend_sched_get_buffer_size(sched, b);

            ggml_backend_dev_t dev = ggml_backend_get_device(b);
            enum ggml_backend_dev_type dtype = dev ? ggml_backend_dev_type(dev) : GGML_BACKEND_DEVICE_TYPE_CPU;

            bool is_gpu = (dtype == GGML_BACKEND_DEVICE_TYPE_GPU) ||
                          (name_str.find("Vulkan") != std::string::npos) ||
                          (name_str.find("vulkan") != std::string::npos);
            if (is_gpu) {
                result.has_gpu_compute_backend = true;
                result.gpu_compute_buffer_bytes += buf_size;
            }
        }
    }

    // Also check ctx->backends
    for (const auto & b_ptr : ctx->backends) {
        if (!b_ptr) continue;
        const char * name = ggml_backend_name(b_ptr.get());
        std::string name_str = name ? name : "";
        ggml_backend_dev_t dev = ggml_backend_get_device(b_ptr.get());
        enum ggml_backend_dev_type dtype = dev ? ggml_backend_dev_type(dev) : GGML_BACKEND_DEVICE_TYPE_CPU;
        if (dtype == GGML_BACKEND_DEVICE_TYPE_GPU || name_str.find("Vulkan") != std::string::npos) {
            result.has_gpu_compute_backend = true;
        }
    }

    // Check model devices
    const auto & model_devices = ctx->model.devices;
    if (!model_devices.empty()) {
        result.has_gpu_compute_backend = true;
    }

    // CPU isolation invariant:
    // No GPU compute backend attached, 0 GPU compute buffer bytes, no KQV offload, no model GPU devices.
    result.is_cpu_isolated = !result.has_gpu_compute_backend &&
                             (result.gpu_compute_buffer_bytes == 0) &&
                             !result.effective_offload_kqv &&
                             model_devices.empty();

    return result;
}

inline bool verify_cpu_backend_isolation(const llama_context * ctx, GgufBackendInspection * out_inspection = nullptr) {
    if (!ctx) return false;
    GgufBackendInspection insp = inspect_gguf_backend(ctx);
    if (out_inspection) {
        *out_inspection = insp;
    }
    return insp.is_cpu_isolated;
}
