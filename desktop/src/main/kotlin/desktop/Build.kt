package desktop

/**
 * Build identity, shown in Settings (and copied by "Copy build info") so that a
 * user can tell exactly which release they are running — the release exe's
 * Windows version stays 0.1.0 for every build.
 *
 * CI REWRITES THIS FILE before compiling: VERSION becomes the release number
 * (the same number in the .exe name), DATE the UTC build time and COMMIT the
 * short git SHA of the commit it was built from. The values below are the
 * fallback for a build made by hand.
 */
object Build {
    const val VERSION = "0.1.0"
    const val DATE = "dev"
    const val COMMIT = "dev"
}
