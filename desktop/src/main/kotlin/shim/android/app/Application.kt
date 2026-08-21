package android.app

import android.content.Context

open class Application : Context()

/** Minimal Activity shim — desktop plugins that cast the host context to an
 *  Activity (some CloudStream plugins do `app as AppCompatActivity`) will fail
 *  that cast on desktop and fall back to their context path; that is expected
 *  and handled by plugin authors' own try/catch. */
open class Activity : Context()
