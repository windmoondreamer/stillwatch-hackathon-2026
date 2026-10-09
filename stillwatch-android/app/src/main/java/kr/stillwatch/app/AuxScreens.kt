package kr.stillwatch.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.stillwatch.ui.components.*
import dev.stillwatch.ui.model.*

@Composable fun WelcomeScreen(model:StillWatchViewModel,state:AppState,modifier:Modifier){
    ScreenFrame("현장 앱 로그인",modifier){
        Text("혼자 일하는 현장",style=MaterialTheme.typography.headlineLarge)
        Note("작업자의 움직임과 응답을 확인합니다.")
        PrimaryAction("현장 계정으로 로그인",model::login,enabled=model.configured&&!state.loading,processing=state.loading)
        if(!model.configured)Note("현재 설치본은 화면과 흐름을 확인하는 개발 버전입니다.")
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        SectionTitle("예시 화면 체험")
        SecondaryAction("안전관리자",{model.demo(Role.MANAGER)})
        SecondaryAction("작업자",{model.demo(Role.WORKER)})
        state.error?.let{StatusPanel("로그인 연결 확인",it,Tone.WARNING)}
    }
}

@Composable fun IncidentActions(state:AppState,alert:Alert,model:StillWatchViewModel){
    InfoCard{
        SectionTitle("관리자 현장 확인")
        Facts(listOf("관리자 확인" to recordedTime(alert.acknowledgedAt),"현장 확인 종료" to recordedTime(alert.closedAt)))
        alert.reason?.let{Note(it)}
        state.zone.safetyCase?.takeIf{it.id==alert.caseId}?.let{c->
            SectionTitle("AI 초안·연락 상태")
            Fact("문안 준비",when(c.draftStatus){"ready"->"OpenAI 작성 완료";"preparing"->"작성 중";else->"확인된 사실로 기본 문안 준비"})
            Note(c.report)
            Fact("관리자 응답 마감",c.managerDeadline?.let{timeText(java.time.Instant.ofEpochMilli(it).toString())}?:"작업자 확인 중")
            Fact("시험 문자",contactLabel(c.contactStatus))
            if(c.contactError.isNotBlank())Note(c.contactError)
            if(c.contactMessage.isNotBlank())Note(c.contactMessage)
        }
        if(alert.closedAt==null){
            Note("‘확인 시작’은 자동 연락 마감을 연장하지 않습니다. 정상 상태를 직접 확인한 경우 아래에서 종료하고, 도움이 필요하면 바로 구조 요청을 보내주세요.")
            PrimaryAction(if(alert.acknowledgedAt==null)"알림 확인 · 현장 확인 시작"else"관리자 확인 기록됨",
                {model.action("acknowledge",alert)},enabled=alert.acknowledgedAt==null&&!state.loading)
            var open by remember(alert.id){mutableStateOf(false)}
            var reason by remember(alert.id){mutableStateOf("")}
            SecondaryAction("도움 필요 · 시험 연락처로 즉시 요청",{model.action("request-help",alert,"관리자가 도움 필요를 확인했습니다.")},enabled=!state.loading&&alert.status==AlertState.RESCUE&&alert.caseId==state.zone.safetyCase?.id&&state.zone.safetyCase?.contactStatus in listOf("standby","awaiting_manager","queued"))
            SecondaryAction("정상 확인 · 자동 연락 취소",{open=true},enabled=!state.loading)
            if(open)AlertDialog(onDismissRequest={open=false},title={Text("현장 확인 결과")},
                text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Note("작업자의 정상 상태를 확인한 근거를 기록해주세요. 이미 발송된 문자는 취소되지 않습니다.")
                    OutlinedTextField(reason,{reason=it.take(500)},label={Text("확인 결과·조치 내용")},minLines=3,modifier=Modifier.fillMaxWidth())
                }},confirmButton={TextButton(onClick={model.action("close",alert,reason);open=false},enabled=reason.trim().length>=2&&!state.loading){Text("기록 후 종료")}},
                dismissButton={TextButton(onClick={open=false}){Text("취소")}})
        }
    }
}
fun contactLabel(status:String)=when(status){"standby"->"작업자 확인 중";"awaiting_manager"->"관리자 정상 확인 대기";"queued"->"발송 준비";"sending"->"발송 요청 중";"gateway_accepted"->"AWS 발송 요청 수락 · 수신 별도 확인";"cancelled"->"정상 확인으로 취소";"unknown"->"발송 결과 미확인";"failed"->"발송 실패";else->"대기"}

@Composable fun MonitoringDetailScreen(state:AppState,model:StillWatchViewModel,modifier:Modifier,back:()->Unit,workerAction:(WorkerAction)->Unit){
    ScreenFrame("감시 상태·현장 도면",modifier,source=state.source(),onBack=back){
        ZoneHeading(state.zone.displayZone())
        MonitoringPanel(state.monitoring())
        if(state.zone.alerts.any{it.status==AlertState.RESCUE&&it.closedAt==null})StatusPanel("진행 중인 현장 확인 요청","센서 연결 상태와 별도로 미해결 사건을 확인해 주세요.",Tone.DANGER)
        if(state.ownCheckAvailable())PrimaryAction("본인 확인 요청 열기",{workerAction(WorkerAction.OpenSafetyCheck)})
        InfoCard{Facts(listOf("지정 작업자" to (state.zone.workerName?:"미지정"),"등록 휴대폰" to phoneLabel(state.zone.phoneStatus),"마지막 움직임" to recordedTime(state.zone.lastMotionAt),"마지막 데이터 수신" to recordedTime(state.zone.lastReceivedAt)));Note("재실은 휴대폰 연결 기반 추정입니다.")}
        InfoCard{FloorPlanCard(state,model)}
        InfoCard{
            SectionTitle("움직임 기록")
            MotionGraph(state.zone.samples)
            Note(state.zone.motionScore?.let{"현재 점수 %.2f · 최근 수신 순서".format(it)}?:"점수 미확인")
        }
        SecondaryAction("현재 상태 다시 불러오기",model::refresh,enabled=!state.loading)
    }
}
@Composable fun EvacuationScreen(state:AppState,model:StillWatchViewModel,modifier:Modifier,back:()->Unit){
    ScreenFrame("고정 대피 안내",modifier,source=state.source(),onBack=back){
        ZoneHeading(state.zone.displayZone())
        StatusPanel("설치 구역에서 시작하는 안내","관리자가 확인한 고정 도면과 경로입니다. 개인의 실시간 위치나 막힌 통로를 표시하지 않습니다.")
        InfoCard{FloorPlanCard(state,model)}
        if(state.zone.exitGuide.isNotBlank())InfoCard{SectionTitle("구역 대피 안내");Text(state.zone.exitGuide,style=MaterialTheme.typography.bodyLarge)}
        else Note("등록된 대피 안내가 없습니다.")
        Note("관리자가 확인한 경로만 대피 도면에 표시합니다.")
    }
}
@Composable private fun MotionGraph(samples:List<Double>){
    if(samples.size<2){Note("표시할 움직임 기록이 없습니다.");return}
    val color=MaterialTheme.colorScheme.primary
    val grid=MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(140.dp)){
        repeat(4){n->val y=size.height*n/3;drawLine(grid,Offset(0f,y),Offset(size.width,y),1f)}
        val maximum=(samples.maxOrNull()?:1.0).coerceAtLeast(.01)
        val path=Path()
        samples.forEachIndexed{index,v->val x=index*size.width/(samples.size-1);val y=size.height*(1-(v/maximum).toFloat().coerceIn(0f,1f));if(index==0)path.moveTo(x,y)else path.lineTo(x,y)}
        drawPath(path,color,style=Stroke(2.dp.toPx()))
    }
}
