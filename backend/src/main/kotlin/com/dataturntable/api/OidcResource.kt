package com.dataturntable.api

import com.dataturntable.auth.OidcLoginService
import io.quarkus.oidc.IdToken
import io.quarkus.security.Authenticated
import jakarta.inject.Inject
import jakarta.ws.rs.*
import jakarta.ws.rs.core.*
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import java.net.URI

@Path("/api/internal/v1/auth/oidc")
class OidcResource {
    @Inject lateinit var logins: OidcLoginService
    @Inject @IdToken lateinit var idToken: JsonWebToken
    @ConfigProperty(name = "dataturntable.oidc.enabled", defaultValue = "false") var enabled: Boolean = false

    @GET @Path("/status") @Produces(MediaType.APPLICATION_JSON)
    fun status(): Response = Response.ok(mapOf("enabled" to enabled)).header("Cache-Control", "no-store").build()

    @GET @Path("/login") @Authenticated
    fun login(@Context uri: UriInfo): Response {
        if (!enabled) return Response.status(404).build()
        return try {
            val issuer = idToken.issuer ?: return Response.status(401).build()
            val subject = idToken.subject ?: return Response.status(401).build()
            val handoff = logins.begin(issuer, subject)
            Response.seeOther(URI.create("/login?oidcCode=${handoff.code}"))
                .header("Set-Cookie", browserCookie(handoff.browser, uri, 60))
                .header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer").build()
        } catch (_: SecurityException) {
            Response.status(403).entity("This identity is not linked to an active account. Contact an administrator.")
                .header("Cache-Control", "no-store").build()
        }
    }

    @POST @Path("/exchange") @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
    fun exchange(request: Map<String, String>, @CookieParam("dt_oidc_handoff") browser: String?, @Context uri: UriInfo): Response {
        if (!enabled) return Response.status(404).build()
        return try {
            Response.ok(logins.exchange(request["code"] ?: "", browser ?: ""))
                .header("Set-Cookie", browserCookie("", uri, 0)).header("Cache-Control", "no-store").build()
        } catch (_: SecurityException) {
            Response.status(401).entity(mapOf("error" to "Invalid or expired login handoff"))
                .header("Cache-Control", "no-store").build()
        }
    }

    private fun browserCookie(value: String, uri: UriInfo, age: Int): String =
        "dt_oidc_handoff=$value; Path=/api/internal/v1/auth/oidc; HttpOnly; SameSite=Strict; Max-Age=$age" +
            if (uri.baseUri.scheme == "https") "; Secure" else ""
}
