package kr.stillwatch.app

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable fun FloorPlanCard(state:AppState,model:StillWatchViewModel) {
    var edit by remember { mutableStateOf(false) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri->uri?.let(model::uploadPlan) }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text("현장 도면",fontSize=17.sp,fontWeight=FontWeight.SemiBold)
            if(state.role==Role.MANAGER)TextButton(onClick={picker.launch(arrayOf("application/pdf","image/png","image/jpeg"))},enabled=!state.loading) { Text(if(state.zone.floorplan==null) "도면 등록" else "도면 교체") }
        }
        val plan=state.zone.floorplan
        if(plan==null) Text("도면을 등록하면 ESP32 위치와 확인된 대피 경로를 표시합니다.",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            PlanCanvas(state.floorplanBitmap,if(plan.reviewedAt==null)plan.copy(primaryRoute=emptyList(),backupRoute=emptyList())else plan,Modifier.fillMaxWidth().height(290.dp))
            Text(if(plan.reviewedAt==null)"관리자 검토 전 · 대피 경로 미확정"else "관리자 확인: ${dev.stillwatch.ui.model.recordedTime(plan.reviewedAt)}",fontSize=12.sp)
            Text("지정 작업자: ${state.zone.workerName?:"미지정"} · 휴대폰 ${when(state.zone.phoneStatus){"CONNECTED"->"연결됨";"DISCONNECTED"->"연결되지 않음";else->"상태 미확인"}}",fontSize=13.sp)
            Text("ESP32: ${state.zone.state.label} · 재실은 휴대폰 연결 기반 추정입니다.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(plan.mime=="application/pdf")Text("PDF 첫 페이지 표시",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(state.role==Role.MANAGER)OutlinedButton(onClick={edit=true},modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.small) { Text("ESP32 위치·대피 경로 확인 및 수정") }
        }
    }
    if(edit && state.zone.floorplan!=null)PlanEditor(state.floorplanBitmap,state.zone.floorplan,{model.savePlan(it);edit=false},{edit=false})
}
@Composable private fun PlanCanvas(bitmap:Bitmap?,plan:FloorPlan,modifier:Modifier,onPoint:((PlanPoint)->Unit)?=null) {
    Box(modifier) {
        if(bitmap!=null)Image(bitmap.asImageBitmap(),"현장 도면",Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { Text("도면 표시를 준비하는 중…",fontSize=13.sp) }
        Canvas(Modifier.fillMaxSize().pointerInput(bitmap,onPoint) {
            detectTapGestures { tap ->
                if(bitmap==null||onPoint==null)return@detectTapGestures
                val scale=minOf(size.width.toFloat()/bitmap.width,size.height.toFloat()/bitmap.height)
                val w=bitmap.width*scale;val h=bitmap.height*scale;val x=(tap.x-(size.width-w)/2)/w;val y=(tap.y-(size.height-h)/2)/h
                if(x in 0f..1f && y in 0f..1f)onPoint(PlanPoint(x,y))
            }
        }) {
            if(bitmap==null)return@Canvas
            val scale=minOf(size.width/bitmap.width,size.height/bitmap.height)
            val w=bitmap.width*scale;val h=bitmap.height*scale
            fun offset(p:PlanPoint)=Offset((size.width-w)/2+p.x*w,(size.height-h)/2+p.y*h)
            fun route(points:List<PlanPoint>,color:Color,backup:Boolean) {
                if(points.size<2)return
                val path=Path();points.forEachIndexed { index,p->val o=offset(p);if(index==0)path.moveTo(o.x,o.y)else path.lineTo(o.x,o.y) }
                drawPath(path,color,style=Stroke(3.dp.toPx(),pathEffect=if(backup)PathEffect.dashPathEffect(floatArrayOf(12f,8f))else null))
                points.lastOrNull()?.let { drawCircle(color,6.dp.toPx(),offset(it)) }
            }
            route(plan.primaryRoute,Color(0xFF20634E),false);route(plan.backupRoute,Color(0xFF895119),true)
            drawCircle(Color.White,10.dp.toPx(),offset(plan.espPosition));drawCircle(Color(0xFF304E55),7.dp.toPx(),offset(plan.espPosition))
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun PlanEditor(bitmap:Bitmap?,original:FloorPlan,save:(FloorPlan)->Unit,close:()->Unit) {
    var plan by remember(original) { mutableStateOf(original) };var tool by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface) { Column(Modifier.statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                Text("도면 위치·대피 경로",modifier=Modifier.weight(1f),fontSize=23.sp,fontWeight=FontWeight.Bold)
                TextButton(onClick=close){Text("취소")}
            }
            Text("도면을 눌러 위치를 지정합니다. 경로의 마지막 점은 출구입니다.",fontSize=14.sp)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("ESP32","주 경로","대체 경로").forEachIndexed { n,label -> FilterChip(selected=tool==n,onClick={tool=n},label={Text(label)}) } }
            PlanCanvas(bitmap,plan,Modifier.fillMaxWidth().height(320.dp)) { point ->
                plan=when(tool){0->plan.copy(espPosition=point);1->plan.copy(primaryRoute=(plan.primaryRoute+point).take(100));else->plan.copy(backupRoute=(plan.backupRoute+point).take(100))}
            }
            Text("진한 점: ESP32 · 녹색: 주 경로 · 주황색 점선: 대체 경로",fontSize=12.sp)
            Text("출구와 경로는 관리자가 실제 현장 기준으로 확인해 주세요.",fontSize=13.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={plan=when(tool){1->plan.copy(primaryRoute=emptyList());2->plan.copy(backupRoute=emptyList());else->plan.copy(espPosition=PlanPoint(.5f,.5f))}},modifier=Modifier.weight(1f),shape=MaterialTheme.shapes.small) { Text("선택 항목 초기화") }
                Button(onClick={save(plan)},modifier=Modifier.weight(1f),shape=MaterialTheme.shapes.small) { Text("확인 후 저장") }
            }
        } }
    }
}
@Composable fun PhoneSetup(state:AppState,model:StillWatchViewModel) {
    val context=LocalContext.current
    var name by remember { mutableStateOf(PhoneIdentity.name(context)) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if(result[Manifest.permission.ACCESS_FINE_LOCATION]==true)model.startPresence()
    }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("작업자 휴대폰 등록",fontSize=16.sp,fontWeight=FontWeight.SemiBold)
        if(state.role==Role.WORKER) {
            OutlinedTextField(name,{name=it.take(50)},label={Text("작업자 이름")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            Button(onClick={model.registerPhone(name)},enabled=name.isNotBlank()&&!state.loading,modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.small) { Text("이 휴대폰 등록") }
            Text("휴대폰을 구역에 지정한 뒤 Wi-Fi 연결 상태로 식별합니다. GPS 좌표는 수집하지 않습니다.",fontSize=13.sp)
            OutlinedButton(onClick={
                if(state.demo)model.startPresence()else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
            },modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.small) { Text("Wi-Fi 연결 확인 켜기") }
        }else {
            Text("현재 지정: ${state.zone.workerName?:"미지정"}",fontSize=14.sp)
            OutlinedButton(onClick={model.listPhones()},modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.small) { Text("등록된 작업자 휴대폰 불러오기") }
            state.phones.forEach { phone -> OutlinedButton(onClick={model.assignPhone(phone)},modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.small) { Text("${phone.name} · 구역에 지정") } }
        }
    }
}
@Composable fun EspSetupButton(onConnected:(String)->Unit) {
    val context=LocalContext.current
    var open by remember { mutableStateOf(false) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if(result[Manifest.permission.ACCESS_FINE_LOCATION]==true)open=true
    }
    OutlinedButton(onClick={permission.launch(if(Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.NEARBY_WIFI_DEVICES)else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))},modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.small) { Text("ESP32 현장 Wi-Fi 설정") }
    if(open) {
        val controller=remember { EspProvisioning(context) };val status by controller.state.collectAsState()
        var pop by remember { mutableStateOf("") };var ssid by remember { mutableStateOf("") };var password by remember { mutableStateOf("") }
        DisposableEffect(Unit){onDispose{controller.close()}}
        AlertDialog(onDismissRequest={open=false},title={Text("ESP32 Wi-Fi 설정")},text={Column(Modifier.heightIn(max=470.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("ESP-IDF 보안 1 프로비저닝을 지원하는 장치의 설정 Wi-Fi에 연결해 주세요.",fontSize=13.sp)
            OutlinedButton(onClick={context.startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))},modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.small) { Text("휴대폰 Wi-Fi 설정 열기") }
            OutlinedTextField(pop,{pop=it},label={Text("장치 확인 코드(PoP)")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            Button(onClick={controller.connect(pop)},modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.small) { Text("연결한 ESP32 확인") }
            Text(status.message,fontSize=13.sp)
            if(status.connected){
                OutlinedButton(onClick={controller.scan()},modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.small) { Text("ESP32에서 현장 Wi-Fi 검색") }
                status.networks.forEach { network -> TextButton(onClick={ssid=network},modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.small) { Text(network) } }
                OutlinedTextField(ssid,{ssid=it},label={Text("현장 Wi-Fi 이름")},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(password,{password=it},label={Text("현장 Wi-Fi 비밀번호")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                Button(onClick={controller.provision(ssid,password);password=""},enabled=ssid.isNotBlank(),modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.small) { Text("ESP32에 연결 설정 전달") }
            }
        }},confirmButton={TextButton(onClick={if(status.success)onConnected(ssid);open=false}) { Text(if(status.success) "Wi-Fi 이름 적용" else "닫기") }})
    }
}
