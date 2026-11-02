package wowchat.fluxer

import java.io.{ByteArrayOutputStream, InputStream}
import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets

import scala.collection.JavaConverters._

case class HttpRequest(method: String,
                       url: String,
                       headers: Seq[(String, String)] = Seq.empty,
                       body: Option[String] = None,
                       followRedirects: Boolean = false) {

  // Headers carry the Authorization credential, so they are never part of the string form.
  override def toString: String = s"$method $url"

  def header(name: String): Option[String] = headers.find(_._1.equalsIgnoreCase(name)).map(_._2)
}

case class HttpResponse(status: Int, headers: Map[String, String], body: String) {

  def header(name: String): Option[String] = headers.get(name.toLowerCase)
}

// Performs one HTTP exchange. Throws an IOException when no response could be obtained.
trait HttpTransport {
  def execute(request: HttpRequest): HttpResponse
}

class UrlConnectionHttpTransport(connectTimeoutMs: Int = 10000, readTimeoutMs: Int = 30000) extends HttpTransport {

  private val MaxBodyBytes = 32 * 1024 * 1024

  override def execute(request: HttpRequest): HttpResponse = {
    val connection = new URL(request.url).openConnection().asInstanceOf[HttpURLConnection]
    try {
      connection.setRequestMethod(request.method)
      connection.setConnectTimeout(connectTimeoutMs)
      connection.setReadTimeout(readTimeoutMs)
      // Never follow redirects with credentials attached.
      connection.setInstanceFollowRedirects(request.followRedirects && request.header("Authorization").isEmpty)
      connection.setUseCaches(false)
      request.headers.foreach {
        case (name, value) => connection.setRequestProperty(name, value)
      }
      request.body.foreach(body => {
        connection.setDoOutput(true)
        val bytes = body.getBytes(StandardCharsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.length)
        val out = connection.getOutputStream
        try out.write(bytes) finally out.close()
      })

      val status = connection.getResponseCode
      val stream = if (status >= 400) connection.getErrorStream else connection.getInputStream
      val body = Option(stream).map(readFully).getOrElse("")
      val headers = connection.getHeaderFields.asScala
        .collect {
          case (name, values) if name != null && values != null && !values.isEmpty =>
            name.toLowerCase -> values.asScala.mkString(",")
        }
        .toMap
      HttpResponse(status, headers, body)
    } finally {
      connection.disconnect()
    }
  }

  private def readFully(stream: InputStream): String = {
    try {
      val out = new ByteArrayOutputStream()
      val buffer = new Array[Byte](8192)
      var read = stream.read(buffer)
      while (read != -1 && out.size < MaxBodyBytes) {
        out.write(buffer, 0, read)
        read = stream.read(buffer)
      }
      new String(out.toByteArray, StandardCharsets.UTF_8)
    } finally {
      stream.close()
    }
  }
}
