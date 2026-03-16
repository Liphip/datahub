package com.dataturntable.auth

import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import io.quarkus.hibernate.orm.panache.PanacheEntityBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
open class User : PanacheEntityBase() {
    
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
    
    companion object {
        fun findById(id: UUID): User? = 
            PanacheEntityBase.find<User>("id = ?1", id).firstResult()
        
        fun findByTenantId(tenantId: UUID): List<User> = 
            PanacheEntityBase.list<User>(
                "id in (select m.authUser.id from Membership m where m.tenant.id = ?1)", 
                tenantId
            )
        
        fun findByEmail(email: String): User? = 
            PanacheEntityBase.find<User>("email = ?1", email).firstResult()
        
        fun findByTenantAndEmail(tenantId: UUID, email: String): User? = 
            PanacheEntityBase.find<User>("email = ?1 and id in (select m.authUser.id from Membership m where m.tenant.id = ?2)", email, tenantId).firstResult()
        
        @JvmStatic
        fun countUsers(): Long = count()
    }
    
    fun loadMemberships(): List<Membership> {
        val userId = this.id ?: return emptyList()
        return Membership.findByUserId(userId)
    }
    
    fun getPrimaryTenant(): Tenant? {
        return loadMemberships().firstOrNull()?.tenant
    }
    
    fun getMembershipForTenant(tenantId: UUID): Membership? {
        return loadMemberships().firstOrNull { it.tenant?.id == tenantId }
    }
    
    fun isSuperAdmin(): Boolean {
        // Superadmin is defined as having TenantAdmin role in the root tenant
        if (isSuperadmin) return true // backward compatibility
        return loadMemberships().any { membership ->
            membership.role?.name == "Tenant Admin" && membership.tenant?.parent == null
        }
    }
    
    fun hasAbility(ability: String): Boolean {
        if (isSuperAdmin()) return true
        if (abilities?.contains("*") == true) return true
        if (abilities?.contains(ability) == true) return true
        
        val roleAbilities = loadMemberships().map { it.role }.filterNotNull().flatMap { it.getAbilitiesList() }
        if (roleAbilities.contains("*") || roleAbilities.contains(ability)) return true
        
        return abilities?.contains(ability) == true
    }
    
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