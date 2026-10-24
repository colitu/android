package com.v2ray.ang.colitu.app

import androidx.core.content.FileProvider

/**
 * Shares the passport Story card. Its own class so it can sit next to the
 * direct build's updater FileProvider in the merged manifest.
 */
class ColituShareProvider : FileProvider()
