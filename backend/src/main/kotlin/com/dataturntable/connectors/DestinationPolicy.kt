package com.dataturntable.connectors

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.math.BigInteger
import java.net.InetAddress
import java.util.Optional

/**
 * Which hosts connectors may reach. Tenant administrators configure connectors, so by default a
 * connector cannot reach loopback, private, link-local or multicast addresses of the
 * installation's own network (server-side request forgery). System administrators allow the
 * internal systems a data hub has to integrate (SAP, metering, internal registers) with
 * `dataturntable.connectors.allowed-networks`, a list of CIDR ranges such as `10.20.0.0/16`.
 * Cloud metadata endpoints stay blocked regardless.
 *
 * Checked when a connector is saved and again whenever it connects, so a name that resolves to
 * a different address later (DNS rebinding) is caught too.
 */
@ApplicationScoped
class DestinationPolicy(
    @ConfigProperty(name = "dataturntable.connectors.allowed-networks")
    allowedNetworks: Optional<List<String>>,
) {
    private data class Cidr(val network: BigInteger, val bits: Int, val length: Int)

    private val allowed: List<Cidr> = allowedNetworks.orElse(emptyList())
        .map { it.trim() }.filter { it.isNotEmpty() }.map(::parseCidr)

    class ProhibitedDestinationException(message: String) : IllegalArgumentException(message)

    /** Throws when [host] resolves to an address connectors may not reach; unresolvable hosts pass (they fail on connect). */
    fun check(host: String) {
        val addresses = runCatching { InetAddress.getAllByName(host.trim().removePrefix("[").removeSuffix("]")) }.getOrNull() ?: return
        addresses.firstOrNull(::prohibited)?.let {
            throw ProhibitedDestinationException(
                "Connector destination $host (${it.hostAddress}) is in a loopback, private, link-local or metadata network. " +
                    "A system administrator can allow internal networks with dataturntable.connectors.allowed-networks.")
        }
    }

    fun prohibited(address: InetAddress): Boolean {
        val text = address.hostAddress.substringBefore('%')
        if (text == "169.254.169.254" || text.equals("fd00:ec2::254", ignoreCase = true)) return true
        val internal = address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress || isUniqueLocalV6(address)
        return internal && allowed.none { it.contains(address) }
    }

    private fun isUniqueLocalV6(address: InetAddress) = address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc

    private fun Cidr.contains(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != length) return false
        val shift = length * 8 - bits
        return BigInteger(1, bytes).shiftRight(shift) == network.shiftRight(shift)
    }

    private fun parseCidr(text: String): Cidr {
        val (host, bits) = text.split('/').let { it[0] to it.getOrNull(1) }
        val bytes = InetAddress.getByName(host).address
        val prefix = bits?.toIntOrNull() ?: (bytes.size * 8)
        require(prefix in 0..bytes.size * 8) { "Invalid network in dataturntable.connectors.allowed-networks: $text" }
        return Cidr(BigInteger(1, bytes), prefix, bytes.size)
    }
}
