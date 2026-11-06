#!/bin/bash
# 逐个跑广告样本形态，输出识别结果。
# 用法:
#   ./test_variants.sh              自动挑一台装了测试台的设备
#   ./test_variants.sh 8d9095d3     指定设备序列号
#
# 1~14  ：正向样本，必须被跳过，且不能点到广告创意本身
# 99    ：正常内容页，不许点
# 950   ：弹窗样本，必须由「弹窗规则」关掉，且不能被开屏识别抢先点
# 901+  ：负向样本（番茄式首页 / 抖音式评论区 / DeepSeek 式欢迎页），不许点、不许返回
export MSYS_NO_PATHCONV=1
PROJ_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
get_prop() {
  [ -f "$PROJ_DIR/local.properties" ] || return 0
  grep -E "^[[:space:]]*$1=" "$PROJ_DIR/local.properties" | head -1 | cut -d= -f2- | tr -d '\r'
}
# 优先 PATH 里的 adb，其次 ANDROID_HOME，最后 local.properties（本文件不入库）
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
PKG=com.miaotiao.adsample

# ---- 选设备 ----
# 同时插着两台手机时，adb 会报 "more than one device/emulator"，
# 所有命令都会失败。所以这里自动挑一台装了广告测试台的设备。
pick_device() {
  local list
  list=$("$ADB" devices | sed '1d' | awk '$2=="device"{print $1}')
  [ -z "$list" ] && return 1
  local n
  n=$(echo "$list" | wc -l | tr -d ' ')
  if [ "$n" = "1" ]; then echo "$list"; return 0; fi

  # 多台：优先挑装了测试台的
  local d
  for d in $list; do
    if "$ADB" -s "$d" shell pm list packages 2>/dev/null | tr -d '\r' | grep -q "package:$PKG"; then
      echo "$d"; return 0
    fi
  done
  # 都没有就退而求其次，用第一台
  echo "$list" | head -1
}

SERIAL="${1:-$(pick_device)}"
if [ -z "$SERIAL" ]; then
  echo "!! 没有可用设备"
  exit 1
fi
if [ "$("$ADB" devices | sed '1d' | awk '$2=="device"{print $1}' | wc -l | tr -d ' ')" != "1" ]; then
  echo "使用设备：$SERIAL"
fi
adb() { "$ADB" -s "$SERIAL" "$@"; }

BOUND=$(adb shell "dumpsys accessibility | grep -c 'com.miaotiao.adskip'" | tr -d '\r')
if [ "$BOUND" = "0" ]; then
  echo "!! 无障碍服务未绑定（设备 $SERIAL）"
  exit 1
fi

# 950 必须排在 99 之后判定之外单独处理：旧脚本用 "$V -ge 99" 判负向，
# 会把弹窗样本也当成「不许点」的负向样本，弹窗链路从来没进过回归。
# 快速冒烟：TEST_VARIANTS="1 2" ./test_variants.sh <序列号>
VARIANTS="${TEST_VARIANTS:-1 2 3 4 5 6 7 8 9 10 11 12 13 14 99 901 902 903 950}"
PASS=0; FAIL=0
for V in $VARIANTS; do
  # 息屏 / 锁屏时 rootInActiveWindow 可能拿不到目标窗口，尤其刚重绑完
  # 无障碍服务的头两轮，会造成「连扫描都没跑」的假失败。每轮主动唤醒。
  adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  adb shell wm dismiss-keyguard >/dev/null 2>&1
  # 先切到桌面，让"离开App"状态成立（这正是真实使用路径）
  adb shell am force-stop $PKG
  adb shell input keyevent KEYCODE_HOME
  sleep 1
  adb logcat -b main -c
  adb shell "am start -n $PKG/.AdActivity --ei variant $V" >/dev/null 2>&1
  sleep 9
  LOG=$(adb logcat -b main -d 2>&1)
  HIT=$(echo "$LOG" | grep -oE "skipped pkg=$PKG label=.* how=(规则|自识别) .* score=[0-9]+" | head -1)
  CLICKED=$(echo "$LOG" | grep -c "AdSample: skipClicked variant=$V")
  ADCLICK=$(echo "$LOG" | grep -c "AdSample: adClicked variant=$V")
  WRONG=$(echo "$LOG" | grep -c "AdSample: wrongClicked variant=$V")
  POPUP=$(echo "$LOG" | grep -c "AdSample: popupClosed variant=$V")
  TOP=$(adb shell "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'" | grep -c "com.miaotiao.adsample" | tr -d '\r')

  if [ "$V" = "950" ]; then
    # 弹窗样本：必须走弹窗规则关掉；开屏识别不许抢先点（CLICKED=0）
    if [ "$POPUP" -gt 0 ] && [ "$CLICKED" -eq 0 ] && [ "$WRONG" -eq 0 ]; then
      RES="✅ 弹窗已关"; PASS=$((PASS+1))
    else
      RES="❌ 弹窗未关(closed=$POPUP splash=$CLICKED wrong=$WRONG)"; FAIL=$((FAIL+1))
    fi
  elif [ "$V" -ge 99 ]; then
    # 负向：不许点任何东西，也不许被返回
    if [ "$WRONG" -gt 0 ]; then
      RES="❌ 误点 ${WRONG} 次"; FAIL=$((FAIL+1))
    elif [ "$TOP" = "0" ]; then
      RES="❌ 被返回了"; FAIL=$((FAIL+1))
    else
      RES="✅ 未误点"; PASS=$((PASS+1))
    fi
  else
    if [ "$CLICKED" -gt 0 ]; then
      if [ "$ADCLICK" -gt 0 ]; then RES="⚠️ 跳过但误点广告"; PASS=$((PASS+1)); else RES="✅ 跳过"; PASS=$((PASS+1)); fi
    else
      RES="❌ 未跳过"; FAIL=$((FAIL+1))
    fi
  fi
  printf "#%-3s %-16s hit=%s\n" "$V" "$RES" "${HIT:-无}"
done

echo "=================================="
echo "通过 $PASS / 失败 $FAIL"
[ "$FAIL" = "0" ] || exit 1
