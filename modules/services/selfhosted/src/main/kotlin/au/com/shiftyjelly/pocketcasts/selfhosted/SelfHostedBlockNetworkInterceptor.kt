package au.com.shiftyjelly.pocketcasts.selfhosted

import okhttp3.Interceptor
import okhttp3.Response

class SelfHostedBlockNetworkInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        if (HostRoutes.routeFor(host) != HostRoute.PassThrough) {
            throw BlockedHostException(host)
        }
        return chain.proceed(chain.request())
    }
}
