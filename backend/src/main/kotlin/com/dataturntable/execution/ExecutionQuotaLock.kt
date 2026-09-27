package com.dataturntable.execution

import com.dataturntable.PanacheBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "execution_quota_locks")
class ExecutionQuotaLock : PanacheBase() {
    @Id
    @Column(name = "scope", nullable = false, updatable = false)
    var scope: String = GLOBAL_SCOPE

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    companion object {
        const val GLOBAL_SCOPE = "global"
    }
}
