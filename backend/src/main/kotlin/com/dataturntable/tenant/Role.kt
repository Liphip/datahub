package com.dataturntable.tenant

import com.dataturntable.PanacheBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "roles")
open class Role : PanacheBase() {
    
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
    
    companion object : PanacheCompanionBase<Role, UUID> {
        override fun findById(id: UUID): Role? =
            getEntityManager()
                .createQuery("SELECT r FROM Role r WHERE r.id = :id", Role::class.java)
                .setParameter("id", id)
                .resultList
                .firstOrNull()
        
        fun findByTenantId(tenantId: UUID): List<Role> =
            getEntityManager()
                .createQuery("SELECT r FROM Role r WHERE r.tenant.id = :tenantId", Role::class.java)
                .setParameter("tenantId", tenantId)
                .resultList
        
        fun findDefaultForTenant(tenantId: UUID): Role? =
            getEntityManager()
                .createQuery(
                    "SELECT r FROM Role r WHERE r.tenant.id = :tenantId AND r.isDefault = true",
                    Role::class.java
                )
                .setParameter("tenantId", tenantId)
                .resultList
                .firstOrNull()
        
        fun findSystemRoles(): List<Role> =
            getEntityManager()
                .createQuery("SELECT r FROM Role r WHERE r.isSystem = true", Role::class.java)
                .resultList
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
