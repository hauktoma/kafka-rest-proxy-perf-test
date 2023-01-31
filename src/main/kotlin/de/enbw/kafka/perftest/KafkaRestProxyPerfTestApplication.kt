package de.enbw.kafka.perftest

import io.netty.handler.logging.LogLevel
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.http.codec.ClientCodecConfigurer
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.transport.logging.AdvancedByteBufFormat

@SpringBootApplication
@EnableScheduling
class KafkaRestProxyPerfTestApplication

fun main(args: Array<String>) {
    runApplication<KafkaRestProxyPerfTestApplication>(*args)
}

@Configuration
class PerfTestConfigurationBeans {

    @Bean
    fun createWebClient(): WebClient = doCreateWebClient(withWiretap = false)

    companion object {
        fun doCreateWebClient(
            withWiretap: Boolean = false
        ): WebClient = HttpClient
            .create()
            .let { if (withWiretap) it.withWiretap() else it }
            .let { nettyClient ->
                WebClient.builder()
                    .clientConnector(ReactorClientHttpConnector(nettyClient))
                    .exchangeStrategies(
                        ExchangeStrategies.builder()
                            .codecs { configurer: ClientCodecConfigurer ->
                                configurer.defaultCodecs()
                                    .maxInMemorySize(36000000)
                            }
                            .build())
                    .build()
            }

        /**
         * If enabled, will print HTTP traffic (request, response) to HTTP logs.
         */
        @Suppress("unused") // enabled on demand
        private fun HttpClient.withWiretap(): HttpClient = this.wiretap(
            "reactor.netty.http.client.HttpClient",
            LogLevel.ERROR,
            AdvancedByteBufFormat.TEXTUAL
        )
    }
}
