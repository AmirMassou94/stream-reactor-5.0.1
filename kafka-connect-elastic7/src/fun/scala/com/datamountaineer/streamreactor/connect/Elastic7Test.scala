package com.datamountaineer.streamreactor.connect
import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import com.jayway.jsonpath.JsonPath
import io.confluent.kafka.serializers.KafkaJsonSerializer
import io.lenses.streamreactor.connect.model.Order
import io.lenses.streamreactor.connect.testcontainers.ElasticsearchContainer
import io.lenses.streamreactor.connect.testcontainers.SchemaRegistryContainer
import io.lenses.streamreactor.connect.testcontainers.connect.ConfigValue
import io.lenses.streamreactor.connect.testcontainers.connect.ConnectorConfiguration
import io.lenses.streamreactor.connect.testcontainers.connect.KafkaConnectClient.createConnector
import io.lenses.streamreactor.connect.testcontainers.scalatest.StreamReactorContainerPerSuite
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers

class Elastic7Test extends AsyncFlatSpec with AsyncIOSpec with StreamReactorContainerPerSuite with Matchers {

  lazy val container: ElasticsearchContainer = ElasticsearchContainer(dockerTag = "7.17.8").withNetwork(network)

  override val schemaRegistryContainer: Option[SchemaRegistryContainer] = None

  override val connectorModule: String = "elastic7"

  override def beforeAll(): Unit = {
    container.start()
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    container.stop()
  }

  behavior of "Elastic7 connector"

  it should "sink records" in {
    val resources = for {
      producer  <- createProducer[String, Order](classOf[StringSerializer], classOf[KafkaJsonSerializer[Order]])
      connector <- createConnector(sinkConfig(), 30L)
    } yield (producer, connector)

    resources.use {
      case (producer, _) =>
        IO {
          // Write records to topic
          val order = Order(1, "OP-DAX-P-20150201-95.7", 94.2, 100)
          producer.send(new ProducerRecord[String, Order]("orders", order)).get()
          producer.flush()

          val client = HttpClient.newHttpClient()
          val request = HttpRequest.newBuilder()
            .GET().uri(
              new URI(
                "http://" + container.hostNetwork.httpHostAddress + "/orders/_search/?q=OP-DAX-P-20150201",
              ),
            ).build()

          eventually {
            val response = client.send(request, BodyHandlers.ofString())
            val body     = response.body
            assert(JsonPath.read[Int](body, "$.hits.total.value") == 1)
          }

          client.send(request, BodyHandlers.ofString())
        }.asserting {
          response =>
            val body = response.body
            JsonPath.read[Int](body, "$.hits.hits[0]._source.id") should be(1)
            JsonPath.read[String](body, "$.hits.hits[0]._source.product") should be("OP-DAX-P-20150201-95.7")
            JsonPath.read[Double](body, "$.hits.hits[0]._source.price") should be(94.2)
            JsonPath.read[Int](body, "$.hits.hits[0]._source.qty") should be(100)
        }
    }
  }

  private def sinkConfig(): ConnectorConfiguration =
    ConnectorConfiguration(
      "elastic-sink",
      Map(
        "connector.class"              -> ConfigValue("com.datamountaineer.streamreactor.connect.elastic7.ElasticSinkConnector"),
        "tasks.max"                    -> ConfigValue(1),
        "topics"                       -> ConfigValue("orders"),
        "connect.elastic.protocol"     -> ConfigValue("http"),
        "connect.elastic.hosts"        -> ConfigValue(container.networkAlias),
        "connect.elastic.port"         -> ConfigValue(Integer.valueOf(container.port)),
        "connect.elastic.cluster.name" -> ConfigValue("elasticsearch"),
        "connect.elastic.kcql"         -> ConfigValue("INSERT INTO orders SELECT * FROM orders"),
        "connect.progress.enabled"     -> ConfigValue(true),
      ),
    )

}
