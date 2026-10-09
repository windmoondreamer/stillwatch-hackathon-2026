package kr.stillwatch.app

import android.content.Context
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.stillwatch.ui.components.*
import dev.stillwatch.ui.model.*

@Composable fun SettingsScreen(state:AppState,model:StillWatchViewModel,modifier:Modifier,notificationsAllowed:Boolean,refresh:Int,permission:()->Unit,back:()->Unit){
    val zone=state.zone;val context=LocalContext.current
    var sectionName by rememberSaveable{mutableStateOf(SetupSection.ZONE.name)}
    val section=SetupSection.valueOf(sectionName)
    var name by rememberSaveable(zone.name){mutableStateOf(zone.name)}
    var area by rememberSaveable(zone.area){mutableStateOf(zone.area)}
    var address by rememberSaveable(zone.address){mutableStateOf(zone.address)}
    var floor by rememberSaveable(zone.floor){mutableStateOf(zone.floor)}
    var entrance by rememberSaveable(zone.entrance){mutableStateOf(zone.entrance)}
    var callback by rememberSaveable(zone.callback){mutableStateOf(zone.callback)}
    var workTask by rememberSaveable(zone.task){mutableStateOf(zone.task)}
    var managerEnabled by rememberSaveable(zone.managerEnabled){mutableStateOf(zone.managerEnabled)}
    var guide by rememberSaveable(zone.exitGuide){mutableStateOf(zone.exitGuide)}
    var ssid by rememberSaveable(zone.targetWifiSsid){mutableStateOf(zone.targetWifiSsid)}
    var bssid by rememberSaveable(zone.targetWifiBssid){mutableStateOf(zone.targetWifiBssid)}
    var t1 by rememberSaveable(zone.t1){mutableStateOf(zone.t1.toString())}
    var t2 by rememberSaveable(zone.t2){mutableStateOf(zone.t2.toString())}
    val valid=name.isNotBlank()&&(t1.toIntOrNull()?:0) in 5..3600&&(t2.toIntOrNull()?:0) in 5..3600&&
        (bssid.isBlank()||Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}").matches(bssid))
    ScreenFrame(if(state.role==Role.MANAGER)"현장 설정"else"휴대폰·알림 설정",modifier,source=state.source(),onBack=back){
        if(state.role==Role.MANAGER){
            Text("현장을 먼저 확인해 주세요",style=MaterialTheme.typography.headlineSmall)
            Note("구역·도면·장치·연락 경로와 확인 시간을 설정합니다.")
            LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                items(SetupSection.entries.size){index->val entry=SetupSection.entries[index]
                    FilterChip(selected=section==entry,onClick={sectionName=entry.name},label={Text("${index+1}. ${entry.title}")},modifier=Modifier.heightIn(min=48.dp))
                }
            }
            SectionTitle("${section.ordinal+1} / 5 · ${section.title}")
            when(section){
                SetupSection.ZONE->{
                    OutlinedTextField(name,{name=it.take(100);model.resetRequest()},label={Text("구역명")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!state.loading)
                    OutlinedTextField(area,{area=it.take(150);model.resetRequest()},label={Text("층·구역·관리자가 찾아갈 위치")},modifier=Modifier.fillMaxWidth(),enabled=!state.loading)
                    OutlinedTextField(address,{address=it.take(200)},label={Text("도로명 주소 · 건물명")},modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(floor,{floor=it.take(100)},label={Text("층 · 세부 위치")},modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(entrance,{entrance=it.take(200)},label={Text("현장 진입 방법 · 출입구")},modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(workTask,{workTask=it.take(200)},label={Text("작업 내용")},modifier=Modifier.fillMaxWidth())
                    Note("재실은 지정 휴대폰의 Wi-Fi 연결 기반 추정으로 표시합니다.")
                }
                SetupSection.EVACUATION->{
                    InfoCard{FloorPlanCard(state,model)}
                    OutlinedTextField(guide,{guide=it.take(3000);model.resetRequest()},label={Text("출구·설치 구역에서 시작하는 이동 방향")},minLines=3,modifier=Modifier.fillMaxWidth(),enabled=!state.loading)
                    Note("도면의 ESP32 위치와 대피 경로는 실제 현장 기준으로 확인 후 저장합니다.")
                }
                SetupSection.DEVICE->{
                    InfoCard{Facts(listOf("ESP32 ID" to zone.deviceId.ifBlank{"미등록"},"연결 상태" to (state.device().connectionLabel?:"미확인"),"마지막 신호" to recordedTime(zone.lastReceivedAt)))}
                    EspSetupButton{ssid=it}
                    OutlinedTextField(ssid,{ssid=it.take(32);model.resetRequest()},label={Text("감시 대상 현장 Wi-Fi 이름")},modifier=Modifier.fillMaxWidth(),enabled=!state.loading)
                    OutlinedTextField(bssid,{bssid=it.take(17);model.resetRequest()},label={Text("접속 지점 BSSID · 선택")},modifier=Modifier.fillMaxWidth(),enabled=!state.loading,isError=bssid.isNotBlank()&&!Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}").matches(bssid))
                }
                SetupSection.CONTACTS->{
                    OutlinedTextField(callback,{callback=it.take(100)},label={Text("현장 회신 전화번호")},modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone))
                    Row {Checkbox(managerEnabled,{managerEnabled=it});Text("관리자 앱에 먼저 확인 요청",modifier=Modifier.padding(top=12.dp))}
                    Note("시험 문자 수신: 010-0000-0000. 관리자 정상 확인이 60초 안에 없으면 자동 발송합니다. ‘확인 시작’만으로 마감이 연장되지는 않습니다.")
                    InfoCard{PhoneSetup(state,model)};NotificationSettings(notificationsAllowed,permission,context)
                }
                SetupSection.TIMING->{
                    OutlinedTextField(t1,{t1=it.filter(Char::isDigit).take(4);model.resetRequest()},label={Text("T1 · 본인 확인 요청까지 (초)")},modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),isError=(t1.toIntOrNull()?:0) !in 5..3600,enabled=!state.loading)
                    OutlinedTextField(t2,{t2=it.filter(Char::isDigit).take(4);model.resetRequest()},label={Text("T2 · 무응답 현장 확인까지 (초)")},modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),isError=(t2.toIntOrNull()?:0) !in 5..3600,enabled=!state.loading)
                    Note("5~3600초 범위로 입력합니다.${if(state.demo)" 10초는 시연 값입니다."else""}")
                    Fact("관리자 응답 마감","작업자 응답 마감 이후 60초")
                }
            }
            if(state.actionName=="settings")RequestFeedback(state.actionRequest,"설정 저장이 확인되었습니다","저장된 값으로 현장 장치와 알림을 시험해 주세요.")
            PrimaryAction("설정 저장",{model.saveSettings(name,area,t1.toInt(),t2.toInt(),guide,ssid,bssid,address,floor,entrance,callback,workTask,managerEnabled)},enabled=valid&&!state.loading,processing=state.actionName=="settings"&&state.actionRequest.status==RequestStatus.PROCESSING)
        }else{
            InfoCard{PhoneSetup(state,model)}
            NotificationSettings(notificationsAllowed,permission,context)
        }
        VoiceControls(state,model)
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        SectionTitle("연결 정보")
        InfoCard{Facts(listOf("실행 모드" to if(state.demo)"체험 · 예시 데이터"else"AWS 연결","서버 리전" to "시드니","장치" to zone.deviceId.ifBlank{"미등록"},"마지막 수신" to recordedTime(zone.lastReceivedAt)))}
        if(state.demo){
            SectionTitle("체험할 상황")
            listOf(AlertState.ACTIVE,AlertState.STILL_CHECK,AlertState.RESCUE,AlertState.RESOLVED,AlertState.SENSOR_LOST).forEach{status->SecondaryAction(status.label,{model.demoScenario(status)})}
        }
        SecondaryAction(if(state.demo)"체험 종료"else"로그아웃",model::logout)
        Note("StillWatch ${BuildConfig.VERSION_NAME}")
    }
}
@Composable fun VoiceControls(state:AppState,model:StillWatchViewModel){
    var running by remember{mutableStateOf(VoiceService.running)}
    var status by remember{mutableStateOf(VoiceService.status)}
    LaunchedEffect(Unit){while(true){running=VoiceService.running;status=VoiceService.status;delay(1000)}}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){granted->
        if(state.role==Role.MANAGER||granted[Manifest.permission.RECORD_AUDIO]==true)model.startVoice()
    }
    InfoCard{
        SectionTitle(if(state.role==Role.WORKER)"음성 확인 대기"else"관리자 앱 알림 대기")
        Note(status)
        PrimaryAction(if(running)"대기 중"else"대기 시작",{
            val permissions=mutableListOf<String>();if(state.role==Role.WORKER)permissions.add(Manifest.permission.RECORD_AUDIO)
            if(Build.VERSION.SDK_INT>=33)permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            if(permissions.isEmpty())model.startVoice()else permission.launch(permissions.toTypedArray())
        },enabled=!running&&!state.demo&&!state.loading)
        SecondaryAction("대기 종료",model::stopVoice,enabled=running)
        Note("작업 시작 전에 앱을 열고 대기를 켜주세요. 홈 화면·잠금 상태에서는 실행 중인 서비스가 질문을 받습니다. 강제 종료·재부팅 후에는 다시 시작해야 합니다.")
    }
}
@Composable private fun NotificationSettings(allowed:Boolean,permission:()->Unit,context:Context){
    InfoCard{
        SectionTitle("알림 수신")
        Fact("알림 권한",if(allowed)"허용됨"else"허용 필요")
        Fact("휴대폰 푸시 연결",if(BuildConfig.FIREBASE_APP_ID.isBlank())"연결 설정 준비 중"else if(context.getSharedPreferences("push",Context.MODE_PRIVATE).getString("token",null).isNullOrBlank())"기기 등록 대기"else"기기 토큰 발급됨")
        SecondaryAction("권한 확인",permission)
        SecondaryAction("표시 시험",{Notifications.show(context,"test-notification","[테스트] StillWatch","휴대폰 알림 표시 확인용입니다. 실제 위험 알림이 아닙니다.")},enabled=allowed)
        Note("기기 토큰 발급은 실제 푸시 도착 확인과 별개입니다.")
    }
}
