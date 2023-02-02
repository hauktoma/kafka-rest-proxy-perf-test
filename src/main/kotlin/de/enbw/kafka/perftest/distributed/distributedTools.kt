package de.enbw.kafka.perftest.distributed

import de.enbw.kafka.perftest.PerfTestConfigurationBeans
import de.enbw.kafka.perftest.utils.*
import org.springframework.http.HttpStatus
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import java.io.File
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

private val log by logger {}

const val TEMP_DIR_NAME = "tmp"

val webClient = PerfTestConfigurationBeans.doCreateWebClient(withWiretap = false)

fun checkMachineAvailable(
    machineUrlAndPort: String
): Mono<Unit> = webClient.get()
    .uri("$machineUrlAndPort/pub-sub-demo")
    .exchangeToMono { response ->
        require(response.statusCode() == HttpStatus.OK)
        response.bodyToMono(String::class.java).map { stringBody ->
            log.info("Machine $machineUrlAndPort up! Response: $stringBody")
        }.switchIfEmpty {
            log.info("Machine $machineUrlAndPort up! (no-body)")
            Mono.just(Unit)
        }.map {
            // Unit
        }
    }

fun startTaskOnMachine(
    machineUrlAndPort: String,
    configuration: PublishSubscribeConfDto,
): Mono<Unit> = Mono.fromCallable {
    log.info("Starting task on machine $machineUrlAndPort in mode ${configuration.mode}, full config: $configuration")
}.flatMap {
    webClient.post()
        .uri("$machineUrlAndPort/pub-sub-demo")
        .bodyValue(configuration)
        .exchangeToMono { response ->
            require(response.statusCode() == HttpStatus.OK) {
                "Invalid status ${response.statusCode()}"
            }
            // enable wiretap to see body here...
            response.releaseBody()
        }.map {
            // Unit
        }.switchIfEmpty {
            Mono.just(Unit)
        }
}.doOnNext {
    log.info("Task on machine $machineUrlAndPort in mode ${configuration.mode} started ok...")
}

fun stopTaskOnMachine(
    machineUrlAndPort: String
): Mono<StatsWrapperDto> = Mono.fromCallable {
    log.info("Stopping task on machine $machineUrlAndPort...")
}.flatMap {
    webClient.delete()
        .uri("$machineUrlAndPort/pub-sub-demo")
        .retrieve()
        .bodyToMono(StatsWrapperDto::class.java)
        .switchIfEmpty {
            // just log info
            log.info("Stopped task on machine $machineUrlAndPort, no response body")
            Mono.empty()
        }
}.doOnNext {
    log.info("Stopped task on machine $machineUrlAndPort, got response: $it")
}

fun evaluateRun(
    runId: Int,
    conf: PublishSubscribeConfDto,
    consumerStats: StatsWrapperDto,
    producerStats: List<StatsWrapperDto>,
    approximateRuntime: Duration,
): DistributedRunResultsDto = DistributedRunResultsDto(
    runId = runId,
    runDurationSec = approximateRuntime.toSeconds(),
    minBatchIntervalSec = conf.minBatchIntervalMs,
    maxBatchIntervalSec = conf.maxBatchIntervalMs,
    producerInstances = producerStats.size,
    producersPerInstance = conf.parallelProducers,
    minMsgSizeKb = (conf.minMessagePayloadSizeBytes / 1000),
    maxMsgSizeKb = (conf.maxMessagePayloadSizeBytes / 1000),
    minBatchSize = conf.minMessagesPerBatch,
    maxBatchSize = conf.maxMessagesPerBatch,
    avgMs = consumerStats.wholeTripStats.avgRequestDurationMs,
    medianMs = consumerStats.wholeTripStats.medianRequestTimeMs,
    q90ms = consumerStats.wholeTripStats.percentilesMs[90.0],
    q95ms = consumerStats.wholeTripStats.percentilesMs[95.0],
    q99ms = consumerStats.wholeTripStats.percentilesMs[99.0],
    consumedMessages = consumerStats.totalConsumedMessages,
    consumerErrors = consumerStats.totalFailedMessages,
    producedMessages = producerStats.sumOf { it.totalProducedMessages },
    producerErrors = producerStats.sumOf { it.totalFailedMessages }
)

fun stopAllOrThrowException(): Mono<MutableList<Throwable>> = Flux.fromIterable(
    DistributedSetup.machineUrls
).flatMap { machineUrl ->
    stopTaskOnMachine(machineUrl).flatMap {
        Mono.empty<Throwable>()
    }.doOnError { error ->
        log.error("Failed to kill task on machine $machineUrl", error)
    }.onErrorResume { error ->
        Mono.just(error)
    }
}.collectList().doOnNext { maybeErrors ->
    when {
        maybeErrors.isNullOrEmpty() -> log.info("Deleted all, no errors...")
        else -> throw IllegalStateException("Failed to perform clean termination of tasks, there were ${maybeErrors.size} errors.")
    }
}

fun createReports(
    results: List<DistributedRunResultsDto>,
) {
    // extract properties names, this is a hack that allows to keep the sane order as defined in class
    // for purposes of rendering MD table...
    val properties = DistributedRunResultsDto::class.primaryConstructor!!.parameters
        .map { it.name }
        .map { propertyName ->
            DistributedRunResultsDto::class.memberProperties.find { it.name == propertyName }!!
        }

    File(TEMP_DIR_NAME).mkdir()
    printResultsAsMdTable(results, properties)
    printResultsAsCsv(results, properties)
}

/**
 * Prints results to the csv file in this directory. It contains a timestamp
 * and also it is git-ignored.
 */
private fun printResultsAsMdTable(
    distributedRunResultsDtos: List<DistributedRunResultsDto>,
    properties: List<KProperty1<DistributedRunResultsDto, *>>
) {
    File(reportFileName("md")).printWriter().use {writer ->
        // print header for the result table
        val header = properties.joinToString("|") { it.name }
        writer.appendLine("|$header|")
        // print separator under header
        writer.appendLine("|${properties.joinToString("|") { "----" }}|")

        // print values
        distributedRunResultsDtos.forEach { resultRow ->
            // print each row of results
            val row = properties.joinToString("|") { it.get(resultRow).toString() }
            writer.appendLine("|$row|")
        }
    }
}

private fun printResultsAsCsv(
    distributedRunResultsDtos: List<DistributedRunResultsDto>,
    properties: List<KProperty1<DistributedRunResultsDto, *>>
) {
    File(reportFileName("csv")).printWriter().use { writer ->
        // header
        writer.appendLine(properties.joinToString(",") { it.name })

        // values
        distributedRunResultsDtos.forEach { resultRow ->
            writer.appendLine(properties.joinToString(",") { it.get(resultRow).toString() })
        }
    }
}


private fun reportFileName(extension: String): String {
    val getTimeForFileName = ZonedDateTime.now()
        .format(DateTimeFormatter.ISO_INSTANT)
        .replace(":", "-")
        .replace(".", "_")

    return "$TEMP_DIR_NAME/report_$getTimeForFileName.$extension"
}

data class DistributedRunResultsDto(
    val runId: Int,
    val runDurationSec: Long,
    val minBatchIntervalSec: Int,
    val maxBatchIntervalSec: Int,
    val producerInstances: Int,
    val producersPerInstance: Int,
    val minMsgSizeKb: Int,
    val maxMsgSizeKb: Int,
    val minBatchSize: Int,
    val maxBatchSize: Int,
    val avgMs: Double?,
    val medianMs: Double?,
    val q90ms: Long?,
    val q95ms: Long?,
    val q99ms: Long?,
    val consumedMessages: Long,
    val consumerErrors: Long,
    val producedMessages: Long,
    val producerErrors: Long
)
