package dev.stillwatch.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.stillwatch.ui.model.*
import dev.stillwatch.ui.theme.*

enum class Tone { NEUTRAL, DANGER, SUCCESS, WARNING }
@Composable private fun toneColors(tone: Tone): StatusTone = when (tone) {
    Tone.DANGER -> LocalStatusColors.current.danger
    Tone.SUCCESS -> LocalStatusColors.current.success
    Tone.WARNING -> LocalStatusColors.current.warning
    Tone.NEUTRAL -> StatusTone(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
}

/** Insets belong here for a standalone screen. Pass contentInsets=WindowInsets(0) under a host Scaffold. */
@Composable
fun ScreenFrame(
    title: String,
    modifier: Modifier = Modifier,
    source: DataSource = DataSource.LIVE,
    onBack: (() -> Unit)? = null,
    contentInsets: WindowInsets = if (LocalHandleSystemInsets.current) WindowInsets.safeDrawing else WindowInsets(0),
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val frameInsets = Modifier.windowInsetsPadding(contentInsets)
        Column(if (LocalHandleSystemInsets.current) frameInsets.imePadding() else frameInsets) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.sm)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("StillWatch", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("뒤로") }
                    }
                    Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val bodyModifier = Modifier.weight(1f).fillMaxWidth()
            Column(
                (if (scrollable) bodyModifier.verticalScroll(rememberScrollState()) else bodyModifier)
                    .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val innerModifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
                Column(if (scrollable) innerModifier else innerModifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                    if (source == DataSource.PREVIEW) Text("체험 모드 · 예시 데이터", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    content()
                }
            }
        }
    }
}

@Composable fun SectionTitle(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}
@Composable fun ZoneHeading(zone: Zone) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text("작업 구역", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(knownText(zone.name), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        Text(knownText(zone.floor), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable fun StatusPanel(title: String, description: String, tone: Tone = Tone.NEUTRAL) {
    val colors = toneColors(tone)
    Surface(color = colors.background, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            // Symbol + explicit text; decorative symbol is hidden from TalkBack.
            Text(when (tone) { Tone.DANGER -> "!"; Tone.SUCCESS -> "✓"; Tone.WARNING -> "!"; Tone.NEUTRAL -> "i" }, Modifier.clearAndSetSemantics {}, color = colors.ink, style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(title, color = colors.ink, style = MaterialTheme.typography.titleSmall)
                Text(description, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
@Composable fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.lg), content = content)
    }
}
@Composable fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}
@Composable fun Facts(items: List<Pair<String, String>>) {
    val largeType = LocalDensity.current.fontScale > 1.3f
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wideLayout = maxWidth >= 360.dp && !largeType
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            if (wideLayout) {
                items.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                        pair.forEach { (label, value) -> Fact(label, value, Modifier.weight(1f)) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else items.forEach { (label, value) -> Fact(label, value) }
        }
    }
}
@Composable fun PrimaryAction(text: String, onClick: () -> Unit, enabled: Boolean = true, processing: Boolean = false) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled && !processing, shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.lg)) {
        if (processing) {
            CircularProgressIndicator(Modifier.size(20.dp).clearAndSetSemantics {}, strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(if (processing) "처리 중…" else text)
    }
}
@Composable fun SecondaryAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled, shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(Spacing.lg)) { Text(text) }
}
@Composable fun Note(text: String) { Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable fun <T> ContentGate(state: ContentState<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        ContentState.Loading -> Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text("정보를 불러오는 중…", style = MaterialTheme.typography.bodyLarge)
        }
        is ContentState.Error -> {
            StatusPanel("정보를 불러오지 못했습니다", state.message, Tone.WARNING)
            SecondaryAction("다시 불러오기", onRetry)
        }
        is ContentState.Empty -> StatusPanel("표시할 정보 없음", state.message)
        is ContentState.Ready -> content(state.value)
    }
}
@Composable fun RequestFeedback(request: RequestState, successTitle: String, successDescription: String) {
    val feedback = when (request.status) {
        RequestStatus.READY -> null
        RequestStatus.PROCESSING -> Triple("처리 중", "결과를 확인하고 있습니다. 잠시 기다려 주세요.", Tone.NEUTRAL)
        RequestStatus.SUCCESS -> Triple(successTitle, request.message ?: successDescription, Tone.SUCCESS)
        RequestStatus.ERROR -> Triple("처리하지 못했습니다", request.message ?: "연결 상태를 확인하고 다시 시도해 주세요.", Tone.WARNING)
        RequestStatus.DUPLICATE -> Triple("이미 처리된 요청입니다", request.message ?: "현재 등록 상태를 확인해 주세요.", Tone.NEUTRAL)
        RequestStatus.EXPIRED -> Triple("요청이 만료되었습니다", request.message ?: "현재 상태를 다시 불러와 확인해 주세요.", Tone.WARNING)
    }
    feedback?.let { (title, description, tone) ->
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { StatusPanel(title, description, tone) }
    }
}
fun monitoringLabel(status: Monitoring): String = when (status) {
    Monitoring.ACTIVE -> "감시 중"
    Monitoring.CHECK_REQUESTED -> "본인 확인 요청"
    Monitoring.RESPONSE_RECORDED -> "본인 응답 기록"
    Monitoring.UNAVAILABLE -> "감시 불가"
    Monitoring.NOT_REGISTERED -> "감시 등록 없음"
    Monitoring.UNKNOWN -> "감시 상태 미확인"
}
@Composable fun MonitoringPanel(status: Monitoring) {
    val text = when (status) {
        Monitoring.ACTIVE -> "서버에서 전달한 감시 상태입니다. 작업 중 확인 요청이 오면 응답해 주세요."
        Monitoring.CHECK_REQUESTED -> "움직임이 감지되지 않아 본인 확인을 요청했습니다."
        Monitoring.RESPONSE_RECORDED -> "본인 응답이 기록되었습니다. 응답은 현장 전체의 안전 판정을 의미하지 않습니다."
        Monitoring.UNAVAILABLE -> "센서 신호를 확인할 수 없습니다. 관리자에게 연락해 현장 상태를 확인해 주세요."
        Monitoring.NOT_REGISTERED -> "관리자가 휴대폰을 구역에 지정한 뒤 연결 상태를 확인합니다."
        Monitoring.UNKNOWN -> "전달된 정보가 부족합니다. 현재 감시 상태를 확인해 주세요."
    }
    StatusPanel(monitoringLabel(status), text, when (status) {
        Monitoring.UNAVAILABLE -> Tone.WARNING
        Monitoring.RESPONSE_RECORDED -> Tone.SUCCESS
        else -> Tone.NEUTRAL
    })
}
