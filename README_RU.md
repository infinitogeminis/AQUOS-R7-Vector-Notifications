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
