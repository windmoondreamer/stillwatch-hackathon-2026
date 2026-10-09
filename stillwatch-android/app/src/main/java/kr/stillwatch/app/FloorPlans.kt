package kr.stillwatch.app
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject
object FloorPlans {
    private fun readLimited(input:java.io.InputStream):ByteArray {
        val out=ByteArrayOutputStream();val chunk=ByteArray(8192)
        while(true){val n=input.read(chunk);if(n<0)break;require(out.size()+n<=10*1024*1024){"10MB 이하 도면을 선택해 주세요."};out.write(chunk,0,n)}
        return out.toByteArray()
    }
    suspend fun read(context:Context,uri:Uri)=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)?.use(::readLimited)?:throw IllegalArgumentException("파일을 열 수 없습니다.")}
    suspend fun decode(context:Context,bytes:ByteArray,mime:String):Bitmap=withContext(Dispatchers.IO){
        if(mime=="application/pdf") {
            val file=java.io.File.createTempFile("floorplan-",".pdf",context.cacheDir)
            try{
                file.writeBytes(bytes)
                PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                    require(renderer.pageCount>0){"PDF에 페이지가 없습니다."}
                    renderer.openPage(0).use { page ->
                        val scale=minOf(1600f/page.width,2200f/page.height)
                        Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.WHITE);page.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                    }
                }
            }finally{file.delete()}
        }else {
            val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            require(options.outWidth>0&&options.outHeight>0){"이미지 형식을 확인해 주세요."}
            var sample=1;while(options.outWidth/sample>2048||options.outHeight/sample>2048)sample*=2
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample })?:throw IllegalArgumentException("이미지를 표시할 수 없습니다.")
        }
    }
    suspend fun transfer(url:String,bytes:ByteArray?=null,mime:String?=null):ByteArray=withContext(Dispatchers.IO){
        require(URL(url).protocol=="https")
        val connection=URL(url).openConnection() as HttpURLConnection
        try{
            connection.connectTimeout=10000;connection.readTimeout=30000;connection.instanceFollowRedirects=false
            if(bytes!=null){connection.requestMethod="PUT";connection.doOutput=true;connection.setFixedLengthStreamingMode(bytes.size);connection.setRequestProperty("Content-Type",mime);connection.outputStream.use{it.write(bytes)}}
            require(connection.responseCode in 200..299){"도면 전송을 완료하지 못했습니다."}
            if(bytes==null)connection.inputStream.use(::readLimited)else ByteArray(0)
        }finally{connection.disconnect()}
    }
    fun json(plan:FloorPlan):JSONObject {
        fun point(p:PlanPoint)=JSONObject().put("x",p.x.toDouble()).put("y",p.y.toDouble())
        return JSONObject().put("key",plan.key).put("mime",plan.mime).put("espPosition",point(plan.espPosition))
            .put("primaryRoute",JSONArray(plan.primaryRoute.map(::point))).put("backupRoute",JSONArray(plan.backupRoute.map(::point)))
    }
}
