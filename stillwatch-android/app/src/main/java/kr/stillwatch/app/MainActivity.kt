package kr.stillwatch.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stillwatch.ui.admin.*
import dev.stillwatch.ui.components.*
import dev.stillwatch.ui.model.*
import dev.stillwatch.ui.theme.StillWatchTheme
import dev.stillwatch.ui.worker.*

class MainActivity:ComponentActivity(){
    private val model:StillWatchViewModel by viewModels()
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);enableEdgeToEdge();handleIntent(intent)
        setContent{StillWatchTheme(handleSystemInsets=false){StillWatch(model)}}
    }
    override fun onStart(){super.onStart();model.foreground=true}
    override fun onStop(){model.foreground=false;super.onStop()}
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);handleIntent(intent)}
    private fun handleIntent(intent:Intent){
        if(BuildConfig.DEBUG)intent.getStringExtra("debugFixture")?.takeIf{it in listOf("normal","help")}?.let{DebugFixtures.generate(this,it)}
        if(BuildConfig.DEBUG&&intent.getBooleanExtra("debugImportSession",false)){
            val session=java.io.File(filesDir,"debug-session.json")
            if(session.exists()){stopService(Intent(this,VoiceService::class.java));SecureSession(this).write(org.json.JSONObject(session.readText()));session.delete();model.debugArmAfterLoad=intent.getBooleanExtra("debugArmVoice",false);model.refresh()}
        }
        intent.data?.takeIf{it.scheme=="stillwatch"&&it.host=="auth"}?.let{model.callback(it)}
        intent.getStringExtra("alertId")?.let(model::openAlert)
    }
}

@Composable private fun StillWatch(model:StillWatchViewModel){
    val state by model.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    var page by rememberSaveable{mutableStateOf("home")}
    var lastIdentity by rememberSaveable{mutableStateOf("${state.signedIn}:${state.role}")}
    var permissionsRefresh by remember{mutableIntStateOf(0)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){permissionsRefresh++}
    val snackbar=remember{SnackbarHostState()}
    LaunchedEffect(state.notice,state.error){(state.error?:state.notice)?.let{snackbar.showSnackbar(it);model.clearError()}}
    LaunchedEffect(state.signedIn,state.role){val identity="${state.signedIn}:${state.role}";if(identity!=lastIdentity){page="home";lastIdentity=identity}}
    fun go(next:String){model.dismissAlert();model.resetRequest();page=next}
    fun back(){if(state.selectedAlertId!=null)model.dismissAlert()else page="home"}
    BackHandler(enabled=state.selectedAlertId!=null||page!="home"){back()}
    fun notificationPermission(){
        if(Build.VERSION.SDK_INT>=33)permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,context.packageName))
    }
    val adminAction:(AdminAction)->Unit={a->when(a){
        AdminAction.OpenSetup->go("settings")
        AdminAction.OpenHistory->go("history")
        is AdminAction.OpenIncident->model.openAlert(a.eventId)
        AdminAction.OpenMonitoringIssue->state.zone.alerts.firstOrNull{it.status==AlertState.SENSOR_LOST&&it.closedAt==null}?.let{model.openAlert(it.id)}?:go("sensor")
        AdminAction.Retry->model.refresh()
        AdminAction.Back->back()
        is AdminAction.ShareRecords->state.zone.alerts.firstOrNull{it.id==a.eventId}?.let{alert->
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,alert.shareText(state.zone,state.demo))},"기록 공유"))
        }
        else->Unit
    }}
    val workerAction:(WorkerAction)->Unit={a->when(a){
        WorkerAction.OpenSetup->go("settings")
        WorkerAction.OpenMonitoring->go("monitoring")
        WorkerAction.OpenEvacuation,WorkerAction.OpenEvacuationWeb->go("evacuation")
        WorkerAction.OpenSafetyCheck->state.pendingCheck()?.let{model.resetRequest();model.openAlert(it.id)}
        is WorkerAction.ConfirmResponse->if(state.ownCheckAvailable()&&state.pendingCheck()?.id==a.eventId)model.action("respond")
        WorkerAction.Retry->model.refresh()
        WorkerAction.Back->if(state.actionName=="respond"&&state.actionRequest.status==RequestStatus.SUCCESS)go("home")else back()
        else->Unit
    }}
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){
    Scaffold(
        modifier=Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
        containerColor=MaterialTheme.colorScheme.background,contentWindowInsets=WindowInsets(0),
        snackbarHost={SnackbarHost(snackbar)},
        bottomBar={if(state.signedIn)NavigationBar(containerColor=MaterialTheme.colorScheme.surface,windowInsets=WindowInsets(0)){
            val entries=listOf(Triple("home","홈",Icons.Outlined.Home),Triple("history","기록",Icons.Outlined.History),Triple("settings","설정",Icons.Outlined.Settings))
            entries.forEach{(key,label,icon)->NavigationBarItem(selected=page==key&&state.selectedAlertId==null,onClick={go(key)},icon={Icon(icon,label)},label={Text(label)})}
        }}
    ){padding->
        val modifier=Modifier.padding(padding)
        if(!state.signedIn){WelcomeScreen(model,state,modifier);return@Scaffold}
        val selected=state.zone.alerts.firstOrNull{it.id==state.selectedAlertId}
        when{
            state.selectedAlertId!=null&&selected==null->ScreenFrame("알림 확인",modifier,source=state.source(),onBack={back()}){StatusPanel("알림을 찾을 수 없습니다","현재 연결에서 해당 기록이 확인되지 않습니다.",Tone.WARNING);SecondaryAction("다시 불러오기",{model.refresh()})}
            selected!=null&&state.role==Role.WORKER&&(selected.status==AlertState.STILL_CHECK||(selected.status==AlertState.RESOLVED&&state.actionName=="respond"&&state.actionAlertId==selected.id))->SafetyCheckScreen(ContentState.Ready(state.safetyCheck(selected)),workerAction,modifier){
                if(state.ownCheckAvailable())SecondaryAction("도움이 필요해요 · 즉시 요청",{model.action("help")},enabled=!state.loading)
                state.zone.safetyCase?.let{Note("음성 질문 ${it.questionCount}회 · ${contactLabel(it.contactStatus)}")}
            }
            selected!=null&&selected.status==AlertState.SENSOR_LOST->SensorLostScreen(ContentState.Ready(state.sensorLost(selected)),adminAction,modifier){if(state.role==Role.MANAGER)IncidentActions(state,selected,model)}
            selected!=null->IncidentDetailScreen(ContentState.Ready(state.incident(selected)),adminAction,modifier){if(state.role==Role.MANAGER)IncidentActions(state,selected,model)}
            page=="settings"->SettingsScreen(state,model,modifier,Notifications.permitted(context),permissionsRefresh,{notificationPermission()},{back()})
            page=="history"->EventHistoryScreen(ContentState.Ready(EventHistoryState(state.zone.alerts.map{it.displayEvent(state.zone.name)},state.source())),adminAction,modifier)
            page=="sensor"->SensorLostScreen(ContentState.Ready(state.sensorLost(state.zone.alerts.firstOrNull{it.status==AlertState.SENSOR_LOST&&it.closedAt==null})),adminAction,modifier)
            page=="evacuation"->EvacuationScreen(state,model,modifier,{back()})
            page=="monitoring"->MonitoringDetailScreen(state,model,modifier,{back()},workerAction)
            state.role==Role.MANAGER->AdminHomeScreen(homeContent(state),adminAction,modifier){
                InfoCard{Facts(listOf("지정 작업자" to (state.zone.workerName?:"미지정"),"등록 휴대폰" to phoneLabel(state.zone.phoneStatus)));Note("재실은 휴대폰 연결 기반 추정입니다.")}
                SecondaryAction("현장 도면·움직임 기록",{go("monitoring")})
            }
            else->WorkerHomeScreen(workerContent(state),workerAction,modifier)
        }
    }
    }
}
private fun homeContent(s:AppState):ContentState<AdminHomeState> = when{
    s.loading&&s.zone.deviceId.isBlank()->ContentState.Loading
    s.error!=null&&!s.demo->ContentState.Error(s.error)
    else->ContentState.Ready(s.adminHome())
}
private fun workerContent(s:AppState):ContentState<WorkerHomeState> = when{
    s.loading&&s.zone.deviceId.isBlank()->ContentState.Loading
    s.error!=null&&!s.demo->ContentState.Error(s.error)
    else->ContentState.Ready(s.workerHome())
}
fun phoneLabel(status:String)=when(status){"CONNECTED"->"지정 Wi-Fi 연결됨";"DISCONNECTED"->"지정 Wi-Fi 연결 안 됨";else->"연결 상태 미확인"}
