package de.enbw.kafka.perftest.service

import de.enbw.kafka.perftest.CreateConsumerResponseDTO
import de.enbw.kafka.perftest.serviceutils.*
import de.enbw.kafka.perftest.utils.*
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import reactor.util.function.Tuple2
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

private val log by logger {}

@Service
class KafkaRestProxyPubSubService(
    private val client: WebClient
) {
    var task: PubSubTaskAndConf? = null

    fun doStartPubSub(
        newConfiguration: PublishSubscribeConfDto
    ): PubSubTaskAndConf = PubSubTaskAndConf(
        conf = newConfiguration,
        consumerJob = createConsumerBlock(newConfiguration),
        producerJob = createParallelProducersBlock(newConfiguration),
        stats = PubSubStatsDto(
            latenciesWholeTrip = ConcurrentLinkedQueue<Long>(),
            latenciesProduce = ConcurrentLinkedQueue<Long>(),
            producedMessages = AtomicInteger(0),
            consumedMessages = AtomicInteger(0),
        )
    ).also {
        task = it
    }

    fun createConsumerBlock(
        newConfiguration: PublishSubscribeConfDto
    ): Disposable = createConsumer(
        newConfiguration
    ).flatMap { createConsumerResponse ->
        createSubscriptionToTopic(newConfiguration).switchIfEmpty {
            // empty response from the create-subscription should not be a problem
            log.info("Empty mono before the consumption start.")
            Mono.just(Unit)
        }.map {
            createConsumerResponse
        }
    }.flatMapMany { createConsumerResponse ->
        startConsumingMessages(newConfiguration, createConsumerResponse)
    }.subscribe(
        { log.info("Successfully started to consuming message: $it") },
        { ex -> log.error("Error while consuming", ex) }
    )

    fun createConsumer(
        newConfiguration: PublishSubscribeConfDto
    ): Mono<CreateConsumerResponseDTO> = client.post()
        .uri("${newConfiguration.kafkaRestUri}/consumers/${newConfiguration.kafkaConsumerGroup}")
        .headers {
            it.setBasicAuth(newConfiguration.kafkaUserName, newConfiguration.kafkaUserPassword)
        }
        .contentType(MediaType.parseMediaType("application/vnd.kafka.v2+json"))
        .bodyValue(
            """
              {"name": "${newConfiguration.kafkaConsumerId}", "format": "avro", "auto.offset.reset": "latest"}
            """.trimIndent()
        )
        .exchangeToMono { response ->
            log.info("Status creating consumer group: ${response.statusCode()}")
            when (response.statusCode()) {
                HttpStatus.OK -> response.bodyToMono(CreateConsumerResponseDTO::class.java).map { createResponse ->
                    log.info("Consumer Creation body: $createResponse")
                    createResponse
                }

                else -> response.bodyToMono(CreateConsumerResponseDTO::class.java).map { createResponse ->
                    throw IllegalStateException("Invalid create consumer response: $createResponse.")
                }
            }
        }

    fun createSubscriptionToTopic(
        newConfiguration: PublishSubscribeConfDto
    ): Mono<Unit> = client.post()
        .uri("${newConfiguration.kafkaRestUri}/consumers/${newConfiguration.kafkaConsumerGroup}/instances/${newConfiguration.kafkaConsumerId}/subscription")
        .headers {
            it.setBasicAuth(newConfiguration.kafkaUserName, newConfiguration.kafkaUserPassword)
        }
        .contentType(MediaType.parseMediaType("application/vnd.kafka.v2+json"))
        .bodyValue(
            """ {"topics":["${newConfiguration.kafkaTopic}"]} """.trimIndent()
        )
        .exchangeToMono { response ->
            log.info("Status creating subscription: ${response.statusCode()}")
            when (response.statusCode()) {
                HttpStatus.NO_CONTENT -> Mono.just(Unit) // no content is expected response
                else -> response.bodyToMono(String::class.java).flatMap { stringBody ->
                    log.info("Subscribe body: $stringBody")
                    return@flatMap Mono.error(IllegalStateException("Unexpected response upon subscription to topic."))
                }
            }
        }

    fun startConsumingMessages(
        newConfiguration: PublishSubscribeConfDto,
        createConsumerResponse: CreateConsumerResponseDTO
    ): Flux<Any> = Flux.generate<Any> { sink ->
        sink.next(true)
    }.flatMap(
        {
            val timeout = newConfiguration.consumeTimeoutMs
            val maxBytes = newConfiguration.consumeMaxBytesMs

            log.info("Using timeout=$timeout maxBytes=$maxBytes")
            client
                .get()
                .uri("${createConsumerResponse.baseUri}/records?timeout=$timeout&max_bytes=$maxBytes")
                .headers {
                    it.setBasicAuth(newConfiguration.kafkaUserName, newConfiguration.kafkaUserPassword)
                }
                .accept(MediaType.parseMediaType("application/vnd.kafka.avro.v2+json"))
                .exchangeToMono { response ->
                    if (response.statusCode() != HttpStatus.OK) log.warn("Status Error: ${response.statusCode()}")
                    require(response.statusCode() == HttpStatus.OK)
                    getResponseParser()(response)
                        .doOnNext { eventWrapper -> doComputeStats(eventWrapper, newConfiguration) }
                        .doOnNext { log.info("[CONSUMER] Consumed message: $it") }
                        .map { }
                }
                .flatMap {
                    handleCursorCommit(newConfiguration, createConsumerResponse)
                }.onErrorResume { e ->
                    log.warn("Error while consuming -> CONTINUE", e)
                    task?.stats?.failedMessages?.incrementAndGet()
                    Mono.just(Unit)
                }
        },
        1
    )

    private fun doComputeStats(
        eventWrapper: List<AvroRecordOutDTO<Map<Any, Any>>>,
        newConfiguration: PublishSubscribeConfDto
    ) {
        if (task?.stats?.publishStats == true) {
            eventWrapper.forEach { message ->
                val parsedMessage = message.value.toPrettyJsonString().fromJson<AvroRecordDTO<String>>()
                val latencyMs =
                    Duration.between(Instant.parse(parsedMessage.metadata.occurredAt), Instant.now())
                        .toMillis()
                log.info(
                    "[CONSUMER] Received message $message. Receive duration $latencyMs ms. " +
                            "Message size: ${message.value.toPrettyJsonString().length}"
                )
                task?.stats?.apply {
                    if (newConfiguration.gatherLatencies) latenciesWholeTrip.add(latencyMs)
                    consumedMessages.incrementAndGet()
                }
            }
        }
    }

    private fun handleCursorCommit(
        newConfiguration: PublishSubscribeConfDto,
        createConsumerResponse: CreateConsumerResponseDTO,
    ): Mono<Unit> = when (val commitInterval = newConfiguration.commitInterval) {
        // no commit interval set, do a synchronous commit always
        null -> doCommitCursors(createConsumerResponse, newConfiguration)
        // commit interval set, do async commit and return immediately
        else -> when {
            task!!.lastCommitAt == null || Duration.between(task!!.lastCommitAt, Instant.now()).abs() > commitInterval -> Mono
                .fromCallable {
                    val now = Instant.now()
                    log.info("Setting last commit to $now")
                    task!!.lastCommitAt = now
                }
                .flatMap { doCommitCursors(createConsumerResponse, newConfiguration) }
                // fire-and-forget subscribe
                .subscribe(
                    { log.info("Commit ok...") },
                    { log.error("Failed to commit cursors.", it )}
                )
                .let { Mono.just(Unit) }
            else -> Mono.just(Unit) // just return without commit
        }
    }

    private fun doCommitCursors(
        createConsumerResponse: CreateConsumerResponseDTO,
        newConfiguration: PublishSubscribeConfDto
    ): Mono<Unit> = Mono.fromCallable {
        log.info("Committing offsets...")
    }.flatMap {
        client
            .post()
            .uri("${createConsumerResponse.baseUri}/offsets")
            .headers {
                it.setBasicAuth(newConfiguration.kafkaUserName, newConfiguration.kafkaUserPassword)
            }
            .contentType(MediaType.parseMediaType("application/vnd.kafka.v2+json"))
            .bodyValue("{}")
            .exchangeToMono { response ->
                when {
                    response.statusCode().is2xxSuccessful -> Mono.just(Unit)
                    else -> response.bodyToMono(String::class.java).map {
                        log.error("Failed to commit cursors. Status=${response.statusCode()}, body=${it}")
                        throw IllegalStateException("Failed to commit cursors.")
                    }
                }
            }
    }

    /**
     * Creates infinite generator of message batches that are then sent into Kafka in parallel.
     * The parallelism is set by [PublishSubscribeConfDto.parallelProducers] in [newConfiguration].
     */
    private fun createParallelProducersBlock(
        newConfiguration: PublishSubscribeConfDto
    ): Disposable = Flux.generate<String> { sink ->
        sink.next(UUID.randomUUID().toString())
    }.flatMap(
        { batchId -> produceMessage(newConfiguration, batchId) },
        newConfiguration.parallelProducers
    ).delaySubscription(
        Duration.ofMillis(5_000)
    ).subscribe(
        { log.debug("[PRODUCER] Message $it send to Kafka...") },
        { ex -> log.error("[PRODUCER] Failed to send message to Kafka.", ex) }
    )

    private fun produceMessage(
        newConfiguration: PublishSubscribeConfDto,
        orderNumber: String
    ): Mono<Tuple2<HttpStatus, Unit>> = Mono.just(Unit).delayUntil {
        Mono.delay(
            Duration.ofMillis(Random.nextLong(newConfiguration.minIntervalMs, newConfiguration.maxIntervalMs))
        )
    }.map {
        ProduceMessagesInDTO(
            keySchema = null,
            keySchemaId = null,
            valueSchema = null,
            valueSchemaId = "7",
            records = (newConfiguration.minMessagesPerBatch until newConfiguration.maxMessagesPerBatch).map {
                KeyValuePair(
                    key = null,
                    value = AvroRecordDTO(
                        value = PayloadGenerator.generateStringPayload(
                            Random.nextInt(
                                newConfiguration.minMessagePayloadSizeBytes,
                                newConfiguration.maxMessagePayloadSizeBytes
                            )
                        ),
                        metadata = OrderMetadataDto(
                            eventId = orderNumber,
                            occurredAt = Instant.now().toString()
                        )
                    )
                )
            }
        )
    }.doOnNext { testMessage ->
        log.info(
            "[PRODUCER] Generated ${testMessage.records.size} messages, " +
                    "payload=${testMessage.toPrettyJsonString().take(64)}... (truncated), " +
                    "payloadSize=${testMessage.records.first().value.value.length}, " +
                    "will send to Kafka..."
        )
    }.flatMap { message ->
        client
            .post()
            .uri("${newConfiguration.kafkaRestUri}/topics/${newConfiguration.kafkaTopic}")
            .headers {
                it.setBasicAuth(newConfiguration.kafkaUserName, newConfiguration.kafkaUserPassword)
            }
            .contentType(MediaType.parseMediaType("application/vnd.kafka.avro.v2+json"))
            .bodyValue(message)
            .exchangeToMono { response ->
                Mono.zip(
                    Mono.just(response.statusCode()),
                    if (response.statusCode().is2xxSuccessful) response.bodyToMono(String::class.java).flatMap {
                        log.info("Sent message, response code: ${response.statusCode()}")
                        val latencyMs = Duration.between(
                            Instant.parse(message.records.first().value.metadata.occurredAt),
                            Instant.now()
                        ).toMillis()

                        log.info("=== Order sending result: id=${message.records.first().value.metadata.eventId}, httpStatus=${response.statusCode()} in $latencyMs ===")
                        if (task?.stats?.publishStats == true) {
                            task?.stats?.apply {
                                producedMessages.incrementAndGet()
                                if (newConfiguration.gatherLatencies) latenciesProduce.add(latencyMs)
                            }
                        }
                        response.bodyToMono()
                    }
                    else response.bodyToMono(String::class.java).flatMap { produceError ->
                        log.error("Failed to produce message. Status=${response.statusCode()}, errorMessage=${produceError}")
                        Mono.error(IllegalStateException("Failed to produce message..."))
                    }
                )
            }
    }

    companion object {
        @Suppress("unused") // used at least for reference to be created in schema registry
        private val VALUE_SCHEMA: String = """
            {
              "type": "record",
              "name": "AvroRecord",
              "fields": [
                    {
                        "name": "value",
                        "type": "string"
                    },
                    {
                        "name": "metadata",
                        "type" :    {
                            "name": "MetaData",
                            "type": "record",
                            "fields": [
                                {
                                    "name": "eid",
                                    "type": "string"
                                },
                                {
                                    "name": "occurred_at",
                                    "type": "string"
                                }
                            ]
                        }
                    }
                ]
            }
            """.trimIndent()
    }
}