package com.stillwatch.worker;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.media.*;
import android.os.*;
import android.speech.tts.*;
import android.telephony.*;
import android.util.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/** Native background terminal. API keys remain on the server; PCM stays in memory. */
public class MainActivity extends Activity {
  static volatile VoiceService service;
  static volatile boolean visible;
  static String status = "연결 대기";
  EditText address, token, note; Spinner role; TextView output; CheckBox sms;
  Handler handler = new Handler(Looper.getMainLooper());
  static synchronized void event(Context context, String name, Object... fields) {
    try {
      JSONObject value = new JSONObject(); value.put("event", name); value.put("at", System.currentTimeMillis());
      value.put("interactive", ((PowerManager)context.getSystemService(POWER_SERVICE)).isInteractive());
      value.put("locked", ((KeyguardManager)context.getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked()); value.put("visible", visible);
      for (int i = 0; i < fields.length; i += 2) value.put(String.valueOf(fields[i]), fields[i+1]);
      Log.i("StillWatchWorker", value.toString());
      try (FileOutputStream file = context.openFileOutput("events.jsonl", MODE_APPEND)) { file.write((value + "\n").getBytes("UTF-8")); }
    } catch (Exception ignored) {}
  }
  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved); ScrollView scroll = new ScrollView(this); LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL); box.setPadding(24, 32, 24, 24); scroll.addView(box);
    TextView title = new TextView(this); title.setText("StillWatch · 휴대폰 연결"); title.setTextSize(22); box.addView(title);
    TextView info = new TextView(this); info.setText("서버 주소와 연결 코드를 입력해 대기를 시작하세요.\n화면이 잠겨도 서비스가 대기합니다. 질문 후 최대 5초의 음성을 서버로 전송하며 음성 파일은 저장하지 않습니다.\nOpenAI API 키는 서버에만 넣습니다."); box.addView(info);
    SharedPreferences prefs = getSharedPreferences("terminal", MODE_PRIVATE);
    address = field(box, "서버 주소", prefs.getString("url", "http://127.0.0.1:3210"));
    token = field(box, "휴대폰 연결 코드", prefs.getString("token", "")); token.setInputType(129);
    role = new Spinner(this); role.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"작업자", "관리자"})); role.setSelection(prefs.getString("role", "worker").equals("manager") ? 1 : 0); box.addView(role);
    sms = new CheckBox(this); sms.setText("이 기기로 등록 연락처에 SMS 자동 발송 · 문자 가능한 SIM 필요"); sms.setChecked(prefs.getBoolean("smsEnabled", false)); box.addView(sms);
    button(box, "연결 · 음성 대기 시작", () -> arm()); button(box, "대기 종료", () -> stopService(new Intent(this, VoiceService.class)));
    button(box, "작업자: 괜찮습니다", () -> action("confirm", null)); button(box, "작업자: 도움이 필요합니다", () -> action("help", null));
    note = field(box, "관리자 확인 방법·메모", "");
    button(box, "관리자: 확인 중", () -> action("manager", "checking")); button(box, "관리자: 정상 확인", () -> action("manager", "normal")); button(box, "관리자: 구조 요청", () -> action("manager", "help"));
    output = new TextView(this); box.addView(output); setContentView(scroll);
    handler.post(new Runnable() { public void run() { output.setText(status); handler.postDelayed(this, 1000); } });
    if (getIntent().hasExtra("server")) address.setText(getIntent().getStringExtra("server"));
    if (getIntent().hasExtra("pairing")) token.setText(getIntent().getStringExtra("pairing"));
    if (getIntent().getBooleanExtra("autostart", false)) handler.postDelayed(() -> arm(), 300);
  }
  EditText field(LinearLayout box, String label, String value) { TextView text = new TextView(this); text.setText(label); box.addView(text); EditText input = new EditText(this); input.setSingleLine(true); input.setText(value); box.addView(input); return input; }
  void button(LinearLayout box, String label, Runnable action) { Button button = new Button(this); button.setText(label); button.setOnClickListener(v -> action.run()); box.addView(button); }
  void arm() {
    String url = address.getText().toString().trim(), key = token.getText().toString().trim();
    try { URI uri = new URI(url); if (!(uri.getScheme().equals("https") || uri.getScheme().equals("http")) || uri.getHost() == null || uri.getUserInfo() != null) throw new Exception(); } catch (Exception e) { status = "서버 주소를 확인해주세요."; return; }
    if (!key.matches("[a-f0-9]{48}")) { status = "관리 화면에서 휴대폰 연결 코드를 확인해주세요."; return; }
    SharedPreferences prefs = getSharedPreferences("terminal", MODE_PRIVATE);
    prefs.edit().putString("url", url.replaceAll("/+$", "")).putString("token", key).putString("role", role.getSelectedItemPosition() == 0 ? "worker" : "manager").putString("deviceId", prefs.getString("deviceId", UUID.randomUUID().toString())).apply();
    prefs.edit().putBoolean("smsEnabled", sms.isChecked()).apply();
    ArrayList<String> missing = new ArrayList<>();
    if (role.getSelectedItemPosition() == 0 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.RECORD_AUDIO);
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.POST_NOTIFICATIONS);
    if (sms.isChecked() && checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.SEND_SMS);
    if (!missing.isEmpty()) { requestPermissions(missing.toArray(new String[0]), 1); return; }
    if (service != null) { stopService(new Intent(this, VoiceService.class)); handler.postDelayed(() -> startForegroundService(new Intent(this, VoiceService.class)), 500); }
    else startForegroundService(new Intent(this, VoiceService.class));
  }
  void action(String route, String action) {
    if (service == null) { status = "먼저 대기를 시작해주세요."; return; }
    service.manual(route, action, note.getText().toString());
  }
  @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) { super.onRequestPermissionsResult(request, permissions, grants); if (request == 1) { for (int grant : grants) if (grant != PackageManager.PERMISSION_GRANTED) { status = "대기에 필요한 권한이 없습니다."; return; } arm(); } }
  @Override public void onResume() { super.onResume(); visible = true; }
  @Override public void onStop() { visible = false; super.onStop(); }
  @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }

  public static class VoiceService extends Service {
    Handler main = new Handler(Looper.getMainLooper());
    ScheduledExecutorService polling = Executors.newSingleThreadScheduledExecutor(); ExecutorService work = Executors.newSingleThreadExecutor();
    String url, token, deviceId, role; volatile String incidentId = ""; volatile boolean armed, busy; volatile long offset;
    TextToSpeech tts; boolean ttsReady; AudioManager audio; AudioFocusRequest focus; PowerManager.WakeLock wake;
    Set<String> handled = new HashSet<>(); Map<String, JSONObject> utterances = new ConcurrentHashMap<>();
    @Override public void onCreate() {
      super.onCreate(); service = this;
      SharedPreferences prefs = getSharedPreferences("terminal", MODE_PRIVATE);
      url = prefs.getString("url", ""); token = prefs.getString("token", ""); deviceId = prefs.getString("deviceId", ""); role = prefs.getString("role", "worker");
      audio = (AudioManager)getSystemService(AUDIO_SERVICE);
      tts = new TextToSpeech(this, code -> { ttsReady = code == TextToSpeech.SUCCESS; if (ttsReady) { int result = tts.setLanguage(Locale.KOREAN); if (result < 0) { ttsReady = false; status = "한국어 TTS를 사용할 수 없습니다."; } } event(this, "tts_ready", "ready", ttsReady); });
      tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
        public void onStart(String id) { JSONObject command = utterances.get(id); if (command != null) { event(VoiceService.this, "speech_started", "commandId", id, "volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC)); postEvent(command, "spoken", null); } }
        public void onDone(String id) { JSONObject command = utterances.remove(id); if (command == null) return; event(VoiceService.this, "speech_finished", "commandId", id); if (command.optInt("recordSeconds") > 0 && armed) work.execute(() -> capture(command)); else main.post(() -> { if (!busy && focus != null) audio.abandonAudioFocusRequest(focus); }); }
        public void onError(String id) { JSONObject command = utterances.remove(id); busy = false; if (command != null) postEvent(command, "failed", "질문 음성 재생 실패"); }
      });
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
      if (intent != null && "STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
      if (armed) return START_NOT_STICKY;
      try {
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel("terminal", "음성 확인 대기", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, VoiceService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "terminal").setContentTitle("StillWatch " + (role.equals("worker") ? "작업자" : "관리자") + " 대기 중").setContentText("질문 때만 마이크 사용 · 앱 강제 종료 시 대기 중단").setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(open).addAction(android.R.drawable.ic_menu_close_clear_cancel, "대기 종료", stop).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK | (role.equals("worker") ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0)); else startForeground(1, notification);
        wake = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StillWatch:terminal"); wake.acquire(600000); armed = true;
        event(this, "service_armed", "role", role); polling.scheduleWithFixedDelay(() -> poll(), 0, 3, TimeUnit.SECONDS);
      } catch (Exception e) { status = "대기 시작 실패: " + e.getClass().getSimpleName(); event(this, "service_failed", "error", e.getClass().getSimpleName()); stopSelf(); }
      return START_NOT_STICKY;
    }
    boolean smsReady() {
      try { return getSharedPreferences("terminal", MODE_PRIVATE).getBoolean("smsEnabled", false) && checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED && getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING) && ((TelephonyManager)getSystemService(TELEPHONY_SERVICE)).getSimState() == TelephonyManager.SIM_STATE_READY; }
      catch (Exception ignored) { return false; }
    }
    JSONObject common() throws JSONException { return new JSONObject().put("deviceId", deviceId).put("role", role).put("armed", armed).put("smsReady", smsReady()); }
    JSONObject request(String route, JSONObject body) throws Exception {
      HttpURLConnection connection = (HttpURLConnection)new URL(url + "/api/mobile/" + route).openConnection();
      connection.setConnectTimeout(5000); connection.setReadTimeout(route.equals("audio") ? 25000 : 5000); connection.setInstanceFollowRedirects(false);
      connection.setRequestMethod("POST"); connection.setRequestProperty("Authorization", "Bearer " + token); connection.setRequestProperty("Content-Type", "application/json"); connection.setDoOutput(true);
      try {
        try (OutputStream output = connection.getOutputStream()) { output.write(body.toString().getBytes("UTF-8")); }
        int code = connection.getResponseCode(); InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(); if (input != null) try (InputStream stream = input) { byte[] bytes = new byte[4096]; int count; while ((count = stream.read(bytes)) > 0) { buffer.write(bytes, 0, count); if (buffer.size() > 500000) throw new IOException("response too large"); } }
        JSONObject result = new JSONObject(buffer.toString("UTF-8")); if (code >= 400) throw new IOException(result.optString("error", "HTTP " + code)); return result;
      } finally { connection.disconnect(); }
    }
    void poll() {
      if (!armed) return;
      try {
        if (!wake.isHeld()) wake.acquire(600000);
        JSONObject value = request("poll", common()); incidentId = value.optString("incidentId", ""); offset = value.optLong("serverTime", System.currentTimeMillis()) - System.currentTimeMillis();
        status = "서버 연결됨 · " + value.optString("state") + "\n연락 상태: " + value.optString("contactStatus", "없음") + "\n" + (busy ? "음성 확인 중" : "질문 대기");
        JSONObject command = value.optJSONObject("command"); if (command != null) main.post(() -> speak(command));
      } catch (Exception e) { status = "서버 연결 실패: " + e.getMessage() + "\n앱을 다시 열고 주소·코드를 확인해주세요."; }
    }
    void speak(JSONObject command) {
      if (!armed) return;
      String id = command.optString("id"), kind = command.optString("kind");
      if (kind.equals("send_contact")) {
        if (!handled.contains(id) && smsReady()) { handled.add(id); work.execute(() -> sendSms(command)); }
        return;
      }
      if (!ttsReady) return;
      if (handled.contains(id) || (command.has("deadline") && System.currentTimeMillis() + offset >= command.optLong("deadline"))) return;
      if (kind.equals("check") && busy) return;
      AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
      focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).setOnAudioFocusChangeListener(change -> { if (change < 0) { tts.stop(); busy = false; postEvent(command, "failed", "다른 오디오 사용으로 질문 중단"); } }).build();
      if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { postEvent(command, "failed", "오디오 사용 권한을 얻지 못했습니다."); return; }
      handled.add(id); if (handled.size() > 300) handled.clear(); utterances.put(id, command); if (kind.equals("check")) busy = true;
      postEvent(command, "received", null); tts.setAudioAttributes(attrs);
      int result = tts.speak(command.optString("prompt"), TextToSpeech.QUEUE_ADD, null, id);
      if (result != TextToSpeech.SUCCESS) { busy = false; postEvent(command, "failed", "TTS 요청 실패"); }
    }
    void postEvent(JSONObject command, String kind, String error) {
      if (work.isShutdown()) return;
      work.execute(() -> { try { JSONObject body = common().put("incidentId", command.optString("incidentId")).put("commandId", command.optString("id")).put("kind", kind).put("text", command.optString("prompt")); if (error != null) body.put("error", error); request("event", body); } catch (Exception ignored) {} });
    }
    void capture(JSONObject command) {
      AudioRecord record = null;
      try {
        if (!armed || !command.optString("incidentId").equals(incidentId) || System.currentTimeMillis() + offset >= command.optLong("deadline")) return;
        int size = Math.max(4096, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
        record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size); record.startRecording();
        event(this, "mic_started", "incidentId", incidentId); ByteArrayOutputStream pcm = new ByteArrayOutputStream(); short[] samples = new short[1024];
        long until = Math.min(SystemClock.elapsedRealtime() + 5000, SystemClock.elapsedRealtime() + Math.max(0, command.optLong("deadline") - System.currentTimeMillis() - offset)); int peak = 0;
        while (armed && SystemClock.elapsedRealtime() < until) { int count = record.read(samples, 0, samples.length); if (count < 0) throw new IOException("mic read"); for (int i = 0; i < count; i++) { int sample = samples[i]; peak = Math.max(peak, Math.abs(sample)); pcm.write(sample & 255); pcm.write((sample >> 8) & 255); } }
        boolean silenced = false; if (Build.VERSION.SDK_INT >= 29) for (AudioRecordingConfiguration config : audio.getActiveRecordingConfigurations()) if (config.getClientAudioSessionId() == record.getAudioSessionId()) silenced |= config.isClientSilenced();
        event(this, "mic_finished", "bytes", pcm.size(), "peak", peak, "clientSilenced", silenced); record.stop(); record.release(); record = null;
        if (silenced) throw new IOException("microphone silenced");
        if (!armed || System.currentTimeMillis() + offset >= command.optLong("deadline")) return;
        if (peak < 120) { status = "응답 음성이 감지되지 않았습니다. 확인 마감은 유지됩니다."; return; }
        JSONObject response = request("audio", common().put("incidentId", command.optString("incidentId")).put("wav", android.util.Base64.encodeToString(wav(pcm.toByteArray()), android.util.Base64.NO_WRAP)));
        if (response.has("error")) { postEvent(command, "failed", response.optString("error")); status = response.optString("error"); }
        else if (!response.optString("reply").isEmpty()) {
          JSONObject reply = new JSONObject().put("id", command.optString("id") + ":reply").put("incidentId", command.optString("incidentId")).put("kind", "reply").put("prompt", response.optString("reply")).put("recordSeconds", 0);
          main.post(() -> speak(reply));
        }
      } catch (Exception e) { status = "음성 확인 오류: " + e.getMessage(); postEvent(command, "failed", e.getMessage()); }
      finally { if (record != null) { try { record.stop(); } catch (Exception ignored) {} record.release(); } busy = false; main.post(() -> { if (focus != null) audio.abandonAudioFocusRequest(focus); }); }
    }
    byte[] wav(byte[] pcm) throws IOException {
      ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
      header.put("RIFF".getBytes("US-ASCII")).putInt(36 + pcm.length).put("WAVEfmt ".getBytes("US-ASCII")).putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16).put("data".getBytes("US-ASCII")).putInt(pcm.length);
      ByteArrayOutputStream output = new ByteArrayOutputStream(); output.write(header.array()); output.write(pcm); return output.toByteArray();
    }
    void manual(String route, String action, String note) {
      work.execute(() -> { try { JSONObject body = common().put("incidentId", incidentId); if (action != null) body.put("action", action).put("note", note); request(route, body); status = "확인 결과를 전달했습니다."; } catch (Exception e) { status = "요청 실패: " + e.getMessage(); } });
    }
    void sendSms(JSONObject command) {
      boolean claimed = false;
      try {
        JSONObject result = request("sms-claim", common().put("incidentId", command.optString("incidentId")).put("commandId", command.optString("id")));
        claimed = result.optBoolean("accepted");
        if (!claimed || !smsReady()) throw new IOException("SMS 요청이 취소되었거나 권한·SIM이 없습니다.");
        SmsManager manager = Build.VERSION.SDK_INT >= 31 ? getSystemService(SmsManager.class) : SmsManager.getDefault();
        ArrayList<String> parts = manager.divideMessage(result.getString("message")); ArrayList<PendingIntent> sent = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
          Intent callback = new Intent(this, SmsResultReceiver.class).setAction("com.stillwatch.worker.SMS_SENT." + UUID.randomUUID()).putExtra("incidentId", command.optString("incidentId")).putExtra("commandId", command.optString("id")).putExtra("deviceId", deviceId).putExtra("part", i).putExtra("total", parts.size());
          sent.add(PendingIntent.getBroadcast(this, 0, callback, PendingIntent.FLAG_IMMUTABLE));
        }
        manager.sendMultipartTextMessage(result.getString("recipient"), null, parts, sent, null);
        event(this, "sms_requested", "incidentId", command.optString("incidentId"), "parts", parts.size());
      } catch (Exception e) {
        status = "SMS 전송 오류: " + e.getMessage(); event(this, "sms_failed", "error", e.getClass().getSimpleName());
        if (claimed) try { request("sms-result", common().put("incidentId", command.optString("incidentId")).put("commandId", command.optString("id")).put("sent", false).put("error", e.getClass().getSimpleName()).put("part", 0).put("total", 1)); } catch (Exception ignored) {}
      }
    }
    @Override public void onTaskRemoved(Intent root) { event(this, "task_removed", "continues", armed); }
    @Override public void onDestroy() {
      armed = false; service = null; polling.shutdownNow();
      new Thread(() -> { try { request("release", common()); } catch (Exception ignored) {} }).start();
      work.shutdownNow(); if (tts != null) { tts.stop(); tts.shutdown(); } if (focus != null) audio.abandonAudioFocusRequest(focus); if (wake != null && wake.isHeld()) wake.release(); status = "대기 종료"; event(this, "service_destroyed"); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
  }
  public static class SmsResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
      final int resultCode = getResultCode(); final PendingResult pending = goAsync();
      event(context, "sms_part_result", "code", resultCode, "part", intent.getIntExtra("part", 0), "total", intent.getIntExtra("total", 1));
      new Thread(() -> {
        try {
          SharedPreferences prefs = context.getSharedPreferences("terminal", MODE_PRIVATE);
          HttpURLConnection connection = (HttpURLConnection)new URL(prefs.getString("url", "") + "/api/mobile/sms-result").openConnection();
          connection.setConnectTimeout(4000); connection.setReadTimeout(4000); connection.setInstanceFollowRedirects(false); connection.setRequestMethod("POST"); connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Authorization", "Bearer " + prefs.getString("token", "")); connection.setDoOutput(true);
          JSONObject body = new JSONObject().put("deviceId", intent.getStringExtra("deviceId")).put("incidentId", intent.getStringExtra("incidentId")).put("commandId", intent.getStringExtra("commandId")).put("sent", resultCode == Activity.RESULT_OK).put("error", "SMS result " + resultCode).put("part", intent.getIntExtra("part", 0)).put("total", intent.getIntExtra("total", 1));
          try { try (OutputStream output = connection.getOutputStream()) { output.write(body.toString().getBytes("UTF-8")); } connection.getResponseCode(); } finally { connection.disconnect(); }
        } catch (Exception ignored) {} finally { pending.finish(); }
      }).start();
    }
  }
}
