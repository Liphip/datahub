package com.dataturntable.test

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName

class KafkaTestResource : QuarkusTestResourceLifecycleManager {
    private lateinit var kafka: KafkaContainer

    override fun start(): MutableMap<String, String> {
        kafka = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"))
        kafka.start()
        val bootstrap = kafka.bootstrapServers
        // Make it available as a system property for tests that read it directly
        System.setProperty("KAFKA_BOOTSTRAP_SERVERS", bootstrap)
        return mutableMapOf(
            "KAFKA_BOOTSTRAP_SERVERS" to bootstrap,
            // Ensure the reactive incoming pipeline-input is enabled for the test
            "mp.messaging.incoming.pipeline-input.enabled" to "true",
            "mp.messaging.incoming.pipeline-input.bootstrap.servers" to bootstrap,
            "mp.messaging.outgoing.pipeline-commands.bootstrap.servers" to bootstrap,
            "mp.messaging.outgoing.pipeline-output.bootstrap.servers" to bootstrap
        )
    }

    override fun stop(): Unit {
        if (this::kafka.isInitialized) kafka.stop()
    }
}
