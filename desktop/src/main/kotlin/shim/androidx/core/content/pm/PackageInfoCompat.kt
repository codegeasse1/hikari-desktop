package androidx.core.content.pm

import android.content.pm.PackageInfo

/**
 * Desktop stand-in for AndroidX's PackageInfoCompat: `versionCode` grew a high
 * half (`versionCodeMajor`) in API 28, and the packer is `(major shl 32) or
 * (code and 0xFFFFFFFF)` on every platform.
 */
object PackageInfoCompat {

    @JvmStatic
    fun getLongVersionCode(info: PackageInfo): Long =
        (info.versionCodeMajor.toLong() shl 32) or (info.versionCode.toLong() and 0xFFFFFFFFL)
}
