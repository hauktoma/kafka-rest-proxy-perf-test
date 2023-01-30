# Kafka perf tests

Helper project to measure performance of Kafka producer-consumer setup using the REST Proxy API of Kafka.

## Build 

```shell
./gradlew clean build;
docker build --platform linux/amd64 -t kafka-perf-tests .
docker tag kafka-perf-tests:latest DOCKER_REPO/kafka-perf-tests:latest
docker push DOCKER_REPO/kafka-perf-tests:latest
```

## Usage

After building and deploying the application to appropriate environment, the application exposes REST API on
port 8080. This API can be used to start, query and end the performance test.

The HTTP requests API can be found in `http/http-requests.http` and can be simply executed 
with IntellijIDEA HTTP plugin. Example `curl` usage see below.

### Assumptions
- AVRO schema created in schema registry (see `de.enbw.kafka.perftest.service.KafkaRestProxyPubSubService.VALUE_SCHEMA`)
- topic exists

### Example curl usage
Start test run:
```shell

```

End test run and obtain results:
```shell

```

### Important configuration options that can be set into HTTP payload that starts the run
- `minIntervalMs` / `maxIntervalMs`: random interval in ms between messages being produced.
- `minMessagePayloadSizeBytes` / `maxMessagePayloadSizeBytes`: random interval of the single message size in bytes
  - note that the size is for message, not for the batch (aka HTTP produce request)
- `parallelProducers`: count of independent concurrent producers
- `kafkaTopic`: name of topic to use, must exist
- `valueSchemaId`: id of the existing AVRO schema (see assumptions above)
- `kafkaConsumerGroup`: consumer group, should be newly created for each run to avoid messing up results between runs
- `kafkaConsumerId`: ID of consumer, should be newly created for each run to avoid messing up results between runs
- `minMessagesPerBatch` / `maxMessagesPerBatch`: number of messages in single HTTP producing request
- `consumeTimeoutMs`: equivalent parameter to `timeout` parameter of the REST Proxy produce endpoint
  - https://docs.confluent.io/platform/current/kafka-rest/api.html#get--consumers-(string-group_name)-instances-(string-instance)-records
- `consumeMaxBytesMs`: equivalent parameter to `max_bytes` of REST proxy
  - https://docs.confluent.io/platform/current/kafka-rest/api.html#get--consumers-(string-group_name)-instances-(string-instance)-records
- `commitInterval`: determines whether to use synchronous or async commits.
  - null -> synchronous commits
  - ISO duration (e.g. `PT1M`) -> async commits
  - see `Performance test method outline` below for more info

More details see class `de.enbw.kafka.perftest.utils.PublishSubscribeConfDto`, the parameters not mentioned
should not be touched.

## Performance test method outline

- number of producers can be configured per run
- only single consumer supported per run
  - aim: simulate lag between overwhelming number of producers
  - assumption: consumer can be scaled horizontally
- offset committing can be configured by the `commitInterval` of the payload
  - `null` / not set -> will commit synchronously, i.e. next poll of consumer will happen after successful commit only
  - ISO Interval (e.g. `PT1M`) -> will commit asynchronously in this interval independent of the polling / consumption
- each run should create its own new consumer group and consumer with `latest` offset, so that the topic can be and
still have the runs to be independent (easily done with IntellijIDEA HTTP plugin and `$uuid` clause) 
