package com.example.magnificentb8

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ProgressDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.SharedPreferences
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.lang.ref.WeakReference
import java.util.UUID

@SuppressLint("MissingPermission")
class PIDTuneActivity : AppCompatActivity() {

    private var bluetoothGatt: BluetoothGatt? = null
    private lateinit var editTextKp_omegaView: EditText
    private lateinit var editTextKi_omegaView: EditText
    private lateinit var editTextKd_omegaView: EditText
    private lateinit var editTextKp_angleView: EditText
    private lateinit var editTextKi_angleView: EditText
    private lateinit var editTextKd_angleView: EditText

    private lateinit var sharedPreferences: SharedPreferences

    companion object {
        var mServiceUUID: UUID = UUID.fromString("1d87f922-9b01-4c92-b8ef-c76077e00369")
        var mControlCharacteristicUUID: UUID = UUID.fromString("b4d9ecc7-71dd-436b-89f2-39ff78519bb1")
        var mResponseCharacteristicUUID: UUID = UUID.fromString("135cba93-749a-4e78-9f22-8ff29fef8684")
        var mDescriptorUUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34fB")
        lateinit var mProgress: ProgressDialog
        lateinit var  mBluetoothAdapter: BluetoothAdapter
        var mIsConnected: Boolean = false
        lateinit var mAddress: String
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_pidtune)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        mAddress = intent.getStringExtra("Device_address").toString()
        ConnectToDevice(this).execute()
        val mbtn_disconnect = findViewById<Button>(R.id.btn_disconnect)
        mbtn_disconnect.setOnClickListener{
            if(mIsConnected){
                bluetoothGatt?.disconnect()
                handler.removeCallbacksAndMessages(null)
            }
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.fade_in, R.anim.fade_out) // Add fade-out animation
            finish()
        }

        /////////////////////////////////// Only for PID tune//////////////////
        editTextKp_omegaView = findViewById(R.id.et_Kp_omega)
        editTextKi_omegaView = findViewById(R.id.et_Ki_omega)
        editTextKd_omegaView = findViewById(R.id.et_Kd_omega)
        editTextKp_angleView = findViewById(R.id.et_Kp_angle)
        editTextKi_angleView = findViewById(R.id.et_Ki_angle)
        editTextKd_angleView = findViewById(R.id.et_Kd_angle)

        sharedPreferences = getSharedPreferences("PID_PREFS", Context.MODE_PRIVATE)
        restoreEditTextValues()

        val mbtn_PIDsend = findViewById<Button>(R.id.btn_PIDsend)
        mbtn_PIDsend.setOnClickListener{
            val editText_Kp_angleProg = editTextKp_angleView.text.toString().toDouble()
            val editText_Ki_angleProg = editTextKi_angleView.text.toString().toDouble()
            val editText_Kd_angleProg = editTextKd_angleView.text.toString().toDouble()
            val editText_Kp_omegaProg = editTextKp_omegaView.text.toString().toDouble()
            val editText_Ki_omegaProg = editTextKi_omegaView.text.toString().toDouble()
            val editText_Kd_omegaProg = editTextKd_omegaView.text.toString().toDouble()
            bleSend("$editText_Kp_angleProg, $editText_Ki_angleProg, $editText_Kd_angleProg, $editText_Kp_omegaProg, $editText_Ki_omegaProg, $editText_Kd_omegaProg")
            saveEditTextValues()
        }
    }

    private fun saveEditTextValues() {
        with(sharedPreferences.edit()) {
            putString("Kp_omega", editTextKp_omegaView.text.toString())
            putString("Ki_omega", editTextKi_omegaView.text.toString())
            putString("Kd_omega", editTextKd_omegaView.text.toString())
            putString("Kp_angle", editTextKp_angleView.text.toString())
            putString("Ki_angle", editTextKi_angleView.text.toString())
            putString("Kd_angle", editTextKd_angleView.text.toString())
            apply()
        }
    }

    private fun restoreEditTextValues() {
        editTextKp_omegaView.setText(sharedPreferences.getString("Kp_omega", ""))
        editTextKi_omegaView.setText(sharedPreferences.getString("Ki_omega", ""))
        editTextKd_omegaView.setText(sharedPreferences.getString("Kd_omega", ""))
        editTextKp_angleView.setText(sharedPreferences.getString("Kp_angle", ""))
        editTextKi_angleView.setText(sharedPreferences.getString("Ki_angle", ""))
        editTextKd_angleView.setText(sharedPreferences.getString("Kd_angle", ""))
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        if(mIsConnected){
            bluetoothGatt?.disconnect()
            handler.removeCallbacksAndMessages(null)
        }
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.fade_in, R.anim.fade_out) // Add fade-out animation
        finish()
    }

    private class ConnectToDevice(c: Context) : AsyncTask<Void, Void, String>(){

        private var connectSuccess: Boolean = true
        private val weakContext: WeakReference<Context> = WeakReference(c)
        private lateinit var bluetoothGatt: BluetoothGatt

        @Deprecated("Deprecated in Java")
        override fun onPreExecute() {
            super.onPreExecute()
            val context = weakContext.get()
            if (context != null) {
                mProgress = ProgressDialog.show(context, "Connecting...", "please wait")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun doInBackground(vararg p0: Void?): String {
            try {
                if(!mIsConnected){
                    val bluetoothManager = weakContext.get()?.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                    mBluetoothAdapter = bluetoothManager.adapter
                    val device: BluetoothDevice = mBluetoothAdapter.getRemoteDevice(mAddress)
                    bluetoothGatt = device.connectGatt(weakContext.get(), false, gattCallback)
                }
            } catch (e: Exception) {
                connectSuccess = false
                e.printStackTrace()
            }
            return null.toString()
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        @Deprecated("Deprecated in Java")
        override fun onPostExecute(result: String?) {
            super.onPostExecute(result)
            val context = weakContext.get()
            if (!connectSuccess) {
                Log.e("data", "couldn't connect")
                if (context != null) {
                    showToast(context, "couldn't connect")
                }
                (context as? Activity)?.overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.fade_in, R.anim.fade_out) // Add fade-out animation
                (context as? Activity)?.finish()
            } else {
                mIsConnected = true
            }
            mProgress.dismiss()
        }

        private val gattCallback = object : BluetoothGattCallback() {

            @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        mIsConnected = true
                        bluetoothGatt.discoverServices() // Start service discovery
                        Log.i("BLE", "Connected to GATT server.")
                        (weakContext.get() as? PIDTuneActivity)?.bluetoothGatt = gatt
                        gatt.requestMtu(250)
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        mIsConnected = false
                        Log.i("BLE", "Disconnected from GATT server.")
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            // Handle unexpected disconnection (server closed connection)
                            Log.w("BLE", "Unexpected disconnection! Status: $status")
                            (weakContext.get() as? PIDTuneActivity)?.runOnUiThread {
                                showToast(weakContext.get()!!, "Controller disconnected")
                                (weakContext.get() as? Activity)?.overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.fade_in, R.anim.fade_out)
                                (weakContext.get() as? Activity)?.finish() // Finish the activity
                            }
                        }
                        gatt.close()
                        (weakContext.get() as? PIDTuneActivity)?.bluetoothGatt = null
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d("BLE", "MTU changed to: $mtu")
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(mServiceUUID)
                    val responseCharacteristic = service?.getCharacteristic(
                        mResponseCharacteristicUUID)
                    if(responseCharacteristic != null){
                        // Enable notifications/indications
                        gatt.setCharacteristicNotification(responseCharacteristic, true)
                        // Set up the client controlCharacteristic configuration descriptor
                        val descriptor = responseCharacteristic.getDescriptor(mDescriptorUUID)
                        // Client responseCharacteristic Configuration Descriptor UUID
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE // Or ENABLE_INDICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == mResponseCharacteristicUUID) {
                    val receivedData = characteristic.value.decodeToString()
                    val robotControlContext = weakContext.get() as? PIDTuneActivity
                    robotControlContext?.runOnUiThread {
                        // robotControlContext.processReceivedData(receivedData)
                    }
                }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private fun bleSend(data: String){
        if(mIsConnected){
            Log.e("BLE", "Sending data: $data")
            val service = bluetoothGatt?.getService(mServiceUUID)
            val controlCharacteristic = service?.getCharacteristic(mControlCharacteristicUUID)
            if(controlCharacteristic != null) {
                controlCharacteristic.value = data.toByteArray()
                bluetoothGatt?.writeCharacteristic(controlCharacteristic)
            }
        }
    }
}