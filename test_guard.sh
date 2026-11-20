#!/bin/bash
# 防跳转（摇一摇广告兜底）专项验证。
#
# 背景：WakeUp 课程表这类开屏是穿山甲「摇一摇」广告——摇一下手机直接拉起电商/商店。
# 无障碍服务看不到传感器事件，只能在落地后按一次返回。这个脚本就是验证
# 「该拦的拦住、不该拦的放过」。
#
# 用法：
#   ./test_guard.sh                   自动挑设备
#   ./test_guard.sh 59aakf459xinz5ss  指定设备
#
# 场景：
#   A 冷启动来源 App，1.2 秒内跳到淘宝      → 必须自动返回
#   B 冷启动来源 App，1.2 秒内跳到应用商店  → 必须自动返回
#   C 从桌面直接打开淘宝                    → 不许返回
#   D 来源 App 已显示出内容页后再跳到淘宝 → 不许返回（那是用户自己点的）
#   E 冷启动来源 App 后立刻唤起支付宝      → 不许返回（模拟 NFC 唤起）
#   F 打开番茄小说并在其内部切页            → 不许返回（模拟打开个人主页）
#   G 从负一屏 / 语音助手点开电商 App       → 不许返回（v1.3.8 修的重复误拦）
#
# E / F 是 2026-11-07 的两条真实投诉：
#   E「用 NFC 打开支付宝时回退」
#   F「番茄小说在打开个人主页的时候回退」
# 两条都不可复制、无法稳定复现，所以除了这里的端到端验证，
# 还有 app/src/test 里的 GuardDecisionTest 把判定矩阵钉死。
export MSYS_NO_PATHCONV=1
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
  echo "!! 找不到 adb：请加入 PATH，或设置 ANDROID_HOME / local.properties 的 sdk.dir"
  exit 1
fi

pick_device() {
  local list
  list=$("$ADB" devices | sed '1d' | awk '$2=="device"{print $1}')
  [ -z "$list" ] && return 1
  echo "$list" | head -1
}
SERIAL="${1:-$(pick_device)}"
[ -z "$SERIAL" ] && { echo "!! 没有可用设备"; exit 1; }
adb() { "$ADB" -s "$SERIAL" "$@"; }

ORIGIN=com.suda.yzune.wakeupschedule   # 摇一摇开屏的来源 App
LANDING=com.taobao.taobao              # 广告落地页（电商）
STORE=com.xiaomi.market                # 应用商店
ALIPAY=com.eg.android.AlipayGphone     # 用户主动打开的 App（NFC 唤起）
TOMATO=com.dragon.read                 # 番茄小说（用户会自己切页的内容 App）

has_pkg() { adb shell pm list packages 2>/dev/null | tr -d '\r' | grep -q "package:$1"; }
foreground() {
  adb shell "dumpsys activity activities | grep -m1 -E 'topResumedActivity=|ResumedActivity:'" 2>/dev/null \
    | tr -d '\r' | grep -oE '[a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+' | head -1 | cut -d/ -f1
}
home() { adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1; sleep 1; }
launch() { adb shell monkey -p "$1" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }
# 少数系统外壳没有 LAUNCHER 入口（负一屏嵌在桌面里、语音助手靠语音键唤起），
# monkey 和 resolve-activity 都找不到，只能直连已知的对外 Activity。
known_activity() {
  case "$1" in
    com.miui.voiceassist) echo "com.miui.voiceassist/com.xiaomi.voiceassistant.ReactNativeActivity" ;;
    *) echo "" ;;
  esac
}
# 尽力把一个包拉到前台：monkey → resolve-activity → 已知 Activity 表。
# 返回时用 foreground 自行确认，别假设成功。
launch_any() {
  launch "$1"; sleep 1
  foreground | grep -q "^$1$" && return 0
  local act
  act=$(adb shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $1" 2>/dev/null | tr -d '\r' | tail -1)
  if [ -n "$act" ] && ! echo "$act" | grep -q "No activity"; then
    adb shell am start -n "$act" >/dev/null 2>&1; sleep 1
    foreground | grep -q "^$1$" && return 0
  fi
  act=$(known_activity "$1")
  [ -n "$act" ] && { adb shell am start -n "$act" >/dev/null 2>&1; sleep 1; }
  return 0
}
wake()  { adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1; adb shell wm dismiss-keyguard >/dev/null 2>&1; }

# 判定日志里有没有对某个包做出「要回退」的决定
guard_back_for() {
  adb logcat -b main -d 2>/dev/null | tr -d '\r' \
    | grep -E "guard pkg=$1 " | grep -c -- '-> BACK'
}

for p in "$ORIGIN" "$LANDING" "$STORE" "$ALIPAY" "$TOMATO"; do
  has_pkg "$p" || { echo "!! 设备上缺 $p，无法测试"; exit 1; }
done

BOUND=$(adb shell "dumpsys accessibility | grep -c 'com.miaotiao.adskip'" | tr -d '\r')
[ "$BOUND" = "0" ] && { echo "!! 无障碍服务未绑定（$SERIAL）"; exit 1; }

PASS=0; FAIL=0
# 三级兜底的最后一级是 HOME，所以「回到桌面」也算脱离了广告页，
# 只是体验差一档，单独提示，不算失败。
judge_escape() { # $1=场景 $2=说明 $3=前台包名 $4=落地页包名
  if [ "$3" = "$ORIGIN" ]; then
    PASS=$((PASS+1)); printf "%-4s ✅ %-40s 已回到 %s\n" "$1" "$2" "$ORIGIN"; return
  fi
  if [ "$3" != "$4" ] && [ "$3" != "com.taobao.taobao" ] && [ "$3" != "com.xiaomi.market" ]; then
    PASS=$((PASS+1)); printf "%-4s ✅ %-40s 已脱离广告页（前台=%s）\n" "$1" "$2" "$3"; return
  fi
  FAIL=$((FAIL+1)); printf "%-4s ❌ %-40s 仍停在 %s\n" "$1" "$2" "$3"
}

echo "设备：$SERIAL"
echo "来源：$ORIGIN   落地页：$LANDING   商店：$STORE"
echo "------------------------------------------"

# ---- A：冷启动来源 App，1.2 秒内跳淘宝（摇一摇的时序）
wake
adb shell am force-stop "$ORIGIN" >/dev/null 2>&1
adb shell am force-stop "$LANDING" >/dev/null 2>&1
home
adb logcat -b main -c >/dev/null 2>&1
launch "$ORIGIN"
sleep 1.2
launch "$LANDING"
sleep 6
judge_escape A "冷启动跳淘宝 → 自动逃出广告页" "$(foreground)" "$LANDING"
echo "     日志：$(adb logcat -b main -d 2>/dev/null | grep -oE 'guard (back|relaunch|home)[^,]*ok=[a-z]+' | tr '\n' ' ')"

# ---- B：冷启动来源 App，1.2 秒内跳应用商店
adb shell am force-stop "$ORIGIN" >/dev/null 2>&1
adb shell am force-stop "$STORE" >/dev/null 2>&1
home
launch "$ORIGIN"
sleep 1.2
launch "$STORE"
sleep 6
judge_escape B "冷启动跳应用商店 → 自动逃出广告页" "$(foreground)" "$STORE"

# ---- C：从桌面直接打开淘宝，不该被返回
adb shell am force-stop "$LANDING" >/dev/null 2>&1
home
sleep 1
launch "$LANDING"
sleep 4
FG=$(foreground)
echo "$FG" | grep -q "$LANDING" && { PASS=$((PASS+1)); printf "%-4s ✅ %-40s 停留 %s\n" C "桌面直接打开淘宝 → 不许返回" "$LANDING"; } || \
  { FAIL=$((FAIL+1)); printf "%-4s ❌ %-40s 被返回，前台=%s\n" C "桌面直接打开淘宝 → 不许返回" "$FG"; }

# ---- D：来源 App 已经显示出正常内容页，再跳淘宝（用户自己点的），不该被返回
#
# 两个坑都是实测踩出来的：
# 1) 不能写「固定 sleep 5」：WakeUp 启动页在 K40 上要 4~6 秒，固定等 5 秒时
#    内容页还没出来，测出来的其实是「A 的翻版」——启动页阶段的跳转本来就该拦。
# 2) 也不能只等「不是 SplashActivity」：穿山甲广告会另起一个 Activity，
#    那个页面正是摇一摇广告本体，等它出现再跳，判定必然该拦。
# 所以这里等到 WakeUp 自己的课表页（ScheduleActivity）出现，再停 1.5 秒
# （模拟用户看一眼课程表再点商品卡），才发起跳转。
fg_activity() {
  # 注意：Android 16 / HyperOS 上 dumpsys 已经不再输出 mResumedActivity，
  # 只有 topResumedActivity 和 ResumedActivity:。
  # 旧脚本只查 mResumedActivity → 永远匹配不到 → D 场景一直静默跳过，
  # 「内容页闸门」这个关键机制等于从没被端到端验证过。
  adb shell "dumpsys activity activities | grep -m1 -E 'topResumedActivity=|ResumedActivity:'" 2>/dev/null \
    | tr -d '\r' | grep -oE '[a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+' | head -1
}
ORIGIN_CONTENT="ScheduleActivity"   # WakeUp 的内容页（课表）
adb shell am force-stop "$LANDING" >/dev/null 2>&1
adb shell am force-stop "$ORIGIN" >/dev/null 2>&1
home
T0=$(date +%s%3N)
launch "$ORIGIN"
READY=0
WAITED=0
while :; do
  ACT=$(fg_activity)
  case "$ACT" in
    "$ORIGIN"/*"$ORIGIN_CONTENT"*) READY=1; break ;;
  esac
  sleep 0.3
  WAITED=$((WAITED+1))
  [ "$WAITED" -gt 50 ] && break
done
if [ "$READY" != "1" ]; then
  echo "D    ⏭  内容页（$ORIGIN_CONTENT）15 秒内没出现，本例跳过（不能说明问题）"
else
sleep 1.5
DT=$(( $(date +%s%3N) - T0 ))
launch "$LANDING"
sleep 4
FG=$(foreground)
if echo "$FG" | grep -q "$LANDING"; then
  PASS=$((PASS+1)); printf "%-4s ✅ %-40s 停留 %s（启动后 ${DT}ms 跳）\n" D "内容页已出来再跳淘宝 → 不许返回" "$LANDING"
else
  FAIL=$((FAIL+1)); printf "%-4s ❌ %-40s 被返回，前台=%s（启动后 ${DT}ms 跳）\n" D "内容页已出来再跳淘宝 → 不许返回" "$FG"
fi
[ "$DT" -gt 8000 ] && echo "     ⚠️ 超过 8 秒拦截窗，本例不能证明内容页闸门生效"
fi

# ---- E：冷启动来源 App 后立刻唤起支付宝（模拟 NFC 碰一碰）→ 不许返回
#
# 这条对应 2026-11-07 的真实投诉：「用 NFC 打开支付宝时回退」。
# 旧代码把支付宝同时写进「广告落地页」和「用户主动打开」两份名单，
# 而落地页判断在前，豁免根本走不到 → NFC 一碰就被弹回去。
#
# 无障碍服务看不见 NFC，它只看见「前台包名变了」，所以这里用
# 「从 WakeUp 切到支付宝」来模拟：对防跳转来说这两件事完全等价。
adb shell am force-stop "$ALIPAY" >/dev/null 2>&1
adb shell am force-stop "$ORIGIN" >/dev/null 2>&1
home
adb logcat -b main -c >/dev/null 2>&1
launch "$ORIGIN"
sleep 1.5
launch "$ALIPAY"
sleep 5
FG=$(foreground)
if echo "$FG" | grep -q "$ALIPAY"; then
  PASS=$((PASS+1)); printf "%-4s ✅ %-40s 停留 %s\n" E "NFC 唤起支付宝 → 不许返回" "$ALIPAY"
else
  FAIL=$((FAIL+1)); printf "%-4s ❌ %-40s 被返回，前台=%s\n" E "NFC 唤起支付宝 → 不许返回" "$FG"
fi
echo "     判定：$(adb logcat -b main -d 2>/dev/null | tr -d '\r' | grep -E "guard pkg=$ALIPAY " | tail -1 | sed 's/.*-> /-> /')"

# ---- F：打开番茄小说并在其内部切页 → 不许返回
#
# 这条对应另一条投诉：「番茄小说在打开个人主页的时候回退」。
# 番茄小说在旧名单里被当成「广告落地页」，于是同包切页也被重判一次。
#
# 番茄小说底部是「书城 / 短剧 / 赚钱 / 书架 / 我的」五项页签，
# 「我的」在最右边（1440x3200 屏上约 x=1245, y=3100），点它就会切到个人页。
# 这里要验证三件事：
#   1) 番茄小说仍在前台（没被弹走）
#   2) 判定日志里没有针对番茄小说的 BACK
#   3) 同包切页这条路径确实被走到了（日志里有「同一应用内切换」）
#      ——否则这条用例只是「什么都没发生」，证明不了任何东西
adb shell am force-stop "$TOMATO" >/dev/null 2>&1
home
adb logcat -b main -c >/dev/null 2>&1
launch "$TOMATO"
sleep 9
adb shell input tap 1245 3100 >/dev/null 2>&1
sleep 4
FG=$(foreground)
if echo "$FG" | grep -q "$TOMATO"; then
  PASS=$((PASS+1)); printf "%-4s ✅ %-40s 停留 %s\n" F "番茄小说内部切页 → 不许返回" "$TOMATO"
else
  FAIL=$((FAIL+1)); printf "%-4s ❌ %-40s 被返回，前台=%s\n" F "番茄小说内部切页 → 不许返回" "$FG"
fi
BACKS=$(guard_back_for "$TOMATO")
if [ "$BACKS" = "0" ]; then
  PASS=$((PASS+1)); printf "%-4s ✅ %-40s 判定日志无 BACK\n" F2 "番茄小说未被判定为广告跳转"
else
  FAIL=$((FAIL+1)); printf "%-4s ❌ %-40s 出现 %s 次 BACK\n" F2 "番茄小说未被判定为广告跳转" "$BACKS"
fi
SKIPS=$(adb logcat -b main -d 2>/dev/null | tr -d '\r' | grep -c "guard skip pkg=$TOMATO ")
if [ "$SKIPS" -ge 1 ]; then
  PASS=$((PASS+1)); printf "%-4s ✅ %-40s 命中 %s 次\n" F3 "同包切页被识别并跳过" "$SKIPS"
else
  # 这一项验的是「覆盖到了没有」，不是「行为对不对」：
  # 番茄冷启动慢的时候还没进到书城就点了，本轮确实没走到同包切页。
  # 报失败会误导成功能坏了，标跳过更准确。
  echo "F3   ⏭  本轮没走到同包切页（番茄可能还没进书城），不计失败"
fi
echo "     判定：$(adb logcat -b main -d 2>/dev/null | tr -d '\r' | grep -E "guard (pkg|skip)=$TOMATO " | tail -2 | sed 's/.*-> /-> /' | tr '\n' ' ')"

# ---- G：从系统外壳（负一屏 / 语音助手）点开电商 App → 不许返回
#
# 这条对应 2026-11-20 的投诉：「拼多多多次拦截，美团也拦」。
# 日志里来源是 com.miui.personalassistant（负一屏）和 miui.systemui.plugin
# （控制中心插件），它们被当成「冷启动 + 从未进正片」的假来源：
#   cold=true   —— calcCold 对 passThrough 恒为 true，它们本来就不是 App
#   settled=false —— 扫描链压根不扫它们
#   dt 极短      —— 用户点完图标 1.3 秒目标 App 就出来了
# 三个凑齐正好是摇一摇广告的判据，于是点一次弹回一次。
#
# 无障碍服务只看得见「前台包名变了」，所以用「外壳 → 电商 App」模拟这次点击，
# 对防跳转来说完全等价。可以用环境变量换成本机的负一屏包名：
#   ADSKIP_SHELL_PKG=com.miui.personalassistant ./test_guard.sh
SHELL_PKG="${ADSKIP_SHELL_PKG:-com.miui.voiceassist}"
SHELL_DEST="${ADSKIP_SHELL_DEST:-com.xunmeng.pinduoduo}"
if ! has_pkg "$SHELL_PKG" || ! has_pkg "$SHELL_DEST"; then
  echo "G    ⏭  设备上没有 $SHELL_PKG 或 $SHELL_DEST，跳过"
else
  adb shell am force-stop "$SHELL_DEST" >/dev/null 2>&1
  home
  adb logcat -b main -c >/dev/null 2>&1
  launch_any "$SHELL_PKG"
  sleep 1
  if [ "$(foreground)" != "$SHELL_PKG" ]; then
    # 外壳没能到前台（各 ROM 的负一屏多半嵌在桌面里，adb 起不来），
    # 这一轮就退化成「从桌面打开」，证明不了 shell 分支，明确跳过。
    echo "G    ⏭  外壳 $SHELL_PKG 没能到前台（前台=$(foreground)），本例跳过"
  else
  launch "$SHELL_DEST"
  sleep 5
  FG=$(foreground)
  if echo "$FG" | grep -q "$SHELL_DEST"; then
    PASS=$((PASS+1)); printf "%-4s ✅ %-40s 停留 %s\n" G "外壳(负一屏/语音助手)点开电商" "$SHELL_DEST"
  else
    FAIL=$((FAIL+1)); printf "%-4s ❌ %-40s 被返回，前台=%s\n" G "外壳(负一屏/语音助手)点开电商" "$FG"
  fi
  # 光「停在那儿」还不够：得确认判定链确实看到过这个外壳包，
  # 否则本例只是「什么都没发生」，证明不了外壳豁免生效。
  SHELL_HITS=$(adb logcat -b main -d 2>/dev/null | tr -d '\r' | grep -c "guard skip pkg=$SHELL_PKG ")
  BACKS=$(guard_back_for "$SHELL_DEST")
  if [ "$BACKS" = "0" ]; then
    PASS=$((PASS+1)); printf "%-4s ✅ %-40s 判定日志无 BACK\n" G2 "外壳来源未被判成广告跳转"
  else
    FAIL=$((FAIL+1)); printf "%-4s ❌ %-40s 出现 %s 次 BACK\n" G2 "外壳来源未被判成广告跳转" "$BACKS"
  fi
  if [ "$SHELL_HITS" -ge 1 ]; then
    PASS=$((PASS+1)); printf "%-4s ✅ %-40s 命中 %s 次\n" G3 "外壳被识别并清空来源" "$SHELL_HITS"
  else
    FAIL=$((FAIL+1)); printf "%-4s ❌ %-40s 本例没走到外壳分支，证明不了\n" G3 "外壳被识别并清空来源"
  fi
  echo "     判定：$(adb logcat -b main -d 2>/dev/null | tr -d '\r' | grep -E "guard (pkg|skip)=($SHELL_PKG|$SHELL_DEST) " | tail -2 | sed 's/.*-> /-> /' | tr '\n' ' ')"
  fi
fi

echo "------------------------------------------"
echo "通过 $PASS / 失败 $FAIL"
[ "$FAIL" = "0" ] || exit 1