package com.dataturntable.tenant

import com.dataturntable.auth.User
import io.quarkus.hibernate.orm.panache.PanacheEntityBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "memberships")
class Membership : PanacheEntityBase() {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auth_user_id")
    var authUser: User? = null
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    var tenant: Tenant? = null
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id")
    var role: Role? = null
    
    @Column(name = "abilities", columnDefinition = "text")
    var abilities: String? = null
    
    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()
    
    companion object {
        fun findById(id: UUID): Membership? = 
            PanacheEntityBase.find<Membership>("id = ?1", id).firstResult()
        
        fun findByUserId(userId: UUID): List<Membership> = 
            PanacheEntityBase.list<Membership>("authUser.id = ?1", userId)
        
        fun findByTenantId(tenantId: UUID): List<Membership> = 
            PanacheEntityBase.list<Membership>("tenant.id = ?1", tenantId)
        
        fun findByUserAndTenant(userId: UUID, tenantId: UUID): Membership? = 
            PanacheEntityBase.find<Membership>("authUser.id = ?1 AND tenant.id = ?2", userId, tenantId).firstResult()
    }
}