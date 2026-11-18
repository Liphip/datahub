package com.dataturntable.security

import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.jwt.auth.cdi.NullJsonWebToken
import jakarta.annotation.Priority
import jakarta.enterprise.context.RequestScoped
import jakarta.enterprise.inject.Alternative
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import org.eclipse.microprofile.jwt.JsonWebToken

/** Preserve local JWT injection when SmallRye JWT and OIDC code-flow mechanisms coexist. */
@Alternative
@Priority(3)
@RequestScoped
class AuthenticatedTokenProducer {
    @Inject lateinit var identity: SecurityIdentity
    @Produces @RequestScoped
    fun token(): JsonWebToken = identity.principal as? JsonWebToken ?: NullJsonWebToken()
}
