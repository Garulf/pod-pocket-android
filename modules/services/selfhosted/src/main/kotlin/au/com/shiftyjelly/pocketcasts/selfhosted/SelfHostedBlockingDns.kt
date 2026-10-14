package au.com.shiftyjelly.pocketcasts.selfhosted

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

class SelfHostedBlockingDns(
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (HostRoutes.routeFor(hostname) != HostRoute.PassThrough) {
            throw UnknownHostException(blockedHostMessage(hostname))
        }
        return delegate.lookup(hostname)
    }
}
