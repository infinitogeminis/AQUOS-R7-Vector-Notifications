#!/usr/bin/env bash
set -Eeuo pipefail

# GitHub Actions steps run in separate shells. Reconstruct the Android SDK
# environment here instead of relying on PATH exported by the emulator step.
export ANDROID_HOME="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/cmdline-tools/16.0/bin:$PATH"

for required_command in adb timeout; do
  if ! command -v "$required_command" >/dev/null 2>&1; then
    echo "Required command not found in PATH: $required_command" >&2
    echo "PATH=$PATH" >&2
    exit 127
  fi
done

mkdir -p test-results
exec > >(tee -a test-results/emulator-session.log) 2>&1

stage() {
  echo "===== $(date -u +%FT%TZ) $* ====="
}

diagnostics() {
  rc=$?
  trap - EXIT
  echo "$rc" > test-results/exit-code.txt
  stage "DIAGNOSTICS exit=$rc"
  timeout 20s adb devices -l > test-results/adb-devices.txt 2>&1 || true
  timeout 20s adb shell getprop > test-results/getprop.txt 2>&1 || true
  timeout 30s adb logcat -b all -d -t 12000 > test-results/logcat-on-exit.txt 2>&1 || true
  timeout 20s adb shell dmesg > test-results/dmesg-on-exit.txt 2>&1 || true
  timeout 20s adb shell dumpsys activity processes > test-results/activity-processes.txt 2>&1 || true
  exit "$rc"
}
trap diagnostics EXIT

wait_boot() {
  stage "WAIT_FOR_BOOT"
  timeout 180s adb wait-for-device
  for i in $(seq 1 180); do
    state="$(timeout 10s adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [[ "$state" == "1" ]]; then
      stage "BOOT_COMPLETED after $((i * 2)) seconds"
      return 0
    fi
    sleep 2
  done
  echo "BOOT_TIMEOUT" | tee -a test-results/status.txt
  return 1
}

stage "INITIAL_BOOT"
wait_boot
timeout 15s adb shell getprop ro.build.version.release | tee test-results/android-version.txt
timeout 15s adb shell getprop ro.build.version.sdk | tee test-results/android-api.txt
timeout 15s adb shell uname -a | tee test-results/kernel-uname.txt
KERNEL_RELEASE="$(timeout 15s adb shell uname -r | tr -d '\r')"
echo "$KERNEL_RELEASE" | tee test-results/kernel-release.txt

# This workflow runs on the Android 14 x86_64 AVD, not on the AQUOS R7.
# The AVD uses its own android14-6.1 KMI. The phone's android12-5.10 KMI
# is validated separately on the physical device and must not gate this AVD test.
if [[ ! "$KERNEL_RELEASE" =~ ^6\.1\.[0-9]+-android14- ]]; then
  echo "Kernel KMI mismatch: expected Android 14 AVD kernel 6.6.x-android15; got $KERNEL_RELEASE" | tee test-results/status.txt
  exit 1
fi
echo "AVD kernel release family check passed: android14-6.1" | tee test-results/kmi-check.txt

stage "PATCH_AVD_RAMDISK_WITH_KERNELSU_X86_64_LKM"
AVD_SYSTEM_PATH="$ANDROID_HOME/system-images/android-34/google_apis/x86_64"
test -s "$AVD_SYSTEM_PATH/ramdisk.img"
timeout 180s downloads/ksud-host boot-patch --ramdisk -b "$AVD_SYSTEM_PATH/ramdisk.img" --out "$AVD_SYSTEM_PATH" --out-name ramdisk.img.kernelsu --arch x86_64 --kmi android14-6.1 | tee test-results/kernelsu-ramdisk-patch.txt
test -s "$AVD_SYSTEM_PATH/ramdisk.img.kernelsu"
sha256sum "$AVD_SYSTEM_PATH/ramdisk.img" "$AVD_SYSTEM_PATH/ramdisk.img.kernelsu" | tee test-results/ramdisk-sha256.txt

stage "BOOT_KERNELSU_PATCHED_AVD"
emulator -avd aquosr7-api34 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot -no-metrics -ramdisk "$AVD_SYSTEM_PATH/ramdisk.img.kernelsu" > test-results/emulator.log 2>&1 &
echo $! > test-results/emulator.pid
timeout 30s adb start-server
wait_boot
adb devices -l | tee test-results/adb-devices.txt
timeout 60s adb install -r downloads/KernelSU-v3.3.1.apk
timeout 30s adb push downloads/ksud-android /data/local/tmp/ksud
timeout 20s adb shell chmod 0755 /data/local/tmp/ksud
timeout 20s adb shell /data/local/tmp/ksud --version | tee test-results/ksud-version.txt
stage "VERIFY_KERNELSU_AFTER_RAMDISK_BOOT"
timeout 20s adb shell /data/local/tmp/ksud boot-info current-kmi | tee test-results/kernelsu-current-kmi.txt

stage "INSTALL_REGULAR_MODULES_THEN_METAMODULE_WITHOUT_INTERMEDIATE_REBOOT"
# KernelSU v3.3.1 blocks regular module installs while a metamodule with
# metainstall.sh has a pending update. Install Mountify last, then reboot once.
for module in zygisk-next vector mountify; do
  timeout 30s adb push "downloads/$module.zip" "/data/local/tmp/$module.zip"
  timeout 120s adb shell /data/local/tmp/ksud module install /data/local/tmp/$module.zip
  stage "INSTALLED $module"
done
timeout 30s adb shell /data/local/tmp/ksud module list | tee test-results/modules-before-reboot.txt

stage "SINGLE_REBOOT_AFTER_FULL_MODULE_STACK"
timeout 30s adb reboot
wait_boot

stage "POST_REBOOT_KERNELSU_AND_MODULE_VALIDATION"
timeout 30s adb shell /data/local/tmp/ksud module list | tee test-results/modules-after-reboot-check.txt
timeout 30s adb shell /data/local/tmp/ksud module list | tee test-results/modules-final.txt
timeout 60s adb install -r app/build/outputs/apk/debug/app-debug.apk
timeout 20s adb shell pm path org.aquosr7.colorednotifications | tee test-results/notification-package-path.txt
timeout 20s adb logcat -d -t 12000 > test-results/logcat.txt 2>&1
timeout 20s adb exec-out screencap -p > test-results/android14-kernelsu-screen.png

{
  echo "Android 14 AVD KMI (android14-6.1) + KernelSU v3.3.1 x86_64 LKM smoke test"
  echo "Kernel: $KERNEL_RELEASE"
  echo "KernelSU module API after reboot:"
  cat test-results/modules-after-reboot-check.txt
  echo "Modules:"
  cat test-results/modules-final.txt
  echo "APK:"
  cat test-results/notification-package-path.txt
} | tee test-results/status.txt

stage "TEST_FINISHED_SUCCESSFULLY"

# Trigger-only commit marker; no runtime behavior change.
