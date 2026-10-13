package au.com.shiftyjelly.pocketcasts.selfhosted

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SelfHostedBlockingDnsTest {
    private val resolved = mutableListOf<String>()
    private val address = InetAddress.getLoopbackAddress()
    private val dns = SelfHostedBlockingDns(
        Dns {
            resolved += it
            listOf(address)
        },
    )

    @Test
    fun `does not resolve telemetry hosts`() {
        listOf("public-api.wordpress.com", "secure.gravatar.com", "o1.ingest.sentry.io").forEach { host ->
            assertThrows(host, UnknownHostException::class.java) { dns.lookup(host) }
        }

        assertEquals(emptyList<String>(), resolved)
    }

    @Test
    fun `does not resolve Pocket Casts hosts`() {
        listOf("api.pocketcasts.com", "static.pocketcasts.com", "unknown.pocketcasts.net", "pca.st").forEach { host ->
            assertThrows(host, UnknownHostException::class.java) { dns.lookup(host) }
        }

        assertEquals(emptyList<String>(), resolved)
    }

    @Test
    fun `resolves other hosts through the delegate`() {
        val hosts = listOf("feeds.example.org", "files.wordpress.com", "pocketcasts.com.example.org")

        hosts.forEach { host -> assertEquals(host, listOf(address), dns.lookup(host)) }

        assertEquals(hosts, resolved)
    }
}
