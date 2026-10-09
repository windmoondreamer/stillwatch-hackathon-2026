package dev.stillwatch.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stillwatch.ui.components.*
import dev.stillwatch.ui.model.*
import dev.stillwatch.ui.theme.Spacing

@Composable private fun EventRow(event: EventRecord, onAction: (AdminAction) -> Unit) {
    val kind = EventKind.fromServer(event.rawType)
    OutlinedButton(
        onClick = { event.id?.takeIf { it.isNotBlank() }?.let { onAction(AdminAction.OpenIncident(it)) } },
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        enabled = !event.id.isNullOrBlank(), shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.outlinedButtonColors(disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        contentPadding = PaddingValues(Spacing.lg),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(event.label?.takeIf { it.isNotBlank() } ?: eventTitle(kind), style = MaterialTheme.typography.titleSmall)
            Text(knownText(event.zoneName), style = MaterialTheme.typography.bodyMedium)
            Text(recordedTime(event.occurredAt), style = MaterialTheme.typography.bodySmall)
            Text(incidentStatusText(event.status), style = MaterialTheme.typography.labelMedium)
            if (event.id.isNullOrBlank()) Text("상세 식별 정보 미확인", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun AdminHomeScreen(state: ContentState<AdminHomeState>, onAction: (AdminAction) -> Unit, modifier: Modifier = Modifier, extra: @Composable ColumnScope.() -> Unit = {}) {
    ScreenFrame("관리자 홈", modifier, source = (state as? ContentState.Ready)?.value?.source ?: DataSource.LIVE) {
        ContentGate(state, { onAction(AdminAction.Retry) }) { data ->
            ZoneHeading(data.zone)
            if (data.unresolved.isNotEmpty()) {
                SectionTitle("미해결 사건 ${data.unresolved.size}건")
                data.unresolved.take(3).forEach { EventRow(it, onAction) }
                if (data.unresolved.size > 3) SecondaryAction("전체 사건 내역 보기", { onAction(AdminAction.OpenHistory) })
            } else StatusPanel("전달된 미해결 사건 없음", "사건 목록의 조회 결과입니다. 현장의 안전을 보장하는 표시는 아닙니다.")
            InfoCard {
                Facts(listOf("휴대폰 연결 기반 추정" to countText(data.registeredCount), "감시 상태" to monitoringLabel(data.monitoring), "장치 연결" to knownText(data.device.connectionLabel), "최근 움직임" to recordedTime(data.lastMotionAt)))
            }
            extra()
            if (data.monitoring == Monitoring.UNAVAILABLE) PrimaryAction("감시 불가 정보 확인", { onAction(AdminAction.OpenMonitoringIssue) })
            SectionTitle("최근 이벤트")
            if (data.recentEvents.isEmpty()) Note("전달된 이벤트가 없습니다.")
            data.recentEvents.take(3).forEach { EventRow(it, onAction) }
            SecondaryAction("이벤트 내역", { onAction(AdminAction.OpenHistory) })
            SecondaryAction("현장 최초 설정", { onAction(AdminAction.OpenSetup) })
            Note("무응답 알림은 현장 확인 요청입니다. 사고 발생을 확정한 정보가 아닙니다.")
        }
    }
}

@Composable
fun IncidentDetailScreen(state: ContentState<IncidentState>, onAction: (AdminAction) -> Unit, modifier: Modifier = Modifier, actions: @Composable ColumnScope.() -> Unit = {}) {
    ScreenFrame("사건 상세", modifier, source = (state as? ContentState.Ready)?.value?.source ?: DataSource.LIVE, onBack = { onAction(AdminAction.Back) }) {
        ContentGate(state, { onAction(AdminAction.Retry) }) { data ->
            val tone = when (data.kind) { EventKind.RESCUE -> Tone.DANGER; EventKind.RESOLVED -> Tone.SUCCESS; EventKind.SENSOR_LOST -> Tone.WARNING; else -> Tone.NEUTRAL }
            Text(eventTitle(data.kind), style = MaterialTheme.typography.labelLarge)
            ZoneHeading(data.zone)
            StatusPanel(eventTitle(data.kind), when (data.kind) {
                EventKind.RESCUE -> "작업자의 본인 응답이 확인되지 않았습니다. 기록된 위치로 현장 확인이 필요합니다."
                EventKind.RESOLVED -> "본인 응답이 기록되었습니다. 현장 전체의 위험 해소를 의미하지 않습니다."
                EventKind.SENSOR_LOST -> "신호가 끊겨 감시 정보를 확인할 수 없습니다. 장치와 현장 상태를 확인해 주세요."
                EventKind.STILL_CHECK -> "움직임이 감지되지 않아 본인 확인 요청이 전달되었습니다."
                EventKind.UNKNOWN -> "이벤트 유형을 확인할 수 없습니다. 시스템 원본 기록을 확인해 주세요."
            }, tone)
            InfoCard {
                Facts(listOf("휴대폰 연결 기반 추정" to countText(data.registeredCount), "작업자 응답" to when (data.workerResponse) { WorkerResponse.UNANSWERED -> "응답 없음"; WorkerResponse.RESPONDED -> "응답 기록됨"; WorkerResponse.UNKNOWN -> "미확인" }, "현재 감시" to monitoringLabel(data.monitoring), "서버 처리 상태" to incidentStatusText(data.status)))
                Fact("관리자 현장 확인 위치", knownText(data.zone.checkLocation))
            }
            actions()
            SectionTitle("기록된 시각")
            Facts(listOf("휴대폰 Wi-Fi 연결" to recordedTime(data.enteredAt), "마지막 움직임" to recordedTime(data.lastMotionAt), "본인 확인 요청" to recordedTime(data.requestedAt), "관리자 경고" to recordedTime(data.alertedAt), "본인 응답" to recordedTime(data.respondedAt)))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SectionTitle("사건 타임라인")
            if (data.timeline.isEmpty()) Note("전달된 시간 기록이 없습니다.")
            data.timeline.forEach { entry -> Fact(entry.label, recordedTime(entry.at)) }
            SectionTitle("AI 상황 요약")
            if (data.aiMessage == null) Note("AI 요약이 제공되지 않았습니다. 아래 시스템 원본 기록은 계속 확인할 수 있습니다.")
            else InfoCard {
                Text("AI가 생성한 보조 정보", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(knownText(data.aiMessage.title), style = MaterialTheme.typography.titleMedium)
                Text(knownText(data.aiMessage.summary), style = MaterialTheme.typography.bodyLarge)
                Note(knownText(data.aiMessage.followUp))
            }
            var expanded by rememberSaveable(data.id) { mutableStateOf(false) }
            SecondaryAction(if (expanded) "시스템 원본 기록 접기" else "시스템 원본 기록 펼치기", { expanded = !expanded })
            if (expanded) {
                Fact("사건 ID", knownText(data.id))
                Fact("이벤트", data.kind.name)
                if (data.sourceRecords.isEmpty()) Note("추가 원본 기록이 없습니다.")
                data.sourceRecords.forEach { (label, value) -> Fact(label, knownText(value)) }
            }
            SecondaryAction("기록 공유 요청", { data.id?.takeIf { it.isNotBlank() }?.let { onAction(AdminAction.ShareRecords(it)) } }, enabled = !data.id.isNullOrBlank())
            Note("현장 상태 확인과 필요한 대응은 담당 관리자가 수행합니다. 이 화면에는 구조 완료 또는 사고 원인 판정 기능이 없습니다.")
        }
    }
}

@Composable
fun SensorLostScreen(state: ContentState<SensorLostState>, onAction: (AdminAction) -> Unit, modifier: Modifier = Modifier, actions: @Composable ColumnScope.() -> Unit = {}) {
    ScreenFrame("감시 불가", modifier, source = (state as? ContentState.Ready)?.value?.source ?: DataSource.LIVE, onBack = { onAction(AdminAction.Back) }) {
        ContentGate(state, { onAction(AdminAction.Retry) }) { data ->
            ZoneHeading(data.zone)
            StatusPanel("현재 감시 정보를 확인할 수 없습니다", "장치 전원이나 통신 연결에 문제가 있을 수 있습니다. 연결 상태와 현장을 확인해 주세요.", Tone.WARNING)
            InfoCard {
                Facts(listOf("감시 불가 발생" to recordedTime(data.occurredAt), "장치 ID" to knownText(data.device.id), "장치 연결" to knownText(data.device.connectionLabel), "마지막 신호" to recordedTime(data.device.lastSignalAt), "배터리" to batteryText(data.device.batteryPercent)))
            }
            actions()
            if (data.hasUnresolvedIncident == true) StatusPanel("진행 중인 사건을 함께 확인해 주세요", "센서가 다시 연결되어도 기존 현장 확인 사건이 자동 해결되지는 않습니다.", Tone.DANGER)
            SecondaryAction("현재 상태 다시 불러오기", { onAction(AdminAction.Retry) })
            SecondaryAction("사건 내역 확인", { onAction(AdminAction.OpenHistory) })
            Note("감시 불가는 사고 확정이 아닙니다. 푸시 전달 실패와 센서 신호 중단은 각각 확인해야 합니다.")
        }
    }
}

@Composable
fun EventHistoryScreen(state: ContentState<EventHistoryState>, onAction: (AdminAction) -> Unit, modifier: Modifier = Modifier) {
    ScreenFrame("이벤트 내역", modifier, source = (state as? ContentState.Ready)?.value?.source ?: DataSource.LIVE, onBack = { onAction(AdminAction.Back) }, scrollable = false) {
        ContentGate(state, { onAction(AdminAction.Retry) }) { data ->
            SectionTitle("전달된 이벤트 ${data.events.size}건")
            Note("시스템이 전달한 순서로 표시합니다. 이 화면은 과거 기록이나 해결 상태를 생성하지 않습니다.")
            if (data.events.isEmpty()) StatusPanel("이벤트 내역 없음", "아직 전달된 이벤트가 없습니다.")
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                items(data.events) { EventRow(it, onAction) }
            }
        }
    }
}
