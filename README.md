# M-Extension-Server — headless Mihon (Tachiyomi)/Aniyomi extensions server

M-Extension-Server is a lightweight, headless service for running Mihon (Tachiyomi)/Aniyomi extensions (APKs). It dynamically loads extensions from Base64-encoded APKs, executes catalogue and content methods (manga/anime), and returns results via a small HTTP API.


## Desktop and iOS runtimes

The normal `:server:shadowJar` task builds the desktop server and retains
KCEF/JCEF plus its native desktop dependencies. Mangayomi's on-device iOS
bridge uses `-PiosRuntime=true`, which replaces the logging backend and
excludes Chromium, JCEF, JOGL, and other desktop-only runtime classes.

Both modes compile the same bridge and extension-compatibility code. Pull
requests build and test both variants. Each changed iOS server JAR requires a
new immutable `ios-runtime-v*` release and checksum. The separate embedded
OpenJDK release only needs rebuilding when its OpenJDK sources, patches, or
toolchain change.

## Extension WebViews

The JVM has no browser engine. When a client hosts WebViews, every
`android.webkit.WebView` an extension creates runs as a real WebView in the
client (`mextensionserver.webview.RemoteWebViewProvider`), and the extension's
`WebViewClient` / `WebChromeClient` receive the same callbacks, on the same
threads and in the same order as on Android:

- `onPageStarted`, `onPageFinished`, `onProgressChanged`, `onReceivedTitle`,
  `onReceivedError`, `onReceivedHttpError`, `onLoadResource`,
  `doUpdateVisitedHistory`, `onConsoleMessage`, `onRenderProcessGone`;
- `shouldInterceptRequest` (on a background thread, its response served to the
  page), `shouldOverrideUrlLoading` (on the WebView thread);
- `addJavascriptInterface` methods, called synchronously from the page;
- `loadUrl`, `postUrl`, `loadData(WithBaseURL)`, `evaluateJavascript`,
  `stopLoading`, `reload`, `goBack`/`goForward`, `destroy`.

Cookies are shared both ways between the WebView, `CookieManager` and the
network cookie jar, and `WebSettings.getUserAgentString()` /
`WebSettings.getDefaultUserAgent()` report the client engine's user-agent. A
Cloudflare challenge is solved in such a WebView when no FlareSolverr-compatible
proxy is configured.

A client offers its WebView host with two headers on every `/dalvik` call:
`x-mangayomi-webview-port` and `x-mangayomi-webview-token`. The server then
opens `ws://127.0.0.1:<port>/remote_webview`, presenting the token in
`x-mangayomi-webview-token`. Messages are JSON text frames, symmetric in both
directions:

```
{"type":"req","id":1,"method":"loadUrl","params":{"session":"…",…}}
{"type":"res","id":1,"result":…}            or  {"type":"res","id":1,"error":"…"}
{"type":"evt","method":"pageFinished","params":{"session":"…",…}}
```

`/capabilities` reports `"remoteWebView":1`. Without a WebView host, the KCEF
provider is used, and fails loads gracefully when no CEF runtime is available.

## Credits

The `AndroidCompat` module was originally developed by [@null-dev](https://github.com/null-dev) for [TachiWeb-Server](https://github.com/Tachiweb/TachiWeb-server) and is licensed under `Apache License Version 2.0`.

Parts of [Mihon (Tachiyomi)](https://github.com/mihonapp/mihon) is adopted into this codebase, also licensed under `Apache License Version 2.0`.

Parts of [Aniyomi](https://github.com/aniyomiorg/aniyomi) is adopted into this codebase, also licensed under `Apache License Version 2.0`.

You can obtain a copy of `Apache License Version 2.0` from  http://www.apache.org/licenses/LICENSE-2.0

Changes to both codebases is licensed under `MPL 2.0` as the rest of this project.

YouTube stream resolution uses
[NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor), version
`v0.26.3`, which is licensed under the GNU General Public License version 3.
Distributed server bundles include NewPipe Extractor and must comply with its
GPLv3 terms.

## License

```
Copyright © 2024 Aniyomi Open Source Project

This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at http://mozilla.org/MPL/2.0/.
```

```
Copyright © 2024 Mihon Open Source Project

This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at http://mozilla.org/MPL/2.0/.
```

```
Copyright (C) Contributors to the Suwayomi project

This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at http://mozilla.org/MPL/2.0/.
```
