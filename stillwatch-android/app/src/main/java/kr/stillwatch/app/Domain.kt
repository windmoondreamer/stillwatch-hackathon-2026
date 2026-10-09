package kr.stillwatch.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class Role(val label: String) { MANAGER("안전관리자"), WORKER("작업자") }
enum class AlertState(val label: String, val message: String) {
    ACTIVE("감시 중", "작업 중 움직임 정보를 확인하고 있습니다."),
    STILL_CHECK("본인 확인 요청", "움직임이 감지되지 않아 작업자의 응답을 기다립니다."),
    RESCUE("현장 확인 요청", "작업자의 응답이 없어 관리자 확인이 필요합니다."),
    RESOLVED("작업자 응답 확인", "본인 응답이 기록됐습니다. 감시를 계속합니다."),
    SENSOR_LOST("감시 불가", "센서 정보를 받지 못해 작업자 상태를 확인할 수 없습니다."),
    UNKNOWN("상태 미확인", "확인된 상태 정보가 없습니다.");
    companion object {
        fun parse(value: String): AlertState = when (value.uppercase()) {
            "ACTIVE", "MONITORING" -> ACTIVE
            "STILL_CHECK" -> STILL_CHECK
            "RESCUE", "EMERGENCY" -> RESCUE
            "RESOLVED", "RESPONDED" -> RESOLVED
            "SENSOR_LOST", "MONITORING_INTERRUPTED" -> SENSOR_LOST
            else -> UNKNOWN
        }
    }
}
data class TimelineEvent(val label: String, val at: String?)
data class PlanPoint(val x:Float,val y:Float)
data class FloorPlan(val key:String,val mime:String,val espPosition:PlanPoint=PlanPoint(.5f,.5f),
    val primaryRoute:List<PlanPoint> = emptyList(),val backupRoute:List<PlanPoint> = emptyList(),val reviewedAt:String?=null)
data class PhoneRef(val phoneId:String,val name:String,val sub:String)

data class SafetyCase(val id:String,val phase:String,val deadline:Long,val managerDeadline:Long?,
    val questionCount:Int,val draftStatus:String,val summary:String,val report:String,val contactStatus:String,
    val contactMessage:String,val contactError:String,val voiceError:String)
data class Alert(
    val id: String, val status: AlertState, val occurredAt: String?,
    val events: List<TimelineEvent> = emptyList(), val acknowledgedAt: String? = null,
    val closedAt: String? = null, val reason: String? = null,
    val aiSummary: String? = null,val enteredAt:String?=null,val lastMotionAt:String?=null,val registeredCount:Int?=null,
    val caseId:String?=null
)
data class Zone(
    val id: String = "room-01", val name: String = "공학관 B1 전기실",
    val area: String = "지하 1층 · 설비 점검 구역", val deviceId: String = "",
    val state: AlertState = AlertState.UNKNOWN, val registeredCount: Int? = null,
    val workerSub: String? = null, val enteredAt: String? = null, val lastMotionAt: String? = null,
    val lastReceivedAt: String? = null, val motionScore: Double? = null,
    val samples: List<Double> = emptyList(), val t1: Int = 60, val t2: Int = 60,
    val exitGuide: String = "", val guideReviewedAt: String? = null,
    val alerts: List<Alert> = emptyList(),val workerName:String?=null,val phoneStatus:String="UNKNOWN",
    val targetWifiSsid:String="",val targetWifiBssid:String="",val assignedPhoneId:String?=null,
    val floorplan:FloorPlan?=null,val address:String="",val floor:String="",val entrance:String="",
    val callback:String="",val task:String="단독 작업",val managerEnabled:Boolean=true,
    val testRecipient:String="+821000000000",val safetyCase:SafetyCase?=null
)
fun timeText(value: String?): String = try {
    DateTimeFormatter.ofPattern("MM.dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul")).format(Instant.parse(value))
} catch (_: Exception) { "미확인" }
fun countText(value: Int?): String = if (value != null && value >= 0) "${value}명" else "미확인"
fun canWorkerRespond(role: Role, state: AlertState, ownSession: Boolean): Boolean =
    role == Role.WORKER && ownSession && state == AlertState.STILL_CHECK
fun isFresh(value: String?, now: Instant = Instant.now(), seconds: Long = 30): Boolean = try {
    val age = java.time.Duration.between(Instant.parse(value), now).seconds
    age in 0..seconds
} catch (_: Exception) { false }
fun demoZone(state: AlertState = AlertState.RESCUE): Zone {
    val now = Instant.now()
    val start = now.minusSeconds(420).toString()
    val last = now.minusSeconds(150).toString()
    val requested = now.minusSeconds(60).toString()
    val eventLabels = when (state) {
        AlertState.RESCUE -> listOf(TimelineEvent("등록 휴대폰 Wi-Fi 연결", start), TimelineEvent("움직임 미감지 · 본인 확인 요청", requested), TimelineEvent("무응답 · 현장 확인 요청", now.toString()))
        AlertState.RESOLVED -> listOf(TimelineEvent("등록 휴대폰 Wi-Fi 연결", start), TimelineEvent("본인 확인 요청", requested), TimelineEvent("작업자 본인 응답", now.toString()))
        AlertState.SENSOR_LOST -> listOf(TimelineEvent("등록 휴대폰 Wi-Fi 연결", start), TimelineEvent("센서 정보 수신 중단", now.toString()))
        else -> listOf(TimelineEvent("등록 휴대폰 Wi-Fi 연결", start), TimelineEvent(state.label, now.toString()))
    }
    return Zone(deviceId="ESP32-DEMO", state=state, registeredCount=1, workerSub="demo-worker",
        enteredAt=start, lastMotionAt=last, lastReceivedAt=if(state==AlertState.SENSOR_LOST) now.minusSeconds(90).toString() else now.toString(),
        motionScore=if(state==AlertState.SENSOR_LOST) null else 0.08,
        samples=listOf(.3,.45,.35,.6,.52,.4,.65,.35,.25,.13,.09,.08,.09,.08,.06,.08),
        t1=10,t2=10,exitGuide="설비 구역 입구 → 복도 → 주 계단 → 1층 외부 출입구\n대체 출구: 반대편 비상 계단\n이 안내는 ESP32 설치 구역을 출발점으로 한 고정 예시입니다.",
        alerts=if(state==AlertState.ACTIVE) emptyList() else listOf(Alert("demo-incident",state,now.toString(),eventLabels,closedAt=if(state==AlertState.RESOLVED)now.toString()else null,enteredAt=start,lastMotionAt=last,registeredCount=1)),
        workerName="야간 점검 작업자",phoneStatus="CONNECTED",targetWifiSsid="STILLWATCH-SITE",assignedPhoneId="demo-phone")
}
