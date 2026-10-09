package kr.stillwatch.app
import org.junit.Assert.*
import org.junit.Test
import dev.stillwatch.ui.model.*
import java.time.Instant

class UiAdapterTest {
    private fun state(status:AlertState)=AppState(signedIn=true,demo=true,role=Role.WORKER,sub="demo-worker",zone=demoZone(status))
    @Test fun rescueDoesNotClaimSensorDisconnectedOrCaseResolved(){val s=state(AlertState.RESCUE);assertEquals(Monitoring.ACTIVE,s.monitoring());assertEquals(IncidentStatus.OPEN,s.incident(s.zone.alerts.first()).status);assertFalse(s.ownCheckAvailable())}
    @Test fun lostSensorKeepsPendingCaseAndWorkerRequestSeparate(){val s=state(AlertState.STILL_CHECK).let{it.copy(zone=it.zone.copy(state=AlertState.SENSOR_LOST))};assertEquals(Monitoring.UNAVAILABLE,s.monitoring());assertTrue(s.ownCheckAvailable());assertEquals(RequestStatus.READY,s.responseRequest(s.zone.alerts.first()).status)}
    @Test fun staleLiveDataIsNotDisplayedAsActive(){val s=state(AlertState.ACTIVE).copy(demo=false);val now=Instant.parse(s.zone.lastReceivedAt).plusSeconds(31);assertEquals(Monitoring.UNAVAILABLE,s.monitoring(now))}
    @Test fun closedManagerCaseDoesNotInventWorkerResponse(){val s=state(AlertState.RESCUE);val a=s.zone.alerts.first().copy(closedAt="2026-10-09T05:00:00Z");assertEquals(WorkerResponse.UNANSWERED,s.incident(a).workerResponse);assertNull(s.incident(a).respondedAt)}
    @Test fun historyUsesIncidentSnapshotInsteadOfLatestMovement(){val s=state(AlertState.RESCUE);val a=s.zone.alerts.first().copy(lastMotionAt="2026-10-09T01:00:00Z",registeredCount=null);assertEquals(a.lastMotionAt,s.incident(a).lastMotionAt);assertNull(s.incident(a).registeredCount)}
    @Test fun anotherWorkersRequestIsNeverEnabled(){val s=state(AlertState.STILL_CHECK).copy(sub="another-worker");assertFalse(s.ownCheckAvailable());assertEquals(RequestStatus.EXPIRED,s.responseRequest(s.zone.alerts.first()).status)}
    @Test fun managerAcknowledgementIsNotAWorkerResponseReceipt(){val s=state(AlertState.STILL_CHECK).copy(actionName="acknowledge",actionRequest=RequestState(RequestStatus.SUCCESS));assertEquals(RequestStatus.READY,s.responseRequest(s.zone.alerts.first()).status)}
    @Test fun missingConnectivityRemainsUnknown(){val s=state(AlertState.ACTIVE).let{it.copy(zone=it.zone.copy(phoneStatus="UNKNOWN",registeredCount=null))};assertEquals(Registration.UNKNOWN,s.workerHome().registration);assertNull(s.adminHome().registeredCount)}
    @Test fun priorResponseReceiptCannotResolveAnotherRequest(){val s=state(AlertState.STILL_CHECK).copy(actionName="respond",actionAlertId="previous-request",actionRequest=RequestState(RequestStatus.SUCCESS));assertEquals(RequestStatus.READY,s.responseRequest(s.zone.alerts.first()).status)}
    @Test fun serverClosedRequestDisablesRetryAfterTransportFailure(){val initial=state(AlertState.STILL_CHECK);val closed=initial.zone.alerts.first().copy(closedAt="2026-10-09T05:00:00Z");val s=initial.copy(zone=initial.zone.copy(alerts=listOf(closed)),actionName="respond",actionAlertId=closed.id,actionRequest=RequestState(RequestStatus.ERROR));assertEquals(RequestStatus.EXPIRED,s.responseRequest(closed).status)}
}
