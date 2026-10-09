package dev.stillwatch.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.stillwatch.ui.components.*
import dev.stillwatch.ui.model.*
import dev.stillwatch.ui.theme.Spacing

private data class SetupInput(val field: SetupField, val label: String, val hint: String, val keyboard: KeyboardType = KeyboardType.Text)
private val inputs = mapOf(
    SetupSection.ZONE to listOf(
        SetupInput(SetupField.ZONE_NAME, "구역명", "예: 공학관 전기실"),
        SetupInput(SetupField.FLOOR, "층", "예: 지하 1층"),
        SetupInput(SetupField.INSTALLATION, "ESP32 설치 위치", "예: 전기실 출입문 안쪽"),
        SetupInput(SetupField.CHECK_LOCATION, "관리자가 찾아갈 위치", "건물·출입문·현장 접근 위치"),
    ),
    SetupSection.EVACUATION to listOf(
        SetupInput(SetupField.MAIN_EXIT, "주 출구", "사전 확인한 출구명"),
        SetupInput(SetupField.ALTERNATE_EXIT, "대체 출구", "사전 확인한 대체 출구명"),
        SetupInput(SetupField.DIRECTIONS, "이동 방향", "설치 구역에서 시작하는 고정 안내"),
        SetupInput(SetupField.REVIEW_DATE, "도면 검토 날짜", "YYYY-MM-DD"),
    ),
    SetupSection.DEVICE to listOf(SetupInput(SetupField.DEVICE_ID, "ESP32 ID", "설치 장치의 식별 정보", KeyboardType.Ascii)),
    SetupSection.CONTACTS to listOf(
        SetupInput(SetupField.WORKER_CONTACT, "작업자 연락 정보", "팀에서 정한 연락 식별 정보"),
        SetupInput(SetupField.ADMIN_CONTACT, "당번 관리자 연락 정보", "팀에서 정한 연락 식별 정보"),
        SetupInput(SetupField.BACKUP_CONTACT, "예비 관리자 연락 정보", "선택 항목"),
    ),
    SetupSection.TIMING to listOf(
        SetupInput(SetupField.T1_SECONDS, "T1 · 본인 확인 요청까지 (초)", "현장에서 검토한 값", KeyboardType.Number),
        SetupInput(SetupField.T2_SECONDS, "T2 · 무응답 현장 확인까지 (초)", "현장에서 검토한 값", KeyboardType.Number),
    ),
)

@Composable
fun AdminSetupScreen(state: ContentState<AdminSetupState>, onAction: (AdminAction) -> Unit, modifier: Modifier = Modifier) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val sections = SetupSection.entries
    val section = sections[step]
    ScreenFrame("현장 최초 설정", modifier, source = (state as? ContentState.Ready)?.value?.source ?: DataSource.LIVE, onBack = { onAction(AdminAction.Back) }) {
        ContentGate(state, { onAction(AdminAction.Retry) }) { data ->
            Text("현장을 먼저 확인해 주세요", style = MaterialTheme.typography.headlineSmall)
            Note("구역·장치·연락 경로를 등록합니다. 저장 결과는 연결된 앱에서 전달받아 표시합니다.")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(sections.size) { index ->
                    FilterChip(selected = step == index, onClick = { step = index },
                        modifier = Modifier.heightIn(min = 48.dp),
                        label = { Text("${index + 1}. ${sections[index].title}") })
                }
            }
            SectionTitle("${step + 1} / ${sections.size} · ${section.title}")
            if (data.fieldErrors.isNotEmpty()) StatusPanel("입력 내용을 확인해 주세요", "${data.fieldErrors.size}개 항목에 확인이 필요합니다. 각 섹션의 안내를 확인해 주세요.", Tone.WARNING)
            inputs.getValue(section).forEach { input ->
                val error = data.fieldErrors[input.field]
                OutlinedTextField(
                    value = data.values[input.field].orEmpty(),
                    onValueChange = { onAction(AdminAction.ChangeField(input.field, it)) },
                    label = { Text(input.label) }, placeholder = { Text(input.hint) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = data.request.status != RequestStatus.PROCESSING,
                    isError = error != null,
                    supportingText = if (error != null) { { Text(error) } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = input.keyboard),
                    minLines = 1, maxLines = 4,
                )
            }
            when (section) {
                SetupSection.ZONE -> Note("휴대폰 등록 인원과 Wi-Fi 접속 인원은 서로 다른 정보입니다.")
                SetupSection.EVACUATION -> {
                    Fact("선택된 현장 도면", knownText(data.values[SetupField.PLAN_NAME]))
                    data.fieldErrors[SetupField.PLAN_NAME]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    SecondaryAction("현장 도면 선택", { onAction(AdminAction.ChoosePlan) }, enabled = data.request.status != RequestStatus.PROCESSING)
                    Note("개인 위치나 통로 폐쇄 상태를 감지하지 않습니다. 실제 안내 페이지는 ESP32 담당자의 별도 웹 결과물과 연결합니다.")
                }
                SetupSection.DEVICE -> InfoCard {
                    Facts(listOf("연결 상태" to knownText(data.device.connectionLabel), "마지막 신호" to recordedTime(data.device.lastSignalAt), "배터리" to batteryText(data.device.batteryPercent)))
                }
                SetupSection.CONTACTS -> {
                    Fact("앱 알림 권한", knownText(data.notificationPermissionLabel))
                    SecondaryAction("알림 권한 확인 요청", { onAction(AdminAction.ReviewNotificationPermission) })
                    Note("작업자·관리자·예비 관리자 전달과 실패 처리는 알림 담당자가 연결합니다.")
                }
                SetupSection.TIMING -> Note("T1/T2 각 10초는 해커톤 데모값입니다. 현장 운영 기준으로 검증된 값이 아닙니다.")
            }
            RequestFeedback(data.request, "설정 저장이 확인되었습니다", "저장된 값과 실제 장치·휴대폰 알림 경로를 현장에서 시험해 주세요.")
            if (step < sections.lastIndex) PrimaryAction("다음 · ${sections[step + 1].title}", { step += 1 }, enabled = data.request.status != RequestStatus.PROCESSING)
            else PrimaryAction("현장 설정 저장 요청", { onAction(AdminAction.SaveSetup) }, processing = data.request.status == RequestStatus.PROCESSING)
            if (step > 0) SecondaryAction("이전 항목", { step -= 1 }, enabled = data.request.status != RequestStatus.PROCESSING)
            Note("입장·정지·본인 응답·무응답 알림·전원 분리·대피 안내를 현장에서 시험한 뒤 운영을 시작합니다.")
        }
    }
}
