package dev.stillwatch.ui.model

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun knownText(value: String?): String = value?.trim()?.takeIf { it.isNotEmpty() } ?: "미확인"
fun countText(count: Int?): String = if (count == null || count < 0) "미확인" else "${count}명"
fun batteryText(percent: Int?): String = if (percent == null || percent !in 0..100) "미확인" else "$percent%"

/** Explicit UTC offset is required. Invalid or local-only timestamps stay unknown. */
fun recordedTime(iso: String?): String {
    if (iso.isNullOrBlank()) return "미확인"
    return runCatching {
        OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.of("Asia/Seoul"))
            .format(DateTimeFormatter.ofPattern("M월 d일 HH:mm:ss", Locale.KOREAN)) + " KST"
    }.getOrDefault("미확인")
}
fun eventTitle(kind: EventKind): String = when (kind) {
    EventKind.STILL_CHECK -> "본인 확인 요청"
    EventKind.RESOLVED -> "본인 응답 기록"
    EventKind.RESCUE -> "현장 확인 요청"
    EventKind.SENSOR_LOST -> "감시 불가"
    EventKind.UNKNOWN -> "유형 미확인"
}
fun incidentStatusText(status: IncidentStatus): String = when (status) {
    IncidentStatus.OPEN -> "미해결"
    IncidentStatus.RESOLVED -> "서버 처리 완료"
    IncidentStatus.UNKNOWN -> "처리 상태 미확인"
}

/** Content proposal; creating/delivering native notifications belongs to the host app. */
fun notificationContent(kind: EventKind, eventId: String?, zone: String?): NotificationContent {
    val location = knownText(zone)
    return when (kind) {
        EventKind.STILL_CHECK -> NotificationContent(eventId, kind, "괜찮으신가요?", "$location · 본인 확인 요청이 도착했습니다. 앱에서 응답해 주세요.", "본인 확인 요청이 도착했습니다. 앱을 열어 주세요.", "worker/safety-check")
        EventKind.RESCUE -> NotificationContent(eventId, kind, "현장 확인 요청", "$location · 작업자의 응답이 확인되지 않았습니다. 현장 상태를 확인해 주세요.", "현장 확인 요청이 도착했습니다. 앱에서 확인해 주세요.", "admin/incident")
        EventKind.SENSOR_LOST -> NotificationContent(eventId, kind, "감시 불가", "$location · 센서 신호를 확인할 수 없습니다. 장치와 현장을 확인해 주세요.", "감시 상태를 확인할 수 없습니다. 앱을 열어 주세요.", "admin/sensor-lost")
        else -> NotificationContent(eventId, kind, "StillWatch 알림", "앱에서 전달된 기록을 확인해 주세요.", "새 알림이 있습니다.", "host/event")
    }
}
