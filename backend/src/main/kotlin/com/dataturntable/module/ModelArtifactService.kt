package com.dataturntable.module

import com.dataturntable.security.EncryptionUtils
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Persists and loads the model artifacts a PROCESSING module saves with `save_model_fn` and
 * loads with `load_model_fn` (MOD-15/16). Named, versioned and tenant+module scoped, like
 * [com.dataturntable.secrets.SecretService] but keyed by module rather than by tenant alone,
 * since a model artifact only ever makes sense to the module (or a sibling module deliberately
 * sharing the name) that produced it.
 *
 * Only the current version is preloaded before a module runs (MOD-19: a worker sees a newer
 * version without a pipeline restart). Pinning an explicit past version is supported when a
 * caller already knows it needs one (e.g. a reproducibility tool); `load_model_fn` inside a
 * running module container cannot request an arbitrary past version on demand — the module
 * process has no live channel back to the platform, only the artifacts preloaded before it
 * started (see the functional specification, §5.6, and the handoff for why).
 */
@ApplicationScoped
class ModelArtifactService {
    @PersistenceContext lateinit var em: EntityManager

    @ConfigProperty(name = "dataturntable.execution.model-artifact-cache-seconds", defaultValue = "60")
    lateinit var cacheSeconds: String

    private val nameRegex = Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,127}")

    /** [preloadLatestForModule] avoids a database round trip on every execution (MOD-18): a save
     * on this instance invalidates its module's entry at once; other instances (and a save made
     * elsewhere) are picked up within [cacheSeconds] — the same instant-local/eventual-remote
     * pattern already used for ingest API keys ([com.dataturntable.security.IngestAuthorizationService]). */
    private data class CacheEntry(val loadedAt: Instant, val artifacts: Map<String, String>, val versions: Map<String, Int>)
    private val cache = ConcurrentHashMap<UUID, CacheEntry>()

    /** Saves [base64Data] as the next version of [name] under [moduleId]; never overwrites. */
    @Transactional
    fun save(tenantId: UUID, moduleId: UUID, name: String, base64Data: String, runId: String? = null): ModelArtifact {
        require(name.matches(nameRegex)) { "Invalid model artifact name" }
        require(base64Data.isNotEmpty()) { "Model artifact data must not be empty" }
        val nextVersion = (latestVersion(moduleId, name) ?: 0) + 1
        val artifact = ModelArtifact().apply {
            this.tenantId = tenantId
            this.moduleId = moduleId
            this.name = name
            this.version = nextVersion
            this.dataEncrypted = EncryptionUtils.encrypt(base64Data)
            this.sizeBytes = base64Data.length.toLong()
            this.createdByRunId = runId
        }
        em.persist(artifact)
        em.flush()
        cache.remove(moduleId)
        return artifact
    }

    /** The current version's base64 data, or null if [name] was never saved under [moduleId]. */
    fun loadLatest(moduleId: UUID, name: String): String? = latestRow(moduleId, name)?.let { EncryptionUtils.decrypt(it.dataEncrypted) }

    /** A specific past version's base64 data, or null if it does not exist. */
    fun loadVersion(moduleId: UUID, name: String, version: Int): String? =
        em.createQuery(
            "SELECT a FROM ModelArtifact a WHERE a.moduleId = :m AND a.name = :n AND a.version = :v",
            ModelArtifact::class.java
        ).setParameter("m", moduleId).setParameter("n", name).setParameter("v", version)
            .resultList.firstOrNull()?.let { EncryptionUtils.decrypt(it.dataEncrypted) }

    /**
     * The latest base64 data of every distinct name this module has saved, for the platform to
     * preload into the container before it runs (MOD-18/19). Small in practice: a module manages
     * a handful of named artifacts (e.g. one per device class), not an open-ended set.
     */
    fun preloadLatestForModule(moduleId: UUID): Map<String, String> = preload(moduleId).artifacts

    /** What is handed to one execution: each name's data and the version that data is (MOD-16). */
    data class Preloaded(val artifacts: Map<String, String>, val versions: Map<String, Int>)

    /** Data and version numbers come from the same cache entry, so they always belong together. */
    fun preload(moduleId: UUID): Preloaded {
        val ttl = cacheSeconds.toLongOrNull() ?: 60L
        cache[moduleId]?.let { entry ->
            if (entry.loadedAt.isAfter(Instant.now().minusSeconds(ttl))) return Preloaded(entry.artifacts, entry.versions)
        }
        val latest = em.createQuery(
            "SELECT a FROM ModelArtifact a WHERE a.moduleId = :m ORDER BY a.name, a.version DESC",
            ModelArtifact::class.java
        ).setParameter("m", moduleId).resultList.distinctBy { it.name }
        val entry = CacheEntry(
            Instant.now(),
            latest.associate { it.name to EncryptionUtils.decrypt(it.dataEncrypted) },
            latest.associate { it.name to it.version },
        )
        cache[moduleId] = entry
        return Preloaded(entry.artifacts, entry.versions)
    }

    /**
     * MOD-16: records that [runId] used [version] of [name]. Idempotent, so a run that is
     * executed again after a failover does not add a second row.
     */
    @Transactional
    fun recordRead(tenantId: UUID, moduleId: UUID, name: String, version: Int, runId: String) {
        val known = (em.createQuery(
            "SELECT COUNT(r) FROM ModelArtifactRead r WHERE r.runId = :run AND r.moduleId = :m AND r.name = :n AND r.version = :v"
        ).setParameter("run", runId).setParameter("m", moduleId).setParameter("n", name).setParameter("v", version)
            .singleResult as Number).toLong() > 0
        if (known) return
        em.persist(ModelArtifactRead().apply {
            this.tenantId = tenantId; this.moduleId = moduleId; this.name = name; this.version = version; this.runId = runId
        })
    }

    /** The artifact versions [runId] used, by name. */
    fun readsForRun(runId: String): List<ModelArtifactRead> =
        em.createQuery("SELECT r FROM ModelArtifactRead r WHERE r.runId = :run ORDER BY r.name, r.version", ModelArtifactRead::class.java)
            .setParameter("run", runId).resultList

    /** Every saved name under [moduleId] with its latest version, size and creation time (for a future UI). */
    fun listArtifacts(moduleId: UUID): List<ModelArtifact> =
        em.createQuery(
            "SELECT a FROM ModelArtifact a WHERE a.moduleId = :m ORDER BY a.name, a.version DESC",
            ModelArtifact::class.java
        ).setParameter("m", moduleId).resultList.distinctBy { it.name }

    private fun latestRow(moduleId: UUID, name: String): ModelArtifact? =
        em.createQuery(
            "SELECT a FROM ModelArtifact a WHERE a.moduleId = :m AND a.name = :n ORDER BY a.version DESC",
            ModelArtifact::class.java
        ).setParameter("m", moduleId).setParameter("n", name).setMaxResults(1).resultList.firstOrNull()

    private fun latestVersion(moduleId: UUID, name: String): Int? = latestRow(moduleId, name)?.version
}
