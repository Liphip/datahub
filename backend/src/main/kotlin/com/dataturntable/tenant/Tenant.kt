package com.dataturntable.tenant

import io.quarkus.hibernate.orm.panache.PanacheEntityBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "tenants")
class Tenant : PanacheEntityBase() {
    
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
    
    @Column(columnDefinition = "jsonb")
    var settings: String = "{}"
    
    @Column(columnDefinition = "jsonb")
    var quota: String = """{"max_users": 100, "max_pipelines": 50, "max_storage_mb": 10240}"""
    
    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()
    
    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()
    
    fun isDescendantOf(ancestorId: UUID): Boolean {
        var current = this.parent
        while (current != null) {
            if (current.id == ancestorId) return true
            current = current.parent
        }
        return false
    }
    
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
