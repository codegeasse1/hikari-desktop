package android.util

object Log {
    fun d(tag: String, msg: String) = println("D/$tag: $msg")
    fun v(tag: String, msg: String) = println("V/$tag: $msg")
    fun i(tag: String, msg: String) = println("I/$tag: $msg")
    fun w(tag: String, msg: String) = println("W/$tag: $msg")
    fun w(tag: String, msg: String, t: Throwable?) {
        println("W/$tag: $msg")
        t?.printStackTrace()
    }
    fun e(tag: String, msg: String) = System.err.println("E/$tag: $msg")
    fun e(tag: String, msg: String, t: Throwable?) {
        System.err.println("E/$tag: $msg")
        t?.printStackTrace()
    }
}
