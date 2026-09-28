package com.dataturntable.service

import com.dataturntable.security.AbilityEvaluator
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `TenantService` used to also carry tenant CRUD methods (create/get/update/delete, a `toDto`),
 * each disabled ("requires investigation") rather than fixed. None had a caller outside this test:
 * `TenantResource` does tenant CRUD itself directly, and only `createDefaultRoles` is live (called
 * from `TenantResource` and `AuthResource`'s first-run setup). The dead methods and the
 * request/response DTOs only they used (`dto/TenantDto.kt`) were removed instead of re-enabling
 * tests for code nothing calls. What remains here tests what is actually live.
 */
@QuarkusTest
class TenantServiceTest {

    @Inject
    lateinit var tenantService: TenantService

    @Test
    @Transactional
    fun `createDefaultRoles seeds Viewer, Editor and Tenant Admin with distinct ability sets`() {
        val tenant = Tenant().apply { name = "Default Roles Test ${java.util.UUID.randomUUID()}" }
        tenant.persist()

        tenantService.createDefaultRoles(tenant)

        val roles = Role.findByTenantId(tenant.id!!).associateBy { it.name }
        assertEquals(setOf("Viewer", "Editor", "Tenant Admin"), roles.keys)

        val viewer = AbilityEvaluator.parse(roles.getValue("Viewer").abilities)
        val editor = AbilityEvaluator.parse(roles.getValue("Editor").abilities)
        val admin = AbilityEvaluator.parse(roles.getValue("Tenant Admin").abilities)

        assertTrue("pipeline:read" in viewer && "pipeline:write" !in viewer, "Viewer must be read-only")
        assertTrue("pipeline:write" in editor && "connector:delete" !in editor, "Editor writes but doesn't administer the tenant")
        assertTrue("tenant:manage" in admin && "connector:delete" in admin, "Tenant Admin holds the full set, including tenant:manage")

        assertTrue(roles.getValue("Viewer").isDefault, "Viewer is the role assigned when none is specified")
    }

    @Test
    @Transactional
    fun `tenant hierarchy resolves descendants and ancestors across levels`() {
        val root = Tenant().apply { name = "Root ${java.util.UUID.randomUUID()}" }
        root.persist()
        val child = Tenant().apply { name = "Child"; parent = root }
        child.persist()
        val grandchild = Tenant().apply { name = "Grandchild"; parent = child }
        grandchild.persist()

        assertTrue(grandchild.isDescendantOf(root.id!!))
        assertTrue(grandchild.isDescendantOf(child.id!!))
        assertTrue(!root.isDescendantOf(grandchild.id!!))

        val ancestors = grandchild.getAncestorIds()
        assertTrue(root.id in ancestors)
        assertTrue(child.id in ancestors)
        assertTrue(grandchild.id !in ancestors, "a tenant is not its own ancestor")
    }
}
