LOCAL_PATH := $(call my-dir)
MAIN_LOCAL_PATH := $(LOCAL_PATH)

include $(CLEAR_VARS)
LOCAL_MODULE := libdobby
LOCAL_SRC_FILES := Hook/Dobby/$(TARGET_ARCH_ABI)/libdobby.a
include $(PREBUILT_STATIC_LIBRARY)

# ========== Main Shared Library (blackbox) ==========
include $(CLEAR_VARS)

# --- Module Name ---
LOCAL_MODULE := id1z5u62rg48rrchhw3

# -------- C FLAGS (SAFE) --------
LOCAL_CFLAGS := \
-O3 \
-Wno-error=format-security \
-fvisibility=hidden \
-ffunction-sections \
-fdata-sections

# -------- CPP FLAGS (NO CODE REMOVED) --------
LOCAL_CPPFLAGS := \
-std=c++17 \
-Wno-error=format-security \
-Wno-error=c++11-narrowing \
-fvisibility=hidden \
-ffunction-sections \
-fdata-sections \
-fexceptions \
-fno-rtti \
-fno-unwind-tables \
-fno-asynchronous-unwind-tables

# -------- LINKER FLAGS --------
LOCAL_LDFLAGS := \
-Wl,--gc-sections \
-Wl,--strip-all

LOCAL_LDLIBS := -llog -landroid -lz
LOCAL_ARM_MODE := arm

# --- Include Paths (AS-IS) ---
LOCAL_C_INCLUDES += $(LOCAL_PATH)
LOCAL_C_INCLUDES += $(LOCAL_PATH)/Hook
LOCAL_C_INCLUDES += $(LOCAL_PATH)/IO
LOCAL_C_INCLUDES += $(LOCAL_PATH)/JniHook
LOCAL_C_INCLUDES += $(LOCAL_PATH)/SandHook
LOCAL_C_INCLUDES += $(LOCAL_PATH)/SandHook/xdl
LOCAL_C_INCLUDES += $(LOCAL_PATH)/SandHook/libzip

# --- ADD: libzip headers ---
LOCAL_C_INCLUDES += $(LOCAL_PATH)/libzip

# --- Source Files (AS-IS) ---
ELITE_SRC := $(wildcard $(LOCAL_PATH)/*.cpp)
ELITE_SRC += $(wildcard $(LOCAL_PATH)/Hook/*.cpp)
ELITE_SRC += $(wildcard $(LOCAL_PATH)/IO/*.cpp)
ELITE_SRC += $(wildcard $(LOCAL_PATH)/JniHook/*.cpp)
ELITE_SRC += $(wildcard $(LOCAL_PATH)/SandHook/*.cpp)

# C Files (xdl - SandHook/xdl mein)
ELITE_SRC += $(wildcard $(LOCAL_PATH)/SandHook/xdl/*.c)
# C Files (libzip - SandHook/libzip mein) - CORRECTED
ELITE_SRC += $(wildcard $(LOCAL_PATH)/SandHook/libzip/*.c)


LOCAL_SRC_FILES := $(ELITE_SRC:$(LOCAL_PATH)/%=%)

LOCAL_STATIC_LIBRARIES := libdobby

# --- Build Shared Library ---
include $(BUILD_SHARED_LIBRARY)