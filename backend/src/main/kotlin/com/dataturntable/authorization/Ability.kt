package com.dataturntable.authorization

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "abilities")
class Ability {
    @Id
    @Column(name = "ability_key", nullable = false, updatable = false)
    var value: String = ""
}
