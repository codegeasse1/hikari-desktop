package android.util

object Log {
    @JvmStatic fun d(tag: String, msg: String) = println("D/$tag: $msg")
    @JvmStatic fun v(tag: String, msg: String) = println("V/$tag: $msg")
    @JvmStatic fun i(tag: String, msg: String) = println("I/$tag: $msg")
    @JvmStatic fun w(tag: String, msg: String) = println("W/$tag: $msg")
    @JvmStatic fun w(tag: String, msg: String, t: Throwable?) {
        println("W/$tag: $msg")
        t?.printStackTrace()
    }
    @JvmStatic fun e(tag: String, msg: String) = System.err.println("E/$tag: $msg")
    @JvmStatic fun e(tag: String, msg: String, t: Throwable?) {
        System.err.println("E/$tag: $msg")
        t?.printStackTrace()
    }
}
