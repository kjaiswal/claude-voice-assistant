#!/bin/bash
# Build and deploy Claude Assistant to connected phone
# Usage: ./deploy.sh

set -e
ADB=~/Library/Android/sdk/platform-tools/adb
cd "$(dirname "$0")"

echo "Building..."
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug -q

echo "Installing..."
$ADB install -r app/build/outputs/apk/debug/app-debug.apk

echo "Restoring assistant settings..."
$ADB shell settings put secure assistant com.lunapunks.claudeassistant/.AssistActivity
$ADB shell settings put secure voice_interaction_service com.lunapunks.claudeassistant/.ClaudeVoiceInteractionService
$ADB shell settings put secure assist_gesture_enabled 1
$ADB shell settings put secure assist_gesture_setup_complete 1
$ADB shell settings put secure assist_touch_gesture_enabled 1

echo "Done. Long-press power to test."
