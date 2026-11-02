#!/bin/bash
# Source this file to set up Android build environment
# Usage: source setup_env.sh

export ANDROID_HOME=/home/user/android-sdk
export ANDROID_SDK_ROOT=/home/user/android-sdk
export ANDROID_NDK_HOME=/home/user/android-sdk/ndk/24.0.8215888
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/34.0.0
export GRADLE_HOME=/home/user/gradle-8.7/gradle-8.7
export PATH=$PATH:$GRADLE_HOME/bin

echo "Android SDK: $ANDROID_HOME"
echo "NDK:         $ANDROID_NDK_HOME"
echo "Gradle:      $(gradle --version 2>/dev/null | grep '^Gradle')"
