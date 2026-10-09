package kr.stillwatch.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiFailure(val status: Int, message: String): Exception(message)
object Http {
    suspend fun request(method: String, url: String, token: String? = null, body: String? = null, form: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        require(URL(url).protocol == "https") { "HTTPS 연결이 필요합니다." }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10000; connection.readTimeout = if(URL(url).path=="/api/voice/audio")30000 else 15000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.use { input ->
                val output=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192)
                while(true) { val size=input.read(chunk);if(size<0) break;require(output.size()+size<=1_048_576) { "서버 응답이 너무 큽니다." };output.write(chunk,0,size) }
                output.toString("UTF-8")
            }.orEmpty()
            val data = try { JSONObject(raw.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
            if (status !in 200..299) throw ApiFailure(status, data.optString("message").ifBlank { "서버 연결 실패 ($status)" })
            data
        } finally { connection.disconnect() }
    }
}

class SecureSession(context: Context) {
    private val prefs = context.getSharedPreferences("secure_session", Context.MODE_PRIVATE)
    private val alias = "stillwatch.session"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun write(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }
    fun read(): JSONObject = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
        }
        JSONObject(String(cipher.doFinal(Base64.decode(prefs.getString("data", ""), Base64.NO_WRAP)), Charsets.UTF_8))
    } catch (_: Exception) { JSONObject() }
    fun clear() { prefs.edit().clear().apply() }
}

class Auth(private val context: Context) {
    private val store = SecureSession(context)
    val configured get() = BuildConfig.API_URL.startsWith("https://") && BuildConfig.COGNITO_DOMAIN.startsWith("https://") && BuildConfig.COGNITO_CLIENT_ID.isNotBlank()
    private fun random(): String = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    fun launch() {
        check(configured) { "AWS 로그인 연결 설정이 필요합니다." }
        val verifier = random(); val state = random(); val nonce = random()
        store.write(JSONObject().put("verifier", verifier).put("state", state).put("nonce", nonce))
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val uri = Uri.parse(BuildConfig.COGNITO_DOMAIN.trimEnd('/') + "/oauth2/authorize").buildUpon()
            .appendQueryParameter("response_type", "code").appendQueryParameter("client_id", BuildConfig.COGNITO_CLIENT_ID)
            .appendQueryParameter("redirect_uri", "stillwatch://auth").appendQueryParameter("scope", "openid email")
            .appendQueryParameter("code_challenge", challenge).appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state).appendQueryParameter("nonce", nonce).build()
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    suspend fun callback(uri: Uri) {
        require(uri.scheme == "stillwatch" && uri.host == "auth") { "로그인 주소가 올바르지 않습니다." }
        val pending = store.read()
        require(pending.optString("state").isNotBlank() && pending.optString("state") == uri.getQueryParameter("state")) { "로그인 요청이 만료됐습니다. 다시 로그인해 주세요." }
        val code = uri.getQueryParameter("code") ?: throw IllegalArgumentException("로그인이 완료되지 않았습니다.")
        store.clear() // single-use callback, including failed exchanges
        val tokens = token(mapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to "stillwatch://auth", "code_verifier" to pending.getString("verifier")))
        val claims = jwt(tokens.getString("id_token"))
        require(claims.optString("nonce") == pending.getString("nonce") && claims.optString("aud") == BuildConfig.COGNITO_CLIENT_ID) { "로그인 응답이 올바르지 않습니다." }
        save(tokens)
    }
    private fun jwt(token: String): JSONObject = JSONObject(String(Base64.decode(token.split('.')[1], Base64.URL_SAFE), Charsets.UTF_8))
    private suspend fun token(fields: Map<String, String>): JSONObject = Http.request("POST", BuildConfig.COGNITO_DOMAIN.trimEnd('/') + "/oauth2/token",
        body=(fields + ("client_id" to BuildConfig.COGNITO_CLIENT_ID)).entries.joinToString("&") { URLEncoder.encode(it.key,"UTF-8") + "=" + URLEncoder.encode(it.value,"UTF-8") }, form=true)
    private fun save(value: JSONObject, previous: JSONObject = JSONObject()) {
        if (!value.has("refresh_token")) value.put("refresh_token", previous.optString("refresh_token"))
        value.put("expiresAt", System.currentTimeMillis() + value.optLong("expires_in", 3600) * 1000)
        store.write(value)
    }
    fun hasSession(): Boolean = store.read().optString("access_token").isNotBlank()
    suspend fun accessToken(): String {
        var value = store.read()
        if (value.optLong("expiresAt") < System.currentTimeMillis() + 60000) {
            try { val refreshed = token(mapOf("grant_type" to "refresh_token", "refresh_token" to value.getString("refresh_token"))); save(refreshed,value);value=store.read() }
            catch (error: Exception) { store.clear();throw error }
        }
        return value.optString("access_token").ifBlank { throw ApiFailure(401, "로그인이 필요합니다.") }
    }
    fun logout() { store.clear() }
}

class Api(private val auth: Auth) {
    suspend fun call(method: String, path: String, body: JSONObject? = null): JSONObject = Http.request(method,
        BuildConfig.API_URL.trimEnd('/') + path, auth.accessToken(), body?.toString())
    suspend fun zone(): Pair<Zone, Pair<Role,String>> {
        val data = call("GET", "/api/bootstrap")
        return parseZone(data.getJSONObject("zone")) to ((if(data.getString("role")=="MANAGER") Role.MANAGER else Role.WORKER) to data.getString("sub"))
    }
    private fun text(data: JSONObject, key: String): String? = if(data.isNull(key)) null else data.optString(key).takeIf { it.isNotBlank() }
    fun parseZone(data: JSONObject): Zone {
        val alerts = data.optJSONArray("alerts")
        val items = (0 until (alerts?.length() ?: 0)).map { n ->
            val item=alerts!!.getJSONObject(n);val events=item.optJSONArray("events")
            Alert(item.getString("id"),AlertState.parse(item.optString("status")),text(item,"occurredAt"),
                (0 until (events?.length()?:0)).map { j -> val e=events!!.getJSONObject(j);TimelineEvent(e.optString("label",e.optString("type")),text(e,"at")) },
                text(item,"acknowledgedAt"),text(item,"closedAt"),text(item,"reason"),text(item,"aiSummary"),text(item,"enteredAt"),text(item,"lastMotionAt"),if(item.isNull("registeredCount"))null else item.optInt("registeredCount"),text(item,"caseId"))
        }
        val samples=data.optJSONArray("samples")
        return Zone(id=data.getString("id"),name=data.optString("name","위치 미확인"),area=data.optString("area"),deviceId=data.optString("deviceId"),
            state=AlertState.parse(data.optString("state")),registeredCount=if(data.isNull("registeredCount")) null else data.optInt("registeredCount"),
            workerSub=text(data,"workerSub"),enteredAt=text(data,"enteredAt"),lastMotionAt=text(data,"lastMotionAt"),lastReceivedAt=text(data,"lastReceivedAt"),
            motionScore=if(data.isNull("motionScore")) null else data.optDouble("motionScore").takeIf { it.isFinite() },
            samples=(0 until (samples?.length()?:0)).mapNotNull { samples!!.optDouble(it).takeIf { v -> v.isFinite() } },
            t1=data.optInt("t1",60),t2=data.optInt("t2",60),exitGuide=data.optString("exitGuide"),guideReviewedAt=text(data,"guideReviewedAt"),alerts=items,
            workerName=text(data,"workerName"),phoneStatus=data.optString("phoneStatus","UNKNOWN"),targetWifiSsid=data.optString("targetWifiSsid"),targetWifiBssid=data.optString("targetWifiBssid"),assignedPhoneId=text(data,"assignedPhoneId"),
            floorplan=data.optJSONObject("floorplan")?.let(::parsePlan),address=data.optString("address"),floor=data.optString("floor"),
            entrance=data.optString("entrance"),callback=data.optString("callback"),task=data.optString("task","단독 작업"),
            managerEnabled=data.optBoolean("managerEnabled",true),testRecipient=data.optString("testRecipient","+821000000000"),
            safetyCase=data.optJSONObject("safetyCase")?.let { c ->
                val report=c.optJSONObject("report");val contact=c.optJSONObject("contact")
                SafetyCase(c.optString("id"),c.optString("phase"),c.optLong("deadline"),if(c.isNull("managerDeadline"))null else c.optLong("managerDeadline"),
                    c.optInt("questionCount"),c.optString("draftStatus","fallback"),report?.optString("summary_ko").orEmpty(),
                    report?.optString("report_119_ko").orEmpty(),contact?.optString("status").orEmpty(),contact?.optString("message").orEmpty(),
                    contact?.optString("error").orEmpty(),c.optString("voiceError"))
            })
    }
    fun parsePlan(data:JSONObject):FloorPlan {
        fun points(key:String):List<PlanPoint> { val list=data.optJSONArray(key);return (0 until (list?.length()?:0)).map { val p=list!!.getJSONObject(it);PlanPoint(p.getDouble("x").toFloat(),p.getDouble("y").toFloat()) } }
        val p=data.optJSONObject("espPosition")
        return FloorPlan(data.getString("key"),data.optString("mime","image/png"),PlanPoint(p?.optDouble("x",.5)?.toFloat()?:.5f,p?.optDouble("y",.5)?.toFloat()?:.5f),points("primaryRoute"),points("backupRoute"),text(data,"reviewedAt"))
    }
}
