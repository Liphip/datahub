package com.dataturntable.api

import com.dataturntable.test.TestAuthHelpers
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.common.http.TestHTTPResource
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.smallrye.jwt.build.Jwt
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.net.*
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** A real loopback OIDC server: discovery, JWKS, authorization code, PKCE and nonce. No Docker required. */
class DesktopOidcProvider : QuarkusTestResourceLifecycleManager {
    private lateinit var server: HttpServer
    override fun start(): Map<String, String> {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        issuer = "http://127.0.0.1:${server.address.port}"
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pub = keys.public as RSAPublicKey
        fun number(n: java.math.BigInteger) = Base64.getUrlEncoder().withoutPadding().encodeToString(n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
        val codes = ConcurrentHashMap<String, Map<String, String>>()
        server.createContext("/.well-known/openid-configuration") { x ->
            val body = """{"issuer":"$issuer","authorization_endpoint":"$issuer/authorize","token_endpoint":"$issuer/token","jwks_uri":"$issuer/jwks","response_types_supported":["code"],"subject_types_supported":["public"],"id_token_signing_alg_values_supported":["RS256"],"code_challenge_methods_supported":["S256"],"token_endpoint_auth_methods_supported":["client_secret_basic"]}"""
            x.responseHeaders.add("Content-Type", "application/json"); val b=body.toByteArray();x.sendResponseHeaders(200,b.size.toLong());x.responseBody.use { it.write(b) }
        }
        server.createContext("/jwks") { x ->
            val body="""{"keys":[{"kty":"RSA","kid":"desktop-test","use":"sig","alg":"RS256","n":"${number(pub.modulus)}","e":"${number(pub.publicExponent)}"}]}""".toByteArray()
            x.responseHeaders.add("Content-Type", "application/json");x.sendResponseHeaders(200,body.size.toLong());x.responseBody.use { it.write(body) }
        }
        server.createContext("/authorize") { x ->
            val q = form(x.requestURI.rawQuery)
            if (q["code_challenge_method"] != "S256" || q["nonce"].isNullOrBlank() || q["state"].isNullOrBlank()) { x.sendResponseHeaders(400,-1);x.close() }
            else {
                val code=java.util.UUID.randomUUID().toString();codes[code]=q
                x.responseHeaders.add("Location", q["redirect_uri"]+"?code="+code+"&state="+URLEncoder.encode(q["state"],"UTF-8"))
                x.sendResponseHeaders(302,-1);x.close()
            }
        }
        server.createContext("/token") { x ->
            val q=form(x.requestBody.bufferedReader().readText());val auth=codes.remove(q["code"])
            val challenge=q["code_verifier"]?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
            if(auth==null || challenge!=auth["code_challenge"] || q["redirect_uri"]!=auth["redirect_uri"]) { x.sendResponseHeaders(400,-1);x.close() }
            else {
                val signingKey = if (mode == "signature") KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private else keys.private
                val token=Jwt.issuer(if (mode == "issuer") "$issuer/other" else issuer).subject("desktop-subject")
                    .audience(if (mode == "audience") "other-client" else "dataturntable")
                    .expiresIn(if (mode == "expired") -600 else 300)
                    .claim("nonce", if (mode == "nonce") "wrong-nonce" else auth["nonce"])
                    .jws().keyId("desktop-test").sign(signingKey)
                val b="""{"access_token":"opaque-provider-access-token","id_token":"$token","token_type":"Bearer","expires_in":300}""".toByteArray()
                x.responseHeaders.add("Content-Type","application/json");x.sendResponseHeaders(200,b.size.toLong());x.responseBody.use { it.write(b) }
            }
        }
        server.start()
        return mapOf("quarkus.oidc.external.tenant-enabled" to "true", "quarkus.oidc.external.auth-server-url" to issuer,
            "quarkus.oidc.external.client-id" to "dataturntable", "quarkus.oidc.external.credentials.secret" to "desktop-test-client-secret-32-chars",
            "dataturntable.oidc.enabled" to "true")
    }
    override fun stop() = server.stop(0)
    companion object {
        lateinit var issuer: String
        @Volatile var mode: String = "valid"
        fun form(raw: String?): Map<String,String> = raw.orEmpty().split('&').filter { it.contains('=') }.associate {
            val (k,v)=it.split('=',limit=2);URLDecoder.decode(k,"UTF-8") to URLDecoder.decode(v,"UTF-8")
        }
    }
}

@QuarkusTest
@QuarkusTestResource(value = DesktopOidcProvider::class, restrictToAnnotatedClass = true)
class OidcFlowTest {
    @TestHTTPResource lateinit var base: URI

    @Test
    fun rejectsInvalidProviderTokensBeforeCreatingALocalLogin() {
        try {
            for (mode in listOf("nonce", "audience", "issuer", "signature", "expired")) {
                DesktopOidcProvider.mode = mode
                val browser = HttpClient.newBuilder().cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL))
                    .followRedirects(HttpClient.Redirect.NEVER).build()
                var response = browser.send(HttpRequest.newBuilder(base.resolve("/api/internal/v1/auth/oidc/login")).GET().build(), HttpResponse.BodyHandlers.ofString())
                var redirects = 0
                while (response.statusCode() in listOf(302, 303) && redirects++ < 6) {
                    val next = response.uri().resolve(response.headers().firstValue("Location").orElseThrow())
                    assertNotEquals("/login", next.path, "Invalid $mode token reached local login")
                    response = browser.send(HttpRequest.newBuilder(next).GET().build(), HttpResponse.BodyHandlers.ofString())
                }
                assertTrue(response.statusCode() in listOf(400, 401), "Invalid $mode token: expected authentication failure, got ${response.statusCode()}")
            }
        } finally { DesktopOidcProvider.mode = "valid" }
    }

    @Test
    fun browserLoginRequiresAnExplicitLinkAndReturnsAnExistingLocalAccount() {
        val admin=TestAuthHelpers.createAdminToken()
        val userId=given().header("Authorization","Bearer $admin").get("/api/internal/v1/auth/me")
            .then().statusCode(200).extract().jsonPath().getString("id")
        val links="/api/internal/v1/users/$userId/identities"
        val linkId=given().header("Authorization","Bearer $admin").contentType(ContentType.JSON)
            .body(mapOf("issuer" to DesktopOidcProvider.issuer,"subject" to "desktop-subject"))
            .post(links).then().statusCode(201).extract().jsonPath().getString("id")
        try {
            val cookies=CookieManager(null,CookiePolicy.ACCEPT_ALL)
            val browser=HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build()
            var response=browser.send(HttpRequest.newBuilder(base.resolve("/api/internal/v1/auth/oidc/login")).GET().build(),HttpResponse.BodyHandlers.ofString())
            var redirects=0
            while(response.statusCode() in listOf(302,303) && redirects++ < 6) {
                val next=response.uri().resolve(response.headers().firstValue("Location").orElseThrow())
                if(next.path=="/login") {
                    val code=DesktopOidcProvider.form(next.rawQuery)["oidcCode"]!!
                    // A copied URL alone cannot log another browser in.
                    given().contentType(ContentType.JSON).body(mapOf("code" to code)).post("/api/internal/v1/auth/oidc/exchange").then().statusCode(401)
                    val cookie=cookies.cookieStore.cookies.first { it.name=="dt_oidc_handoff" }.value
                    val token=given().cookie("dt_oidc_handoff",cookie).contentType(ContentType.JSON).body(mapOf("code" to code))
                        .post("/api/internal/v1/auth/oidc/exchange").then().statusCode(200).body("user.id",org.hamcrest.CoreMatchers.equalTo(userId)).extract().jsonPath().getString("token")
                    given().header("Authorization","Bearer $token").get("/api/internal/v1/auth/me").then().statusCode(200)
                    given().cookie("dt_oidc_handoff",cookie).contentType(ContentType.JSON).body(mapOf("code" to code)).post("/api/internal/v1/auth/oidc/exchange").then().statusCode(401)
                    return
                }
                response=browser.send(HttpRequest.newBuilder(next).GET().build(),HttpResponse.BodyHandlers.ofString())
            }
            fail<Unit>("OIDC login did not redirect to the UI: ${response.statusCode()} ${response.body().take(1000)}")
        } finally {
            given().header("Authorization","Bearer $admin").delete("$links/$linkId").then().statusCode(204)
        }
    }
}
