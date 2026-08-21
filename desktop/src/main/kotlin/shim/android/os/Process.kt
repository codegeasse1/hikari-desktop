package android.os

object Process {
    fun myPid(): Int = ProcessHandle.current().pid().toInt()
    fun killProcess(pid: Int) {
        Runtime.getRuntime().exit(1)
    }
}
