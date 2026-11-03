package wowchat.fluxer

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import scala.collection.JavaConverters._
import scala.util.Try

// Small helpers over Jackson's tree model. All accessors are total: missing or mistyped fields give None/empty.
object Json {

  val mapper = new ObjectMapper()

  def parse(text: String): Option[JsonNode] = {
    Try(mapper.readTree(text)).toOption.filter(node => node != null && !node.isMissingNode)
  }

  def obj(): ObjectNode = mapper.createObjectNode()

  def write(node: JsonNode): String = mapper.writeValueAsString(node)

  implicit class RichJsonNode(val node: JsonNode) extends AnyVal {

    private def field(name: String): Option[JsonNode] = {
      Option(node).filter(_.isObject).flatMap(n => Option(n.get(name))).filterNot(_.isNull)
    }

    def has(name: String): Boolean = field(name).isDefined

    def str(name: String): Option[String] = field(name).filter(_.isTextual).map(_.asText)

    // Snowflakes are decimal strings. Integers are accepted too, without trusting floating point values.
    def id(name: String): Option[String] = field(name).flatMap(n => {
      if (n.isTextual && n.asText.nonEmpty && n.asText.forall(_.isDigit)) {
        Some(n.asText)
      } else if (n.isIntegralNumber && n.canConvertToLong && n.asLong >= 0) {
        Some(n.asLong.toString)
      } else {
        None
      }
    })

    def long(name: String): Option[Long] = field(name).filter(n => n.isIntegralNumber && n.canConvertToLong).map(_.asLong)

    def int(name: String): Option[Int] = field(name).filter(n => n.isIntegralNumber && n.canConvertToInt).map(_.asInt)

    def double(name: String): Option[Double] = field(name).filter(_.isNumber).map(_.asDouble)

    def bool(name: String): Option[Boolean] = field(name).filter(_.isBoolean).map(_.asBoolean)

    def obj(name: String): Option[JsonNode] = field(name).filter(_.isObject)

    def arr(name: String): Seq[JsonNode] = field(name).filter(_.isArray).map(_.elements.asScala.toList).getOrElse(Nil)
  }
}
