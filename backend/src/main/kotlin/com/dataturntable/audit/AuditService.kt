package com.dataturntable.audit

import com.dataturntable.api.RequestIdFilter
import com.dataturntable.security.SensitiveDataRedactor
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@ApplicationScoped
class AuditService {
    @PersistenceContext lateinit var em: EntityManager
    @Inject lateinit var redactor: SensitiveDataRedactor
    private val mapper = com.dataturntable.json.JsonObjectMappers.create()

    /**
     * Appends one record to the audit trail and to its hash chain (AUD-05). The chain head row is
     * locked until the caller's transaction ends, so appends are strictly ordered across all
     * instances; a transaction that audits early therefore holds other audited transactions back
     * until it commits — audit at the end of the unit of work.
     */
    @Transactional
    fun record(actorId: UUID?, tenantId: UUID?, action: String, resourceType: String, resourceId: String?, metadata: Map<String, Any?> = emptyMap(), requestId: String? = null) {
        require(action.matches(Regex("[a-z0-9_.:-]{1,128}"))) { "Invalid audit action" }
        val head = lockHead()
        val event = AuditEvent().apply {
            // Millisecond precision: what is hashed must read back identically from any database.
            this.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            this.actorId = actorId
            this.tenantId = tenantId
            this.action = action
            this.resourceType = resourceType
            this.resourceId = resourceId
            this.requestId = requestId ?: RequestIdFilter.currentRequestId()
            this.metadata = mapper.writeValueAsString(redact(metadata))
            this.seq = head.lastSeq + 1
            this.prevHash = head.lastHash
        }
        event.hash = hashOf(event)
        em.persist(event)
        head.lastSeq = event.seq!!
        head.lastHash = event.hash!!
    }

    private fun lockHead(): AuditChainHead =
        em.find(AuditChainHead::class.java, 1.toShort(), LockModeType.PESSIMISTIC_WRITE)
            // Created by the migration in production; absent only where the schema comes from the entities.
            ?: AuditChainHead().also { em.persist(it); em.flush() }

    data class ChainReport(
        val intact: Boolean, val checked: Long, val unchained: Long,
        val firstSeq: Long?, val lastSeq: Long?, val brokenAtSeq: Long?, val problem: String?,
    )

    /**
     * Recomputes the chain (AUD-05). Records removed by retention at the old end are not a
     * break: the oldest record still present is taken as the anchor. A missing sequence number
     * in between, a record whose content no longer matches its hash, or a record that does not
     * continue its predecessor's hash is reported with the sequence number where it was found.
     */
    @Transactional
    fun verifyChain(): ChainReport {
        val unchained = (em.createQuery("SELECT COUNT(a) FROM AuditEvent a WHERE a.seq IS NULL").singleResult as Number).toLong()
        var checked = 0L
        var first: Long? = null
        var previous: AuditEvent? = null
        var after = 0L
        while (true) {
            val page = em.createQuery("SELECT a FROM AuditEvent a WHERE a.seq > :after ORDER BY a.seq", AuditEvent::class.java)
                .setParameter("after", after).setMaxResults(1000).resultList
            if (page.isEmpty()) break
            for (event in page) {
                val seq = event.seq!!
                if (first == null) first = seq
                val prev = previous
                val problem = when {
                    event.hash != hashOf(event) -> "record content does not match its hash"
                    prev != null && seq != prev.seq!! + 1 -> "records are missing before this one"
                    prev != null && event.prevHash != prev.hash -> "record does not continue the previous record"
                    else -> null
                }
                if (problem != null) return ChainReport(false, checked, unchained, first, seq, seq, problem)
                checked++
                previous = event
                after = seq
            }
            em.clear()
        }
        return ChainReport(true, checked, unchained, first, previous?.seq, null, null)
    }

    internal fun hashOf(event: AuditEvent): String {
        val content = listOf(
            event.prevHash, event.seq, event.occurredAt.toEpochMilli(), event.actorId, event.tenantId, event.action,
            event.resourceType, event.resourceId, event.outcome, event.requestId, event.metadata,
        ).joinToString("\u001f") { it?.toString() ?: "\u0000" }
        return MessageDigest.getInstance("SHA-256").digest(content.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun redact(value: Any?): Any? = redactor.value(value)
}
