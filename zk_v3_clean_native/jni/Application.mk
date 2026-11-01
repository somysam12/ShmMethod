APP_ABI := arm64-v8a
APP_PLATFORM := android-21
APP_STL := c++_static
APP_OPTIM := release
APP_THIN_ARCHIVE := true
APP_PIE 		:= true

# Route all compilation through Hikari LLVM for BCF/CFF/SUB/STRCRY/INDIBRAN protection
#NDK_CCACHE := /opt/hikari-wrapper.sh
