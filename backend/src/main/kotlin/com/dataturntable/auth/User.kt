package com.dataturntable.auth

import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import io.quarkus.hibernate.orm.panache.PanacheEntityBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class User : PanacheEntityBase() {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    var tenant: Tenant? = null
    
    @Column(nullable = false)
    var email: String? = null
    
    @Column(name = "password_hash", nullable = false)
    var passwordHash: String? = null
    
    @Column(name = "first_name")
    var firstName: String? = null
    
    @Column(name = "last_name")
    var lastName: String? = null
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id")
    var role: Role? = null
    
    @Column(name = "abilities", columnDefinition = "text")
    var abilities: String? = null
    
    @Column(name = "is_superadmin")
    var isSuperadmin: Boolean = false
    
    @Column(name = "is_active")
    var isActive: Boolean = true
    
    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null
    
    @Column(name = "created_at")
    var createdAt: Instant? = null
    
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
    
    companion object {
        fun findById(id: UUID): User? = 
            io.quarkus.hibernate.orm.panache.PanacheEntityBase.find<User>("id = ?1", id).firstResult()
        
        fun findByTenantId(tenantId: UUID): List<User> = 
            io.quarkus.hibernate.orm.panache.PanacheEntityBase.list<User>("tenant.id = ?1", tenantId)
        
        fun findByEmail(email: String): User? = 
            io.quarkus.hibernate.orm.panache.PanacheEntityBase.find<User>("email = ?1", email).firstResult()
        
        fun findByTenantAndEmail(tenantId: UUID, email: String): User? = 
            io.quarkus.hibernate.orm.panache.PanacheEntityBase.find<User>("tenant.id = ?1 AND email = ?2", tenantId, email).firstResult()
        
        fun countUsers(): Long = io.quarkus.hibernate.orm.panache.PanacheEntityBase.count()
    }
    
    fun hasAbility(ability: String): Boolean {
        if (isSuperadmin) return true
        if (abilities?.contains("*") == true) return true
        if (abilities?.contains(ability) == true) return true
        return role?.hasAbility(ability) ?: false
    }
    
    fun getAllAbilities(): Set<String> {
        val result = mutableSetOf<String>()
        if (isSuperadmin || abilities?.contains("*") == true) {
            result.add("*")
            return result
        }
        role?.getAbilitiesList()?.forEach { result.add(it) }
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
        if (isSuperadmin) return true
        if (tenant?.id == tenantId) return true
        tenant?.getAncestorIds()?.contains(tenantId)?.let { return it }
        return false
    }
}
