package no.nav.sf.henvendelse.api.proxy

lateinit var application: Application

fun main() {
    Thread.sleep(3000)
    application = Application()
    application.start()
}
