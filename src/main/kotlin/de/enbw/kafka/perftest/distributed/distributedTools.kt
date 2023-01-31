package de.enbw.kafka.perftest.distributed

import de.enbw.kafka.perftest.PerfTestConfigurationBeans
import de.enbw.kafka.perftest.utils.*
import org.springframework.http.HttpStatus
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import java.time.Duration

private val log by logger {}

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
): Mono<StatsWrapperDto> =Mono.fromCallable {
    log.info("Stopping task on machine $machineUrlAndPort...")
}.flatMap {
    webClient.delete()
        .uri("$machineUrlAndPort/pub-sub-demo")
        .retrieve()
        .bodyToMono(StatsWrapperDto::class.java)
        .switchIfEmpty {
            // just log info
            log.info("Stopped task on machine $machineUrlAndPort, no response")
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
    runtimeDuration = approximateRuntime,
    batchInterval = conf.minBatchIntervalMs to conf.maxBatchIntervalMs,
    numberOfProducerInstances = producerStats.size,
    producersPerInstance =  conf.parallelProducers,
    messageSizeKb = (conf.minMessagePayloadSizeBytes / 1000) to (conf.maxMessagePayloadSizeBytes / 1000),
    batchSize = conf.minMessagesPerBatch to conf.maxMessagesPerBatch,
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

data class DistributedRunResultsDto(
    val runId: Int,
    val runtimeDuration: Duration,
    val batchInterval: Pair<Int, Int>,
    val numberOfProducerInstances: Int,
    val producersPerInstance: Int,
    val messageSizeKb: Pair<Int, Int>,
    val batchSize: Pair<Int, Int>,
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
