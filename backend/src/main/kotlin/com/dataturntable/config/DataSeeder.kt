package com.dataturntable.config

import com.dataturntable.auth.User
import com.dataturntable.tenant.Role
import com.dataturntable.tenant.Tenant
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.transaction.Transactional
import org.mindrot.jbcrypt.BCrypt

@ApplicationScoped
class DataSeeder {
    
    @Transactional
    fun onStart(@Observes ev: StartupEvent) {
        // Check if default tenant exists
        val defaultTenant = Tenant.findByName("Default")
        if (defaultTenant == null) {
            val tenant = Tenant().apply {
                name = "Default"
            }
            tenant.persist()
            
            // Create default role
            val role = Role().apply {
                this.tenant = tenant
                name = "admin"
                description = "Administrator role"
                isDefault = true
            }
            role.persist()
            
            // Create default admin user
            val admin = User().apply {
                this.tenant = tenant
                email = "admin@dataturntable.io"
                passwordHash = BCrypt.hashpw("admin123", BCrypt.gensalt())
                firstName = "Admin"
                lastName = "User"
                this.role = role
                isActive = true
            }
            admin.persist()
            
            println("Seeded default tenant, role, and admin user")
        }
    }
}
