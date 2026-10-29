package com.cryptochecker.app.data.portfolio

import com.cryptochecker.app.domain.portfolio.AddressRedirect
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Folgt Weiterleitungen der Adressabfragen nur auf denselben Host ([AddressRedirect]); der Client selbst folgt keinen
 * (`followRedirects(false)`). Eine andere Weiterleitung kommt unverändert als Antwort zurück (HTTP-Fehler). Wie iOS
 * `SameHostRedirectDelegate`. 307/308 behalten Methode und Inhalt, die übrigen werden wie bei OkHttp zum GET.
 */
class SameHostRedirectInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        var request = original
        var response = chain.proceed(request)
        var hops = 0
        while (response.isRedirect && hops < AddressRedirect.MAX_HOPS) {
            val target = response.header("Location")?.let { request.url.resolve(it) } ?: return response
            if (!AddressRedirect.allowed(original.url.scheme, original.url.host, target.scheme, target.host)) return response
            val keepMethod = response.code == HTTP_TEMPORARY_REDIRECT || response.code == HTTP_PERMANENT_REDIRECT
            response.close()
            request = if (keepMethod) request.newBuilder().url(target).build() else request.newBuilder().url(target).get().build()
            hops++
            response = chain.proceed(request)
        }
        return response
    }

    private companion object {
        const val HTTP_TEMPORARY_REDIRECT = 307
        const val HTTP_PERMANENT_REDIRECT = 308
    }
}
