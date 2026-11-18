package com.dataturntable.auth

import com.dataturntable.audit.AuditService
import com.dataturntable.dto.AuthResponse
import com.dataturntable.service.AuthService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

@ApplicationScoped
class OidcLoginService {
    @Inject lateinit var em: EntityManager
    @Inject lateinit var auth: AuthService
    @Inject lateinit var audit: AuditService
    private val random = SecureRandom()
    data class Handoff(val code: String, val browser: String)

    /** Called only with the issuer and subject from Quarkus's verified ID token. */
    @Transactional
    fun begin(issuer: String, subject: String): Handoff {
        val link = em.createQuery("SELECT i FROM ExternalIdentity i JOIN FETCH i.user WHERE i.issuer = :issuer AND i.subject = :subject", ExternalIdentity::class.java)
            .setParameter("issuer", issuer).setParameter("subject", subject).resultList.singleOrNull()
            ?: throw SecurityException("External identity is not linked")
        if (link.user?.isActive != true) throw SecurityException("Account is disabled")
        em.createQuery("DELETE FROM OidcLoginTicket t WHERE t.expiresAt <= :now").setParameter("now", Instant.now()).executeUpdate()
        val handoff = Handoff(secret(), secret())
        em.persist(OidcLoginTicket().apply {
            codeHash = hash(handoff.code); browserHash = hash(handoff.browser)
            externalIdentity = link; expiresAt = Instant.now().plusSeconds(60)
        })
        return handoff
    }

    @Transactional
    fun exchange(code: String, browser: String): AuthResponse {
        if (code.length != 43 || browser.length != 43) throw SecurityException("Invalid login handoff")
        val ticket = em.find(OidcLoginTicket::class.java, hash(code), LockModeType.PESSIMISTIC_WRITE)
            ?: throw SecurityException("Invalid login handoff")
        if (ticket.expiresAt <= Instant.now() || !MessageDigest.isEqual(ticket.browserHash.toByteArray(), hash(browser).toByteArray())) {
            throw SecurityException("Invalid login handoff")
        }
        val user = ticket.externalIdentity?.user ?: throw SecurityException("Identity was removed")
        val response = auth.loginExternal(user.id!!)
        em.remove(ticket)
        audit.record(actorId = user.id, tenantId = null, action = "auth.oidc.login.succeeded", resourceType = "auth_user", resourceId = user.id.toString(), metadata = mapOf("outcome" to "succeeded"))
        return response
    }

    private fun secret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
