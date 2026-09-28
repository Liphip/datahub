package com.dataturntable.tenant

import com.dataturntable.PanacheBase
import com.dataturntable.authorization.Ability
import com.dataturntable.security.AbilityEvaluator
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "roles", uniqueConstraints = [UniqueConstraint(columnNames = ["tenant_id", "name"])])
/**
 * Defines the class `Role`, the contract implemented by this source unit.
 */
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

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "role_abilities",
        joinColumns = [JoinColumn(name = "role_id")],
        inverseJoinColumns = [JoinColumn(name = "ability_value")]
    )
    var grantedAbilities: MutableSet<Ability> = mutableSetOf()
    
    companion object : PanacheCompanionBase<Role, UUID> {
        override fun findById(id: UUID): Role? =
            getEntityManager()
                .createQuery("SELECT r FROM Role r WHERE r.id = :id", Role::class.java)
                .setParameter("id", id)
                .resultList
                .firstOrNull()
        
        /**
         * Retrieves or enumerates the requested state.
         */
        fun findByTenantId(tenantId: UUID): List<Role> =
            getEntityManager()
                .createQuery("SELECT r FROM Role r WHERE r.tenant.id = :tenantId", Role::class.java)
                .setParameter("tenantId", tenantId)
                .resultList
        
        /**
         * Retrieves or enumerates the requested state.
         */
        fun findDefaultForTenant(tenantId: UUID): Role? =
            getEntityManager()
                .createQuery(
                    "SELECT r FROM Role r WHERE r.tenant.id = :tenantId AND r.isDefault = true",
                    Role::class.java
                )
                .setParameter("tenantId", tenantId)
                .resultList
                .firstOrNull()
        
        /**
         * Retrieves or enumerates the requested state.
         */
        fun findSystemRoles(): List<Role> =
            getEntityManager()
                .createQuery("SELECT r FROM Role r WHERE r.isSystem = true", Role::class.java)
                .resultList
    }
    
    /**
     * Evaluates the named capability or state predicate.
     */
    fun hasAbility(ability: String): Boolean {
        return grantedAbilities.any { it.value == "*" || it.value == ability } ||
            AbilityEvaluator.hasAbility(abilities, ability)
    }
    
    /**
     * Retrieves or enumerates the requested state.
     */
    fun getAbilitiesList(): List<String> {
        return (AbilityEvaluator.parse(abilities) + grantedAbilities.map { it.value }).toList()
    }
}
