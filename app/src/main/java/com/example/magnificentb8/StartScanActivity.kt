package com.example.magnificentb8

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class StartScanActivity : AppCompatActivity() {

    private var mBluetoothAdapter: BluetoothAdapter? = null
    private val REQUEST_ENABLE_BLUETOOTH = 1
    private var mBluetoothLeScanner: BluetoothLeScanner? = null
    private val scanResults = mutableListOf<ScanResult>()
    private lateinit var scanResultAdapter: ArrayAdapter<String>
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var swipeCaption: TextView
    private lateinit var swchPIDTune: SwitchCompat

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_start_scan)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (mBluetoothAdapter == null) {
            showToast(applicationContext, "This device does not support bluetooth")
            return
        }

        if (!mBluetoothAdapter!!.isEnabled) {
            val enableBluetoothIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    1
                )
                return
            }
            startActivityForResult(enableBluetoothIntent, REQUEST_ENABLE_BLUETOOTH)
        }

        mBluetoothLeScanner = mBluetoothAdapter?.bluetoothLeScanner
        scanResultAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        swipeCaption = findViewById(R.id.swipe_caption)
        swchPIDTune = findViewById(R.id.swch_pidTune)

        val mlist_nearby_sensors = findViewById<ListView>(R.id.list_nearby_controller)
        mlist_nearby_sensors.adapter = scanResultAdapter
        mlist_nearby_sensors.setOnItemClickListener { _, _, position, _ ->
            mBluetoothLeScanner?.stopScan(scanCallback)
            swipeRefreshLayout.isRefreshing = false

            val selectedItem = scanResults[position]
            val device = selectedItem.device
            val address: String = device.address

            //////////////////////////////PID tune//////////////////////////////////
            val intent: Intent
            if(swchPIDTune.isChecked){
                intent = Intent(this, PIDTuneActivity::class.java)
            }
            else{
                intent = Intent(this, RobotControlActivity::class.java)
            }
            val transOptions =
                ActivityOptionsCompat.makeCustomAnimation(this, R.anim.fade_in, R.anim.fade_out)
            intent.putExtra("Device_address", address)
            startActivity(intent, transOptions.toBundle())
        }

        swipeRefreshLayout.setOnRefreshListener {
            swipeCaption.visibility = View.GONE
            startBleScan()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (resultCode == Activity.RESULT_OK) {
                if (mBluetoothAdapter!!.isEnabled) {
                    showToast(applicationContext, "Bluetooth has been enabled")
                } else {
                    showToast(applicationContext, "Bluetooth has been disabled")
                }
            } else if (resultCode == Activity.RESULT_CANCELED) {
                showToast(applicationContext, "Bluetooth enabling has been canceled")

            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startBleScan() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            ActivityCompat.requestPermissions(this, permissions, 1)
            return
        }
        mBluetoothLeScanner?.stopScan(scanCallback)
        scanResults.clear()
        scanResultAdapter.clear()
        mBluetoothLeScanner?.startScan(
            null, ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build(), scanCallback
        )

        // Stop the scan after 5 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            mBluetoothLeScanner?.stopScan(scanCallback)
            swipeRefreshLayout.isRefreshing = false
            if (scanResults.isEmpty()) {
                swipeCaption.visibility = View.VISIBLE
            }
        }, 5000)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name ?: "Anonymous"
            val isDeviceAlreadyInList =
                scanResults.any { it.device.address == result.device.address }
            if (!deviceName.contains("Anonymous") && !isDeviceAlreadyInList) {
                scanResults.add(result)
                scanResultAdapter.add(deviceName)
                scanResultAdapter.notifyDataSetChanged()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BleScan", "Scan failed with error code: $errorCode")
            showToast(applicationContext, "Scan failed with error code: $errorCode")
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @SuppressLint("MissingPermission")
    override fun onBackPressed() {
        super.onBackPressed()
        mBluetoothLeScanner?.stopScan(scanCallback)
        overrideActivityTransition(
            OVERRIDE_TRANSITION_CLOSE,
            R.anim.fade_in,
            R.anim.fade_out
        ) // Add fade-out animation
        finish()
    }
}

fun showToast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}