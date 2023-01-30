package de.enbw.kafka.perftest

import com.fasterxml.jackson.annotation.JsonProperty
import de.enbw.kafka.perftest.service.KafkaRestProxyPubSubService
import de.enbw.kafka.perftest.utils.*
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono
import java.time.Instant

private val log by logger {}

@RestController
class PubSubController(
    val kafkaRestProxyService: KafkaRestProxyPubSubService,
) {
    private var task: PubSubTaskAndConf? = null

    @Scheduled(fixedDelay = 10_000)
    fun printStatsTask() {
        task?.stats?.also {
            log.info(" ===== PRINT STATS ==== ")
            log.info(computeLatencies(it).toPrettyJsonString())
            log.info(" ===== PRINT STATS ==== ")
        }
    }

    @GetMapping("/pub-sub-demo")
    fun getRunningTaskConfOrNull(): Mono<PublishSubscribeConfDto> = Mono.justOrEmpty(task?.conf)

    @PostMapping("/pub-sub-demo")
    fun startPubSub(
        @RequestBody(required = true) newConfiguration: PublishSubscribeConfDto
    ): Mono<PublishSubscribeConfDto> = Mono.fromCallable {
        log.info("Acquired request to start pub-sub task with configuration: $newConfiguration")

        if (newConfiguration.minIntervalMs >= newConfiguration.maxIntervalMs)
            throw IllegalArgumentException("Invalid minIntervalMs or maxIntervalMs")
        if (newConfiguration.minMessagesPerBatch >= newConfiguration.maxMessagesPerBatch)
            throw IllegalArgumentException("Invalid minMessagesPerBatch or maxMessagesPerBatch")
        if (newConfiguration.minMessagePayloadSizeBytes >= newConfiguration.maxMessagePayloadSizeBytes)
            throw IllegalArgumentException("Invalid minMessagePayloadSizeBytes or maxMessagePayloadSizeBytes")

        when (val maybeRunningTask = task) {
            null -> log.info("Nothing runs currently...")
            else -> {
                log.info("Task with configuration ${maybeRunningTask.conf} is running, will kill it")
                tryKillRunningTaskOrException()
            }
        }
    }.map {
        val newTask = when (newConfiguration.type) {
            PubSubType.KAFKA_REST_PROXY -> kafkaRestProxyService.doStartPubSub(newConfiguration)
        }

        require(!newTask.producerJob.isDisposed) { "Started producer job but it is already disposed of..." }
        require(!newTask.consumerJob.isDisposed) { "Started consumer job but it is already disposed of..." }
        task = newTask
        task?.stats?.taskStartTime = Instant.now()
        newConfiguration
    }

    @DeleteMapping("/pub-sub-demo")
    fun stopPubSub(): Mono<StatsWrapperDto?> {
        val stats: PubSubStatsDto? = tryKillRunningTaskOrException()
        log.info("Final stats: $stats")
        return Mono.fromCallable {
            val maybeResultStats = stats?.let { computeLatencies(it) }
            maybeResultStats
        }
    }

    private fun tryKillRunningTaskOrException(): PubSubStatsDto? = when (val taskToKill = task) {
        null -> {
            log.info("Nothing to kill...")
            null
        }

        else -> {
            log.info("Initiated kill process...")
            taskToKill.stats.taskKillTime = Instant.now()
            taskToKill.close()
            val stats: PubSubStatsDto = taskToKill.stats
            task = null
            log.info("Process killed...")
            stats
        }
    }
}

data class CreateConsumerResponseDTO(
    @JsonProperty("base_uri")
    val baseUri: String,
    @JsonProperty("instance_id")
    val instanceId: String
)
