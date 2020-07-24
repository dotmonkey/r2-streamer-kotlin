package org.readium.r2.streamer.container

import org.readium.r2.shared.RootFile
import org.readium.r2.shared.drm.DRM
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.lang.RuntimeException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import kotlin.concurrent.thread

open class HttpContainer(var base: String, rf: RootFile) : Container {
    override var rootFile: RootFile = rf

    override var drm: DRM? = null

    override fun data(relativePath: String): ByteArray {
        var res:ByteArray? = null
        thread {
            try {
                res = dataInputStream(relativePath).readBytes()
            }catch (e:Throwable){
                //e.printStackTrace()
            }
        }.join()
        if(res==null){
            throw  FileNotFoundException(relativePath)
        }
        return res!!
    }

    override fun dataLength(relativePath: String): Long {
        return getFileSize(URL("$base/$relativePath")).toLong()
    }
    override fun dataInputStream(relativePath: String): InputStream {
        //println("open $base/$relativePath")
        return URL("$base/$relativePath").openStream()
    }
    companion object{
        fun getFileSize(url: URL): Int {
            var conn: URLConnection? = null
            return try {
                conn = url.openConnection()
                if (conn is HttpURLConnection) {
                    conn.requestMethod = "HEAD"
                }
                conn.getInputStream()
                conn.contentLength
            } catch (e: IOException) {
                throw RuntimeException(e)
            } finally {
                if (conn is HttpURLConnection) {
                    conn.disconnect()
                }
            }
        }
    }

}