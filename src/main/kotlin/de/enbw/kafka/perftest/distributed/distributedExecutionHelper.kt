/**
 * This script allows for distributed execution of the benchmark runs on multiple machines.
 *
 * How to use:
 * - deploy the application in at least 2 instances, make its port 8080 accessible via HTTP
 * - configure the `DistributedSetup` class below
 * - run the main method of this script
 *
 * Method outline:
 * - the `DistributedSetup` contains configuration such as test duration, URL of the benchmark instances,
 * run configurations (parallelism, size of messages etc) -> this will be executed in sequence
 * - for each run execution one consumer and multiple producers are chosen randomly from instances
 * - at the end of the execution MD table is printed into log with results
 *
 * See DistributedSetup for configuration below.
 *
 * R|FIXME THa: make this configurable, more user friendly and usable
 */
package de.enbw.kafka.perftest.distributed

import de.enbw.kafka.perftest.utils.ExecutionMode
import de.enbw.kafka.perftest.utils.PubSubType
import de.enbw.kafka.perftest.utils.PublishSubscribeConfDto
import de.enbw.kafka.perftest.utils.logger
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.switchIfEmpty
import java.time.Duration
import java.time.Instant
import java.util.*

private val log by logger {}

/**
 * Main configuration class of runs. Just modifying this is sufficient to execute the whole thing.
 */
object DistributedSetup {

    /**
     * Determines duration of each run.
     */
    val testDuration: Duration = Duration.ofSeconds(60)

    /**
     * Set of URL/ports with deployed benchmark application. Example value: 'http://111.111.111.111:8080'.
     * There must be at least two instances here so that we have distinct producers and consumers.
     */
    val machineUrls: Set<String> = setOf(
        TODO()
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
        commitInterval = Duration.ofSeconds(10),
        runDuration = null, // do not set duration here test is terminated by DELETE endpoint
    )

    val runs: List<PublishSubscribeConfDto> = listOf(1, 2, 4, 8, 16).map { parallelProducers ->
        listOf(1, 2, 4).map { messagesPerBatch ->
            listOf(200000, 480000).map { messageSizesBytes ->
                listOf(500, 250, 100).map { batchInterval ->
                    template.copy(
                        parallelProducers = parallelProducers,
                        minMessagesPerBatch = messagesPerBatch,
                        maxMessagesPerBatch = messagesPerBatch,
                        minBatchIntervalMs = batchInterval,
                        maxBatchIntervalMs = batchInterval,
                        minMessagePayloadSizeBytes = messageSizesBytes,
                        maxMessagePayloadSizeBytes = messageSizesBytes
                    )
                }
            }.flatten()
        }.flatten()
    }.flatten()
}

private fun doExecuteRun(runId: Int, conf: PublishSubscribeConfDto): DistributedRunResultsDto = try {
    if (runId > 1) { // index from 1
        log.warn("There was a run before this one, sleeping a bit to give the setup a breather...")
        Mono.delay(Duration.ofSeconds(20)).block()
        log.info("Continuing after break...")
    }

    // do clean everything
    log.info("Stopping all the stuff...")
    stopAllOrThrowException().block(Duration.ofSeconds(30))
    checkMachinesOk()

    log.info("Executing run $runId with conf $conf")

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
            runId = runId,
            conf = conf,
            consumerStats = consumerResult,
            producerStats = it.t1,
            approximateRuntime = Duration.between(timerStart, endTime)
        ).also { runResults ->
            log.info("Run $runId evaluated, results: $runResults")
        }
    }.block(Duration.ofSeconds(30))!!
} catch (ex: Exception) {
    log.error("Failed to execute run $runId: ${ex.message}", ex)
    throw ex
} finally {
    // no matter what happens in the run, kill everything after
    log.info("Final cleanup of run $runId...")
    stopAllOrThrowException().block(Duration.ofSeconds(30))
    log.info("Run $runId cleaned up.")
}

fun doExecuteRuns() {
    // check we have proper configuration
    require(DistributedSetup.machineUrls.size > 1) {
        "At least two distinct machines must be provided for distributed run."
    }

    // check that machines are available
    checkMachinesOk()

    log.info("All checks passed, will execute total of ${DistributedSetup.runs.size} runs...")

    DistributedSetup.runs.mapIndexed { index, conf ->
        val runId = index + 1
        val runString = "${runId}/${DistributedSetup.runs.size}"

        log.info("Executing run [$runString]")
        doExecuteRun(runId, conf).also {
            log.info("Run [$runString] executed")
        }
    }.also { results ->
        createReports(results)
    }
}

private fun checkMachinesOk() {
    DistributedSetup.machineUrls.forEach { machineUrl ->
        checkMachineAvailable(machineUrl).block(Duration.ofSeconds(10))
    }
}

/**
 * Execute this, wait for results and then take the MD table and format it somewhere.
 *
 * Double check that your tasks were killed properly!
 */
fun main() {
    stopAllOrThrowException().block(Duration.ofSeconds(30))

    // debug print runs, outside logger to save space
    DistributedSetup.runs.forEach { conf ->
        println(conf)
    }
    doExecuteRuns()
}
