package com.shounak.localmeshai.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

object NetworkUtils {
    /**
     * Checks if the device has an active internet connection.
     */
    fun isConnected(context: Context): Boolean {
        return try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val capabilities = cm.getNetworkCapabilities(network) ?: return false
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            } else {
                @Suppress("DEPRECATION")
                val activeNetworkInfo = cm.activeNetworkInfo
                @Suppress("DEPRECATION")
                activeNetworkInfo != null && activeNetworkInfo.isConnected
            }
        } catch (_: Exception) {
            true // Fail open to avoid blocking if connectivity service query fails
        }
    }

    /**
     * Identifies if a Throwable is related to network failure (offline, DNS failure, connection refused, or timeout).
     */
    fun isNetworkException(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is UnknownHostException ||
                current is ConnectException ||
                current is NoRouteToHostException ||
                current is SocketException ||
                current is SocketTimeoutException
            ) {
                return true
            }
            val msg = current.message.orEmpty().lowercase(Locale.US)
            if (msg.contains("unable to resolve host") ||
                msg.contains("no address associated with hostname") ||
                msg.contains("network is unreachable") ||
                msg.contains("connection refused") ||
                msg.contains("software caused connection abort") ||
                msg.contains("connection reset") ||
                msg.contains("failed to connect") ||
                msg.contains("timed out") ||
                msg.contains("timeout") ||
                msg.contains("broken pipe")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * Specifically identifies if a Throwable indicates that the device has NO internet connection or DNS resolution failed.
     */
    fun isOfflineException(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is UnknownHostException ||
                current is NoRouteToHostException ||
                current is ConnectException
            ) {
                return true
            }
            val msg = current.message.orEmpty().lowercase(Locale.US)
            if (msg.contains("unable to resolve host") ||
                msg.contains("no address associated with hostname") ||
                msg.contains("network is unreachable") ||
                msg.contains("no internet connection") ||
                msg.contains("failed to connect")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
