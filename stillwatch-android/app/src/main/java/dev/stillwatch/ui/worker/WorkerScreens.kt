package dev.stillwatch.ui.worker

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stillwatch.ui.components.*
import dev.stillwatch.ui.model.*
import dev.stillwatch.ui.theme.Spacing

@Composable
fun WorkerHomeScreen(state: ContentState<WorkerHomeState>, onAction: (WorkerAction) -> Unit, modifier: Modifier = Modifier) {
    ScreenFrame("작업자 홈", modifier, source = (state as? ContentState.Ready)?.value?.source ?: DataSource.LIVE) {
        ContentGate(state, { onAction(WorkerAction.Retry) }) { data ->
            ZoneHeading(data.zone)
            InfoCard {
                data.workerName?.let { Fact("지정 작업자", it) }
                Fact("등록 휴대폰", when (data.registration) { Registration.INSIDE -> "지정 Wi-Fi 연결됨"; Registration.OUTSIDE -> "지정 Wi-Fi 연결 안 됨"; Registration.UNKNOWN -> "연결 상태 미확인" })
                Fact("휴대폰 연결 시각", recordedTime(data.enteredAt))
            }
            MonitoringPanel(data.monitoring)
            if(data.rescuePending)StatusPanel("관리자 현장 확인 요청", "본인 응답이 확인되지 않아 관리자에게 현장 확인이 요청된 상태입니다.",Tone.DANGER)
            if(data.checkAvailable) PrimaryAction("본인 확인 요청 열기", { onAction(WorkerAction.OpenSafetyCheck) })
            SecondaryAction("감시 상태 보기", { onAction(WorkerAction.OpenMonitoring) })
            SecondaryAction("고정 대피 안내", { onAction(WorkerAction.OpenEvacuation) })
            SecondaryAction("휴대폰 등록·연결 설정", { onAction(WorkerAction.OpenSetup) })
            Note("등록된 휴대폰의 지정 Wi-Fi 연결 상태로 자동 식별합니다. 재실은 휴대폰 연결 기반 추정입니다.")
        }
    }
}

@Composable
fun AttendanceScreen(state: ContentState<AttendanceState>, onAction: (WorkerAction) -> Unit, modifier: Modifier = Modifier) {
    ScreenFrame("입장 · 퇴장", modifier, source = (state as? ContentState.Ready)?.value?.source ?: DataSource.LIVE, onBack = { onAction(WorkerAction.Back) }) {
        ContentGate(state, { onAction(WorkerAction.Retry) }) { data ->
            val enter = data.operation == AttendanceOperation.ENTER
            val title = if (enter) "입장을 등록할까요?" else "작업을 마치셨나요?"
            ZoneHeading(data.zone)
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
            Note(if (enter) "아래 구역에 입장한 사실을 등록합니다. 등록 결과는 서버 확인 후 표시됩니다." else "구역에서 나온 뒤 퇴장을 등록해 주세요. 퇴장 처리가 확인되기 전에는 등록 상태가 유지됩니다.")
            RequestFeedback(data.request, if (enter) "입장이 등록되었습니다" else "퇴장이 등록되었습니다", if (enter) "감시 상태는 홈 화면에서 확인해 주세요." else "구역에서 나온 사실이 기록되었습니다.")
            if (data.request.status == RequestStatus.SUCCESS) {
                InfoCard { Fact("등록된 시각", recordedTime(data.recordedAt)) }
                PrimaryAction("작업자 홈으로", { onAction(WorkerAction.Back) })
            } else {
                PrimaryAction(if (enter) "입장 등록" else "퇴장 등록", { onAction(WorkerAction.SubmitAttendance(data.operation)) },
                    enabled = data.request.status in listOf(RequestStatus.READY, RequestStatus.ERROR), processing = data.request.status == RequestStatus.PROCESSING)
                if (data.request.status in listOf(RequestStatus.DUPLICATE, RequestStatus.EXPIRED)) SecondaryAction("현재 등록 상태 확인", { onAction(WorkerAction.Retry) })
            }
            Note("등록 인원은 휴대폰에서 입력한 기록입니다. 센서가 사람을 식별하거나 인원을 세는 정보가 아닙니다.")
        }
    }
}

@Composable
fun SafetyCheckScreen(state: ContentState<SafetyCheckState>, onAction: (WorkerAction) -> Unit, modifier: Modifier = Modifier, extraActions:@Composable ()->Unit = {}) {
    ScreenFrame("본인 확인", modifier, source = (state as? ContentState.Ready)?.value?.source ?: DataSource.LIVE, onBack = { onAction(WorkerAction.Back) }) {
        ContentGate(state, { onAction(WorkerAction.Retry) }) { data ->
            Text("괜찮으신가요?", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
            Text(knownText(data.zone.name), style = MaterialTheme.typography.titleMedium)
            Note("움직임이 한동안 감지되지 않았습니다. 정상적으로 작업 중이라면 아래 버튼으로 본인 응답을 보내 주세요.")
            RequestFeedback(data.request, "본인 응답이 기록되었습니다", "응답 처리가 확인되었습니다. 현재 감시 상태는 홈에서 확인해 주세요.")
            InfoCard {
                Fact("확인 요청 시각", recordedTime(data.requestedAt))
                if (data.deadlineAt != null) Fact("서버가 전달한 응답 마감", recordedTime(data.deadlineAt))
            }
            if (data.request.status == RequestStatus.SUCCESS) PrimaryAction("작업자 홈으로", { onAction(WorkerAction.Back) })
            else {
                PrimaryAction("괜찮아요", { data.eventId?.takeIf { it.isNotBlank() }?.let { onAction(WorkerAction.ConfirmResponse(it)) } },
                    enabled = !data.eventId.isNullOrBlank() && data.request.status in listOf(RequestStatus.READY, RequestStatus.ERROR),
                    processing = data.request.status == RequestStatus.PROCESSING)
                if (data.eventId.isNullOrBlank()) Note("요청 식별 정보가 없습니다. 현재 요청을 다시 불러와 주세요.")
                if (data.request.status in listOf(RequestStatus.EXPIRED, RequestStatus.DUPLICATE) || data.eventId.isNullOrBlank()) SecondaryAction("현재 요청 다시 확인", { onAction(WorkerAction.Retry) })
            }
            extraActions()
            Note("알림을 열기만 해서는 응답이 전송되지 않습니다. 움직임 미감지는 사고나 의식 상태를 판정한 결과가 아닙니다.")
        }
    }
}

@Composable
fun MonitoringScreen(state: ContentState<MonitoringState>, onAction: (WorkerAction) -> Unit, modifier: Modifier = Modifier) {
    ScreenFrame("감시 상태", modifier, source = (state as? ContentState.Ready)?.value?.source ?: DataSource.LIVE, onBack = { onAction(WorkerAction.Back) }) {
        ContentGate(state, { onAction(WorkerAction.Retry) }) { data ->
            ZoneHeading(data.zone)
            MonitoringPanel(data.status)
            InfoCard { Fact("마지막 움직임 기록", recordedTime(data.lastMotionAt)) }
            if (data.status == Monitoring.CHECK_REQUESTED) PrimaryAction("본인 확인 요청 열기", { onAction(WorkerAction.OpenSafetyCheck) })
            SecondaryAction("현재 상태 다시 불러오기", { onAction(WorkerAction.Retry) })
            Note("연결이 복구되어도 진행 중인 현장 확인 사건이 자동으로 해결되지는 않습니다.")
        }
    }
}

@Composable
fun EvacuationEntryScreen(state: ContentState<EvacuationEntryState>, onAction: (WorkerAction) -> Unit, modifier: Modifier = Modifier) {
    ScreenFrame("고정 대피 안내", modifier, source = (state as? ContentState.Ready)?.value?.source ?: DataSource.LIVE, onBack = { onAction(WorkerAction.Back) }) {
        ContentGate(state, { onAction(WorkerAction.Retry) }) { data ->
            ZoneHeading(data.zone)
            StatusPanel("설치 구역에서 시작하는 안내", "사전 등록된 도면과 출구 정보를 별도 웹 페이지에서 확인합니다. 개인의 실시간 위치나 막힌 통로는 표시하지 않습니다.")
            InfoCard {
                Fact("안내 정보", when (data.guideAvailable) { true -> "웹 안내 정보 제공됨"; false -> "안내 정보 없음"; null -> "미확인" })
                Fact("도면 검토 일자", knownText(data.reviewedAt))
            }
            PrimaryAction("대피 안내 웹 열기", { onAction(WorkerAction.OpenEvacuationWeb) }, enabled = data.guideAvailable == true)
            Note("웹 주소와 ESP32 Wi-Fi 연결 방법은 현장 설정에 따라 달라집니다. 인터넷이 없는 Wi-Fi에서는 휴대폰 푸시를 받지 못할 수 있습니다.")
        }
    }
}
