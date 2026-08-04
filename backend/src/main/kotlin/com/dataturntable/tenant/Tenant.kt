package com.dataturntable.tenant

import com.dataturntable.PanacheBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "tenants")
/**
 * Defines the class `Tenant`, the contract implemented by this source unit.
 */
open class Tenant : PanacheBase() {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
    
    @Column(nullable = false)
    var name: String? = null
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    var parent: Tenant? = null
    
    @OneToMany(mappedBy = "parent", fetch = FetchType.LAZY)
    var children: MutableList<Tenant> = mutableListOf()
    
    @Column(columnDefinition = "text")
    var settings: String? = null
    
    @Column(columnDefinition = "text")
    var quota: String? = null
    
    @Column(name = "created_at")
    var createdAt: Instant? = null
    
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
    
    companion object : PanacheCompanionBase<Tenant, UUID> {
        override fun findById(id: UUID): Tenant? =
            getEntityManager()
                .createQuery("SELECT t FROM Tenant t WHERE t.id = :id", Tenant::class.java)
                .setParameter("id", id)
                .resultList
                .firstOrNull()
        
        /**
         * Retrieves or enumerates the requested state.
         */
        fun findRootTenants(): List<Tenant> = 
            getEntityManager().createQuery("SELECT t FROM Tenant t WHERE t.parent IS NULL", Tenant::class.java).resultList
        
        /**
         * Retrieves or enumerates the requested state.
         */
        fun findByParentId(parentId: UUID): List<Tenant> = 
            getEntityManager().createQuery("SELECT t FROM Tenant t WHERE t.parent.id = :parentId", Tenant::class.java)
                .setParameter("parentId", parentId)
                .resultList
    }
    
    /**
     * Evaluates the named capability or state predicate.
     */
    fun isDescendantOf(ancestorId: UUID): Boolean {
        var current = this.parent
        while (current != null) {
            if (current.id == ancestorId) return true
            current = current.parent
        }
        return false
    }
    
    /**
     * Evaluates the named capability or state predicate.
     */
    fun hasDescendant(tenantId: UUID): Boolean {
        if (this.id == tenantId) return true
        for (child in children) {
            if (child.id == tenantId || child.hasDescendant(tenantId)) return true
        }
        return false
    }
    
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getAncestorIds(): List<UUID> {
        val ancestors = mutableListOf<UUID>()
        var current = this.parent
        while (current != null) {
            current.id?.let { ancestors.add(it) }
            current = current.parent
        }
        return ancestors
    }
}
