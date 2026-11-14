package com.codelab.futbot

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private lateinit var web: WebView
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var out: OutputStream? = null
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val bridge = Bridge()
    private val io = Executors.newSingleThreadExecutor()   // escritura ordenada, sin bloquear la interfaz
    private val pending = AtomicInteger(0)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(bridge, "Android")
        web.loadUrl("file:///android_asset/index.html")
        setContentView(web)
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    inner class Bridge {
        @JavascriptInterface fun devices(): String {
            val arr = JSONArray()
            try {
                BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.forEach {
                    arr.put(JSONObject().put("name", it.name ?: it.address).put("address", it.address))
                }
            } catch (e: SecurityException) {}
            return arr.toString()
        }

        @JavascriptInterface fun connect(address: String, name: String) {
            Thread {
                var err = ""
                try {
                    disconnect()
                    val dev = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
                    val attempts = listOf<() -> BluetoothSocket>(
                        { dev.createRfcommSocketToServiceRecord(spp) },
                        { dev.createInsecureRfcommSocketToServiceRecord(spp) },
                        { dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                            .invoke(dev, 1) as BluetoothSocket }
                    )
                    for (a in attempts) {
                        var s: BluetoothSocket? = null
                        try {
                            s = a()
                            s.connect()
                            socket = s; out = s.outputStream
                            startReader(s)
                            js("onBt(true,${JSONObject.quote(name)})")
                            return@Thread
                        } catch (e: Exception) {
                            err = e.javaClass.simpleName + ": " + (e.message ?: "")
                            try { s?.close() } catch (x: Exception) {}
                            Thread.sleep(600)
                        }
                    }
                } catch (e: Exception) {
                    err = e.javaClass.simpleName + ": " + (e.message ?: "")
                }
                js("onBt(false,${JSONObject.quote("Error: $err")})")
            }.start()
        }

        // Lee (y descarta) lo que responde el ESP32, para que su buffer nunca se llene
        private fun startReader(s: BluetoothSocket) {
            Thread {
                val buf = ByteArray(256)
                try { while (true) { if (s.inputStream.read(buf) < 0) break } } catch (e: Exception) {}
                if (socket === s) { disconnect(); js("onBt(false,'Conexion perdida')") }
            }.start()
        }

        // Comando importante (parar, cambio de direccion): siempre se envia
        @JavascriptInterface fun send(text: String) = enqueue(text)
        // Repeticion periodica: si hay cola atrasada, se descarta para no acumular retraso
        @JavascriptInterface fun sendRep(text: String) { if (pending.get() < 3) enqueue(text) }

        private fun enqueue(text: String) {
            pending.incrementAndGet()
            io.execute {
                try { out?.write(text.toByteArray()); out?.flush() }
                catch (e: Exception) { if (out != null) { disconnect(); js("onBt(false,'Conexion perdida')") } }
                finally { pending.decrementAndGet() }
            }
        }

        @JavascriptInterface fun disconnect() {
            val s = socket
            socket = null; out = null
            try { s?.close() } catch (e: Exception) {}
        }
        @JavascriptInterface fun landscape(on: Boolean) = runOnUiThread {
            requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                   else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        @JavascriptInterface fun exit() = runOnUiThread { finish() }
    }

    override fun onPause() { super.onPause(); web.evaluateJavascript("releaseAll()", null) }
    @Deprecated("back")
    override fun onBackPressed() { web.evaluateJavascript("goBack()", null) }
    override fun onDestroy() { bridge.disconnect(); io.shutdown(); super.onDestroy() }
}
