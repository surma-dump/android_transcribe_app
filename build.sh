#!/bin/bash

mkdir -p .adh
export ANDROID_HOME="$PWD/.adh"
export ANDROID_SDK_ROOT="$PWD/.adh"
export ANDROID_NDK_HOME="$PWD/env/libexec/android-sdk/ndk/29.0.14206865"
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" assembleRelease
