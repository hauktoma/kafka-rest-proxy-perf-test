package de.enbw.kafka.perftest.utils

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import reactor.core.Disposable
import java.io.Closeable
import java.lang.NullPointerException
import java.math.RoundingMode
import java.text.DecimalFormat
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.random.Random

private val log by logger {}

enum class PubSubType {
    KAFKA_REST_PROXY
}


data class OrderMetadataDto(
    @JsonProperty("eid")
    val eventId: String,
    @JsonProperty("occurred_at")
    val occurredAt: String
)

val MAPPER: ObjectMapper = ObjectMapper()
    .registerKotlinModule()
    .registerModule(JavaTimeModule())
    .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
    .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .enable(JsonParser.Feature.ALLOW_COMMENTS)
    // .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
    // .enable(MapperFeature.DEFAULT_VIEW_INCLUSION)
    .disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS)
    .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
    .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)


inline fun <reified T> String.fromJson(): T = MAPPER.readValue(this)

fun Any.toPrettyJsonString(): String = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this)

fun <T> profile(block: () -> T): Pair<Duration, T> {
    val start = Instant.now()
    val result = block()
    val end = Instant.now()
    return Duration.between(start, end) to result
}

/**
 * Generates random number. Allows to use equal values for 'from' and 'to' (Kotlin does not allow this).
 */
fun generateRandom(from: Int, to: Int): Int {
    require(from <= to) { "The 'from' must be lower/equal to 'to'." }
    return when {
        from == to -> from
        else -> Random.nextInt(from, to)
    }
}

object PayloadGenerator {

    fun generateStringPayload(
        payloadSizeInBytes: Int
    ): String {
        val payload = String((0 until payloadSizeInBytes).map { ALLOWED_CHARS.random() }.toCharArray())

        // we are using ascii, so string length == expected bytes length
        require(payload.length == payloadSizeInBytes) {
            "Lengths do not match: generatedPayloadLength=${payload.length} vs expectedPayloadLength=$payloadSizeInBytes"
        }
        return payload
    }

    private const val ALLOWED_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
}

data class PublishSubscribeConfDto(

    /**
     * Allows extending this to other APIs.
     */
    val type: PubSubType,

    /**
     * Minimal delay between produce batches.
     */
    val minIntervalMs: Int,

    /**
     * Max interval between produce batches.
     */
    val maxIntervalMs: Int,

    /**
     * Min message size.
     */
    val minMessagePayloadSizeBytes: Int,

    /**
     * Max message size.
     */
    val maxMessagePayloadSizeBytes: Int,

    val parallelProducers: Int,

    /**
     * Allows to disable metrics gathering. Should not be changed.
     */
    val gatherLatencies: Boolean = true,

    val kafkaRestUri: String,
    val kafkaUserName: String,
    val kafkaUserPassword: String,

    /**
     * Topic to produce to/from. Must exist on the platform since cannot be created manually.
     */
    val kafkaTopic: String,

    /**
     * ID of the schema that should be used. Note that it should already be created,
     * the current implementation does not create its own schema.
     *
     * Easiest way to do this in some kind of console or CURL call. The schema used
     * by this application can be found at
     * [de.enbw.kafka.perftest.service.KafkaRestProxyPubSubService.VALUE_SCHEMA].
     */
    val valueSchemaId: String,

    /**
     * Consumer group to use.
     */
    val kafkaConsumerGroup: String,

    /**
     * ID of the consumer to use.
     */
    val kafkaConsumerId: String,

    /**
     * Minimal amount of message per batch.
     */
    val minMessagesPerBatch: Int,

    /**
     * Max messages per batch.
     */
    val maxMessagesPerBatch: Int,

    /**
     * Timeout parameter of the consume request.
     */
    val consumeTimeoutMs: Int,

    /**
     * MaxBytes parameter of the consume request.
     */
    val consumeMaxBytesMs: Int,

    /**
     * Optional duration/interval between commits.
     *
     * If set, the consumer will commit synchronously, i.e. another consume request will start only
     * after the commit is processed. (This simulates stronger delivery guarantees at cost of performance.)
     *
     * If NOT set, the commits are done asynchronously, i.e. consuming loop does not wait for commits to finish.
     * Interval between commits is set as ISO duration, e.g. for 1 minute: "PT1M".
     */
    val commitInterval: Duration?
)

data class PubSubStatsDto(
    val latenciesWholeTrip: ConcurrentLinkedQueue<Long>,
    val latenciesProduce: ConcurrentLinkedQueue<Long>,
    val producedMessages: AtomicInteger,
    val failedMessages: AtomicInteger = AtomicInteger(0),
    val consumedMessages: AtomicInteger,
    @Volatile
    var publishStats: Boolean = true,
    var taskStartTime: Instant? = null,
    var taskKillTime: Instant? = null
)

data class StatsWrapperDto(
    val totalProducedMessages: Long,
    val totalConsumedMessages: Long,
    val totalFailedMessages: Long,
    val producerStats: StatsSpecificPrintDto,
    val wholeTripStats: StatsSpecificPrintDto,
    var taskStartTime: Instant? = null,
    var taskKillTime: Instant? = null,
    var taskDuration: Duration? = null,
)

data class StatsSpecificPrintDto(
    val totalLatenciesGathered: Int,
    val minRequestDurationMs: Long?,
    val maxRequestDurationMs: Long?,
    val avgRequestDurationMs: Double?,
    val medianRequestTimeMs: Double?,
    val percentilesMs: Map<Double, Long>
)

data class PubSubTaskAndConf(
    val conf: PublishSubscribeConfDto,
    val producerJob: Disposable,
    val consumerJob: Disposable,
    val stats: PubSubStatsDto,
    @Volatile
    var lastCommitAt: Instant? = null
) : Closeable {

    @Throws(IllegalStateException::class)
    override fun close() {
        stats.taskKillTime = Instant.now()
        stats.publishStats = false // stop gathering new stats

        log.info("Going to kill producer job...")
        tryKillRunningTaskOrException(producerJob)
        log.info("Killed producer job...")

        val numberOfAttempts: Long = (timeBuffer / delay)

        log.info("Going to kill consumer job... Time buffer to consume leftover message: $timeBuffer")

        val attemptToConsumeLeftoverMessages: Long? = (0..numberOfAttempts).firstNotNullOfOrNull { attempt ->
            kotlin.runCatching {
                // return attempt on which the messages were consumed
                when (stats.consumedMessages.toInt() >= stats.producedMessages.toInt()) {
                    true -> attempt // return attempt index on which job was killed
                    false -> {
                        log.warn("Waiting for leftover unconsumed messages after ${attempt * delay} ms: " +
                                "Consumed messages = ${stats.consumedMessages}, Produced Messages = ${stats.producedMessages}")
                        Thread.sleep(delay)
                        null// try to consume the messages again
                    }
                }
            }.onFailure { ex ->
                log.info("Error while trying to consume leftover messages", ex)
            }.getOrNull()
        }

        when (attemptToConsumeLeftoverMessages) {
            null -> log.info("Failed to consume all of the leftover messages: " +
                    "Consumed messages = ${stats.consumedMessages}, Produced Messages = ${stats.producedMessages}, " +
                    "Difference = ${stats.producedMessages.toInt() - stats.consumedMessages.toInt()}")
            else -> log.info("Successfully consumed all of the leftover messages")
        }

        tryKillRunningTaskOrException(consumerJob)
        log.info("Killed consumer job...")
    }

    private fun tryKillRunningTaskOrException(
        jobToKill: Disposable, attempts: Int = 10
    ) {
        val attemptThatKilledTaskOrNull: Int? = (0..attempts).firstNotNullOfOrNull { attempt ->
            kotlin.runCatching {
                jobToKill.dispose()
                // return attempt on which task was killed
                when (jobToKill.isDisposed) {
                    true -> attempt // return attempt index on which job was killed
                    false -> {
                        log.warn("Failed to kill running task in $attempt...")
                        null// try to kill task again
                    }
                }
            }.onFailure { ex ->
                log.error("Failed to kill job.", ex)
            }.getOrNull()
        }

        when {
            attemptThatKilledTaskOrNull == null -> throw IllegalStateException("Failed to kill task in $attempts.")
            !jobToKill.isDisposed -> throw IllegalStateException("Job not killed, even though attempts reached.")
            else -> log.info("Killed task in $attemptThatKilledTaskOrNull attempt.")
        }
    }

    companion object {
        const val timeBuffer: Long = 20_000
        const val delay: Long = 2_000
    }
}

fun computeLatencies(
    stats: PubSubStatsDto
): StatsWrapperDto {
    val latenciesWholeTrip =
        listOf(*stats.latenciesWholeTrip.toTypedArray()) // copy to another list to avoid concurrent issues
    val latenciesProduce =
        listOf(*stats.latenciesProduce.toTypedArray()) // copy to another list to avoid concurrent issues

    fun percentile(values: List<Long>, percentile: Double): Long {
        require(values.isNotEmpty())
        val index = ceil((percentile / 100.0) * values.size).toInt()
        return values.sorted()[index - 1]
    }

    fun roundDouble(number: Double): String {
        val df = DecimalFormat("#.##")
        df.roundingMode = RoundingMode.UP
        return df.format(number)
    }

    fun Collection<Number>.median(): Double? {
        if (isEmpty()) return null
        val sorted = this.map { it.toDouble() }.sorted()
        val divideIndex = (size - 1) / 2
        return if (size % 2 != 0) {
            sorted[divideIndex]
        } else {
            (sorted[divideIndex] + sorted[divideIndex + 1]) / 2.0
        }
    }

    fun computeLatenciesPercentiles(
        toCompute: List<Long>
    ): Map<Double, Long> = when (toCompute.isEmpty()) {
        true -> mapOf()
        else -> listOf(50.0, 80.0, 90.0, 95.0, 98.0, 99.0).associateWith { percentile ->
            percentile(toCompute, percentile)
        }
    }

    fun computeStats(
        latenciesToPrint: List<Long>,
        percentilesToPrint: Map<Double, Long>
    ): StatsSpecificPrintDto = StatsSpecificPrintDto(
        totalLatenciesGathered = latenciesToPrint.size,
        minRequestDurationMs = latenciesToPrint.minOfOrNull { it },
        maxRequestDurationMs = latenciesToPrint.maxOfOrNull { it },
        avgRequestDurationMs = roundDouble(latenciesToPrint.average()).toDoubleOrNull(),
        medianRequestTimeMs = latenciesToPrint.median(),
        percentilesMs = percentilesToPrint
    )

    return StatsWrapperDto(
        totalProducedMessages = stats.producedMessages.toLong(),
        totalConsumedMessages = stats.consumedMessages.toLong(),
        totalFailedMessages = stats.failedMessages.toLong(),
        producerStats = latenciesProduce.let { computeStats(it, computeLatenciesPercentiles(it)) },
        wholeTripStats = latenciesWholeTrip.let { computeStats(it, computeLatenciesPercentiles(it)) },
        taskStartTime = stats.taskStartTime,
        taskKillTime = stats.taskKillTime,
        taskDuration = try {
            Duration.between(stats.taskStartTime, stats.taskKillTime?: Instant.now() )
        } catch (e: NullPointerException) { null }
    )
}
