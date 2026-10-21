#ifndef NATIVE_OPERATION_CONTROL_H
#define NATIVE_OPERATION_CONTROL_H

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

struct NativeOperationControl {
    const uint64_t id;
    std::atomic<bool> cancelled{false};
    std::atomic<int> progress_permille{0}; // 0 to 1000
    std::atomic<int64_t> completed_units{0};
    std::atomic<int64_t> total_units{0};

    mutable std::mutex stage_mutex;
    std::string current_stage;

    explicit NativeOperationControl(uint64_t op_id) : id(op_id) {}

    bool is_cancelled() const {
        return cancelled.load(std::memory_order_acquire);
    }

    void cancel() {
        cancelled.store(true, std::memory_order_release);
    }

    void set_progress(int permille, int64_t completed = 0, int64_t total = 0) {
        if (permille >= 0 && permille <= 1000) {
            progress_permille.store(permille, std::memory_order_release);
        }
        if (completed >= 0) {
            completed_units.store(completed, std::memory_order_release);
        }
        if (total >= 0) {
            total_units.store(total, std::memory_order_release);
        }
    }

    int get_progress() const {
        return progress_permille.load(std::memory_order_acquire);
    }

    void set_stage(const std::string & stage) {
        std::lock_guard<std::mutex> lock(stage_mutex);
        current_stage = stage;
    }

    std::string get_stage() const {
        std::lock_guard<std::mutex> lock(stage_mutex);
        return current_stage;
    }

    std::string get_snapshot_json() const {
        std::lock_guard<std::mutex> lock(stage_mutex);
        std::string s = "{";
        s += "\"id\":" + std::to_string(id) + ",";
        s += "\"cancelled\":" + std::string(cancelled.load(std::memory_order_acquire) ? "true" : "false") + ",";
        s += "\"progress_permille\":" + std::to_string(progress_permille.load(std::memory_order_acquire)) + ",";
        s += "\"completed_units\":" + std::to_string(completed_units.load(std::memory_order_acquire)) + ",";
        s += "\"total_units\":" + std::to_string(total_units.load(std::memory_order_acquire)) + ",";
        s += "\"stage\":\"" + current_stage + "\"";
        s += "}";
        return s;
    }
};

class NativeOperationControlRegistry {
private:
    std::mutex mutex_;
    std::unordered_map<uint64_t, std::shared_ptr<NativeOperationControl>> registry_;
    std::atomic<uint64_t> next_id_{1};

    NativeOperationControlRegistry() = default;

public:
    static NativeOperationControlRegistry & instance() {
        static NativeOperationControlRegistry reg;
        return reg;
    }

    uint64_t create() {
        uint64_t id = next_id_.fetch_add(1, std::memory_order_relaxed);
        auto control = std::make_shared<NativeOperationControl>(id);
        std::lock_guard<std::mutex> lock(mutex_);
        registry_[id] = control;
        return id;
    }

    std::shared_ptr<NativeOperationControl> get(uint64_t id) {
        if (id == 0) return nullptr;
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = registry_.find(id);
        if (it != registry_.end()) {
            return it->second;
        }
        return nullptr;
    }

    void cancel(uint64_t id) {
        if (id == 0) return;
        std::shared_ptr<NativeOperationControl> ctrl;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            auto it = registry_.find(id);
            if (it != registry_.end()) {
                ctrl = it->second;
            }
        }
        if (ctrl) {
            ctrl->cancel();
        }
    }

    void release(uint64_t id) {
        if (id == 0) return;
        std::lock_guard<std::mutex> lock(mutex_);
        registry_.erase(id);
    }

    void clear_all() {
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto & pair : registry_) {
            pair.second->cancel();
        }
        registry_.clear();
    }
};

#endif // NATIVE_OPERATION_CONTROL_H
