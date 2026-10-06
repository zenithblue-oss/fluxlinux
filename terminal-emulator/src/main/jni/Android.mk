LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c
# FluxLinux: 16 KB page-size devices (Android 15+).
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
