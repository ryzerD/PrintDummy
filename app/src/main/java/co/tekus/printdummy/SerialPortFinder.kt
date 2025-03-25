package co.tekus.printdummy

import java.io.File
import java.io.FileReader
import java.io.IOException
import java.io.LineNumberReader
import java.util.Vector
class SerialPortFinder {
    private var mDrivers: Vector<Driver>? = null

    inner class Driver(private val mDriverName: String, private val mDeviceRoot: String) {
        var mDevices: Vector<File>? = null

        fun getDevices(): Vector<File> {
            if (mDevices == null) {
                mDevices = Vector()
                val listFiles = File("/dev").listFiles()
                if (listFiles != null) {
                    for (i in listFiles.indices) {
                        if (listFiles[i].absolutePath.startsWith(mDeviceRoot)) {
                            mDevices!!.add(listFiles[i])
                        }
                    }
                }
            }
            return mDevices!!
        }

        fun getName(): String {
            return mDriverName
        }
    }

    fun getAllDevices(): Array<String> {
        val vector = Vector<String>()
        try {
            val it = getDrivers().iterator()
            while (it.hasNext()) {
                val next = it.next()
                val it2 = next.getDevices().iterator()
                while (it2.hasNext()) {
                    vector.add(String.format("%s (%s)", it2.next().name, next.getName()))
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return vector.toTypedArray()
    }

    fun getAllDevicesPath(): Array<String> {
        val vector = Vector<String>()
        try {
            val it = getDrivers().iterator()
            while (it.hasNext()) {
                val it2 = it.next().getDevices().iterator()
                while (it2.hasNext()) {
                    vector.add(it2.next().absolutePath)
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return vector.toTypedArray()
    }

    fun getDrivers(): Vector<Driver> {
        if (mDrivers == null) {
            mDrivers = Vector()
            try {
                val lineNumberReader = LineNumberReader(FileReader("/proc/tty/drivers"))
                while (true) {
                    val readLine = lineNumberReader.readLine() ?: break
                    val trim = readLine.substring(0, minOf(21, readLine.length)).trim()
                    val split = readLine.split(" +".toRegex()).toTypedArray()
                    if (split.size >= 5 && split[split.size - 1] == "serial") {
                        mDrivers!!.add(Driver(trim, split[split.size - 4]))
                    }
                }
                lineNumberReader.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return mDrivers!!
    }
}