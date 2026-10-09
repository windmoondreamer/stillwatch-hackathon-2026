package kr.stillwatch.app

import dev.stillwatch.ui.model.*
import dev.stillwatch.ui.model.Zone as DisplayZone
import java.time.Instant

fun AppState.source()=if(demo)DataSource.PREVIEW else DataSource.LIVE
fun Zone.displayZone()=DisplayZone(name=name,floor=area,installation=deviceId.takeIf{it.isNotBlank()},checkLocation=area.takeIf{it.isNotBlank()})
fun AppState.monitoring(now:Instant=Instant.now()):Monitoring=when {
    zone.state==AlertState.SENSOR_LOST -> Monitoring.UNAVAILABLE
    zone.state==AlertState.UNKNOWN -> Monitoring.UNKNOWN
    !demo&&!isFresh(zone.lastReceivedAt,now) -> Monitoring.UNAVAILABLE
    zone.state==AlertState.STILL_CHECK -> Monitoring.CHECK_REQUESTED
    zone.state==AlertState.RESOLVED -> Monitoring.RESPONSE_RECORDED
    else -> Monitoring.ACTIVE
}
fun AppState.device()=DeviceInfo(zone.deviceId.takeIf{it.isNotBlank()},when(monitoring()){
    Monitoring.UNAVAILABLE->"센서 정보 수신 불가";Monitoring.UNKNOWN->"연결 상태 미확인";else->"최근 센서 정보 수신됨"
},zone.lastReceivedAt)
fun AppState.pendingCheck()=zone.alerts.firstOrNull{it.status==AlertState.STILL_CHECK&&it.closedAt==null}
fun AppState.ownCheckAvailable()=role==Role.WORKER&&zone.workerSub==sub&&pendingCheck()!=null&&zone.alerts.none{it.status==AlertState.RESCUE&&it.closedAt==null}
fun Alert.displayEvent(zoneName:String)=EventRecord(id,status.name,zoneName,occurredAt,if(closedAt==null)IncidentStatus.OPEN else IncidentStatus.RESOLVED,status.label)
fun AppState.adminHome()=AdminHomeState(zone.displayZone(),zone.registeredCount,monitoring(),device(),zone.lastMotionAt,
    zone.alerts.filter{it.closedAt==null}.map{it.displayEvent(zone.name)},zone.alerts.take(3).map{it.displayEvent(zone.name)},source())
fun AppState.workerHome()=WorkerHomeState(zone.displayZone(),when(zone.phoneStatus){
    "CONNECTED"->Registration.INSIDE;"DISCONNECTED"->Registration.OUTSIDE;else->Registration.UNKNOWN
},monitoring(),zone.enteredAt,zone.workerName,ownCheckAvailable(),zone.alerts.any{it.status==AlertState.RESCUE&&it.closedAt==null},source())
fun AppState.incident(alert:Alert)=IncidentState(
    id=alert.id,kind=EventKind.fromServer(alert.status.name),status=if(alert.closedAt==null)IncidentStatus.OPEN else IncidentStatus.RESOLVED,
    zone=zone.displayZone(),registeredCount=alert.registeredCount,
    workerResponse=when(alert.status){AlertState.RESOLVED->WorkerResponse.RESPONDED;AlertState.RESCUE,AlertState.STILL_CHECK->WorkerResponse.UNANSWERED;else->WorkerResponse.UNKNOWN},
    monitoring=monitoring(),enteredAt=alert.enteredAt,lastMotionAt=alert.lastMotionAt,
    requestedAt=if(alert.status==AlertState.STILL_CHECK)alert.occurredAt else alert.events.firstOrNull{it.label.contains("본인 확인 요청") }?.at,
    alertedAt=if(alert.status==AlertState.RESCUE)alert.occurredAt else null,
    respondedAt=if(alert.status==AlertState.RESOLVED)alert.closedAt else null,
    timeline=alert.events.map{TimelineEntry(it.label,it.at)},
    aiMessage=alert.aiSummary?.let{AiMessage("제공된 상황 요약",it,null)},
    sourceRecords=listOf("알림 발생 원문" to alert.occurredAt,"휴대폰 연결 원문" to alert.enteredAt,"마지막 움직임 원문" to alert.lastMotionAt,
        "관리자 확인 원문" to alert.acknowledgedAt,"종료 원문" to alert.closedAt,"확인 결과" to alert.reason),source=source())
fun AppState.responseRequest(alert:Alert):RequestState=when {
    role!=Role.WORKER||zone.workerSub!=sub -> RequestState(RequestStatus.EXPIRED,"본인에게 지정된 확인 요청이 아닙니다.")
    actionName=="respond"&&actionAlertId==alert.id&&actionRequest.status in listOf(RequestStatus.PROCESSING,RequestStatus.SUCCESS) -> actionRequest
    alert.status==AlertState.RESOLVED -> RequestState(RequestStatus.SUCCESS)
    alert.status!=AlertState.STILL_CHECK||alert.closedAt!=null||zone.alerts.any{it.status==AlertState.RESCUE&&it.closedAt==null} -> RequestState(RequestStatus.EXPIRED)
    actionName=="respond"&&actionAlertId==alert.id&&actionRequest.status!=RequestStatus.READY -> actionRequest
    else -> RequestState()
}
fun AppState.safetyCheck(alert:Alert)=SafetyCheckState(alert.id,zone.displayZone(),responseRequest(alert),alert.occurredAt,deadlineAt=zone.safetyCase?.deadline?.let{Instant.ofEpochMilli(it).toString()},source=source())
fun AppState.sensorLost(alert:Alert?)=SensorLostState(alert?.id,zone.displayZone(),device(),alert?.occurredAt,
    zone.alerts.any{it.status==AlertState.RESCUE&&it.closedAt==null},source())
fun Alert.shareText(zone:Zone,example:Boolean)=
    "${if(example)"[예시 데이터]\n"else""}${zone.name}\n${status.label}\n휴대폰 연결 기반 추정: ${countText(registeredCount)}\n휴대폰 연결: ${recordedTime(enteredAt)}\n마지막 움직임: ${recordedTime(lastMotionAt)}\n알림 발생: ${recordedTime(occurredAt)}"
