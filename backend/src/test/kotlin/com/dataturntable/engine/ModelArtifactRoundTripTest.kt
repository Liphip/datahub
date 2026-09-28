package com.dataturntable.engine

import com.dataturntable.execution.ExecutionLimits
import com.dataturntable.module.CodeModule
import com.dataturntable.module.ModelArtifactService
import com.dataturntable.module.ModuleType
import com.dataturntable.module.ModuleVersion
import com.dataturntable.tenant.Tenant
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * End-to-end MOD-15 – MOD-19: a PROCESSING module fits and saves a model in one execution, and a
 * later, entirely independent execution (of the same or a different module) loads and applies it
 * — the "train and apply a model, no dedicated ML server" use case (functional spec §5.6/§13.6).
 * Runs against a real Docker daemon like [DockerServiceHostileModuleTest]; skipped without one.
 */
@org.junit.jupiter.api.condition.EnabledIf("com.dataturntable.test.DockerAvailability#available")
@QuarkusTest
class ModelArtifactRoundTripTest {

    @Inject lateinit var dockerService: DockerService
    @Inject lateinit var modelArtifactService: ModelArtifactService
    @Inject lateinit var em: EntityManager

    private fun newModuleVersion(code: String): ModuleVersion {
        val moduleId = QuarkusTransaction.requiringNew().call<UUID> {
            val tenant = Tenant().apply { name = "Model Artifact Test ${UUID.randomUUID()}" }
            em.persist(tenant)
            val module = CodeModule().apply {
                this.tenant = tenant
                this.name = "forecast"
                this.moduleType = ModuleType.PROCESSING
            }
            em.persist(module)
            em.flush()
            module.id!!
        }
        return ModuleVersion().apply {
            this.code = code
            this.requirements = ""
            this.module = QuarkusTransaction.requiringNew().call { em.find(CodeModule::class.java, moduleId) }
        }
    }

    private fun run(version: ModuleVersion, input: Map<String, Any?>): ModuleExecutionOutput =
        dockerService.executeModule(
            moduleVersion = version,
            inputData = input,
            pipelineId = "model-artifact-test",
            runId = UUID.randomUUID().toString(),
            moduleType = ModuleType.PROCESSING,
            limits = ExecutionLimits(timeoutSeconds = 30)
        )

    @Test
    fun `a saved model is loaded and applied by a later, independent execution`() {
        val trainCode = "def process(input_data, query, save_model, load_model):\n" +
            "    save_model('multiplier', {'factor': input_data['factor']})\n" +
            "    return {'trained': True}"
        val inferCode = "def process(input_data, query, save_model, load_model):\n" +
            "    model = load_model('multiplier')\n" +
            "    return {'result': input_data['value'] * model['factor']}"
        val moduleVersion = newModuleVersion(trainCode)

        val trainOutput = run(moduleVersion, mapOf("factor" to 3))
        @Suppress("UNCHECKED_CAST")
        assertEquals(true, (trainOutput.result as Map<String, Any?>)["trained"])
        // The training result never leaks the internal _savedModels marker to the next node.
        assertNull((trainOutput.result as Map<*, *>)["_savedModels"])

        val moduleId = moduleVersion.module!!.id!!
        assertEquals(1, modelArtifactService.listArtifacts(moduleId).single().version)

        val inferVersion = moduleVersion.apply { code = inferCode }
        val inferOutput = run(inferVersion, mapOf("value" to 10))
        @Suppress("UNCHECKED_CAST")
        assertEquals(30, ((inferOutput.result as Map<String, Any?>)["result"] as Number).toInt())
    }

    @Test
    fun `retraining creates a new version that the next inference execution sees`() {
        val trainCode = "def process(input_data, query, save_model, load_model):\n" +
            "    save_model('multiplier', {'factor': input_data['factor']})\n" +
            "    return {'trained': True}"
        val moduleVersion = newModuleVersion(trainCode)
        run(moduleVersion, mapOf("factor" to 3))
        run(moduleVersion, mapOf("factor" to 7))  // retrain
        val moduleId = moduleVersion.module!!.id!!
        assertEquals(2, modelArtifactService.listArtifacts(moduleId).single().version)

        val inferVersion = moduleVersion.apply {
            code = "def process(input_data, query, save_model, load_model):\n" +
                "    return {'result': input_data['value'] * load_model('multiplier')['factor']}"
        }
        val inferOutput = run(inferVersion, mapOf("value" to 2))
        @Suppress("UNCHECKED_CAST")
        assertEquals(14, ((inferOutput.result as Map<String, Any?>)["result"] as Number).toInt())
    }

    @Test
    fun `loading a name that was never saved returns None, not an error`() {
        val code = "def process(input_data, query, save_model, load_model):\n" +
            "    return {'model_present': load_model('never-saved') is not None}"
        val output = run(newModuleVersion(code), mapOf("value" to 1))
        @Suppress("UNCHECKED_CAST")
        assertEquals(false, (output.result as Map<String, Any?>)["model_present"])
    }

    @Test
    fun `pinning an explicit historical version inside a running module fails loudly`() {
        // MOD-16's documented limitation: a running module has no live channel back to the
        // platform, so only the version preloaded at start (the latest) is available.
        val code = "def process(input_data, query, save_model, load_model):\n" +
            "    return {'x': load_model('m', version=1)}"
        assertThrows(ModuleExecutionException::class.java) { run(newModuleVersion(code), mapOf("value" to 1)) }
    }
}
