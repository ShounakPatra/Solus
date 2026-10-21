package com.shounak.localmeshai.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class NetworkUtilsTest {

    @Test
    fun detectsNetworkExceptions() {
        assertTrue(NetworkUtils.isNetworkException(UnknownHostException("Unable to resolve host")))
        assertTrue(NetworkUtils.isNetworkException(ConnectException("Connection refused")))
        assertTrue(NetworkUtils.isNetworkException(NoRouteToHostException("No route to host")))
        assertTrue(NetworkUtils.isNetworkException(SocketException("Connection reset")))
        assertTrue(NetworkUtils.isNetworkException(SocketTimeoutException("Read timed out")))
        assertTrue(NetworkUtils.isNetworkException(IOException("Software caused connection abort")))
        assertTrue(NetworkUtils.isNetworkException(IOException("broken pipe")))

        // Nested cause
        val wrapped = RuntimeException("Wrapper", UnknownHostException("Unable to resolve host"))
        assertTrue(NetworkUtils.isNetworkException(wrapped))

        // Non-network exceptions
        assertFalse(NetworkUtils.isNetworkException(IllegalArgumentException("Invalid argument")))
        assertFalse(NetworkUtils.isNetworkException(IllegalStateException("Bad state")))
        assertFalse(NetworkUtils.isNetworkException(IOException("File not found on local disk")))
    }

    @Test
    fun detectsOfflineExceptions() {
        assertTrue(NetworkUtils.isOfflineException(UnknownHostException("Unable to resolve host")))
        assertTrue(NetworkUtils.isOfflineException(IOException("no internet connection")))
        assertTrue(NetworkUtils.isOfflineException(IOException("Network is unreachable")))
        assertTrue(NetworkUtils.isOfflineException(ConnectException("Failed to connect")))

        // Timeout is not an offline/no-host exception
        assertFalse(NetworkUtils.isOfflineException(SocketTimeoutException("Read timed out")))
        assertFalse(NetworkUtils.isOfflineException(IOException("Checksum mismatch")))
    }
}
