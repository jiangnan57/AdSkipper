#!/usr/bin/env bash
# 秒跳 跳过能力测试脚本
# 用法: ./test_skip.sh <包名> [次数]
# 每个包名前台/后台各测一次，输出是否命中

PROJ_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
get_prop() {
  [ -f "$PROJ_DIR/local.properties" ] || return 0
  grep -E "^[[:space:]]*$1=" "$PROJ_DIR/local.properties" | head -1 | cut -d= -f2- | tr -d '\r'
}
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(get_prop sdk.dir)}}"
if command -v adb >/dev/null 2>&1; then
  ADB="$(command -v adb)"
elif [ -n "$SDK_DIR" ] && [ -f "$SDK_DIR/platform-tools/adb.exe" ]; then
  ADB="$SDK_DIR/platform-tools/adb.exe"
elif [ -n "$SDK_DIR" ] && [ -f "$SDK_DIR/platform-tools/adb" ]; then
  ADB="$SDK_DIR/platform-tools/adb"
else
  echo "!! 找不到 adb：请把它加入 PATH，或设置 ANDROID_HOME / local.properties 的 sdk.dir"
  exit 1
fi
COMP="com.miaotiao.adskip/com.miaotiao.adskip.service.SkipAccessibilityService"
PKG="${1:?需要包名}"
N="${2:-1}"

ensure_service() {
  "$ADB" shell "settings put secure --user 0 enabled_accessibility_services $COMP" >/dev/null 2>&1
  "$ADB" shell "settings put secure --user 0 accessibility_enabled 1" >/dev/null 2>&1
  sleep 3
}

run_once() {
  local label="$1"
  "$ADB" logcat -c
  "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
  sleep 1
  "$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 10
  local hit
  hit=$("$ADB" logcat -b all -d 2>&1 | grep -oE "skipped pkg=$PKG label=[^ ]+" | head -1)
  local evts
  evts=$("$ADB" logcat -b all -d 2>&1 | grep -cE "pkg=$PKG")
  if [ -n "$hit" ]; then
    echo "  [$label] ✅ $hit"
  else
    echo "  [$label] ❌ 未跳过 (收到 ${evts} 个事件)"
  fi
}

echo "=== 测试 $PKG ==="
ensure_service

# 场景1: 秒跳 App 在前台（用户正在用秒跳）
"$ADB" shell am start -n com.miaotiao.adskip/.ui.MainActivity >/dev/null 2>&1
sleep 3
for i in $(seq 1 $N); do run_once "秒跳前台"; done

# 场景2: 秒跳 App 在后台（正常使用场景：秒跳不用，只是它常驻）
"$ADB" shell input keyevent KEYCODE_HOME >/dev/null 2>&1
sleep 4
for i in $(seq 1 $N); do run_once "秒跳后台"; done
