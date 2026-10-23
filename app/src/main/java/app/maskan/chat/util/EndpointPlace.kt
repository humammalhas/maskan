package app.maskan.chat.util

/**
 * Where a request to a URL actually goes: this phone, the user's own network, or out of it.
 *
 * The provider badge used to come from a static flag, so "Custom" pointed at a third-party cloud
 * was labelled "Local — stays on device" - wrong in the reassuring direction, in an app whose
 * whole pitch is seeing where your data goes. This reads the address the user typed instead. It is
 * also the one place "is this host private" is decided: the http:// warning asks it too, so the
 * badge and the warning cannot disagree.
 *
 * What it can't know: a home server on a public domain ("ai.myhouse.com") is REMOTE here. That is
 * still a true sentence - the request does leave the network.
 *
 * Pure, so the tests reach it.
 */
object EndpointPlace {

    enum class Place {
        /** Loopback: on Android, 127.0.0.1 and localhost are the phone itself. */
        PHONE,

        /** A private or link-local address, a LAN name, or Tailscale. */
        NETWORK,

        /** Anything else. */
        REMOTE
    }

    /** The host part of [url], lower-case, without scheme, user, port or path. "" when there is none. */
    fun host(url: String): String {
        var rest = url.trim().lowercase()
        rest = rest.substringAfter("://", rest)
        rest = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        rest = rest.substringAfterLast('@')
        if (rest.startsWith("[")) return rest.substringAfter('[').substringBefore(']')
        return rest.substringBefore(':')
    }

    /** Where [url] points, or null when it names no host at all (an empty field). */
    fun placeOf(url: String): Place? {
        val host = host(url).trimEnd('.')
        if (host.isEmpty()) return null
        if (host == "localhost" || host.endsWith(".localhost")) return Place.PHONE

        if (host.contains(':')) return ipv6Place(host)

        val octets = ipv4(host)
        if (octets != null) {
            val (a, b) = octets[0] to octets[1]
            return when {
                a == 127 || (a == 0 && octets.all { it == 0 }) -> Place.PHONE
                a == 10 -> Place.NETWORK
                a == 172 && b in 16..31 -> Place.NETWORK
                a == 192 && b == 168 -> Place.NETWORK
                a == 169 && b == 254 -> Place.NETWORK          // link-local
                a == 100 && b in 64..127 -> Place.NETWORK      // Tailscale's range (RFC 6598)
                else -> Place.REMOTE
            }
        }

        // A name. Without a dot it can only resolve on the local network ("myserver"); these
        // suffixes are the ones reserved or used for exactly that.
        if (!host.contains('.')) return Place.NETWORK
        if (NETWORK_SUFFIXES.any { host.endsWith(it) }) return Place.NETWORK
        return Place.REMOTE
    }

    /** Plain http to a host that leaves the network - readable by anyone on the way. */
    fun isInsecureRemote(url: String): Boolean =
        url.trim().lowercase().startsWith("http://") && placeOf(url) == Place.REMOTE

    private fun ipv4(host: String): List<Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { part -> part.toIntOrNull()?.takeIf { part.isNotEmpty() && it in 0..255 } ?: return null }
        return octets
    }

    private fun ipv6Place(host: String): Place = when {
        host == "::1" -> Place.PHONE
        host.startsWith("fc") || host.startsWith("fd") -> Place.NETWORK                 // unique local
        host.startsWith("fe8") || host.startsWith("fe9") ||
            host.startsWith("fea") || host.startsWith("feb") -> Place.NETWORK          // link-local
        else -> Place.REMOTE
    }

    private val NETWORK_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal", ".ts.net")
}
