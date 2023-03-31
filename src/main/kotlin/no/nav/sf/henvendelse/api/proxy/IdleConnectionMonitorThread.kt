package no.nav.sf.henvendelse.api.proxy

import org.apache.http.conn.HttpClientConnectionManager
import java.util.concurrent.TimeUnit

class IdleConnectionMonitorThread(private val connMgr: HttpClientConnectionManager) : Thread() {
    @Volatile
    private var shutdown = false
    override fun run() {
        try {
            while (!shutdown) {
                synchronized(this) {
                    Thread.sleep(5000)
                    // Close expired connections
                    connMgr.closeExpiredConnections()
                    // Optionally, close connections
                    // that have been idle longer than 30 sec
                    connMgr.closeIdleConnections(30, TimeUnit.SECONDS)
                }
            }
        } catch (ex: InterruptedException) {
            // terminate
        }
    }

    fun shutdown() {
        shutdown = true
    }
}
