package de.enbw.kafka.perftest.controller

import com.fasterxml.jackson.annotation.JsonProperty
import de.enbw.kafka.perftest.service.KafkaRestProxyPubSubService
import de.enbw.kafka.perftest.utils.*
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.bind.annotation.*
import reactor.core.Disposable
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

private val log by logger {}

@RestController
class PubSubController(
    val kafkaRestProxyService: KafkaRestProxyPubSubService,
) {
    private var task: PubSubTaskAndConf? = null

    /**
     * Task that is responsible for killing the main [task] after period of time. Intentionally
     * here and not as part of [PubSubTaskAndConf] so that the [PubSubTaskAndConf.close] can
     * be used for this task.
     */
    private var terminationTask: Disposable? = null

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
        log.info("Acquired request to start pub-sub task with configuration:\n${newConfiguration.toPrettyJsonString()}")

        if (newConfiguration.minBatchIntervalMs > newConfiguration.maxBatchIntervalMs)
            throw IllegalArgumentException("Invalid minBatchIntervalMs or maxBatchIntervalMs")
        if (newConfiguration.minMessagesPerBatch > newConfiguration.maxMessagesPerBatch)
            throw IllegalArgumentException("Invalid minMessagesPerBatch or maxMessagesPerBatch")
        if (newConfiguration.minMessagePayloadSizeBytes > newConfiguration.maxMessagePayloadSizeBytes)
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

        // the time limit was requested -> schedule job that will kill the task
        if (newConfiguration.runDuration != null) {
            terminationTask = Mono.fromCallable {
                log.info("Spinning up auto termination task, duration=${newConfiguration.runDuration}")
            }.delayElement(
                newConfiguration.runDuration
            ).flatMap {
                log.info("Stopping pub-sub by termination task after ${newConfiguration.runDuration}.")
                stopPubSub()
            }.subscribe(
                { log.info("Termination task terminated current run.") },
                { log.error("Failed to terminate current run.", it) }
            )
        }

        task = newTask
        task?.stats?.taskStartTime = Instant.now()
        newConfiguration
    }

    @DeleteMapping("/pub-sub-demo")
    fun stopPubSub(): Mono<StatsWrapperDto?> {
        val stats: PubSubStatsDto? = tryKillRunningTaskOrException()
        log.info("=== Final stats: $stats")
        return Mono.fromCallable {
            val maybeResultStats = stats
                ?.let { computeLatencies(it) }
                ?.also { log.info("=== Final results of the run:\n${it.toPrettyJsonString()}") }
            maybeResultStats
        }
    }

    private fun tryKillRunningTaskOrException(): PubSubStatsDto? = try {
        when (val taskToKill = task) {
            null -> {
                log.info("Nothing to kill...")
                null
            }

            else -> {
                log.info("Initiated kill process...")
                taskToKill.close()
                val stats: PubSubStatsDto = taskToKill.stats
                task = null
                log.info("Process killed...")
                stats
            }
        }
    } finally {
        // quietly dispose of termination task
        terminationTask?.dispose()
        terminationTask = null
        log.info("Termination task disposed of...")
    }
}

data class CreateConsumerResponseDTO(
    @JsonProperty("base_uri")
    val baseUri: String,
    @JsonProperty("instance_id")
    val instanceId: String
)
