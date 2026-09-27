package com.dataturntable.pipeline

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import java.util.UUID

@ApplicationScoped
class PipelineVersionService {
    @PersistenceContext lateinit var em: EntityManager
    fun latestFor(pipelineId: UUID?): PipelineVersion? {
        if (pipelineId == null) return null
        return em.createQuery("SELECT v FROM PipelineVersion v WHERE v.pipeline.id = :pipelineId ORDER BY v.version DESC", PipelineVersion::class.java)
            .setParameter("pipelineId", pipelineId)
            .setMaxResults(1)
            .resultList.firstOrNull()
    }
}
