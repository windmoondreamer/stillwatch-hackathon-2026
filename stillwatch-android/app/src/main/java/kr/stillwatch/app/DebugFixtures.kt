package kr.stillwatch.app
import android.content.Context
import android.speech.tts.*
import android.util.Log
import java.io.File
import java.util.Locale
/** Explicit synthetic speech fixtures for the authorized USB integration test. Debug APK only. */
object DebugFixtures {
    fun generate(context:Context,kind:String){
        if(!BuildConfig.DEBUG)return
        lateinit var engine:TextToSpeech
        engine=TextToSpeech(context){status->
            if(status==TextToSpeech.SUCCESS){
                engine.setLanguage(Locale.KOREAN)
                engine.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
                    override fun onStart(id:String){}
                    override fun onDone(id:String){Log.i("StillWatchFixture","synthetic_fixture_ready kind=$kind");engine.shutdown()}
                    override fun onError(id:String){engine.shutdown()}
                })
                val text=if(kind=="help")"넘어져서 움직일 수 없어요. 도움이 필요해요."else"괜찮아요. 정상적으로 작업 중이고 도움이 필요하지 않습니다."
                engine.synthesizeToFile(text,null,File(context.filesDir,"fixture-$kind.wav"),"fixture-$kind")
            }else engine.shutdown()
        }
    }
}
