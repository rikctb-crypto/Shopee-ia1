package br.com.shopeeai.publisher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var statusText: TextView
    private lateinit var licenseText: TextView
    private lateinit var backendEdit: EditText
    private lateinit var tokenEdit: EditText
    private lateinit var autoSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val license = LicenseManager(this)
        license.ensureStarted()
        licenseText = findViewById(R.id.licenseText)
        licenseText.text = license.statusText()

        val prefs = getSharedPreferences("publisher", Context.MODE_PRIVATE)
        backendEdit = findViewById(R.id.backendEdit)
        tokenEdit = findViewById(R.id.tokenEdit)
        autoSwitch = findViewById(R.id.autoSwitch)
        statusText = findViewById(R.id.statusText)
        backendEdit.setText(prefs.getString("backend", "http://192.168.15.9:8000"))
        tokenEdit.setText(prefs.getString("token", ""))
        autoSwitch.isChecked = prefs.getBoolean("auto", false)

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            prefs.edit()
                .putString("backend", backendEdit.text.toString().trim().trimEnd('/'))
                .putString("token", tokenEdit.text.toString().trim())
                .apply()
            testConnection()
        }

        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        autoSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && !license.isValid()) {
                autoSwitch.isChecked = false
                Toast.makeText(this, license.statusText(), Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean("auto", checked).apply()
            statusText.text = if (checked) {
                "Status: automático ATIVADO. Deixe a Shopee logada."
            } else {
                "Status: automático pausado."
            }
        }

        findViewById<Button>(R.id.openShopeeButton).setOnClickListener { openShopee() }
        statusText.text = prefs.getString("last_status", "Status: aguardando configuração")
    }

    override fun onResume() {
        super.onResume()
        licenseText.text = LicenseManager(this).statusText()
        val prefs = getSharedPreferences("publisher", Context.MODE_PRIVATE)
        statusText.text = prefs.getString("last_status", statusText.text.toString())
    }

    private fun testConnection() {
        statusText.text = "Status: testando conexão..."
        io.execute {
            val result = try { BackendClient(this).testConnection() } catch (e: Exception) { "Erro: ${e.message}" }
            runOnUiThread { statusText.text = result }
        }
    }

    private fun openShopee() {
        val launch = packageManager.getLaunchIntentForPackage("com.shopee.br")
        if (launch == null) {
            Toast.makeText(this, "Shopee Brasil não encontrada no celular.", Toast.LENGTH_LONG).show()
        } else {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        }
    }
}
