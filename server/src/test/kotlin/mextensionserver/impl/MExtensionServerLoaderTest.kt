package mextensionserver.impl

import android.content.pm.PackageInfo
import java.net.URLClassLoader
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals

class MExtensionServerLoaderTest {
    @Test
    fun `factory children are recreated after preferences change`() {
        var configuredChildren = 1
        val jar = createTempFile(prefix = "mextensionserver-test-", suffix = ".jar").toFile()
        val classLoader = URLClassLoader(emptyArray())
        val extension =
            MExtensionServerLoader.LoadedExtension(
                initialSources = listOf("source-1"),
                packageInfo = PackageInfo(),
                jarFile = jar,
                classLoader = classLoader,
                sourceFactory = {
                    (1..configuredChildren).map { "source-$it" }
                },
            )

        configuredChildren = 3

        assertEquals(
            listOf("source-1", "source-2", "source-3"),
            extension.refreshFactorySources(),
        )
        assertEquals(3, extension.sources.size)
        extension.close()
    }
}
