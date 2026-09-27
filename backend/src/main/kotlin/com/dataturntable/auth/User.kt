package com.dataturntable.auth

import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import com.dataturntable.PanacheBase
import com.dataturntable.authorization.Ability
import com.dataturntable.security.AbilityEvaluator
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
/**
 * Defines the class `User`, the contract implemented by this source unit.
 */
open class User : PanacheBase() {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
    
    @Column(nullable = false, unique = true)
    var email: String? = null
    
    @Column(name = "password_hash", nullable = false)
    var passwordHash: String? = null
    
    @Column(name = "first_name")
    var firstName: String? = null
    
    @Column(name = "last_name")
    var lastName: String? = null
    
    @Column(name = "is_platform_admin")
    var isSuperadmin: Boolean = false
    
    @Column(name = "is_active")
    var isActive: Boolean = true

    @Column(name = "authorization_version", nullable = false)
    var authorizationVersion: Long = 0
    
    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null
    
    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()
    
    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()
    
    @Column(name = "abilities", columnDefinition = "text")
    var abilities: String? = null
    
    @OneToMany(mappedBy = "authUser", cascade = [CascadeType.ALL], orphanRemoval = true)
    var memberships: MutableList<Membership> = mutableListOf()

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "user_abilities",
        joinColumns = [JoinColumn(name = "user_id")],
        inverseJoinColumns = [JoinColumn(name = "ability_value")]
    )
    var grantedAbilities: MutableSet<Ability> = mutableSetOf()
    
    companion object : PanacheCompanionBase<User, UUID> {
        override fun findById(id: UUID): User? =
            getEntityManager()
                .createQuery("SELECT u FROM User u WHERE u.id = :id", User::class.java)
                .setParameter("id", id)
                .resultList
                .firstOrNull()
        
        /**
         * Retrieves or enumerates the requested state.
         */
        fun findByTenantId(tenantId: UUID): List<User> =
            getEntityManager().createQuery(
                "SELECT DISTINCT u FROM User u JOIN u.memberships m WHERE m.tenant.id = :tenantId",
                User::class.java
            )
                .setParameter("tenantId", tenantId)
                .resultList
        
        /**
         * Retrieves or enumerates the requested state.
         */
        fun findByEmail(email: String): User? =
            getEntityManager()
                .createQuery("SELECT u FROM User u WHERE u.email = :email", User::class.java)
                .setParameter("email", email)
                .resultList
                .firstOrNull()
        
        /**
         * Retrieves or enumerates the requested state.
         */
        fun findByTenantAndEmail(tenantId: UUID, email: String): User? =
            getEntityManager().createQuery(
                "SELECT u FROM User u JOIN u.memberships m WHERE u.email = :email AND m.tenant.id = :tenantId",
                User::class.java
            )
                .setParameter("email", email)
                .setParameter("tenantId", tenantId)
                .resultList
                .firstOrNull()
        
        @JvmStatic
        /**
         * Implements `countUsers` for this source unit.
         */
        fun countUsers(): Long =
            getEntityManager().createQuery("SELECT COUNT(u) FROM User u", java.lang.Long::class.java).singleResult.toLong()
    }
    
    /**
     * Retrieves or enumerates the requested state.
     */
    fun loadMemberships(): List<Membership> {
        return memberships.toList()
    }
    
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getPrimaryTenant(): Tenant? {
        return loadMemberships().firstOrNull()?.tenant
    }
    
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getMembershipForTenant(tenantId: UUID): Membership? {
        return loadMemberships().firstOrNull { it.tenant?.id == tenantId }
    }
    
    /**
     * Evaluates the named capability or state predicate.
     */
    fun isSuperAdmin(): Boolean {
        return isSuperadmin
    }
    
    /**
     * Evaluates the named capability or state predicate.
     */
    fun hasAbility(ability: String): Boolean {
        if (isSuperAdmin()) return true
        if (grantedAbilities.any { it.value == "*" || it.value == ability }) return true
        if (AbilityEvaluator.hasAbility(abilities, ability)) return true

        return loadMemberships().any { membership ->
            membership.grantedAbilities.any { it.value == "*" || it.value == ability } ||
                AbilityEvaluator.hasAbility(membership.abilities, ability) ||
                membership.role?.hasAbility(ability) == true
        }
    }
    
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getAllAbilities(): Set<String> {
        val result = mutableSetOf<String>()
        val directAbilities = AbilityEvaluator.parse(abilities) + grantedAbilities.map { it.value }
        if (isSuperAdmin() || "*" in directAbilities) {
            result.add("*")
            return result
        }

        result.addAll(directAbilities)
        
        loadMemberships().forEach { membership ->
            membership.role?.getAbilitiesList()?.forEach { result.add(it) }
            membership.grantedAbilities.forEach { result.add(it.value) }
            result.addAll(AbilityEvaluator.parse(membership.abilities))
        }

        return result
    }
    
    /**
     * Evaluates the named capability or state predicate.
     */
    fun canAccessTenant(tenantId: UUID): Boolean {
        if (isSuperAdmin()) return true
        // Access is granted if the user has a membership for the tenant or any ancestor
        val memberships = loadMemberships()
        return memberships.any { membership ->
            membership.tenant?.let { tenant ->
                tenant.id == tenantId || tenant.hasDescendant(tenantId)
            } == true
        }
    }
}
