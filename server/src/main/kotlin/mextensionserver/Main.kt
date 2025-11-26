package mextensionserver

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import eu.kanade.tachiyomi.App
import mextensionserver.controller.MExtensionServerController
import mu.KotlinLogging
import org.kodein.di.DI
import org.kodein.di.conf.global
import xyz.nulldev.androidcompat.AndroidCompat
import xyz.nulldev.androidcompat.AndroidCompatInitializer
import xyz.nulldev.ts.config.ConfigKodeinModule

private val logger = KotlinLogging.logger {}
private val androidCompat by lazy { AndroidCompat() }

@Suppress("BlockingMethodInNonBlockingContext")
suspend fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toIntOrNull() ?: 0
    val appDir = args.getOrNull(1)
    initApplication(appDir)
    val controller = MExtensionServerController()
    controller.start(port)
    Runtime.getRuntime().addShutdownHook(Thread { controller.stop() })
    // Keep running
    while (controller.isRunning()) {
        Thread.sleep(1000)
    }
}

private fun initApplication(appDir: String?) {
    logger.info("Running MExtensionServer ${BuildConfig.VERSION} revision ${BuildConfig.REVISION}")

    // Set custom app directory if provided
    appDir?.let { System.setProperty("ts.server.rootDir", it) }

    // Load config API
    DI.global.addImport(ConfigKodeinModule().create())
    // Load Android compatibility dependencies
    AndroidCompatInitializer().init()
    // start app
    androidCompat.startApp(App())
}
