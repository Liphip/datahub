package com.dataturntable.security

import com.dataturntable.auth.User
import com.dataturntable.dto.Ability
import com.dataturntable.dto.UserDto
import io.quarkus.security.identity.IdentityProviderManager
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.request.AuthenticationRequest
import io.quarkus.vertx.http.runtime.security.ChallengeData
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism
import io.smallrye.mutiny.Uni
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import java.util.Collections

@ApplicationScoped
class JwtAuthenticationMechanism : HttpAuthenticationMechanism {
    
    override fun authenticate(
        context: RoutingContext,
        identityProviderManager: IdentityProviderManager
    ): Uni<SecurityIdentity>? {
        return null
    }
    
    override fun getChallengeData(
        context: RoutingContext,
        status: Int,
        currentSecurityIdentity: SecurityIdentity?
    ): Uni<ChallengeData>? {
        return null
    }
    
    override fun getCredentialTypes(): Set<Class<out AuthenticationRequest>> {
        return Collections.emptySet()
    }
}

object SecurityUtils {
    
    fun toUserDto(user: User): UserDto {
        return UserDto(
            id = user.id.toString(),
            email = user.email ?: "",
            firstName = user.firstName,
            lastName = user.lastName,
            tenantId = user.tenant?.id.toString(),
            roleName = user.role?.name,
            abilities = user.getAllAbilities().toList(),
            isSuperadmin = user.isSuperadmin
        )
    }
    
    fun hasAbility(user: User?, ability: String): Boolean {
        if (user == null) return false
        return user.hasAbility(ability)
    }
    
    fun canAccessTenant(user: User?, tenantId: String): Boolean {
        if (user == null) return false
        if (user.isSuperadmin) return true
        return user.canAccessTenant(java.util.UUID.fromString(tenantId))
    }
}
