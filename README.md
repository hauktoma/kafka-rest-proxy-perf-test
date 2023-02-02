# Kafka REST Proxy performance tests

Helper project to measure performance of Kafka producer-consumer setup using the REST Proxy API of Kafka.
This tests sets up single consumer and multiple producers and tries to measure duration the whole round-trip
of produced message.

Distributed mode also supported to overcome the network limitations of e.g. AWS Fargate containers.

See outline at the end of document for more info about the method.

## Build 

```shell
./gradlew clean build;
docker build --platform linux/amd64 -t kafka-perf-tests .
docker tag kafka-perf-tests:latest DOCKER_REPO/kafka-perf-tests:latest
docker push DOCKER_REPO/kafka-perf-tests:latest
```

## Simple single-node Usage

After building and deploying the application to appropriate environment, the application exposes REST API on
port 8080. This API can be used to start, query and end the performance test.

The HTTP requests API can be found in `http/http-requests.http` and can be simply executed 
with IntellijIDEA HTTP plugin. Example `curl` usage see below.

### Assumptions
- AVRO schema created in schema registry (see `de.enbw.kafka.perftest.service.KafkaRestProxyPubSubService.VALUE_SCHEMA`)
- topic exists in the Kafka cluster

### Example curl usage
Start test run:
```shell
curl -X POST --location "http://localhost:8080/pub-sub-demo" \
    -H "Accept: application/json" \
    -H "Content-Type: application/json" \
    -d "{
          \"type\": \"KAFKA_REST_PROXY\",
          \"minBatchIntervalMs\": 100,
          \"maxBatchIntervalMs\": 100,
          \"minMessagePayloadSizeBytes\": 180000,
          \"maxMessagePayloadSizeBytes\": 180000,
          \"parallelProducers\": 4,
          \"gatherLatencies\": true,
          \"kafkaRestUri\": \"XXX\",
          \"kafkaUserName\": \"XXX\",
          \"kafkaUserPassword\": \"XXX\",
          \"kafkaTopic\": \"latency-test-topic-1\",
          \"valueSchemaId\": \"7\",
          \"kafkaConsumerGroup\": \"consumer-group-b0d0b161-a981-47bf-9cd5-28b43cc5f0c3\",
          \"kafkaConsumerId\": \"consumer-310efdb8-ac48-41cb-a3b9-50b6b8d7c37e\",
          \"minMessagesPerBatch\": 4,
          \"maxMessagesPerBatch\": 4,
          \"consumeTimeoutMs\": 10,
          \"consumeMaxBytesMs\": 5000000,
          \"commitInterval\": \"PT10S\"
        }"
```

End test run and obtain results:
```shell
curl -X DELETE --location "http://localhost:8080/pub-sub-demo"
```

Response:
```json
{
  "totalProducedMessages": 1142,
  "totalConsumedMessages": 1142,
  "totalFailedMessages": 0,
  "producerStats": {
    "totalLatenciesGathered": 1142,
    "minRequestDurationMs": 28,
    "maxRequestDurationMs": 537,
    "avgRequestDurationMs": 297.46,
    "medianRequestTimeMs": 296.0,
    "percentilesMs": {
      "50.0": 296,
      "80.0": 301,
      "90.0": 306,
      "95.0": 313,
      "98.0": 339,
      "99.0": 366
    }
  },
  "wholeTripStats": {
    "totalLatenciesGathered": 1142,
    "minRequestDurationMs": 196,
    "maxRequestDurationMs": 606,
    "avgRequestDurationMs": 328.43,
    "medianRequestTimeMs": 325.0,
    "percentilesMs": {
      "50.0": 325,
      "80.0": 335,
      "90.0": 344,
      "95.0": 355,
      "98.0": 379,
      "99.0": 419
    }
  },
  "taskStartTime": "2023-01-30T16:35:15.659539692Z",
  "taskKillTime": "2023-01-30T16:37:17.013988964Z",
  "taskDuration": "PT2M1.354449272S"
}
```

### Important configuration options that can be set into HTTP payload that starts the run
- `minBatchIntervalMs` / `maxBatchIntervalMs`: random interval in ms between messages being produced.
- `minMessagePayloadSizeBytes` / `maxMessagePayloadSizeBytes`: random interval of the single message size in bytes
  - note that the size is for message, not for the batch (aka HTTP produce request)
- `parallelProducers`: count of independent concurrent producers
- `kafkaTopic`: name of topic to use, must exist
- `valueSchemaId`: id of the existing AVRO schema (see assumptions above)
- `kafkaConsumerGroup`: consumer group, should be newly created for each run to avoid messing up results between runs
- `kafkaConsumerId`: ID of consumer, should be newly created for each run to avoid messing up results between runs
- `minMessagesPerBatch` / `maxMessagesPerBatch`: number of messages in single HTTP producing request
- `consumeTimeoutMs`: parameter used by producers as `timeout` parameter of the REST Proxy produce endpoint
  - https://docs.confluent.io/platform/current/kafka-rest/api.html#get--consumers-(string-group_name)-instances-(string-instance)-records
- `consumeMaxBytesMs`: parameter used by producers as `max_bytes` of REST proxy
  - https://docs.confluent.io/platform/current/kafka-rest/api.html#get--consumers-(string-group_name)-instances-(string-instance)-records
- `commitInterval`: determines whether to use synchronous or async commits.
  - null -> synchronous commits
  - ISO duration (e.g. `PT1M`) -> async commits
  - see `Performance test method outline` below for more info

More details see class `de.enbw.kafka.perftest.utils.PublishSubscribeConfDto`, the parameters not mentioned
should not be touched.

## Distributed mode usage
See `src/main/kotlin/de/enbw/kafka/perftest/distributed/distributedExecutionHelper.kt`

`FIXME`: make this more user friendly and usable

## Performance test method outline

- number of producers can be configured per run
- only single consumer supported per run
  - aim: simulate lag between overwhelming number of producers
  - assumption: consumer(s) can be scaled horizontally
- new instance of consumer and new consumer group created for each run
  - therefore multiple subsequent runs on single topic do not mess up the results
- offset committing can be configured by the `commitInterval` of the run-execution payload
  - `null` / not set -> will commit synchronously, i.e. next poll of consumer will happen after successful commit only
  - ISO Interval (e.g. `PT1M`) -> will commit asynchronously in this interval independent of the polling / consumption
- each run should create its own new consumer group and consumer with `latest` offset, so that the topic can be and
still have the runs to be independent (easily done with IntellijIDEA HTTP plugin and `$uuid` clause) 
