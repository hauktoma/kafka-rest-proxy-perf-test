package de.enbw.kafka.perftest.serviceutils

import com.fasterxml.jackson.annotation.JsonProperty
import de.enbw.kafka.perftest.utils.OrderMetadataDto
import org.springframework.core.ParameterizedTypeReference
import org.springframework.web.reactive.function.client.ClientResponse
import reactor.core.publisher.Mono

data class KeyValuePair<T>(
    val key: String?,
    val value: T
)

data class AvroRecordDTO<T>(
    val value: T,
    val metadata: OrderMetadataDto
)
data class ProduceMessagesInDTO<T>(
    @JsonProperty("key_schema")
    val keySchema: String?,
    @JsonProperty("key_schema_id")
    val keySchemaId: String?,
    @JsonProperty("value_schema")
    val valueSchema: String?,
    @JsonProperty("value_schema_id")
    val valueSchemaId: String?,
    @JsonProperty("records")
    val records: List<KeyValuePair<AvroRecordDTO<T>>>
) {
    override fun toString(): String {
        return "{payload=${records.toString().take(20)}...}"
    }
}

data class AvroRecordOutDTO<T>(
    val key: String?,
    val offset: Int,
    val partition: Int,
    val topic: String,
    val value: T
) {
    override fun toString(): String {
        return "{payload=${value.toString().take(64)}...}"
    }
}

inline fun <reified T> typeReference() = object : ParameterizedTypeReference<T>() {}

fun getResponseParser(): (ClientResponse) -> Mono<List<AvroRecordOutDTO<Map<Any, Any>>>> = { response ->
    response.bodyToMono(typeReference<List<AvroRecordOutDTO<Map<Any, Any>>>>())
}
