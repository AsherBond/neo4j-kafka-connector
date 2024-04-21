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
package org.neo4j.connectors.kafka.sink

import org.apache.kafka.common.config.ConfigException
import org.apache.kafka.connect.data.Schema
import org.apache.kafka.connect.header.Header
import org.apache.kafka.connect.sink.SinkRecord
import org.neo4j.connectors.kafka.data.cdcTxId
import org.neo4j.connectors.kafka.data.cdcTxSeq
import org.neo4j.connectors.kafka.data.isCdcMessage
import org.neo4j.connectors.kafka.sink.strategy.CdcSchemaHandler
import org.neo4j.connectors.kafka.sink.strategy.CdcSourceIdHandler
import org.neo4j.connectors.kafka.sink.strategy.CudHandler
import org.neo4j.connectors.kafka.sink.strategy.CypherHandler
import org.neo4j.connectors.kafka.sink.strategy.NodePatternHandler
import org.neo4j.connectors.kafka.sink.strategy.RelationshipPatternHandler
import org.neo4j.driver.Query

data class SinkMessage(val record: SinkRecord) {
  val topic
    get(): String = record.topic()

  val keySchema
    get(): Schema? = record.keySchema()

  val key
    get(): Any? = record.key()

  val valueSchema
    get(): Schema? = record.valueSchema()

  val value
    get(): Any? = record.value()

  val headers
    get(): Iterable<Header> = record.headers()

  val isCdcMessage
    get(): Boolean = record.isCdcMessage()

  val cdcTxInfo
    get(): Pair<Long, Int> =
        if (!isCdcMessage) throw IllegalArgumentException("not a message generated by cdc")
        else Pair(record.cdcTxId()!!, record.cdcTxSeq()!!)

  override fun toString(): String {
    return "SinkMessage{topic=${record.topic()},partition=${record.kafkaPartition()},offset=${record.kafkaOffset()},timestamp=${record.timestamp()},timestampType=${record.timestampType()}}"
  }
}

enum class SinkStrategy(val description: String) {
  CDC_SCHEMA("cdc-schema"),
  CDC_SOURCE_ID("cdc-source-id"),
  CYPHER("cypher"),
  CUD("cud"),
  NODE_PATTERN("node-pattern"),
  RELATIONSHIP_PATTERN("relationship-pattern")
}

data class ChangeQuery(val txId: Long?, val seq: Int?, val query: Query)

interface SinkStrategyHandler {

  fun strategy(): SinkStrategy

  /**
   * Process incoming sink messages by converting them into Cypher queries, grouped as transactional
   * boundaries. Each `Iterable<ChangeQuery>` will be executed as a transaction.
   *
   * @param messages Incoming sink messages
   * @return Iterable of change queries split into transactional boundaries
   */
  fun handle(messages: Iterable<SinkMessage>): Iterable<Iterable<ChangeQuery>>

  companion object {

    fun createFrom(config: SinkConfiguration): Map<String, SinkStrategyHandler> {
      return config.topicNames.associateWith { topic -> createForTopic(topic, config) }
    }

    private fun createForTopic(topic: String, config: SinkConfiguration): SinkStrategyHandler {
      val originals = config.originalsStrings()

      val query = originals[SinkConfiguration.CYPHER_TOPIC_PREFIX + topic]
      if (query != null) {
        return CypherHandler(
            topic,
            query,
            config.renderer,
            config.batchSize,
            bindHeaderAs = config.cypherBindHeaderAs,
            bindKeyAs = config.cypherBindKeyAs,
            bindValueAs = config.cypherBindValueAs,
            bindValueAsEvent = config.cypherBindValueAsEvent)
      }

      val nodePattern = originals[SinkConfiguration.PATTERN_NODE_TOPIC_PREFIX + topic]
      if (nodePattern != null) {
        return NodePatternHandler(
            topic,
            nodePattern,
            config.getBoolean(SinkConfiguration.PATTERN_NODE_MERGE_PROPERTIES),
            config.batchSize)
      }

      val relationshipPattern =
          originals[SinkConfiguration.PATTERN_RELATIONSHIP_TOPIC_PREFIX + topic]
      if (relationshipPattern != null) {
        return RelationshipPatternHandler(
            topic,
            relationshipPattern,
            config.getBoolean(SinkConfiguration.PATTERN_NODE_MERGE_PROPERTIES),
            config.getBoolean(SinkConfiguration.PATTERN_RELATIONSHIP_MERGE_PROPERTIES),
            config.batchSize)
      }

      val cdcSourceIdTopics = config.getList(SinkConfiguration.CDC_SOURCE_ID_TOPICS)
      if (cdcSourceIdTopics.contains(topic)) {
        val labelName = config.getString(SinkConfiguration.CDC_SOURCE_ID_LABEL_NAME)
        val propertyName = config.getString(SinkConfiguration.CDC_SOURCE_ID_PROPERTY_NAME)

        return CdcSourceIdHandler(topic, config.renderer, labelName, propertyName)
      }

      val cdcSchemaTopics = config.getList(SinkConfiguration.CDC_SCHEMA_TOPICS)
      if (cdcSchemaTopics.contains(topic)) {
        return CdcSchemaHandler(topic, config.renderer)
      }

      val cudTopics = config.getList(SinkConfiguration.CUD_TOPICS)
      if (cudTopics.contains(topic)) {
        return CudHandler(topic, config.batchSize)
      }

      throw ConfigException("Topic $topic is not assigned a sink strategy")
    }

    fun configuredStrategies(config: SinkConfiguration): Set<String> {
      return config.topicNames
          .map { topic -> topicStrategy(topic, config) }
          .map { it.description }
          .toSet()
    }

    private fun topicStrategy(topic: String, config: SinkConfiguration): SinkStrategy {
      val originals = config.originalsStrings()

      val query = originals[SinkConfiguration.CYPHER_TOPIC_PREFIX + topic]
      if (query != null) {
        return SinkStrategy.CYPHER
      }

      val nodePattern = originals[SinkConfiguration.PATTERN_NODE_TOPIC_PREFIX + topic]
      if (nodePattern != null) {
        return SinkStrategy.NODE_PATTERN
      }

      val relationshipPattern =
          originals[SinkConfiguration.PATTERN_RELATIONSHIP_TOPIC_PREFIX + topic]
      if (relationshipPattern != null) {
        return SinkStrategy.RELATIONSHIP_PATTERN
      }

      val cdcSourceIdTopics = config.getList(SinkConfiguration.CDC_SOURCE_ID_TOPICS)
      if (cdcSourceIdTopics.contains(topic)) {
        return SinkStrategy.CDC_SOURCE_ID
      }

      val cdcSchemaTopics = config.getList(SinkConfiguration.CDC_SCHEMA_TOPICS)
      if (cdcSchemaTopics.contains(topic)) {
        return SinkStrategy.CDC_SCHEMA
      }

      val cudTopics = config.getList(SinkConfiguration.CUD_TOPICS)
      if (cudTopics.contains(topic)) {
        return SinkStrategy.CUD
      }

      throw ConfigException("Topic $topic is not assigned a sink strategy")
    }
  }
}
