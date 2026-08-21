package dalvik.system

import com.hikari.app.cs3.DexJar
import java.io.File
import java.net.URLClassLoader

/**
 * Desktop stand-in for Android's dalvik DexClassLoader — same semantics as
 * [PathClassLoader] here (dex archive or plain jar → JVM jar via [DexJar]).
 */
class DexClassLoader(
    dexPath: String,
    optimizedDirectory: String?,
    librarySearchPath: String?,
    parent: ClassLoader,
) : URLClassLoader(arrayOf(DexJar.toJvmJarUrl(File(dexPath))), parent)
