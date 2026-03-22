package com.dataturntable.tenant

import com.dataturntable.auth.User
import com.dataturntable.PanacheBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "memberships")
class Membership : PanacheBase() {
    
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
    
    companion object : PanacheCompanionBase<Membership, UUID> {
        override fun findById(id: UUID): Membership? =
            getEntityManager()
                .createQuery("SELECT m FROM Membership m WHERE m.id = :id", Membership::class.java)
                .setParameter("id", id)
                .resultList
                .firstOrNull()
        
        fun findByUserId(userId: UUID): List<Membership> =
            getEntityManager()
                .createQuery("SELECT m FROM Membership m WHERE m.authUser.id = :userId", Membership::class.java)
                .setParameter("userId", userId)
                .resultList
        
        fun findByTenantId(tenantId: UUID): List<Membership> =
            getEntityManager()
                .createQuery("SELECT m FROM Membership m WHERE m.tenant.id = :tenantId", Membership::class.java)
                .setParameter("tenantId", tenantId)
                .resultList
        
        fun findByUserAndTenant(userId: UUID, tenantId: UUID): Membership? =
            getEntityManager()
                .createQuery(
                    "SELECT m FROM Membership m WHERE m.authUser.id = :userId AND m.tenant.id = :tenantId",
                    Membership::class.java
                )
                .setParameter("userId", userId)
                .setParameter("tenantId", tenantId)
                .resultList
                .firstOrNull()
    }
}
