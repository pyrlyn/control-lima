package controllima.providers.kube

fun fixture(name: String): String =
    requireNotNull(Fixtures::class.java.getResource("/$name")) { "missing fixture $name" }.readText()

private object Fixtures
