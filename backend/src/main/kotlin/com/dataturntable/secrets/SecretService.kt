package com.dataturntable.secrets

import com.dataturntable.security.EncryptionUtils
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.transaction.Transactional
import java.util.UUID

@ApplicationScoped
class SecretService {
    @PersistenceContext lateinit var em: EntityManager

    data class SecretReference(val id: UUID, val version: Int, val name: String)

    @Transactional
    fun create(tenantId: UUID, name: String, value: String): SecretReference {
        require(name.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,127}"))) { "Invalid secret name" }
        require(value.isNotEmpty()) { "Secret value must not be empty" }
        val existing = em.createQuery(
            "SELECT s FROM Secret s WHERE s.tenantId = :tenantId AND s.name = :name AND s.deletedAt IS NULL", Secret::class.java
        ).setParameter("tenantId", tenantId).setParameter("name", name).resultList.firstOrNull()
        require(existing == null) { "Secret name already exists" }
        val secret = Secret().apply { this.tenantId = tenantId; this.name = name }
        em.persist(secret)
        em.flush()
        return appendVersion(secret, value)
    }

    @Transactional
    fun rotate(tenantId: UUID, secretId: UUID, value: String): SecretReference {
        require(value.isNotEmpty()) { "Secret value must not be empty" }
        val secret = em.find(Secret::class.java, secretId)
            ?: throw IllegalArgumentException("Secret not found")
        require(secret.tenantId == tenantId && secret.deletedAt == null) { "Secret is not available for tenant" }
        return appendVersion(secret, value)
    }

    @Transactional
    fun resolve(tenantId: UUID, secretId: UUID, version: Int? = null): String {
        val secret = em.find(Secret::class.java, secretId)
            ?: throw IllegalArgumentException("Secret not found")
        require(secret.tenantId == tenantId && secret.deletedAt == null) { "Secret is not available for tenant" }
        val query = em.createQuery(
            "SELECT v FROM SecretVersion v WHERE v.secretId = :secretId" + if (version == null) " ORDER BY v.version DESC" else " AND v.version = :version",
            SecretVersion::class.java
        ).setParameter("secretId", secretId)
        if (version != null) query.setParameter("version", version)
        val selected = query.setMaxResults(1).resultList.firstOrNull()
            ?: throw IllegalArgumentException("Secret version not found")
        return EncryptionUtils.decrypt(selected.valueEncrypted)
    }

    @Transactional
    fun references(tenantId: UUID): List<SecretReference> = em.createQuery(
        "SELECT s FROM Secret s WHERE s.tenantId = :tenantId AND s.deletedAt IS NULL ORDER BY s.name", Secret::class.java
    ).setParameter("tenantId", tenantId).resultList.map { secret ->
        val latest = em.createQuery("SELECT v FROM SecretVersion v WHERE v.secretId = :secretId ORDER BY v.version DESC", SecretVersion::class.java)
            .setParameter("secretId", secret.id).setMaxResults(1).resultList.firstOrNull()
            ?: throw IllegalStateException("Secret has no version")
        SecretReference(secret.id!!, latest.version, secret.name)
    }

    private fun appendVersion(secret: Secret, value: String): SecretReference {
        val id = secret.id ?: throw IllegalStateException("Secret identity missing")
        val latestVersion = em.createQuery("SELECT MAX(v.version) FROM SecretVersion v WHERE v.secretId = :secretId", Number::class.java)
            .setParameter("secretId", id).singleResult?.toInt() ?: 0
        val version = latestVersion + 1
        em.persist(SecretVersion().apply {
            secretId = id
            this.version = version
            valueEncrypted = EncryptionUtils.encrypt(value)
        })
        return SecretReference(id, version, secret.name)
    }
}
