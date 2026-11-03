package wowchat.fluxer

import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ExecutorService, Executors, ThreadFactory, TimeUnit}

import com.fasterxml.jackson.databind.JsonNode
import com.typesafe.scalalogging.StrictLogging
import wowchat.fluxer.Json._

import scala.util.{Failure, Success, Try}

// A failed REST call, safe to log (never contains the token).
case class RestFailure(status: Option[Int], code: Option[String], message: String, requestId: Option[String]) {

  override def toString: String = {
    val statusText = status.fold("no response")(s => s"HTTP $s")
    val codeText = code.fold("")(c => s" $c")
    val requestText = requestId.fold("")(id => s" (X-Request-ID $id)")
    s"$statusText$codeText: $message$requestText"
  }
}

sealed trait RestDecision
object RestDecision {
  case object Done extends RestDecision
  case class Retry(delayMs: Long, global: Boolean) extends RestDecision
  case class GiveUp(failure: RestFailure) extends RestDecision
}

// Retry rules from https://docs.fluxer.com/topics/rate-limits/ and https://docs.fluxer.com/http-api/errors/.
object RestRetryPolicy {

  val MaxRateLimitRetries = 5
  val MaxServerErrorAttempts = 3
  val MaxNetworkAttempts = 3

  def failureOf(response: HttpResponse, token: String): RestFailure = {
    val body = Json.parse(response.body).filter(_.isObject)
    RestFailure(
      Some(response.status),
      body.flatMap(_.str("code")),
      Redaction.redact(body.flatMap(_.str("message")).getOrElse("no error message"), token),
      response.header("X-Request-ID")
    )
  }

  // attempt starts at 1.
  def decide(response: HttpResponse, attempt: Int, token: String = ""): RestDecision = {
    val status = response.status
    lazy val body = Json.parse(response.body).filter(_.isObject)
    lazy val retryAfterHeaderMs = response.header("Retry-After").flatMap(s => Try(s.trim.toDouble).toOption).map(secondsToMs)
    lazy val retryAfterBodyMs = body.flatMap(_.double("retry_after")).map(secondsToMs)

    if (status >= 200 && status < 300) {
      RestDecision.Done
    } else if (status == 429) {
      if (attempt > MaxRateLimitRetries) {
        RestDecision.GiveUp(failureOf(response, token))
      } else {
        val global = body.flatMap(_.bool("global")).getOrElse(false) ||
          response.header("X-RateLimit-Global").exists(_.equalsIgnoreCase("true"))
        // Prefer the body's retry_after, which is not rounded.
        RestDecision.Retry(retryAfterBodyMs.orElse(retryAfterHeaderMs).getOrElse(1000L), global)
      }
    } else if (status == 423) {
      if (attempt > MaxRateLimitRetries) RestDecision.GiveUp(failureOf(response, token))
      else RestDecision.Retry(retryAfterHeaderMs.getOrElse(1000L), global = false)
    } else if (status == 400 && body.flatMap(_.str("code")).contains("SLOWMODE_RATE_LIMITED") && attempt <= MaxRateLimitRetries) {
      // Bots are documented as exempt from slowmode, but honour retry_after if it happens anyway.
      RestDecision.Retry(retryAfterBodyMs.orElse(retryAfterHeaderMs).getOrElse(1000L), global = false)
    } else if (Set(500, 502, 503, 504).contains(status)) {
      if (attempt >= MaxServerErrorAttempts) RestDecision.GiveUp(failureOf(response, token))
      else RestDecision.Retry(retryAfterHeaderMs.getOrElse(backoffMs(attempt)), global = false)
    } else {
      RestDecision.GiveUp(failureOf(response, token))
    }
  }

  def backoffMs(attempt: Int): Long = math.min(8000L, 1000L << math.min(attempt - 1, 10))

  private def secondsToMs(seconds: Double): Long = math.max(1L, math.ceil(math.max(seconds, 0.0) * 1000).toLong)
}

// Tracks when requests may be sent again, per route and globally.
class RateLimitState(clock: () => Long) {

  private var globalBlockedUntil = 0L
  private val routeBlockedUntil = scala.collection.mutable.Map.empty[String, Long]

  def waitTimeMs(route: String): Long = synchronized {
    math.max(0L, math.max(globalBlockedUntil, routeBlockedUntil.getOrElse(route, 0L)) - clock())
  }

  def blockGlobal(delayMs: Long): Unit = synchronized {
    globalBlockedUntil = math.max(globalBlockedUntil, clock() + delayMs)
  }

  def blockRoute(route: String, delayMs: Long): Unit = synchronized {
    routeBlockedUntil(route) = math.max(routeBlockedUntil.getOrElse(route, 0L), clock() + delayMs)
  }

  // Slow down before the route bucket is empty, as the rate limit documentation asks bots to.
  def update(route: String, response: HttpResponse): Unit = {
    val remaining = response.header("X-RateLimit-Remaining").flatMap(s => Try(s.trim.toInt).toOption)
    val resetAfter = response.header("X-RateLimit-Reset-After").flatMap(s => Try(s.trim.toDouble).toOption)
    (remaining, resetAfter) match {
      case (Some(0), Some(seconds)) => blockRoute(route, math.ceil(seconds * 1000).toLong)
      case _ =>
    }
  }
}

// Fluxer HTTP API client. Independent of the Gateway: messages are sent over HTTP whether or not the Gateway is
// connected. Messages are sent one at a time, in order, on their own thread; member list requests use another
// thread so a large guild does not delay chat.
class FluxerRestClient(http: HttpTransport,
                       apiBase: String,
                       token: String,
                       sleeper: Long => Unit = Thread.sleep,
                       clock: () => Long = () => System.currentTimeMillis) extends StrictLogging {

  import FluxerRestClient._

  private val baseUrl = apiBase.trim.stripSuffix("/") + "/v1"
  private val rateLimits = new RateLimitState(clock)
  private lazy val messageExecutor = singleThread("fluxer-rest-messages")
  private lazy val memberExecutor = singleThread("fluxer-rest-members")

  def createMessageAsync(channelId: String, content: String): Unit = {
    messageExecutor.execute(new Runnable {
      override def run(): Unit = {
        createMessage(channelId, content).left.foreach(failure => logSendFailure(channelId, failure))
      }
    })
  }

  def runMemberTask(task: () => Unit): Unit = {
    memberExecutor.execute(new Runnable {
      override def run(): Unit = {
        Try(task()).failed.foreach(e => logger.error(s"Fluxer member list task failed: ${safe(e)}"))
      }
    })
  }

  // POST /v1/channels/{channel_id}/messages (https://docs.fluxer.com/http-api/messages/)
  def createMessage(channelId: String, content: String): Either[RestFailure, JsonNode] = {
    val body = Json.obj()
    body.put("content", content)
    // Reused on every retry, so a retried send never posts twice (Fluxer remembers a nonce for 5 minutes).
    body.put("nonce", newNonce())
    // Only user and role mentions can notify. Leaving out "everyone" blocks @everyone and @here.
    val allowedMentions = body.putObject("allowed_mentions")
    allowedMentions.putArray("parse").add("users").add("roles")
    request("POST", s"channel:message:create:$channelId", s"/channels/$channelId/messages", Some(Json.write(body)))
  }

  // GET /v1/guilds/{guild_id}/members (https://docs.fluxer.com/http-api/guild-members/), needs no permission.
  def listGuildMembers(guildId: String, after: Option[String], limit: Int): Either[RestFailure, Seq[JsonNode]] = {
    val query = s"limit=$limit" + after.fold("")(a => s"&after=$a")
    request("GET", s"guild:members:$guildId", s"/guilds/$guildId/members?$query", None).flatMap(node => {
      if (node.isArray) {
        import scala.collection.JavaConverters._
        Right(node.elements.asScala.toList)
      } else {
        Left(RestFailure(Some(200), None, "the member list response is not an array", None))
      }
    })
  }

  private[fluxer] def request(method: String, route: String, path: String, body: Option[String]): Either[RestFailure, JsonNode] = {
    val headers = Seq(
      "Authorization" -> s"Bot $token",
      "Accept" -> "application/json",
      "User-Agent" -> UserAgent
    ) ++ body.map(_ => "Content-Type" -> "application/json")
    val httpRequest = HttpRequest(method, baseUrl + path, headers, body)

    var attempt = 0
    var networkFailures = 0
    while (true) {
      attempt += 1
      val wait = rateLimits.waitTimeMs(route)
      if (wait > 0) {
        logger.debug(s"Waiting ${wait}ms for the Fluxer rate limit before $method $path")
        sleeper(wait)
      }

      Try(http.execute(httpRequest)) match {
        case Failure(e: IOException) =>
          networkFailures += 1
          if (networkFailures >= RestRetryPolicy.MaxNetworkAttempts) {
            return Left(RestFailure(None, None, safe(e), None))
          }
          val delay = RestRetryPolicy.backoffMs(networkFailures)
          logger.warn(s"Fluxer request $method $path failed (${safe(e)}). Retrying in ${delay}ms.")
          sleeper(delay)
        case Failure(e) =>
          return Left(RestFailure(None, None, safe(e), None))
        case Success(response) =>
          rateLimits.update(route, response)
          RestRetryPolicy.decide(response, attempt, token) match {
            case RestDecision.Done =>
              return Right(Json.parse(response.body).getOrElse(Json.obj()))
            case RestDecision.Retry(delay, global) =>
              if (global) {
                rateLimits.blockGlobal(delay)
              } else {
                rateLimits.blockRoute(route, delay)
              }
              logger.warn(s"Fluxer request $method $path got HTTP ${response.status}" +
                s"${if (global) " (global rate limit)" else ""}. Retrying in ${delay}ms.")
            case RestDecision.GiveUp(failure) =>
              if (response.status == 401) {
                logger.error("Fluxer rejected the bot token (HTTP 401). Check fluxer.token.")
              }
              return Left(failure)
          }
      }
    }
    throw new IllegalStateException("unreachable")
  }

  private def logSendFailure(channelId: String, failure: RestFailure): Unit = {
    val hint = failure.status match {
      case Some(403) => " The bot needs the VIEW_CHANNEL and SEND_MESSAGES permissions in that channel."
      case Some(404) => " The channel does not exist or the bot cannot see it."
      case _ => ""
    }
    logger.error(s"Failed to send a message to Fluxer channel $channelId: $failure.$hint")
  }

  private def safe(e: Throwable): String = {
    Redaction.redact(s"${e.getClass.getSimpleName}: ${e.getMessage}", token)
  }

  def shutdown(timeoutMs: Long): Unit = {
    Seq(messageExecutor, memberExecutor).foreach(_.shutdown())
    if (!messageExecutor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
      logger.warn("Some messages to Fluxer were not sent before shutdown.")
      messageExecutor.shutdownNow()
    }
    memberExecutor.shutdownNow()
  }
}

object FluxerRestClient {

  val UserAgent = "WoWChat-Fluxer (https://github.com/fjaros/wowchat)"

  // Up to 32 characters, as the Create message documentation allows.
  def newNonce(): String = UUID.randomUUID().toString.replace("-", "")

  private[fluxer] def singleThread(name: String): ExecutorService = {
    Executors.newSingleThreadExecutor(daemonThreads(name))
  }

  private[fluxer] def daemonThreads(name: String): ThreadFactory = new ThreadFactory {
    private val count = new AtomicInteger(0)

    override def newThread(r: Runnable): Thread = {
      val thread = new Thread(r, s"$name-${count.incrementAndGet()}")
      thread.setDaemon(true)
      thread
    }
  }
}
