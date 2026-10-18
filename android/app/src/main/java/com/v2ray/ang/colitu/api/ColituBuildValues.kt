package com.v2ray.ang.colitu.api

import com.google.gson.JsonParser

/**
 * Values a release gets from outside the public source: the API mirror origins
 * (comma separated). The build writes them to one file at the root of the APK,
 * colitu-build.json (task colituBuildValues in app/build.gradle.kts), instead of
 * compiling them into the code: the rest of the APK then comes out byte for byte
 * the same from the public source (reproducible builds). No file (a developer
 * build): no mirrors, as before.
 */
object ColituBuildValues {
    const val FILE_NAME = "colitu-build.json"

    /** Comma-separated https mirror origins, or "". */
    val mirrors: String by lazy { read("mirrors") }

    private fun read(name: String): String = runCatching {
        val stream = ColituBuildValues::class.java.classLoader?.getResourceAsStream(FILE_NAME) ?: return ""
        val root = stream.bufferedReader().use { JsonParser.parseReader(it) }.asJsonObject
        root.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    }.getOrDefault("")
}
