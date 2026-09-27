package com.dataturntable.audit

import com.fasterxml.jackson.databind.ObjectMapper
import com.dataturntable.api.RequestIdFilter
import com.dataturntable.security.SensitiveDataRedactor
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import java.util.UUID

@ApplicationScoped
class AuditService {
    @PersistenceContext lateinit var em: EntityManager
    @Inject lateinit var redactor: SensitiveDataRedactor
    private val mapper = com.dataturntable.json.JsonObjectMappers.create()

    @Transactional
    fun record(actorId: UUID?, tenantId: UUID?, action: String, resourceType: String, resourceId: String?, metadata: Map<String, Any?> = emptyMap(), requestId: String? = null) {
        require(action.matches(Regex("[a-z0-9_.:-]{1,128}"))) { "Invalid audit action" }
        em.persist(AuditEvent().apply {
            this.actorId = actorId
            this.tenantId = tenantId
            this.action = action
            this.resourceType = resourceType
            this.resourceId = resourceId
            this.requestId = requestId ?: RequestIdFilter.currentRequestId()
            this.metadata = mapper.writeValueAsString(redact(metadata))
        })
    }

    private fun redact(value: Any?): Any? = redactor.value(value)
}
