package no.nav.sf.henvendelse.api.proxy

lateinit var application: Application

fun main() {
    // Suspecting potential race condition for load of web proxy envs and instantiating of token validator in dev-fss.
    // Therefor a small delay on boot
    Thread.sleep(3000)
    application = Application()
    application.start()
}
