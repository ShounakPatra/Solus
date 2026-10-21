package com.shounak.localmeshai.utils

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.Locale

/**
 * Enforces URL normalization and credential protection policies for model downloads.
 * Guarantees Hugging Face authentication tokens are only sent to the canonical huggingface.co HTTPS origin.
 */
object ModelDownloadUrlPolicy {

    private const val HF_HOST = "huggingface.co"
    private const val MAX_REDIRECTS = 20

    /**
     * Checks if the given [HttpUrl] has scheme HTTPS and canonical hostname equals huggingface.co.
     */
    fun isExactHuggingFace(httpUrl: HttpUrl): Boolean {
        return httpUrl.isHttps && httpUrl.host.lowercase(Locale.ROOT) == HF_HOST
    }

    /**
     * Checks if the given URL string has scheme HTTPS and canonical hostname equals huggingface.co.
     */
    fun isExactHuggingFace(url: String): Boolean {
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        return isExactHuggingFace(parsed)
    }

    /**
     * Checks whether a supplied Hugging Face token is eligible to be attached to requests for this URL.
     * Uses origin checks exclusively to determine token attachment eligibility.
     */
    fun isTokenEligible(httpUrl: HttpUrl): Boolean = isExactHuggingFace(httpUrl)

    /**
     * Checks whether a supplied Hugging Face token is eligible to be attached to requests for this URL.
     * Uses origin checks exclusively to determine token attachment eligibility.
     */
    fun isTokenEligible(url: String): Boolean = isExactHuggingFace(url)

    /**
     * Known gated catalog repository identifiers that explicitly require Hugging Face authentication.
     */
    val knownGatedCatalogRepos = setOf(
        "google/gemma-3n-E2B-it-litert-preview",
        "google/gemma-3n-E4B-it-litert-preview",
        "litert-community/Gemma3-1B-IT",
        "litert-community/gemma-3-270m-it",
        "litert-community/Gemma3-4B-IT"
    )

    /**
     * Derives whether the given model URL requires a Hugging Face token.
     * Origin alone does not mandate authentication. Unknown access requirements permit an anonymous attempt.
     * Preserves explicit requirements for known gated catalog models.
     */
    fun requiresHuggingFaceToken(url: String): Boolean {
        if (!isExactHuggingFace(url)) return false
        val clean = normalizeUrl(url)
        return knownGatedCatalogRepos.any { repo -> clean.contains(repo, ignoreCase = true) }
    }

    /**
     * Normalizes a model URL or shorthand repository reference.
     * Only rewrites /blob/ and appends ?download=true if the canonical hostname is exactly huggingface.co.
     */
    fun normalizeUrl(input: String): String {
        val clean = input.trim()
        if (clean.isBlank()) return ""

        // Preserve shorthand "organization/repository/file" (or deeper path)
        val isShorthand = !clean.contains("://") && clean.split("/").let { parts ->
            parts.size >= 3 && parts[0].isNotBlank() && parts[1].isNotBlank() && !parts[0].contains("@")
        }

        val effectiveUrl = if (isShorthand) {
            val parts = clean.split("/")
            val org = parts[0]
            val repo = parts[1]
            val rest = parts.drop(2).joinToString("/")
            "https://$HF_HOST/$org/$repo/resolve/main/$rest?download=true"
        } else {
            clean
        }

        val parsed = effectiveUrl.toHttpUrlOrNull() ?: return clean

        // Strip dangerous user-info from URL
        val sanitized = if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            parsed.newBuilder().username("").password("").build()
        } else {
            parsed
        }

        // Rewriting /blob/ and appending download=true only applies to exact huggingface.co
        if (isExactHuggingFace(sanitized)) {
            val builder = sanitized.newBuilder()
            val encSegments = sanitized.encodedPathSegments
            if (encSegments.size >= 4 && encSegments[2].equals("blob", ignoreCase = true)) {
                val newEncodedPath = "/" + encSegments.mapIndexed { idx, seg ->
                    if (idx == 2) "resolve" else seg
                }.joinToString("/")
                builder.encodedPath(newEncodedPath)
            }
            if (sanitized.queryParameter("download") == null) {
                builder.addQueryParameter("download", "true")
            }
            return builder.build().toString()
        }

        return sanitized.toString()
    }

    /**
     * Interceptor that injects Authorization headers exclusively to huggingface.co HTTPS requests,
     * strips authorization on cross-origin redirects, and blocks HTTPS-to-HTTP downgrade redirects.
     */
    class SecurityInterceptor(
        private val tokenProvider: () -> String?
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var request = chain.request()
            val token = tokenProvider()?.trim()

            // Attach token only when scheme is HTTPS and canonical hostname equals huggingface.co
            request = if (!token.isNullOrBlank() && isExactHuggingFace(request.url)) {
                request.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            } else {
                request.newBuilder()
                    .removeHeader("Authorization")
                    .build()
            }

            var response = chain.proceed(request)
            var redirectCount = 0

            while (response.isRedirect) {
                if (redirectCount++ >= MAX_REDIRECTS) {
                    response.close()
                    throw IOException("Too many redirects ($redirectCount) during model download")
                }

                val location = response.header("Location")
                    ?: throw IOException("HTTP redirect missing Location header")
                val newHttpUrl = response.request.url.resolve(location)
                    ?: run {
                        response.close()
                        throw IOException("Invalid redirect Location header: $location")
                    }

                // Reject HTTPS-to-HTTP downgrade redirects
                if (response.request.url.isHttps && !newHttpUrl.isHttps) {
                    response.close()
                    throw IOException("Insecure redirect from HTTPS to HTTP is blocked for model downloads: $newHttpUrl")
                }

                response.close()

                val nextRequestBuilder = response.request.newBuilder().url(newHttpUrl)

                // Ensure authorization is removed when redirecting to another origin
                val isNextHf = isExactHuggingFace(newHttpUrl)
                if (!isNextHf || !newHttpUrl.isHttps) {
                    nextRequestBuilder.removeHeader("Authorization")
                } else if (isNextHf && !token.isNullOrBlank()) {
                    nextRequestBuilder.header("Authorization", "Bearer $token")
                }

                response = chain.proceed(nextRequestBuilder.build())
            }

            return response
        }
    }

    /**
     * Configures an [OkHttpClient.Builder] with redirection and token disclosure protections.
     */
    fun configureClient(builder: OkHttpClient.Builder, tokenProvider: () -> String?): OkHttpClient.Builder {
        return builder
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(SecurityInterceptor(tokenProvider))
    }
}
