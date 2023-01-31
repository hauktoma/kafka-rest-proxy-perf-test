/**
 * This script allows for distributed execution of the benchmark runs on multiple machines.
 * Outline:
 * - set of URLs/ports is provided where the benchmark application is deployed and its API available to this script
 * - set of run scenarios is defined below
 * - scenarios are sequentially ran, for each run, single consumer is randomly picked from available apps, rest
 * will take role of producers
 * - after all runs are executed, MD table is generated with results
 *
 * See DistributedSetup for configuration below.
 *
 * R|FIXME THa: make this configurable, more user friendly and usable
 */
package de.enbw.kafka.perftest.distributed

import de.enbw.kafka.perftest.utils.*
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

private val log by logger {}

/**
 * Main configuration class of runs. Just modifying this is sufficient to execute the whole thing.
 */
object DistributedSetup {

    /**
     * Determines duration of run.
     */
    val testDuration: Duration = Duration.ofSeconds(30)

    /**
     * Set of URL/ports with deployed benchmark application. Example value: 'http://111.111.111.111:8080'.
     */
    val machineUrls = setOf(
        "http://18.196.2.251:8080",
        "http://18.195.203.225:8080",
        "http://3.121.186.36:8080",
        "http://18.159.124.30:8080",
    )

    /**
     * Template configuration. This is used as a base configuration that
     * is modified somehow for each particular run in [runs].
     */
    private val template = PublishSubscribeConfDto(
        mode = ExecutionMode.CONSUMER_AND_PRODUCERS,
        type = PubSubType.KAFKA_REST_PROXY,
        minBatchIntervalMs = 100,
        maxBatchIntervalMs = 100,
        minMessagePayloadSizeBytes = 200000,
        maxMessagePayloadSizeBytes = 200000,
        parallelProducers = 1,
        gatherLatencies = true,
        // X|FIXME SECRETS! ==============================================================
        kafkaRestUri = TODO(),
        kafkaUserName = TODO(),
        kafkaUserPassword = TODO(),
        // X|FIXME SECRETS! ==============================================================
        kafkaTopic = "latency-test-topic-1",
        valueSchemaId = "7",
        // the group and consumer id will be generated in the run (UUID appended), this is just template
        kafkaConsumerGroup = "latency-test-group-${Instant.now().epochSecond}-",
        kafkaConsumerId = "latency-test-consumer-${Instant.now().epochSecond}-",
        minMessagesPerBatch = 1,
        maxMessagesPerBatch = 1,
        consumeTimeoutMs = 10,
        consumeMaxBytesMs = 10000000, // 10 MB
        commitInterval = Duration.ofSeconds(10)
    )

    /**
     * Runs derived from [template] that will be executed.
     */
    val runs = listOf(
        template.copy(parallelProducers = 2),
//        template.copy(parallelProducers = 4),
//        template.copy(parallelProducers = 8),
        template.copy(parallelProducers = 16),
//        template.copy(parallelProducers = 2, minBatchIntervalMs = 4, maxMessagesPerBatch = 4),
//        template.copy(parallelProducers = 4, minBatchIntervalMs = 4, maxMessagesPerBatch = 4),
//        template.copy(parallelProducers = 8, minBatchIntervalMs = 4, maxMessagesPerBatch = 4),
//        template.copy(parallelProducers = 16, minBatchIntervalMs = 4, maxMessagesPerBatch = 4),
//        template.copy(parallelProducers = 2, minMessagePayloadSizeBytes = 480000, maxMessagePayloadSizeBytes = 480000),
//        template.copy(parallelProducers = 4, minMessagePayloadSizeBytes = 480000, maxMessagePayloadSizeBytes = 480000),
//        template.copy(parallelProducers = 8, minMessagePayloadSizeBytes = 480000, maxMessagePayloadSizeBytes = 480000),
//        template.copy(parallelProducers = 16, minMessagePayloadSizeBytes = 480000, maxMessagePayloadSizeBytes = 480000),
    )
}

private fun doExecuteRun(index: Int, conf: PublishSubscribeConfDto): DistributedRunResultsDto = try {
    if (index > 0) {
        log.warn("There was a run before this one, sleeping a bit to give the setup a breather...")
        Mono.delay(Duration.ofSeconds(60)).block()
        log.info("Continuing after break...")
    }

    // do clean everything
    log.info("Stopping all the stuff...")
    stopAllOrThrowException().block(Duration.ofSeconds(30))
    checkMachinesOk()

    log.info("Executing run $index with conf $conf")

    // pick the consumer node randomly and mark rest as producers
    val consumerNode = DistributedSetup.machineUrls.random()
    val producerNodes = DistributedSetup.machineUrls - consumerNode
    log.info("Picked roles: consumer=$consumerNode, producers=$producerNodes")

    // spin consumer
    log.info("Spinning consumer...")
    startTaskOnMachine(
        consumerNode,
        conf.copy(
            mode = ExecutionMode.CONSUMER,
            kafkaConsumerGroup = "${conf.kafkaConsumerGroup}-${UUID.randomUUID()}",
            kafkaConsumerId = "${conf.kafkaConsumerId}-${UUID.randomUUID()}",
        )
    ).block(Duration.ofSeconds(10))

    // wait a bit, then start timer and spin up the producers...
    log.info("Waiting after consumer spin...")
    Mono.delay(Duration.ofSeconds(10)).block()

    log.info("Spinning producers...")
    Flux.fromIterable(producerNodes).flatMap { producerNode ->
        startTaskOnMachine(
            producerNode,
            conf.copy(mode = ExecutionMode.PRODUCERS)
        )
    }.blockLast(Duration.ofSeconds(10))

    // wait until the run finishes, then immediately stop everything ASAP
    log.info("All up, waiting for test to finish for ${DistributedSetup.testDuration}...")
    val timerStart = Instant.now()
    Mono.delay(DistributedSetup.testDuration).block()
    log.info("Gathering results...")

    Mono.zip(
        // producers
        Flux.fromIterable(producerNodes).flatMap { producerNode ->
            stopTaskOnMachine(producerNode).switchIfEmpty {
                throw IllegalArgumentException("Empty response from producer $producerNode!")
            }
        }.collectList(),
        // consumer -> response is mandatory, there are stats there
        stopTaskOnMachine(consumerNode).switchIfEmpty {
            throw IllegalArgumentException("Empty response from consumer!")
        }.map { it to Instant.now() }
    ).map {
        log.info("Evaluating run...")
        val (consumerResult, endTime) = it.t2
        evaluateRun(
            runId = index,
            conf = conf,
            consumerStats = consumerResult,
            producerStats = it.t1,
            approximateRuntime = Duration.between(timerStart, endTime)
        ).also { runResults ->
            log.info("Run $index evaluated, results: $runResults")
        }
    }.block(Duration.ofSeconds(30))!!
} catch (ex: Exception) {
    log.error("Failed to execute run $index: ${ex.message}", ex)
    throw ex
} finally {
    // no matter what happens in the run, kill everything after
    log.info("Final cleanup of run $index...")
    stopAllOrThrowException().block(Duration.ofSeconds(30))
    log.info("Run $index cleaned up.")
}

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


fun doExecuteRuns() {
    // check we have proper configuration
    require(DistributedSetup.machineUrls.size >= 2) {
        "At least two distinct machines must be provided for distributed run."
    }

    // check that machines are available
    checkMachinesOk()

    // extract properties names, this is a hack that allows to keep the sane order as defined in class
    // for purposes of rendering MD table...
    val properties = DistributedRunResultsDto::class.primaryConstructor!!.parameters
        .map { it.name }
        .map { propertyName ->
            DistributedRunResultsDto::class.memberProperties.find { it.name == propertyName }!!
        }

    DistributedSetup.runs.mapIndexed { index, conf ->
        doExecuteRun(index, conf)
    }.also {
        // print header for the result table
        val header = properties.joinToString("|") { it.name }
        println("|$header|")
    }.forEach { resultRow ->
        // print each row of results
        val row = properties.joinToString("|") { it.get(resultRow).toString() }
        println("|$row|")
    }
}

private fun checkMachinesOk() {
    DistributedSetup.machineUrls.forEach { machineUrl ->
        checkMachineAvailable(machineUrl).block(Duration.ofSeconds(10))
    }
}

fun main() {
    // stopAllOrThrowException().block(Duration.ofSeconds(30))
    doExecuteRuns()
}
