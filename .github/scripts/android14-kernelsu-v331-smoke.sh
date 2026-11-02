#!/usr/bin/env bash
set -Eeuo pipefail

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

# Kernel release format is VERSION-ANDROID_RELEASE-KMI_GENERATION-suffix.
# For the android14-6.1 KMI, uname -r starts with 6.1.x-android14-N-...
if [[ ! "$KERNEL_RELEASE" =~ ^6\\.1\\.[0-9]+-android14-[0-9]+- ]]; then
  echo "Kernel KMI mismatch: expected 6.1.x-android14-N; got $KERNEL_RELEASE" | tee test-results/status.txt
  exit 1
fi
echo "KMI check passed: android14-6.1" | tee test-results/kmi-check.txt

stage "ADB_ROOT_BOOTSTRAP"
timeout 30s adb root
wait_boot

stage "INSERT_KERNELSU_LKM"
timeout 30s adb push downloads/kernelsu-x86_64-android14-6.1.ko /data/local/tmp/kernelsu.ko
timeout 30s adb shell insmod /data/local/tmp/kernelsu.ko
sleep 5
timeout 20s adb shell dmesg > test-results/dmesg-after-kernelsu-insmod.txt 2>&1 || true

stage "INSTALL_KERNELSU_MANAGER_AND_KSUD"
timeout 60s adb install -r downloads/KernelSU-v3.3.1.apk
timeout 30s adb push downloads/ksud /data/local/tmp/ksud
timeout 20s adb shell chmod 0755 /data/local/tmp/ksud
timeout 20s adb shell /data/local/tmp/ksud --version | tee test-results/ksud-version.txt
timeout 30s adb shell su -c id | tee test-results/kernelsu-root-check.txt

stage "INSTALL_ALL_THREE_MODULES_WITHOUT_INTERMEDIATE_REBOOT"
for module in mountify zygisk-next vector; do
  timeout 30s adb push "downloads/$module.zip" "/data/local/tmp/$module.zip"
  timeout 120s adb shell su -c "/data/local/tmp/ksud module install /data/local/tmp/$module.zip"
  stage "INSTALLED $module"
done
timeout 30s adb shell su -c '/data/local/tmp/ksud module list' | tee test-results/modules-before-reboot.txt

stage "SINGLE_REBOOT_AFTER_FULL_MODULE_STACK"
timeout 30s adb reboot
wait_boot

stage "POST_REBOOT_KERNELSU_AND_MODULE_VALIDATION"
timeout 30s adb shell su -c id | tee test-results/kernelsu-root-check-final.txt
timeout 30s adb shell su -c '/data/local/tmp/ksud module list' | tee test-results/modules-final.txt
timeout 60s adb install -r app/build/outputs/apk/debug/app-debug.apk
timeout 20s adb shell pm path org.aquosr7.colorednotifications | tee test-results/notification-package-path.txt
timeout 20s adb logcat -d -t 12000 > test-results/logcat.txt 2>&1
timeout 20s adb exec-out screencap -p > test-results/android14-kernelsu-screen.png

{
  echo "ANDROID 14 + KernelSU v3.3.1 LKM smoke test"
  echo "Kernel: $KERNEL_RELEASE"
  echo "Root check:"
  cat test-results/kernelsu-root-check-final.txt
  echo "Modules:"
  cat test-results/modules-final.txt
  echo "APK:"
  cat test-results/notification-package-path.txt
} | tee test-results/status.txt

stage "TEST_FINISHED_SUCCESSFULLY"
