package no.nav.sf.henvendelse.api.proxy

fun readResourceFile(path: String) = ApplicationTest::class.java.getResource(path).readText()
