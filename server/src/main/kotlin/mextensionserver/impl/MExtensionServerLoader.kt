package mextensionserver.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.content.pm.PackageInfo
import io.github.oshai.kotlinlogging.KotlinLogging
import mextensionserver.util.Extension
import mextensionserver.util.PackageTools.dex2jar
import mextensionserver.util.PackageTools.getPackageInfo
import mextensionserver.util.PackageTools.loadExtensionSources
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

object MExtensionServerLoader {
    private val logger = KotlinLogging.logger {}
    private val tempDir = Files.createTempDirectory("mextensionserver").toFile()

    // Extensions retain pagination iterators and other request state. Keep one
    // instance per APK for the server lifetime instead of decoding it per call.
    private val loadedExtensions =
        ExtensionInstanceCache(
            keyOf = ::sha256,
            load = ::loadExtension,
            dispose = LoadedExtension::close,
        )

    private const val MANGA_PACKAGE = "tachiyomi.extension"
    private const val ANIME_PACKAGE = "tachiyomi.animeextension"
    private const val METADATA_SOURCE_CLASS_SUFFIX = ".class"

    init {
        // Close classloaders before deleting their JARs, including on JVM exit.
        try {
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    cleanupTempFiles()
                },
            )
        } catch (e: IllegalStateException) {
            // Shutdown already in progress, ignore
        }
    }

    class LoadedExtension(
        initialSources: List<Any>,
        val packageInfo: PackageInfo,
        val jarFile: File,
        private val classLoader: URLClassLoader,
        private val sourceFactory: (() -> List<Any>)? = null,
    ) : AutoCloseable {
        @Volatile
        var sources: List<Any> = initialSources
            private set

        /**
         * Recreate factory children after request preferences have been
         * applied. Configurable factories such as Jellyfin decide how many
         * sources exist from SharedPreferences, so the list created while the
         * APK was first loaded can be stale after a sync or preference edit.
         */
        @Synchronized
        fun refreshFactorySources(): List<Any> {
            val createSources = sourceFactory ?: return sources
            val previous = sources
            val refreshed = createSources()
            previous
                .filterIsInstance<eu.kanade.tachiyomi.source.Source>()
                .forEach(MihonMetadataCache::remove)
            sources = refreshed
            return refreshed
        }

        override fun close() {
            try {
                sources
                    .filterIsInstance<eu.kanade.tachiyomi.source.Source>()
                    .forEach(MihonMetadataCache::remove)
                classLoader.close()
            } finally {
                if (jarFile.exists() && !jarFile.delete()) {
                    logger.warn { "Failed to delete cached extension JAR ${jarFile.absolutePath}" }
                }
            }
        }
    }

    data class ExtensionInvocation<T>(
        val extensionId: String,
        val result: T,
    )

    class ExtensionNotLoadedException(
        extensionId: String,
    ) : IllegalStateException("Extension handle $extensionId is no longer loaded; resend the APK")

    fun <T> invokeWithExtension(
        base64Data: String?,
        extensionId: String?,
        block: (LoadedExtension) -> T,
    ): ExtensionInvocation<T> {
        val keyedResult =
            if (base64Data != null) {
                loadedExtensions.useAndGetKey(Base64.getDecoder().decode(base64Data), block)
            } else {
                val key = requireNotNull(extensionId) { "Either data or extensionId is required" }
                loadedExtensions.useByKey(key, block) ?: throw ExtensionNotLoadedException(key)
            }
        return ExtensionInvocation(keyedResult.key, keyedResult.value)
    }

    private fun loadExtension(apkData: ByteArray): LoadedExtension {
        val tempApkFile = File(tempDir, "extension-${UUID.randomUUID()}.apk")
        val jarFile = File(tempDir, "extension-${UUID.randomUUID()}.jar")
        var classLoader: URLClassLoader? = null
        try {
            // Write APK data to temp file
            tempApkFile.writeBytes(apkData)

            // Get package info
            val packageInfo = getPackageInfo(tempApkFile.absolutePath)

            // Extract class name
            val metaData = packageInfo.applicationInfo.metaData
            var classNameSuffix = metaData.getString(MANGA_PACKAGE + METADATA_SOURCE_CLASS_SUFFIX)
            if (classNameSuffix == null) {
                classNameSuffix = metaData.getString(ANIME_PACKAGE + METADATA_SOURCE_CLASS_SUFFIX)
            }
            if (classNameSuffix == null) {
                throw IllegalArgumentException("No source class found in extension metadata")
            }
            val className =
                if (classNameSuffix.startsWith(".")) {
                    packageInfo.packageName + classNameSuffix
                } else {
                    classNameSuffix
                }
            logger.debug { "Main class for extension is $className" }

            // Convert to JAR
            val dexFile = File(tempApkFile.absolutePath)

            dex2jar(dexFile, jarFile)

            // Extract assets and resources from APK
            Extension.extractAssetsFromApk(tempApkFile, jarFile)

            // Load extension sources
            val loadedSource = loadExtensionSources(jarFile, className, tempApkFile)
            val extensionMainClassInstance = loadedSource.instance
            classLoader = loadedSource.classLoader
            val sourceFactory: (() -> List<Any>)? =
                when (extensionMainClassInstance) {
                    is eu.kanade.tachiyomi.source.SourceFactory ->
                        extensionMainClassInstance::createSources
                    is eu.kanade.tachiyomi.animesource.AnimeSourceFactory ->
                        extensionMainClassInstance::createSources
                    else -> null
                }
            val sources: List<Any> =
                when (extensionMainClassInstance) {
                    is eu.kanade.tachiyomi.source.Source -> listOf(extensionMainClassInstance)
                    is eu.kanade.tachiyomi.source.SourceFactory -> sourceFactory!!.invoke()
                    is eu.kanade.tachiyomi.animesource.AnimeSource -> listOf(extensionMainClassInstance)
                    is eu.kanade.tachiyomi.animesource.AnimeSourceFactory -> sourceFactory!!.invoke()
                    else -> throw RuntimeException("Unknown source class type! ${extensionMainClassInstance.javaClass}")
                }

            return LoadedExtension(
                sources,
                packageInfo,
                jarFile,
                loadedSource.classLoader,
                sourceFactory,
            )
        } catch (error: Throwable) {
            try {
                classLoader?.close()
            } finally {
                jarFile.delete()
            }
            logger.error(error) { "Failed to load extension from base64 data" }
            throw error
        } finally {
            // Clean up APK file
            if (tempApkFile.exists()) {
                tempApkFile.delete()
            }
        }
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(data)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    fun cleanupTempFiles() {
        MihonImageProxy.clear()
        MihonVideoProxy.clear()
        MihonMetadataCache.clear()
        try {
            loadedExtensions.close()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to close cached extensions" }
        } finally {
            try {
                tempDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to cleanup temp files" }
            }
        }
    }
}
