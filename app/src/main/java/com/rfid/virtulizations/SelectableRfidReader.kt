package com.rfid.virtulizations

import android.content.Context
import android.os.Build
import com.zebra.rfid.api3.ENUM_TRANSPORT

class SelectableRfidReader(private val context: Context) : RfidReader {
    var selection: String = "AUTO"
        private set
    private var active: RfidReader? = null
    val usesXr2: Boolean get() = selection == "XR2" ||
        (selection == "AUTO" && automaticReaderFamily(Build.MANUFACTURER, Build.MODEL) == "XR2")
    fun select(value: String) {
        check(!isConnected()) { "Disconnect before changing reader" }
        require(value in options.keys)
        active?.disconnect(); active = null
        selection = value
    }
    private fun reader(): RfidReader = active ?: (if (usesXr2) RfidSdkManager(context)
        else ZebraRfidReader(context, if (selection == "AUTO") ENUM_TRANSPORT.ALL else ENUM_TRANSPORT.valueOf(selection))).also { active = it }
    override fun isConnected() = active?.isConnected() == true
    override suspend fun connect(): ReaderInfo = try { reader().connect() }
        catch (e: LinkageError) {
            active?.disconnect()
            throw IllegalStateException("This reader connection requires a Zebra hardware service unavailable on this device. Try the matching Bluetooth, serial or USB option.", e)
        }
    override fun info() = reader().info()
    override fun configure(mode: QueryMode) = reader().configure(mode)
    override fun power(value: Int) = reader().power(value)
    override fun range(value: Int) = reader().range(value)
    override fun start(targetEpc: String?, onTag: (RfidTagEvent) -> Unit, onUnexpectedEnd: () -> Unit) =
        reader().start(targetEpc, onTag, onUnexpectedEnd)
    override suspend fun stop() { active?.stop() }
    override fun disconnect() { active?.disconnect() }
    companion object {
        val options = linkedMapOf("AUTO" to "Automatic (this device)", "XR2" to "Janam XR2", "BLUETOOTH" to "Zebra Bluetooth",
            "SERVICE_SERIAL" to "Zebra built-in / serial", "SERVICE_USB" to "Zebra USB",
            "QC_SERIAL" to "Zebra QC serial", "RE_SERIAL" to "Zebra RE serial", "RE_USB" to "Zebra RE USB")
    }
}

fun automaticReaderFamily(manufacturer: String, model: String): String =
    if (model.contains("XR2", true) && !manufacturer.contains("zebra", true)) "XR2" else "Zebra"
