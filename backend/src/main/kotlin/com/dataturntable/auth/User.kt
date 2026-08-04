package com.dataturntable.auth

import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import com.dataturntable.PanacheBase
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
    
    @Column(name = "is_superadmin")
    var isSuperadmin: Boolean = false
    
    @Column(name = "is_active")
    var isActive: Boolean = true
    
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
        // Superadmin is defined as having TenantAdmin role in the root tenant
        if (isSuperadmin) return true // backward compatibility
        return loadMemberships().any { membership ->
            membership.role?.name == "Tenant Admin" && membership.tenant?.parent == null
        }
    }
    
    /**
     * Evaluates the named capability or state predicate.
     */
    fun hasAbility(ability: String): Boolean {
        if (isSuperAdmin()) return true
        if (abilities?.contains("*") == true) return true
        // BUG: Permission checks use substring matching
        // Details: A stored value such as `report:read` also matches a request for `read`. Parse the serialized ability list once and compare exact tokens, as is already done for role abilities below.
        if (abilities?.contains(ability) == true) return true
        
        val roleAbilities = loadMemberships().map { it.role }.filterNotNull().flatMap { it.getAbilitiesList() }
        if (roleAbilities.contains("*") || roleAbilities.contains(ability)) return true
        
        return abilities?.contains(ability) == true
    }
    
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getAllAbilities(): Set<String> {
        val result = mutableSetOf<String>()
        if (isSuperAdmin() || abilities?.contains("*") == true) {
            result.add("*")
            return result
        }
        
        loadMemberships().forEach { membership ->
            membership.role?.getAbilitiesList()?.forEach { result.add(it) }
        }
        
        try {
            abilities?.let {
                val start = it.indexOf('[')
                val end = it.indexOf(']')
                if (start >= 0 && end > start) {
                    val arr = it.substring(start + 1, end)
                    arr.split(",").forEach { s -> 
                        result.add(s.trim().removeSurrounding("\"")) 
                    }
                }
            }
        } catch (e: Exception) { }
        
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
