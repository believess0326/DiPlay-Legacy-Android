package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal object HotspotAddressPolicy {
    const val FAMILY_IPV4 = "IPv4"
    const val FAMILY_IPV6 = "IPv6"

    fun parseFamily(raw: String?): String? = when (raw) {
        FAMILY_IPV4 -> FAMILY_IPV4
        FAMILY_IPV6 -> FAMILY_IPV6
        else -> null
    }

    fun flipFamily(family: String): String = if (family == FAMILY_IPV6) FAMILY_IPV4 else FAMILY_IPV6

    fun select(addresses: List<InetAddress>): InetAddress? {
        var linkLocal: InetAddress? = null
        for (address in addresses) {
            if (address is Inet4Address && usable(address)) {
                return address
            }
            if (address is Inet6Address && address.isLinkLocalAddress && linkLocal == null) {
                linkLocal = address
            }
        }
        return linkLocal
    }

    fun select(addresses: List<InetAddress>, preferredFamily: String?): InetAddress? {
        if (preferredFamily != FAMILY_IPV6) {
            return select(addresses)
        }
        for (address in addresses) {
            if (address is Inet6Address && address.isLinkLocalAddress) {
                return address
            }
        }
        return select(addresses)
    }

    /**
     * Issue #290: the addresses offered to the iPhone in the 0x5703 payload.
     * 0.3.0 offered every interface address; on a unit whose hotspot
     * interface also carries an IPv6 link-local, the iPhone may try that
     * address first, but additional addresses are bound unscoped, so a
     * scoped IPv6 SYN never matches and no TCP arrives (0.2.99 offered
     * IPv4 only and connected 4/4 on the same car and phone, 0.3.0/0.3.1
     * 0/13+ with the same Bluetooth, iAP2 and hotspot healthy). Keep the
     * primary address whatever its family, all IPv4 addresses and
     * non-link-local IPv6 addresses; drop non-primary IPv6 link-locals.
     * The multi-IPv4 announcement (#330) is preserved.
     */
    fun offeredAddresses(primary: InetAddress, addresses: List<InetAddress>): List<InetAddress> =
        (listOf(primary) + addresses).distinct()
            .filter { it == primary || it !is Inet6Address || !it.isLinkLocalAddress }

    /** Issue #343: a usable IPv4 is enough to stop waiting for the preferred IPv6. */
    fun hasUsableIpv4(addresses: List<InetAddress>): Boolean =
        addresses.any { it is Inet4Address && usable(it) }

    fun alternateFamilyCandidate(addresses: List<InetAddress>, published: InetAddress): InetAddress? {
        val publishedIsIpv6 = published is Inet6Address
        var linkLocal: InetAddress? = null
        for (address in addresses) {
            if (address is Inet4Address && usable(address) && publishedIsIpv6) {
                return address
            }
            if (address is Inet6Address && address.isLinkLocalAddress && !publishedIsIpv6 && linkLocal == null) {
                linkLocal = address
            }
        }
        return if (publishedIsIpv6) null else linkLocal
    }

    private fun usable(address: InetAddress): Boolean =
        !address.isLoopbackAddress && !address.isAnyLocalAddress && !address.isLinkLocalAddress &&
            !address.isMulticastAddress
}
