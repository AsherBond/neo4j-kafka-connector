/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package streams.kafka.connect.sink

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.ConfigException
import org.apache.kafka.connect.sink.SinkConnector
import org.junit.jupiter.api.Test
import org.neo4j.driver.Config
import org.neo4j.driver.internal.async.pool.PoolSettings
import streams.kafka.connect.common.AuthenticationType
import streams.kafka.connect.common.Neo4jConnectorConfig

class Neo4jSinkConnectorConfigTest {

  @Test
  fun `should throw a ConfigException because of mismatch`() {
    val exception =
      assertFailsWith(ConfigException::class) {
        val originals =
          mapOf(
            SinkConnector.TOPICS_CONFIG to "foo, bar",
            "${Neo4jSinkConnectorConfig.TOPIC_CYPHER_PREFIX}foo" to
              "CREATE (p:Person{name: event.firstName})")
        Neo4jSinkConnectorConfig(originals)
      }

    assertEquals(
      "There is a mismatch between topics defined into the property `topics` ([bar, foo]) and configured topics ([foo])",
      exception.message)
  }

  @Test
  fun `should throw a ConfigException because of cross defined topics`() {
    val exception =
      assertFailsWith(ConfigException::class) {
        val originals =
          mapOf(
            SinkConnector.TOPICS_CONFIG to "foo, bar",
            "${Neo4jSinkConnectorConfig.TOPIC_CYPHER_PREFIX}foo" to
              "CREATE (p:Person{name: event.firstName})",
            "${Neo4jSinkConnectorConfig.TOPIC_CYPHER_PREFIX}bar" to
              "CREATE (p:Person{name: event.firstName})",
            Neo4jSinkConnectorConfig.TOPIC_CDC_SOURCE_ID to "foo")
        Neo4jSinkConnectorConfig(originals)
      }

    assertEquals("The following topics are cross defined: [foo]", exception.message)
  }

  @Test
  fun `should return the configuration`() {
    val a = "bolt://neo4j:7687"
    val b = "bolt://neo4j2:7687"

    val originals =
      mapOf(
        SinkConnector.TOPICS_CONFIG to "foo",
        "${Neo4jSinkConnectorConfig.TOPIC_CYPHER_PREFIX}foo" to
          "CREATE (p:Person{name: event.firstName})",
        Neo4jConnectorConfig.SERVER_URI to "$a,$b", // Check for string trimming
        Neo4jConnectorConfig.BATCH_SIZE to 10,
        "kafka.${CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG}" to "broker:9093",
        "kafka.${ProducerConfig.ACKS_CONFIG}" to 1,
        Neo4jConnectorConfig.DATABASE to "customers",
        Neo4jConnectorConfig.AUTHENTICATION_BASIC_USERNAME to "FOO",
        Neo4jConnectorConfig.AUTHENTICATION_BASIC_PASSWORD to "BAR")
    val config = Neo4jSinkConnectorConfig(originals)

    assertEquals(
      mapOf(
        CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG to "broker:9093",
        ProducerConfig.ACKS_CONFIG to 1),
      config.kafkaBrokerProperties)
    assertEquals(
      originals["${Neo4jSinkConnectorConfig.TOPIC_CYPHER_PREFIX}foo"],
      config.topics.cypherTopics["foo"])
    assertFalse { config.encryptionEnabled }
    assertEquals(a, config.serverUri.get(0).toString())
    assertEquals(b, config.serverUri.get(1).toString())
    assertEquals(originals[Neo4jConnectorConfig.BATCH_SIZE], config.batchSize)
    assertEquals(
      Config.TrustStrategy.Strategy.TRUST_ALL_CERTIFICATES, config.encryptionTrustStrategy)
    assertEquals(AuthenticationType.BASIC, config.authenticationType)
    assertEquals(
      originals[Neo4jConnectorConfig.AUTHENTICATION_BASIC_USERNAME], config.authenticationUsername)
    assertEquals(
      originals[Neo4jConnectorConfig.AUTHENTICATION_BASIC_PASSWORD], config.authenticationPassword)
    assertEquals(originals[Neo4jConnectorConfig.DATABASE], config.database)
    assertEquals("", config.authenticationKerberosTicket)
    assertNull(config.encryptionCACertificateFile, "encryptionCACertificateFile should be null")

    assertEquals(
      Neo4jConnectorConfig.CONNECTION_MAX_CONNECTION_LIFETIME_MSECS_DEFAULT,
      config.connectionMaxConnectionLifetime)
    assertEquals(
      Neo4jConnectorConfig.CONNECTION_LIVENESS_CHECK_TIMEOUT_MSECS_DEFAULT,
      config.connectionLivenessCheckTimeout)
    assertEquals(
      Neo4jConnectorConfig.CONNECTION_POOL_MAX_SIZE_DEFAULT, config.connectionPoolMaxSize)
    assertEquals(
      PoolSettings.DEFAULT_CONNECTION_ACQUISITION_TIMEOUT, config.connectionAcquisitionTimeout)
    assertEquals(Neo4jConnectorConfig.BATCH_TIMEOUT_DEFAULT, config.batchTimeout)
    assertEquals(Neo4jConnectorConfig.BATCH_TIMEOUT_DEFAULT, config.batchTimeout)
  }

  @Test
  fun `should return valid configuration with multiple URIs`() {
    val a = "bolt://neo4j:7687"
    val b = "bolt://neo4j2:7687"
    val c = "bolt://neo4j3:7777"

    val originals =
      mapOf(
        SinkConnector.TOPICS_CONFIG to "foo",
        "${Neo4jSinkConnectorConfig.TOPIC_CYPHER_PREFIX}foo" to
          "CREATE (p:Person{name: event.firstName})",
        Neo4jConnectorConfig.SERVER_URI to "$a,$b,$c")
    val config = Neo4jSinkConnectorConfig(originals)

    assertEquals(a, config.serverUri[0].toString())
    assertEquals(b, config.serverUri[1].toString())
    assertEquals(c, config.serverUri[2].toString())
  }

  @Test
  fun `should return the configuration with shuffled topic order`() {
    val originals =
      mapOf(
        SinkConnector.TOPICS_CONFIG to "bar,foo",
        "${Neo4jSinkConnectorConfig.TOPIC_PATTERN_NODE_PREFIX}foo" to "(:Foo{!fooId,fooName})",
        "${Neo4jSinkConnectorConfig.TOPIC_PATTERN_NODE_PREFIX}bar" to "(:Bar{!barId,barName})",
        Neo4jConnectorConfig.SERVER_URI to "bolt://neo4j:7687",
        Neo4jConnectorConfig.BATCH_SIZE to 10,
        Neo4jConnectorConfig.AUTHENTICATION_BASIC_USERNAME to "FOO",
        Neo4jConnectorConfig.AUTHENTICATION_BASIC_PASSWORD to "BAR")
    val config = Neo4jSinkConnectorConfig(originals)

    assertEquals(
      originals["${Neo4jSinkConnectorConfig.TOPIC_CYPHER_PREFIX}foo"],
      config.topics.cypherTopics["foo"])
    assertFalse { config.encryptionEnabled }
    assertEquals(originals[Neo4jConnectorConfig.SERVER_URI], config.serverUri.get(0).toString())
    assertEquals(originals[Neo4jConnectorConfig.BATCH_SIZE], config.batchSize)
    assertEquals(
      Config.TrustStrategy.Strategy.TRUST_ALL_CERTIFICATES, config.encryptionTrustStrategy)
    assertEquals(AuthenticationType.BASIC, config.authenticationType)
    assertEquals(
      originals[Neo4jConnectorConfig.AUTHENTICATION_BASIC_USERNAME], config.authenticationUsername)
    assertEquals(
      originals[Neo4jConnectorConfig.AUTHENTICATION_BASIC_PASSWORD], config.authenticationPassword)
    assertEquals(
      originals[Neo4jConnectorConfig.AUTHENTICATION_BASIC_PASSWORD], config.authenticationPassword)
    assertEquals("", config.authenticationKerberosTicket)
    assertNull(config.encryptionCACertificateFile, "encryptionCACertificateFile should be null")

    assertEquals(
      Neo4jConnectorConfig.CONNECTION_MAX_CONNECTION_LIFETIME_MSECS_DEFAULT,
      config.connectionMaxConnectionLifetime)
    assertEquals(
      Neo4jConnectorConfig.CONNECTION_LIVENESS_CHECK_TIMEOUT_MSECS_DEFAULT,
      config.connectionLivenessCheckTimeout)
    assertEquals(
      Neo4jConnectorConfig.CONNECTION_POOL_MAX_SIZE_DEFAULT, config.connectionPoolMaxSize)
    assertEquals(
      PoolSettings.DEFAULT_CONNECTION_ACQUISITION_TIMEOUT, config.connectionAcquisitionTimeout)
    assertEquals(Neo4jConnectorConfig.BATCH_TIMEOUT_DEFAULT, config.batchTimeout)
  }
}
