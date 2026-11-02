# AQUOS R7 — Colored Notification Icons (Vector)

Исходный проект модуля для JingMatrix Vector / libxposed API 102.

## Назначение

Возвращает исходную окраску иконок уведомлений сторонних приложений в строке состояния SystemUI. Область действия ограничена пакетом `com.android.systemui`.

## Что подтверждено по SystemUI.apk AQUOS R7

DEX-анализ класса `com.android.systemui.statusbar.StatusBarIconView` подтвердил:
- поле `mNotification` имеет тип `StatusBarNotification`;
- `setStaticDrawableColor(int)` существует с сигнатурой `(I)V`;
- `updateIconColor()` существует с сигнатурой `()V`;
- `updateContrastedStaticColor()` существует с сигнатурой `()V`.

Текущая тестовая реализация ставит хуки на эти методы и после штатного вызова очищает ColorFilter только у представлений, связанных с уведомлением. Это прототип: результат необходимо проверить на самом AQUOS R7. Не считать совместимость доказанной до телефонного теста.

SystemUI.apk, прошивка и системные разделы не изменяются.

## Сборка

GitHub Actions workflow `.github/workflows/build.yml` собирает debug APK при push в main или вручную через Actions → Build AQUOS R7 Vector module → Run workflow.

Артефакт: `AQUOS-R7-Colored-Notifications-Vector-APK`.

## Ограничения

Этот APK — модуль Vector, не KernelSU ZIP и не отдельный LSPosed. Требуется установленный Vector с поддержкой libxposed API 102. До тестирования на устройстве модуль не устанавливать на рабочую систему.
## AQUOS R7: целевой KMI KernelSU

По строке ядра устройства `5.10.237-android12-9-...` целевой KMI определяется как `5.10-android12-9`. Это не Android 12 как версия ОС: телефон работает на Android 14, а ядро относится к ветке `android12-5.10`.

Для `ksud late-load` семейство embedded LKM assets задаётся ключом `android12-5.10`. Полная проверка требует на самом устройстве выполнить `ksud boot-info current-kmi`, `ksud boot-info supported-kmis`, `ksud debug info` и проверить `su -c id`. Наличие Android 14 само по себе не является причиной несовместимости.

Эмулятор предыдущего сценария использует другой KMI (`android14-6.1`) и не может подтверждать загрузку LKM на AQUOS R7. Скрипт smoke-test теперь отвергает такой чужой KMI, а не выдаёт ложный успех. Физическая загрузка на A202SH не подтверждена: у CI нет доступа к телефону.
