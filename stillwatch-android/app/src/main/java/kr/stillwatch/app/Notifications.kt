package kr.stillwatch.app

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class StillWatchApplication: Application() {
    override fun onCreate() {
        super.onCreate(); Notifications.createChannels(this)
        if(BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_API_KEY.isNotBlank() && BuildConfig.FIREBASE_SENDER_ID.isNotBlank()) {
            FirebaseApp.initializeApp(this,FirebaseOptions.Builder().setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY).setProjectId(BuildConfig.FIREBASE_PROJECT_ID).setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID).build())
            FirebaseMessaging.getInstance().token.addOnSuccessListener { getSharedPreferences("push",MODE_PRIVATE).edit().putString("token",it).apply() }
        }
    }
}
object Notifications {
    fun createChannels(context: Context) {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("stillwatch_alerts","현장 확인 · 본인 확인 요청",NotificationManager.IMPORTANCE_HIGH).apply {
            description="작업자의 본인 확인 요청과 관리자 현장 확인 요청";enableVibration(true)
        })
        manager.createNotificationChannel(NotificationChannel("stillwatch_connection","감시 연결 상태",NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun permitted(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
    fun show(context: Context,id: String,title: String,body: String,lost: Boolean=false) {
        if(!permitted(context)) return
        val intent=Intent(context,MainActivity::class.java).putExtra("alertId",id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending=PendingIntent.getActivity(context,id.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(context,if(lost) "stillwatch_connection" else "stillwatch_alerts")
            .setSmallIcon(R.drawable.ic_shield).setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_ALARM).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        try { NotificationManagerCompat.from(context).notify(id.hashCode(),notification) } catch (_: SecurityException) { }
    }
}
class StillWatchMessagingService: FirebaseMessagingService() {
    override fun onNewToken(token: String) { getSharedPreferences("push",MODE_PRIVATE).edit().putString("token",token).apply() }
    override fun onMessageReceived(message: RemoteMessage) {
        val id=message.data["alertId"] ?: return
        Notifications.show(this,id,message.data["title"] ?: message.notification?.title ?: "StillWatch",
            message.data["body"] ?: message.notification?.body ?: "현장 정보를 확인해 주세요.",message.data["status"]=="SENSOR_LOST")
    }
}
