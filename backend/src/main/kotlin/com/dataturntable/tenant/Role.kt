package com.dataturntable.tenant

import io.quarkus.hibernate.orm.panache.PanacheEntityBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "roles")
class Role : PanacheEntityBase() {
    
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
    
    @Column(name = "abilities", columnDefinition = "jsonb", nullable = false)
    var abilities: String = "[]"
    
    @Column(name = "is_default")
    var isDefault: Boolean = false
    
    @Column(name = "is_system")
    var isSystem: Boolean = false
    
    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()
    
    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()
    
    companion object {
        fun findByTenantId(tenantId: UUID): List<Role> = 
            list("tenant.id = ?1", tenantId)
        
        fun findDefaultForTenant(tenantId: UUID): Role? = 
            find("tenant.id = ?1 AND isDefault = true", tenantId).firstResult()
        
        fun findSystemRoles(): List<Role> = 
            list("isSystem = true")
    }
    
    fun hasAbility(ability: String): Boolean {
        if (abilities.contains("*")) return true
        return abilities.contains(ability)
    }
    
    fun getAbilitiesList(): List<String> {
        return try {
            val list = mutableListOf<String>()
            abilities.let {
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
