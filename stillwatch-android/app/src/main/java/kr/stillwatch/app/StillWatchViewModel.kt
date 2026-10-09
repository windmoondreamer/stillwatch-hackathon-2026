package kr.stillwatch.app

import android.app.Application
import android.net.Uri
import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import dev.stillwatch.ui.model.RequestState
import dev.stillwatch.ui.model.RequestStatus

data class AppState(val signedIn: Boolean=false,val demo: Boolean=false,val role: Role=Role.MANAGER,val sub: String="",
    val zone: Zone=Zone(),val loading: Boolean=false,val error: String?=null,val notice: String?=null,val selectedAlertId: String?=null,
    val floorplanBitmap:Bitmap?=null,val phones:List<PhoneRef> = emptyList(),
    val actionName:String?=null,val actionAlertId:String?=null,val actionRequest:RequestState=RequestState())
class StillWatchViewModel(application: Application): AndroidViewModel(application) {
    private val auth=Auth(application);private val api=Api(auth)
    private var loadedPlanKey:String?=null
    var foreground=false
    var debugArmAfterLoad=false
    private val mutable=MutableStateFlow(AppState())
    val state=mutable.asStateFlow()
    val configured get()=auth.configured
    init {
        if(auth.hasSession()) { mutable.value=mutable.value.copy(signedIn=true);refresh() }
        viewModelScope.launch { while(true) { delay(5000);if(foreground && mutable.value.signedIn && !mutable.value.demo && !mutable.value.loading) refresh() } }
    }
    fun login() { try { auth.launch() } catch(e:Exception) { mutable.value=mutable.value.copy(error=e.message) } }
    fun callback(uri: Uri) = run { task { auth.callback(uri);mutable.value=mutable.value.copy(signedIn=true,demo=false);load() } }
    fun demo(role:Role) { mutable.value=AppState(signedIn=true,demo=true,role=role,sub=if(role==Role.WORKER) "demo-worker" else "demo-manager",zone=demoZone(if(role==Role.WORKER) AlertState.STILL_CHECK else AlertState.RESCUE)) }
    fun demoScenario(status:AlertState) { if(mutable.value.demo) mutable.value=mutable.value.copy(zone=demoZone(status),selectedAlertId=null,actionName=null,actionAlertId=null,actionRequest=RequestState()) }
    fun resetRequest(){if(mutable.value.actionRequest.status!=RequestStatus.PROCESSING)mutable.value=mutable.value.copy(actionName=null,actionAlertId=null,actionRequest=RequestState())}
    fun openAlert(id:String) { if(id!=mutable.value.selectedAlertId)resetRequest();mutable.value=mutable.value.copy(selectedAlertId=id);if(!mutable.value.demo) refresh() }
    fun dismissAlert() { mutable.value=mutable.value.copy(selectedAlertId=null) }
    fun clearError() { mutable.value=mutable.value.copy(error=null,notice=null) }
    fun logout() { viewModelScope.launch {
        if(!mutable.value.demo && auth.hasSession()) try {
            val token=getApplication<Application>().getSharedPreferences("push",Application.MODE_PRIVATE).getString("token",null)
            if(!token.isNullOrBlank()) api.call("DELETE","/api/devices",JSONObject().put("token",token))
        } catch(_:Exception) { }
        auth.logout();loadedPlanKey=null;mutable.value=AppState()
        getApplication<Application>().stopService(Intent(getApplication(),PresenceService::class.java))
        getApplication<Application>().stopService(Intent(getApplication(),VoiceService::class.java))
    } }
    fun refresh() { if(mutable.value.demo){clearError();return};task { load() } }
    private suspend fun load() {
        val (zone,identity)=api.zone()
        mutable.value=mutable.value.copy(signedIn=true,demo=false,zone=zone,role=identity.first,sub=identity.second)
        getApplication<Application>().getSharedPreferences("app_runtime",Context.MODE_PRIVATE).edit().putString("role",identity.first.name).apply()
        if(identity.first==Role.WORKER)api.call("POST","/api/phones",JSONObject().put("phoneId",PhoneIdentity.id(getApplication())).put("name",PhoneIdentity.name(getApplication())))
        if(BuildConfig.DEBUG&&debugArmAfterLoad&&foreground){debugArmAfterLoad=false;startVoice()}
        if(zone.floorplan==null){loadedPlanKey=null;mutable.value=mutable.value.copy(floorplanBitmap=null)}
        if(zone.floorplan!=null && (mutable.value.floorplanBitmap==null || loadedPlanKey!=zone.floorplan.key)) {
            val info=api.call("GET","/api/floorplan")
            mutable.value=mutable.value.copy(floorplanBitmap=FloorPlans.decode(getApplication(),FloorPlans.transfer(info.getString("url")),zone.floorplan.mime))
            loadedPlanKey=zone.floorplan.key
        }
        val token=getApplication<Application>().getSharedPreferences("push",Application.MODE_PRIVATE).getString("token",null)
        if(!token.isNullOrBlank()) api.call("POST","/api/devices",JSONObject().put("token",token).put("enabled",Notifications.permitted(getApplication())).put("phoneId",PhoneIdentity.id(getApplication())))
    }
    private fun task(block:suspend ()->Unit) { if(mutable.value.loading) return;viewModelScope.launch {
        mutable.value=mutable.value.copy(loading=true,error=null)
        try { block() } catch(e:Exception) {
            if(e is ApiFailure && e.status==401) { auth.logout();mutable.value=mutable.value.copy(signedIn=false) }
            mutable.value=mutable.value.copy(error=e.message ?: "연결을 확인해 주세요.")
            if(mutable.value.actionRequest.status==RequestStatus.PROCESSING)mutable.value=mutable.value.copy(actionRequest=RequestState(RequestStatus.ERROR,e.message))
        } finally { mutable.value=mutable.value.copy(loading=false) }
    } }
    fun action(name:String,alert:Alert?=null,reason:String="") {
        val s=mutable.value
        if(s.loading)return
        if(s.demo) {
            val now=Instant.now().toString()
            if(name=="respond"&&(s.role!=Role.WORKER||s.zone.workerSub!=s.sub||s.zone.alerts.none{it.status==AlertState.STILL_CHECK&&it.closedAt==null}||s.zone.alerts.any{it.status==AlertState.RESCUE&&it.closedAt==null})){
                mutable.value=s.copy(actionName=name,actionAlertId=alert?.id?:s.zone.alerts.firstOrNull{it.status==AlertState.STILL_CHECK&&it.closedAt==null}?.id,actionRequest=RequestState(RequestStatus.EXPIRED,"현재 본인 확인 요청에 응답할 수 없습니다."));return
            }
            val zone=when(name) {
                "respond" -> s.zone.copy(state=if(s.zone.state==AlertState.SENSOR_LOST)AlertState.SENSOR_LOST else AlertState.RESOLVED,
                    alerts=s.zone.alerts.map{if(it.status==AlertState.STILL_CHECK&&it.closedAt==null)it.copy(status=AlertState.RESOLVED,closedAt=now,reason="작업자 본인 응답",events=it.events+TimelineEvent("작업자 본인 응답",now))else it})
                "acknowledge" -> s.zone.copy(alerts=s.zone.alerts.map { if(it.id==alert?.id) it.copy(acknowledgedAt=now) else it })
                "close" -> s.zone.copy(state=if(alert?.status==AlertState.RESCUE)AlertState.ACTIVE else s.zone.state,alerts=s.zone.alerts.map { if(it.id==alert?.id) it.copy(closedAt=now,reason=reason) else it })
                else -> s.zone
            }
            mutable.value=s.copy(zone=zone,notice="체험 모드에서 기록했습니다.",actionName=name,actionAlertId=alert?.id?:s.zone.alerts.firstOrNull{it.status==AlertState.STILL_CHECK&&it.closedAt==null}?.id,actionRequest=RequestState(RequestStatus.SUCCESS));return
        }
        mutable.value=s.copy(actionName=name,actionAlertId=alert?.id?:s.zone.alerts.firstOrNull{it.status==AlertState.STILL_CHECK&&it.closedAt==null}?.id,actionRequest=RequestState(RequestStatus.PROCESSING))
        task {
            val path=if(alert==null) "/api/zones/${s.zone.id}/$name" else "/api/alerts/${alert.id}/$name"
            api.call("POST",path,JSONObject().put("reason",reason));mutable.value=mutable.value.copy(actionRequest=RequestState(RequestStatus.SUCCESS));load();mutable.value=mutable.value.copy(notice="기록했습니다.")
        }
    }
    fun saveSettings(name:String,area:String,t1:Int,t2:Int,guide:String,ssid:String,bssid:String,
        address:String="",floor:String="",entrance:String="",callback:String="",workTask:String="단독 작업",managerEnabled:Boolean=true) {
        if(mutable.value.loading)return
        if(mutable.value.demo) { mutable.value=mutable.value.copy(zone=mutable.value.zone.copy(name=name,area=area,t1=t1,t2=t2,exitGuide=guide,targetWifiSsid=ssid,targetWifiBssid=bssid),notice="체험 설정을 저장했습니다.",actionName="settings",actionRequest=RequestState(RequestStatus.SUCCESS));return }
        mutable.value=mutable.value.copy(actionName="settings",actionRequest=RequestState(RequestStatus.PROCESSING))
        task { api.call("PUT","/api/zones/${mutable.value.zone.id}",JSONObject().put("name",name).put("area",area).put("t1",t1).put("t2",t2).put("exitGuide",guide).put("targetWifiSsid",ssid).put("targetWifiBssid",bssid)
            .put("address",address).put("floor",floor).put("entrance",entrance).put("callback",callback).put("task",workTask).put("managerEnabled",managerEnabled));mutable.value=mutable.value.copy(actionRequest=RequestState(RequestStatus.SUCCESS));load();mutable.value=mutable.value.copy(notice="설정을 저장했습니다.") }
    }
    fun startVoice(){
        if(mutable.value.demo){mutable.value=mutable.value.copy(notice="체험 화면에서는 실제 음성·문자 기능을 시작하지 않습니다.");return}
        try { androidx.core.content.ContextCompat.startForegroundService(getApplication(),Intent(getApplication(),VoiceService::class.java));mutable.value=mutable.value.copy(notice="음성·앱 알림 대기를 시작했습니다.") }
        catch(_:Exception){mutable.value=mutable.value.copy(error="대기를 시작하지 못했습니다. 앱을 열고 마이크·알림 권한을 확인해주세요.")}
    }
    fun stopVoice(){getApplication<Application>().stopService(Intent(getApplication(),VoiceService::class.java))}
    fun registerPhone(name:String) {
        PhoneIdentity.saveName(getApplication(),name.take(50))
        if(mutable.value.demo){mutable.value=mutable.value.copy(notice="체험 휴대폰 이름을 저장했습니다.");return}
        task{api.call("POST","/api/phones",JSONObject().put("phoneId",PhoneIdentity.id(getApplication())).put("name",name.take(50)));mutable.value=mutable.value.copy(notice="휴대폰을 등록했습니다.")}
    }
    fun listPhones(){
        if(mutable.value.demo){mutable.value=mutable.value.copy(phones=listOf(PhoneRef("demo-phone","야간 점검 작업자","demo-worker")));return}
        task{val list=api.call("GET","/api/phones").getJSONArray("phones");mutable.value=mutable.value.copy(phones=(0 until list.length()).map{val p=list.getJSONObject(it);PhoneRef(p.getString("phoneId"),p.getString("name"),p.getString("sub"))})}
    }
    fun assignPhone(phone:PhoneRef){
        if(mutable.value.demo){mutable.value=mutable.value.copy(zone=mutable.value.zone.copy(workerName=phone.name,workerSub=phone.sub,assignedPhoneId=phone.phoneId),notice="체험 구역에 지정했습니다.");return}
        task{api.call("PUT","/api/assignment",JSONObject().put("phoneId",phone.phoneId));load();mutable.value=mutable.value.copy(notice="작업자 휴대폰을 구역에 지정했습니다.")}
    }
    fun startPresence(){
        if(mutable.value.demo){mutable.value=mutable.value.copy(notice="체험 모드에서는 실제 연결 상태를 전송하지 않습니다.");return}
        try { androidx.core.content.ContextCompat.startForegroundService(getApplication(),Intent(getApplication(),PresenceService::class.java));mutable.value=mutable.value.copy(notice="Wi-Fi 연결 상태 확인을 시작했습니다.") }
        catch(_:Exception){mutable.value=mutable.value.copy(error="연결 상태 확인을 시작하지 못했습니다. 권한과 위치 서비스 설정을 확인해 주세요.")}
    }
    fun uploadPlan(uri:Uri){task{
        val context=getApplication<Application>();val mime=context.contentResolver.getType(uri).orEmpty()
        require(mime in listOf("application/pdf","image/png","image/jpeg")){"PDF·PNG·JPEG를 선택해 주세요."}
        val bytes=FloorPlans.read(context,uri);val bitmap=FloorPlans.decode(context,bytes,mime)
        if(mutable.value.demo)mutable.value=mutable.value.copy(zone=mutable.value.zone.copy(floorplan=FloorPlan("demo",mime)),floorplanBitmap=bitmap)
        else{
            val upload=api.call("POST","/api/floorplan/upload",JSONObject().put("mime",mime).put("size",bytes.size))
            FloorPlans.transfer(upload.getString("url"),bytes,mime)
            val plan=FloorPlan(upload.getString("key"),mime)
            api.call("PUT","/api/floorplan",FloorPlans.json(plan).put("confirmed",false));loadedPlanKey=plan.key;mutable.value=mutable.value.copy(floorplanBitmap=bitmap);load()
        }
        mutable.value=mutable.value.copy(notice="도면을 불러왔습니다. ESP32 위치와 대피 경로를 확인해 주세요.")
    }}
    fun savePlan(plan:FloorPlan){
        if(mutable.value.demo){mutable.value=mutable.value.copy(zone=mutable.value.zone.copy(floorplan=plan.copy(reviewedAt=Instant.now().toString())),notice="체험 대피 경로를 저장했습니다.");return}
        task{api.call("PUT","/api/floorplan",FloorPlans.json(plan).put("confirmed",true));load();mutable.value=mutable.value.copy(notice="도면 위치와 대피 경로를 저장했습니다.")}
    }
}
