package com.dataturntable.events

import com.dataturntable.security.SensitiveDataRedactor
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional

@ApplicationScoped
class ConsumerDeadLetterService {
    @PersistenceContext lateinit var em: EntityManager
    @Inject lateinit var redactor: SensitiveDataRedactor
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun record(channel: String, payload: String, reason: String) {
        em.persist(ConsumerDeadLetter().apply {
            this.channel = channel
            this.payload = redactor.text(payload) ?: ""
            this.error = redactor.text(reason) ?: ""
        })
    }

    fun list(limit: Int): List<ConsumerDeadLetter> = em.createQuery(
        "SELECT d FROM ConsumerDeadLetter d ORDER BY d.createdAt DESC", ConsumerDeadLetter::class.java
    ).setMaxResults(limit.coerceIn(1, 500)).resultList
}
