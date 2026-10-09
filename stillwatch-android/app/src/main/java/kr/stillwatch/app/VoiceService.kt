package kr.stillwatch.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.Manifest
import android.media.*
import android.os.*
import android.speech.tts.*
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** User starts the microphone foreground service while the activity is visible. */
class VoiceService:Service() {
    companion object { @Volatile var running=false; @Volatile var status="음성·앱 알림 대기 꺼짐" }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val main=Handler(Looper.getMainLooper())
    private val utterances=ConcurrentHashMap<String,JSONObject>()
    private val handled=mutableSetOf<String>()
    private lateinit var api:Api
    private lateinit var tts:TextToSpeech
    private lateinit var audio:AudioManager
    private lateinit var wake:PowerManager.WakeLock
    @Volatile private var ready=false
    @Volatile private var busy=false
    @Volatile private var epoch=0
    @Volatile private var offset=0L
    @Volatile private var incidentId=""
    private var role="WORKER"
    private var focus:AudioFocusRequest?=null
    override fun onBind(intent:Intent?):IBinder?=null
    private fun record(event:String,extra:String="") {
        val power=getSystemService(PowerManager::class.java)
        val locked=getSystemService(KeyguardManager::class.java).isKeyguardLocked
        Log.i("StillWatchVoice","event=$event locked=$locked interactive=${power.isInteractive} $extra")
        try { openFileOutput("voice-events.log",MODE_APPEND).use { it.write("${System.currentTimeMillis()} $event locked=$locked interactive=${power.isInteractive} $extra\n".toByteArray()) } } catch(_:Exception){}
    }
    override fun onCreate(){
        super.onCreate();role=getSharedPreferences("app_runtime",MODE_PRIVATE).getString("role","WORKER")!!
        api=Api(Auth(this));audio=getSystemService(AudioManager::class.java)
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("stillwatch_voice","작업자 음성·관리자 앱 알림 대기",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,20,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,21,Intent(this,VoiceService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(this,"stillwatch_voice").setSmallIcon(R.drawable.ic_shield).setContentTitle(if(role=="WORKER")"StillWatch 음성 확인 대기"else"StillWatch 관리자 알림 대기")
            .setContentText("앱 강제 종료·전원 종료 시 대기가 중단됩니다.").setContentIntent(open).addAction(0,"대기 종료",stop).setOngoing(true).build()
        if(Build.VERSION.SDK_INT>=29)startForeground(1002,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or if(role=="WORKER")ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        else startForeground(1002,notification)
        wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"StillWatch:voice");wake.acquire(600000)
        running=true;status="서버 연결 준비 중";record("service_started","role=$role")
        tts=TextToSpeech(this){code->ready=code==TextToSpeech.SUCCESS;if(ready)ready=tts.setLanguage(Locale.KOREAN)>=0;record("tts_ready","ready=$ready")}
        tts.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
            override fun onStart(id:String){record("speech_started","volume=${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}")}
            override fun onDone(id:String){
                val command=utterances.remove(id)?:return;record("speech_finished")
                scope.launch {
                    postEvent(command,"spoken")
                    main.post { focus?.let(audio::abandonAudioFocusRequest) }
                    if(command.optInt("recordSeconds")>0)capture(command)else busy=false
                }
            }
            override fun onError(id:String){val command=utterances.remove(id);busy=false;record("speech_failed");command?.let { scope.launch { postEvent(it,"failed","질문 재생 실패") } }}
        })
        scope.launch {
            while(isActive){
                try {
                    if(!wake.isHeld)wake.acquire(600000)
                    val data=api.call("POST","/api/voice/poll",common().put("armed",true))
                    offset=data.optLong("serverTime")-System.currentTimeMillis();incidentId=data.optString("incidentId")
                    status="서버 연결됨 · ${data.optString("state")}\n연락: ${data.optString("contactStatus","대기")}"
                    data.optJSONObject("command")?.let { command->main.post { execute(command) } }
                }catch(e:Exception){status="연결 확인 필요: ${e.message}";record("poll_failed",e.javaClass.simpleName)}
                delay(2000)
            }
        }
    }
    private fun common()=JSONObject().put("phoneId",PhoneIdentity.id(this))
    private suspend fun postEvent(command:JSONObject,kind:String,error:String?=null){
        try { api.call("POST","/api/voice/event",common().put("incidentId",command.optString("incidentId")).put("commandId",command.optString("id")).put("kind",kind).put("error",error)) } catch(_:Exception){}
    }
    private fun execute(command:JSONObject){
        val id=command.optString("id");val kind=command.optString("kind")
        if(id in handled||System.currentTimeMillis()+offset>=command.optLong("deadline",Long.MAX_VALUE))return
        if(kind=="manager_alert"){
            if(!Notifications.permitted(this)){status="관리자 알림 권한이 필요합니다.";return}
            Notifications.show(this,command.optString("alertId",command.optString("incidentId")),"작업자 확인 · 60초 내 응답",command.optString("prompt"))
            handled.add(id);scope.launch {postEvent(command,"received")};record("manager_notification");return
        }
        if(!ready||kind=="check"&&busy)return
        if(kind=="warning"){epoch++;tts.stop();busy=false}
        val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).setOnAudioFocusChangeListener { change->
            if(change==AudioManager.AUDIOFOCUS_LOSS&&utterances.isNotEmpty()){tts.stop();epoch++;busy=false;record("audio_interrupted")}
        }.build()
        if(audio.requestAudioFocus(focus!!)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){scope.launch{postEvent(command,"failed","오디오 사용 중")};return}
        handled.add(id);if(handled.size>300)handled.clear();utterances[id]=command;busy=true;tts.setAudioAttributes(attrs)
        scope.launch { postEvent(command,"received") }
        if(tts.speak(command.optString("prompt"),TextToSpeech.QUEUE_FLUSH,null,id)!=TextToSpeech.SUCCESS){busy=false;scope.launch{postEvent(command,"failed","TTS 실패")}}
    }
    private suspend fun capture(command:JSONObject){
        var recorder:AudioRecord?=null;val capturedEpoch=epoch
        try {
            if(command.optString("incidentId")!=incidentId||System.currentTimeMillis()+offset>=command.optLong("deadline"))return
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)throw SecurityException("마이크 권한이 필요합니다.")
            val size=maxOf(4096,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT))
            recorder=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size)
            recorder.startRecording();record("mic_started")
            val pcm=ByteArrayOutputStream();val samples=ShortArray(1024);var peak=0
            val end=minOf(System.currentTimeMillis()+5000,command.optLong("deadline")-offset)
            while(running&&capturedEpoch==epoch&&System.currentTimeMillis()<end){
                val count=recorder.read(samples,0,samples.size);check(count>=0){"마이크 수집 실패"}
                for(n in 0 until count){val value=samples[n].toInt();peak=maxOf(peak,kotlin.math.abs(value));pcm.write(value and 255);pcm.write((value shr 8) and 255)}
            }
            val sessionId=recorder.audioSessionId
            val silenced=Build.VERSION.SDK_INT>=29&&audio.activeRecordingConfigurations.any { it.clientAudioSessionId==sessionId&&it.isClientSilenced }
            recorder.stop();recorder.release();recorder=null;record("mic_finished","bytes=${pcm.size()} peak=$peak silenced=$silenced")
            if(!running||capturedEpoch!=epoch||System.currentTimeMillis()+offset>=command.optLong("deadline"))return
            check(!silenced){"마이크가 다른 앱 또는 시스템에 의해 차단됐습니다."}
            if(peak<150)return
            val result=api.call("POST","/api/voice/audio",common().put("incidentId",command.optString("incidentId")).put("wav",Base64.encodeToString(wav(pcm.toByteArray()),Base64.NO_WRAP)))
            if(result.has("error")){status=result.optString("error");return}
            val reply=result.optString("reply")
            if(reply.isNotBlank()&&capturedEpoch==epoch&&System.currentTimeMillis()+offset<command.optLong("deadline"))main.post {if(capturedEpoch==epoch)execute(JSONObject().put("id",command.optString("id")+":reply").put("incidentId",incidentId).put("kind","reply").put("prompt",reply).put("deadline",command.optLong("deadline")))}
            record("audio_interpreted","category=${result.optString("category")}")
        }catch(e:Exception){status="음성 확인 오류: ${e.message}";postEvent(command,"failed",e.message)}
        finally{try{recorder?.stop()}catch(_:Exception){};recorder?.release();busy=false}
    }
    private fun wav(pcm:ByteArray):ByteArray{
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36+pcm.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size)
        return header.array()+pcm
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{if(intent?.action=="STOP")stopSelf();return START_NOT_STICKY}
    override fun onDestroy(){running=false;epoch++;scope.cancel();if(::tts.isInitialized){tts.stop();tts.shutdown()};focus?.let(audio::abandonAudioFocusRequest);if(::wake.isInitialized&&wake.isHeld)wake.release();status="음성·앱 알림 대기 꺼짐";record("service_stopped");super.onDestroy()}
}
