package kr.stillwatch.app
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID
object PhoneIdentity {
    fun id(context:Context):String {
        val prefs=context.getSharedPreferences("phone_identity",Context.MODE_PRIVATE)
        return prefs.getString("id",null)?:UUID.randomUUID().toString().also { prefs.edit().putString("id",it).apply() }
    }
    fun name(context:Context)=context.getSharedPreferences("phone_identity",Context.MODE_PRIVATE).getString("name","작업자 휴대폰").orEmpty()
    fun saveName(context:Context,name:String) { context.getSharedPreferences("phone_identity",Context.MODE_PRIVATE).edit().putString("name",name).apply() }
    fun wifi(context:Context):JSONObject {
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val hasWifi=manager.allNetworks.any { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true }
        if(!hasWifi)return JSONObject().put("connection","DISCONNECTED")
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return JSONObject().put("connection","UNKNOWN")
        @Suppress("DEPRECATION")
        val info=(context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
        val ssid=info?.ssid?.trim('"')
        if(ssid.isNullOrBlank()||ssid=="<unknown ssid>")return JSONObject().put("connection","UNKNOWN")
        return JSONObject().put("connection","CONNECTED").put("ssid",ssid).put("bssid",info.bssid?.takeUnless { it=="02:00:00:00:00:00" })
    }
}
class PresenceService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("stillwatch_presence","작업 구역 연결 확인",NotificationManager.IMPORTANCE_LOW))
        val pending=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        startForeground(1001,NotificationCompat.Builder(this,"stillwatch_presence").setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("작업 구역 연결 확인 중").setContentText("휴대폰의 Wi-Fi 연결 상태를 확인합니다.").setContentIntent(pending).setOngoing(true).build())
        scope.launch {
            val auth=Auth(this@PresenceService);val api=Api(auth)
            while(isActive) {
                if(!auth.hasSession()){stopSelf();break}
                try { api.call("POST","/api/presence",PhoneIdentity.wifi(this@PresenceService).put("phoneId",PhoneIdentity.id(this@PresenceService))) }
                catch(_:Exception) { /* The server marks missing phone heartbeats UNKNOWN. */ }
                delay(15000)
            }
        }
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_NOT_STICKY
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
