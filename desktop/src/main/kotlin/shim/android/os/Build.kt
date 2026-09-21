package android.os

/**
 * Desktop stand-in for `android.os.Build`. Extensions that branch on the host
 * Android version must never take an Android-only path here, so the reported
 * version is deliberately the oldest supported one — the desktop shim's own API
 * surface, not a real device's.
 */
object Build {

    object VERSION {
        @JvmField val SDK_INT: Int = 29
        @JvmField val RELEASE: String = "10"
        @JvmField val CODENAME: String = "REL"
    }

    @JvmField val MANUFACTURER: String = "Hikari"
    @JvmField val MODEL: String = "Desktop"
    @JvmField val BRAND: String = "Hikari"
    @JvmField val DEVICE: String = "desktop"
    @JvmField val PRODUCT: String = "hikari-desktop"
}
