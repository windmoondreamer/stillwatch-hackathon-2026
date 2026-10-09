package kr.stillwatch.app
import android.content.Context
import com.espressif.provisioning.*
import com.espressif.provisioning.listeners.ProvisionListener
import com.espressif.provisioning.listeners.WiFiScanListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
data class ProvisionState(val message:String="ESP32의 설정 Wi-Fi에 먼저 연결해 주세요.",val connected:Boolean=false,val networks:List<String> = emptyList(),val success:Boolean=false)
class EspProvisioning(context:Context) {
    private val device=ESPProvisionManager.getInstance(context.applicationContext).createESPDevice(ESPConstants.TransportType.TRANSPORT_SOFTAP,ESPConstants.SecurityType.SECURITY_1)
    private val mutable=MutableStateFlow(ProvisionState());val state=mutable.asStateFlow()
    init{EventBus.getDefault().register(this)}
    fun connect(pop:String){device.setProofOfPossession(pop);mutable.value=ProvisionState(message="ESP32에 연결하는 중…");device.connectWiFiDevice()}
    @Subscribe(threadMode=ThreadMode.MAIN)
    fun onConnection(event:DeviceConnectionEvent){
        if(event.eventType==ESPConstants.EVENT_DEVICE_CONNECTED) { mutable.value=ProvisionState(message="장치 연결됨 · 현장 Wi-Fi를 검색해 주세요.",connected=true) }
        else if(event.eventType==ESPConstants.EVENT_DEVICE_CONNECTION_FAILED) mutable.value=ProvisionState(message="ESP32 연결을 확인하지 못했습니다. 설정 Wi-Fi와 장치 확인 코드를 확인해 주세요.")
        else if(event.eventType==ESPConstants.EVENT_DEVICE_DISCONNECTED&&!mutable.value.success)mutable.value=ProvisionState(message="ESP32 설정 연결이 끊겼습니다.")
    }
    fun scan(){device.scanNetworks(object:WiFiScanListener{
        override fun onWifiListReceived(list:ArrayList<WiFiAccessPoint>){mutable.value=mutable.value.copy(networks=list.map{it.wifiName}.distinct(),message="ESP32가 찾은 현장 Wi-Fi를 선택해 주세요.")}
        override fun onWiFiScanFailed(e:Exception){mutable.value=mutable.value.copy(message="현장 Wi-Fi 검색을 완료하지 못했습니다.")}
    })}
    fun provision(ssid:String,password:String){
        mutable.value=mutable.value.copy(message="Wi-Fi 연결 정보를 장치에 전달하는 중…")
        device.provision(ssid,password,object:ProvisionListener{
            override fun createSessionFailed(e:Exception){failed()}
            override fun wifiConfigSent(){mutable.value=mutable.value.copy(message="연결 정보를 전달했습니다. 장치 연결 결과를 기다립니다.")}
            override fun wifiConfigFailed(e:Exception){failed()}
            override fun wifiConfigApplied(){mutable.value=mutable.value.copy(message="장치가 연결 설정을 적용했습니다.")}
            override fun wifiConfigApplyFailed(e:Exception){failed()}
            override fun provisioningFailedFromDevice(reason:ESPConstants.ProvisionFailureReason){failed()}
            override fun deviceProvisioningSuccess(){mutable.value=mutable.value.copy(success=true,message="장치가 현장 Wi-Fi 연결 성공을 보고했습니다.")}
            override fun onProvisioningFailed(e:Exception){failed()}
        })
    }
    private fun failed(){mutable.value=mutable.value.copy(message="장치 연결 설정을 완료하지 못했습니다. 연결 정보와 펌웨어 설정을 확인해 주세요.")}
    fun close(){device.disconnectDevice();EventBus.getDefault().unregister(this)}
}
