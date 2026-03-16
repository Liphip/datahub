package com.dataturntable.tenant

import io.quarkus.hibernate.orm.panache.PanacheEntityBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "roles")
open class Role : PanacheEntityBase() {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    var tenant: Tenant? = null
    
    @Column(nullable = false)
    var name: String? = null
    
    @Column
    var description: String? = null
    
    @Column(name = "abilities", columnDefinition = "text", nullable = false)
    var abilities: String? = null
    
    @Column(name = "is_default")
    var isDefault: Boolean = false
    
    @Column(name = "is_system")
    var isSystem: Boolean = false
    
    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()
    
    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()
    
    companion object {
        fun findById(id: UUID): Role? = 
            PanacheEntityBase.find<Role>("id = ?1", id).firstResult()
        
        fun findByTenantId(tenantId: UUID): List<Role> = 
            PanacheEntityBase.list<Role>("tenant.id = ?1", tenantId)
        
        fun findDefaultForTenant(tenantId: UUID): Role? = 
            PanacheEntityBase.find<Role>("tenant.id = ?1 AND isDefault = ?2", tenantId, true).firstResult()
        
        fun findSystemRoles(): List<Role> = 
            PanacheEntityBase.list<Role>("isSystem = ?1", true)
    }
    
    fun hasAbility(ability: String): Boolean {
        if (abilities?.contains("*") == true) return true
        return abilities?.contains(ability) == true
    }
    
    fun getAbilitiesList(): List<String> {
        return try {
            val list = mutableListOf<String>()
            abilities?.let {
                val start = it.indexOf('[')
                val end = it.indexOf(']')
                if (start >= 0 && end > start) {
                    val arr = it.substring(start + 1, end)
                    arr.split(",").forEach { s -> 
                        list.add(s.trim().removeSurrounding("\"")) 
                    }
                }
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
}
