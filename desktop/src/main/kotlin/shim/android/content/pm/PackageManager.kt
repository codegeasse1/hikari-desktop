package android.content.pm

import java.io.File

/**
 * Desktop stand-in for Android's PackageManager, reduced to what reading an
 * Aniyomi extension APK needs.
 *
 * On Android the platform parses an `.apk`'s binary `AndroidManifest.xml` and
 * hands back a [PackageInfo]; the JVM has no such API, so this delegates to
 * [com.hikari.app.aniyomi.ApkManifest], which reads that XML itself.
 */
open class PackageManager {

    open fun getPackageArchiveInfo(archiveFilePath: String, flags: Int): PackageInfo? =
        com.hikari.app.aniyomi.ApkManifest.read(File(archiveFilePath), flags)

    open fun getApplicationLabel(info: ApplicationInfo): CharSequence =
        info.nonLocalizedLabel ?: info.packageName ?: ""

    companion object {
        const val GET_META_DATA = 0x00000080
        const val GET_CONFIGURATIONS = 0x00004000
        const val GET_ACTIVITIES = 0x00000001
        const val GET_SERVICES = 0x00000004
        const val GET_RECEIVERS = 0x00000002
    }
}

/** A package's `<meta-data>`-bearing application record. */
class ApplicationInfo {
    var packageName: String? = null
    var sourceDir: String? = null
    var publicSourceDir: String? = null
    var dataDir: String? = null
    var metaData: android.os.Bundle? = null
    var nonLocalizedLabel: CharSequence? = null
    var labelRes: Int = 0
    var iconRes: Int = 0

    fun loadLabel(pm: PackageManager): CharSequence = pm.getApplicationLabel(this)
}

/** A `<uses-feature>` entry. */
class FeatureInfo(var name: String? = null) {
    override fun toString(): String = "FeatureInfo{name=$name}"
}

/** The subset of [android.content.pm.PackageInfo] the extension loader reads. */
class PackageInfo {
    var packageName: String? = null
    var versionName: String? = null
    var versionCode: Int = 0
    var versionCodeMajor: Int = 0
    var applicationInfo: ApplicationInfo? = null
    var reqFeatures: Array<FeatureInfo>? = null
    var sharedUserId: String? = null
}
