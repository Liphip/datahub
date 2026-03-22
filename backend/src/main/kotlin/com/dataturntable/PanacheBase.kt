package com.dataturntable

import io.quarkus.hibernate.orm.panache.PanacheEntityBase as JavaPanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase as KotlinPanacheEntityBase

/**
 * Bridge base class for all entities that need both Java Panache JPA support
 * and Kotlin PanacheCompanionBase<T, UUID> companion objects.
 *
 * Background: io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase is an
 * interface with default method implementations. The Java PanacheEntityBase class
 * also provides concrete implementations of the same methods. Kotlin requires an
 * explicit override when inheriting from both. This class resolves that ambiguity
 * once so individual entity classes don't have to.
 */
abstract class PanacheBase : JavaPanacheEntityBase(), KotlinPanacheEntityBase {
    override fun persist(): Unit = super<JavaPanacheEntityBase>.persist()
    override fun persistAndFlush(): Unit = super<JavaPanacheEntityBase>.persistAndFlush()
    override fun delete(): Unit = super<JavaPanacheEntityBase>.delete()
    override fun isPersistent(): Boolean = super<JavaPanacheEntityBase>.isPersistent()
}
