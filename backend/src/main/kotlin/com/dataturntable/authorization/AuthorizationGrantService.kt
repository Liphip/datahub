package com.dataturntable.authorization

import com.dataturntable.auth.User
import com.dataturntable.security.AbilityEvaluator
import com.dataturntable.tenant.Membership
import com.dataturntable.tenant.Role
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext

@ApplicationScoped
class AuthorizationGrantService {
    @PersistenceContext
    lateinit var em: EntityManager

    fun replaceRoleAbilities(role: Role, abilities: Collection<String>) {
        role.grantedAbilities.clear()
        role.grantedAbilities.addAll(resolve(abilities))
    }

    fun replaceMembershipAbilities(membership: Membership, abilities: Collection<String>) {
        membership.grantedAbilities.clear()
        membership.grantedAbilities.addAll(resolve(abilities))
    }

    fun replaceUserAbilities(user: User, abilities: Collection<String>) {
        user.grantedAbilities.clear()
        user.grantedAbilities.addAll(resolve(abilities))
    }

    fun normalize(abilities: Collection<*>?): List<String> = abilities
        ?.mapNotNull { it as? String }
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.distinct()
        ?: emptyList()

    fun legacyTokens(serializedAbilities: String?): List<String> = AbilityEvaluator.parse(serializedAbilities).toList()

    private fun resolve(abilities: Collection<String>): List<Ability> = abilities.map { value ->
        em.find(Ability::class.java, value) ?: Ability().also {
            it.value = value
            em.persist(it)
        }
    }
}
