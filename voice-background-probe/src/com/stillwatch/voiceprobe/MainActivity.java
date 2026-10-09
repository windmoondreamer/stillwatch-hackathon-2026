package com.stillwatch.voiceprobe;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import android.speech.tts.*;
import android.util.Log;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

/** Separate diagnostic app. No camera, network, AI calls or audio file storage. */
public class MainActivity extends Activity {
  static volatile boolean visible = false;
  static volatile VoiceService running;
  TextView output;
  Handler handler = new Handler(Looper.getMainLooper());
  String pendingAction;
  static synchronized void event(Context context, String name, Object... fields) {
    try {
      JSONObject value = new JSONObject();
      value.put("event", name); value.put("at", System.currentTimeMillis());
      value.put("elapsed", SystemClock.elapsedRealtime()); value.put("sdk", Build.VERSION.SDK_INT);
      value.put("activityVisible", visible);
      value.put("interactive", ((PowerManager)context.getSystemService(POWER_SERVICE)).isInteractive());
      value.put("locked", ((KeyguardManager)context.getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked());
      for (int i = 0; i < fields.length; i += 2) value.put(String.valueOf(fields[i]), fields[i+1]);
      String line = value.toString(); Log.i("StillWatchProbe", line);
      try (FileOutputStream file = context.openFileOutput("events.jsonl", MODE_APPEND)) { file.write((line + "\n").getBytes("UTF-8")); }
    } catch (Exception error) { Log.e("StillWatchProbe", "log failed", error); }
  }
  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    ScrollView scroll = new ScrollView(this); LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL); box.setPadding(28, 35, 28, 24); scroll.addView(box);
    TextView heading = new TextView(this); heading.setText("StillWatch · 음성 백그라운드 검증"); heading.setTextSize(23); box.addView(heading);
    TextView explanation = new TextView(this); explanation.setText("별도 검증 앱입니다. 카메라·서버·AI 연결은 없습니다.\n대기를 시작한 뒤 홈 화면이나 잠금 화면에서 10초 후 질문을 재생하고 마이크를 2초간 확인합니다.\n음성 파일은 저장하지 않습니다. 대기는 5분 뒤 종료됩니다."); box.addView(explanation);
    button(box, "대기 시작 · 권한 확인", "ARM");
    button(box, "10초 뒤 질문 · 홈 화면/잠금으로 전환", "DELAY");
    button(box, "지금 질문·마이크 시험", "CHECK");
    button(box, "대기 종료", "STOP");
    output = new TextView(this); output.setTextSize(11); box.addView(output); setContentView(scroll);
    handler.post(new Runnable() { public void run() { refresh(); handler.postDelayed(this, 1000); } });
    event(this, "activity_created", "targetSdk", getApplicationInfo().targetSdkVersion);
    if (getIntent().getBooleanExtra("autostart", false)) operate(getIntent().getStringExtra("action") == null ? "DELAY" : getIntent().getStringExtra("action"));
  }
  void button(LinearLayout box, String title, String action) { Button button = new Button(this); button.setText(title); button.setOnClickListener(v -> operate(action)); box.addView(button); }
  void operate(String action) {
    if (action.equals("STOP")) { stopService(new Intent(this, VoiceService.class)); return; }
    ArrayList<String> missing = new ArrayList<>();
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.RECORD_AUDIO);
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.POST_NOTIFICATIONS);
    if (!missing.isEmpty()) { pendingAction = action; requestPermissions(missing.toArray(new String[0]), 1); return; }
    startForegroundService(new Intent(this, VoiceService.class).setAction(action));
  }
  @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) { super.onRequestPermissionsResult(request, permissions, grants); if (request == 1 && pendingAction != null) { String action = pendingAction; pendingAction = null; boolean granted = true; for (int grant : grants) granted &= grant == PackageManager.PERMISSION_GRANTED; if (granted) operate(action); else event(this, "permission_denied"); } }
  void refresh() { try (BufferedReader reader = new BufferedReader(new InputStreamReader(openFileInput("events.jsonl"), "UTF-8"))) { StringBuilder text = new StringBuilder(); String line; while ((line = reader.readLine()) != null) text.append(line).append("\n\n"); output.setText(text.toString()); } catch (Exception ignored) {} }
  @Override public void onResume() { super.onResume(); visible = true; event(this, "activity_resumed"); }
  @Override public void onStop() { visible = false; event(this, "activity_stopped"); super.onStop(); }
  @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }

  public static class VoiceService extends Service {
    Handler handler = new Handler(Looper.getMainLooper());
    TextToSpeech tts; boolean ttsReady, armed, checking;
    AudioManager audio; AudioFocusRequest focus; PowerManager.WakeLock wake;
    @Override public void onCreate() {
      super.onCreate(); running = this; audio = (AudioManager)getSystemService(AUDIO_SERVICE);
      NotificationManager notifications = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
      notifications.createNotificationChannel(new NotificationChannel("monitor", "음성 확인 대기", NotificationManager.IMPORTANCE_LOW));
      tts = new TextToSpeech(this, status -> { ttsReady = status == TextToSpeech.SUCCESS; if (ttsReady) { int language = tts.setLanguage(Locale.KOREAN); if (language < 0) tts.setLanguage(Locale.US); event(this, "tts_ready", "koreanAvailable", language >= 0); } else event(this, "tts_unavailable", "status", status); });
      tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
        public void onStart(String id) { event(VoiceService.this, "speech_started", "id", id); audioState(); }
        public void onDone(String id) { event(VoiceService.this, "speech_finished", "id", id); }
        public void onError(String id) { event(VoiceService.this, "speech_failed", "id", id); }
      });
      handler.postDelayed(() -> stopSelf(), 300000);
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
      if (intent == null) { stopSelf(); return START_NOT_STICKY; }
      try {
        if (!armed) {
          PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
          Notification note = new Notification.Builder(this, "monitor").setContentTitle("StillWatch 음성 대기 검증 중").setContentText("마이크는 질문 시험 때만 2초 사용 · 최대 5분").setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(open).setOngoing(true).build();
          if (Build.VERSION.SDK_INT >= 29) startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK); else startForeground(1, note);
          armed = true; event(this, "service_armed");
        }
        if ("DELAY".equals(intent.getAction())) { event(this, "check_scheduled", "delayMs", 10000); handler.postDelayed(() -> check(), 10000); }
        if ("CHECK".equals(intent.getAction())) handler.postDelayed(() -> check(), 1000);
      } catch (Exception error) { event(this, "service_start_failed", "error", error.toString()); stopSelf(); }
      return START_NOT_STICKY;
    }
    void check() {
      if (checking || !armed) return; checking = true;
      event(this, "check_triggered");
      audioState();
      wake = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StillWatch:voice-check"); wake.acquire(15000);
      AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
      focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).setOnAudioFocusChangeListener(change -> { event(this, "focus_changed", "value", change); if (change < 0) tts.stop(); }).build();
      int result = audio.requestAudioFocus(focus); event(this, "audio_focus", "result", result);
      if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
        ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
        boolean played = tone.startTone(ToneGenerator.TONE_PROP_BEEP, 400); event(this, "tone_started", "accepted", played);
        handler.postDelayed(() -> tone.release(), 600);
        if (ttsReady) { tts.setAudioAttributes(attributes); String question = tts.getLanguage().getLanguage().equals("ko") ? "괜찮으신가요? 도움이 필요하신가요?" : "Are you okay? Do you need help?"; int accepted = tts.speak(question, TextToSpeech.QUEUE_FLUSH, null, "check-" + SystemClock.elapsedRealtime()); event(this, "speech_requested", "accepted", accepted); }
      }
      handler.postDelayed(() -> new Thread(() -> capture()).start(), 5000);
    }
    void audioState() {
      ArrayList<Integer> outputTypes = new ArrayList<>();
      for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) outputTypes.add(device.getType());
      event(this, "audio_state", "mediaVolume", audio.getStreamVolume(AudioManager.STREAM_MUSIC),
          "mediaMax", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), "mediaMuted", audio.isStreamMute(AudioManager.STREAM_MUSIC),
          "ringerMode", audio.getRingerMode(), "availableOutputTypes", outputTypes.toString());
    }
    void capture() {
      AudioRecord record = null;
      try {
        int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 4096));
        record.startRecording(); event(this, "mic_started", "recordingState", record.getRecordingState());
        short[] buffer = new short[1024]; long stop = SystemClock.elapsedRealtime() + 2000; int frames = 0, peak = 0;
        while (SystemClock.elapsedRealtime() < stop && armed) { int read = record.read(buffer, 0, buffer.length); if (read < 0) throw new IOException("AudioRecord read " + read); frames += read; for (int i = 0; i < read; i++) peak = Math.max(peak, Math.abs((int)buffer[i])); }
        boolean silenced = false;
        if (Build.VERSION.SDK_INT >= 29) for (AudioRecordingConfiguration config : audio.getActiveRecordingConfigurations()) if (config.getClientAudioSessionId() == record.getAudioSessionId()) silenced |= config.isClientSilenced();
        event(this, "mic_finished", "frames", frames, "peak", peak, "clientSilenced", silenced);
      } catch (Exception error) { event(this, "mic_failed", "error", error.toString()); }
      finally {
        if (record != null) { try { record.stop(); } catch (Exception ignored) {} record.release(); }
        handler.post(() -> { if (focus != null) audio.abandonAudioFocusRequest(focus); if (wake != null && wake.isHeld()) wake.release(); checking = false; });
      }
    }
    @Override public void onTaskRemoved(Intent rootIntent) { event(this, "task_removed", "serviceContinues", armed); }
    @Override public void onDestroy() { armed = false; running = null; handler.removeCallbacksAndMessages(null); if (tts != null) { tts.stop(); tts.shutdown(); } if (focus != null) audio.abandonAudioFocusRequest(focus); if (wake != null && wake.isHeld()) wake.release(); event(this, "service_destroyed"); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
  }
}
