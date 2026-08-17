#!/usr/bin/env bash
# Standalone SMS parser unit tests (no full Android Gradle sync required).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-$ROOT/.tools/jdk-17.0.13+11}"
KOTLINC="$ROOT/.tools/kotlin-compiler/bin/kotlinc"
OUT="$ROOT/.tools/test-out"
LIBS="$ROOT/.tools/test-libs"
ANDROID_JAR="$ROOT/.tools/android-sdk/platforms/android-35/android.jar"
export PATH="$JAVA_HOME/bin:$PATH"

CP_LIBS=$(echo "$LIBS"/*.jar | tr ' ' ':')
KOTLIN_STDLIB="$ROOT/.tools/kotlin-compiler/lib/kotlin-stdlib.jar"
SRC="$ROOT/app/src/main/java"
TEST="$ROOT/app/src/test/java"

rm -rf "$OUT" && mkdir -p "$OUT"
"$KOTLINC" -classpath "$ANDROID_JAR:$CP_LIBS" -d "$OUT" \
  "$SRC/com/fintracker/app/domain/model/Enums.kt" \
  "$SRC/com/fintracker/app/domain/sms/SmsModels.kt" \
  "$SRC/com/fintracker/app/domain/sms/SmsTemplateLoader.kt" \
  "$SRC/com/fintracker/app/domain/sms/SmsParseEngine.kt" \
  "$TEST/com/fintracker/app/domain/sms/SmsParseEngineTest.kt"

java -classpath "$OUT:$ANDROID_JAR:$CP_LIBS:$KOTLIN_STDLIB" \
  org.junit.runner.JUnitCore com.fintracker.app.domain.sms.SmsParseEngineTest
