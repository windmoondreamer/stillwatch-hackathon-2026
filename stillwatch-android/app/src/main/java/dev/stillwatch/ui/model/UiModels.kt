package dev.stillwatch.ui.model

/** Presentation contracts only; these types do not define a backend wire format. */
sealed interface ContentState<out T> {
    data class Ready<T>(val value: T) : ContentState<T>
    data object Loading : ContentState<Nothing>
    data class Error(val message: String) : ContentState<Nothing>
    data class Empty(val message: String = "표시할 정보가 없습니다.") : ContentState<Nothing>
}

enum class DataSource { LIVE, PREVIEW }
enum class Monitoring { ACTIVE, CHECK_REQUESTED, RESPONSE_RECORDED, UNAVAILABLE, NOT_REGISTERED, UNKNOWN }
enum class Registration { OUTSIDE, INSIDE, UNKNOWN }
enum class RequestStatus { READY, PROCESSING, SUCCESS, ERROR, DUPLICATE, EXPIRED }
data class RequestState(val status: RequestStatus = RequestStatus.READY, val message: String? = null)
enum class AttendanceOperation { ENTER, EXIT }
enum class EventKind {
    STILL_CHECK, RESOLVED, RESCUE, SENSOR_LOST, UNKNOWN;
    companion object { fun fromServer(raw: String): EventKind = entries.firstOrNull { it.name == raw } ?: UNKNOWN }
}
enum class IncidentStatus { OPEN, RESOLVED, UNKNOWN }
enum class WorkerResponse { UNANSWERED, RESPONDED, UNKNOWN }
data class Zone(val name: String? = null, val floor: String? = null, val installation: String? = null, val checkLocation: String? = null)
data class DeviceInfo(val id: String? = null, val connectionLabel: String? = null, val lastSignalAt: String? = null, val batteryPercent: Int? = null)

data class WorkerHomeState(
    val zone: Zone,
    val registration: Registration,
    val monitoring: Monitoring,
    val enteredAt: String? = null,
    val workerName: String? = null,
    val checkAvailable: Boolean = false,
    val rescuePending: Boolean = false,
    val source: DataSource = DataSource.LIVE,
)
data class AttendanceState(
    val zone: Zone,
    val operation: AttendanceOperation,
    val request: RequestState = RequestState(),
    val recordedAt: String? = null,
    val source: DataSource = DataSource.LIVE,
)
data class SafetyCheckState(
    val eventId: String?,
    val zone: Zone,
    val request: RequestState = RequestState(),
    val requestedAt: String? = null,
    val deadlineAt: String? = null,
    val source: DataSource = DataSource.LIVE,
)
data class MonitoringState(
    val zone: Zone,
    val status: Monitoring,
    val lastMotionAt: String? = null,
    val source: DataSource = DataSource.LIVE,
)
data class EventRecord(
    val id: String?,
    val rawType: String,
    val zoneName: String?,
    val occurredAt: String?,
    val status: IncidentStatus = IncidentStatus.UNKNOWN,
    val label: String? = null,
)
data class TimelineEntry(val label: String, val at: String?)
data class AiMessage(val title: String?, val summary: String?, val followUp: String?)
data class IncidentState(
    val id: String?,
    val kind: EventKind,
    val status: IncidentStatus,
    val zone: Zone,
    val registeredCount: Int?,
    val workerResponse: WorkerResponse,
    val monitoring: Monitoring,
    val enteredAt: String? = null,
    val lastMotionAt: String? = null,
    val requestedAt: String? = null,
    val alertedAt: String? = null,
    val respondedAt: String? = null,
    val timeline: List<TimelineEntry> = emptyList(),
    val aiMessage: AiMessage? = null,
    val sourceRecords: List<Pair<String, String?>> = emptyList(),
    val source: DataSource = DataSource.LIVE,
)
data class AdminHomeState(
    val zone: Zone,
    val registeredCount: Int?,
    val monitoring: Monitoring,
    val device: DeviceInfo,
    val lastMotionAt: String?,
    val unresolved: List<EventRecord> = emptyList(),
    val recentEvents: List<EventRecord> = emptyList(),
    val source: DataSource = DataSource.LIVE,
)
data class SensorLostState(
    val eventId: String?,
    val zone: Zone,
    val device: DeviceInfo,
    val occurredAt: String?,
    val hasUnresolvedIncident: Boolean? = null,
    val source: DataSource = DataSource.LIVE,
)
data class EventHistoryState(val events: List<EventRecord>, val source: DataSource = DataSource.LIVE)

enum class SetupSection(val title: String) { ZONE("현장 구역"), EVACUATION("대피 안내"), DEVICE("장치 정보"), CONTACTS("연락 대상"), TIMING("확인 시간") }
enum class SetupField {
    ZONE_NAME, FLOOR, INSTALLATION, CHECK_LOCATION,
    PLAN_NAME, MAIN_EXIT, ALTERNATE_EXIT, DIRECTIONS, REVIEW_DATE,
    DEVICE_ID, WORKER_CONTACT, ADMIN_CONTACT, BACKUP_CONTACT, T1_SECONDS, T2_SECONDS,
}
/** Draft edits and validation errors are owned by the host. Selection is only UI navigation. */
data class AdminSetupState(
    val values: Map<SetupField, String> = emptyMap(),
    val fieldErrors: Map<SetupField, String> = emptyMap(),
    val device: DeviceInfo = DeviceInfo(),
    val notificationPermissionLabel: String? = null,
    val request: RequestState = RequestState(),
    val source: DataSource = DataSource.LIVE,
)
data class EvacuationEntryState(val zone: Zone, val guideAvailable: Boolean?, val reviewedAt: String?, val source: DataSource = DataSource.LIVE)

data class NotificationContent(val eventId: String?, val kind: EventKind, val title: String, val body: String, val publicBody: String, val destination: String)

sealed interface WorkerAction {
    data object OpenSetup : WorkerAction
    data object OpenEntry : WorkerAction
    data object OpenExit : WorkerAction
    data object OpenMonitoring : WorkerAction
    data object OpenSafetyCheck : WorkerAction
    data object OpenEvacuation : WorkerAction
    data object OpenEvacuationWeb : WorkerAction
    data class SubmitAttendance(val operation: AttendanceOperation) : WorkerAction
    data class ConfirmResponse(val eventId: String) : WorkerAction
    data object Retry : WorkerAction
    data object Back : WorkerAction
}
sealed interface AdminAction {
    data object OpenSetup : AdminAction
    data object OpenHistory : AdminAction
    data object OpenMonitoringIssue : AdminAction
    data class OpenIncident(val eventId: String) : AdminAction
    data class ChangeField(val field: SetupField, val value: String) : AdminAction
    data object ChoosePlan : AdminAction
    data object ReviewNotificationPermission : AdminAction
    data object SaveSetup : AdminAction
    data class ShareRecords(val eventId: String) : AdminAction
    data object Retry : AdminAction
    data object Back : AdminAction
}
