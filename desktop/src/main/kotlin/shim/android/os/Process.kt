package android.os

object Process {
    @JvmStatic fun myPid(): Int = ProcessHandle.current().pid().toInt()
    @JvmStatic fun killProcess(pid: Int) {
        Runtime.getRuntime().exit(1)
    }
}
