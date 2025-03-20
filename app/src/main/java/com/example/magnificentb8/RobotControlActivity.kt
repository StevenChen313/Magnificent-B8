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
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bumptech.glide.Glide
import io.github.controlwear.virtual.joystick.android.JoystickView
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.lang.ref.WeakReference
import java.net.URI
import java.util.Locale
import java.util.UUID
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.widget.doOnTextChanged
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin


@SuppressLint("MissingPermission")
class RobotControlActivity : AppCompatActivity() {

    private var bluetoothGatt: BluetoothGatt? = null
    private var receivedText: TextView? = null
    private var mbtn_sendVoice: Button? = null
    private var mbtn_esp32record: Button? = null
    private var cameraWebView: WebView? = null
//    private lateinit var textToSpeech: TextToSpeech
    private var recognizedTextView: TextView? = null
    private var edittextcamIP: EditText? = null
    private var edittextaudIP: EditText? = null
    private var batteryMeter: eo.view.batterymeter.BatteryMeterView? = null
    private var batteryLevelText: TextView? = null
    private var temperatureText: TextView? = null
    private var humidityText: TextView? = null
    private var joystickView: JoystickView? = null

    // For Audio Recording
    private val SAMPLE_RATE = 32000
    private val BUFFER_SIZE = 1024
    private var ESP32_IP = "ws://192.168.21.78:12345"
    private var webSocketClient: WebSocketClient? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isAudioTransRunning = false
    private var isSTTListening = false
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechToTextButton: Button

    private val MAX_VOLTAGE = 11.0
    private val MIN_VOLTAGE = 0.0

    private var retryCount = 0
    private var maxRetries = 3

    companion object {
        var mServiceUUID: UUID = UUID.fromString("1d87f922-9b01-4c92-b8ef-c76077e00369")
        var mControlCharacteristicUUID: UUID = UUID.fromString("b4d9ecc7-71dd-436b-89f2-39ff78519bb1")
        var mResponseCharacteristicUUID: UUID = UUID.fromString("135cba93-749a-4e78-9f22-8ff29fef8684")
        var mBatteryCharacteristicUUID: UUID = UUID.fromString("95da8517-db0b-4530-8b50-8a1f054d8581")
        var mDescriptorUUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        lateinit var mProgress: ProgressDialog
        lateinit var  mBluetoothAdapter: BluetoothAdapter
        var mIsConnected: Boolean = false
        var mIsWebSocketConnected: Boolean = false
        lateinit var mAddress: String
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_robot_control)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val joystickBackground: ImageView = findViewById(R.id.joystickBackground)
        joystickView = findViewById(R.id.joystickView)

        // Load GIF using Glide
        Glide.with(this)
            .asGif()
            .load(R.drawable.joystick_background) // Replace with your GIF file name
            .into(joystickBackground)

        var prevAngle = -1
        var prevStrength = -1
        val handler = Handler(Looper.getMainLooper())
        var lastSentData: String? // Store last sent data for retry
        joystickView?.setOnMoveListener({ angle, strength ->
            var modifiedAngle = angle
            var modifiedStrength = strength
            if(modifiedAngle > 180){
                modifiedStrength = -modifiedStrength
            }
            if(modifiedAngle < 180) modifiedAngle = 90 - modifiedAngle
            else if(modifiedAngle <= 360) modifiedAngle -= 270
            else modifiedAngle = 0 // Some error occurred

            if(modifiedStrength == 0) modifiedAngle = 0

            // Only send if values have changed
            if (modifiedAngle != prevAngle || modifiedStrength != prevStrength) {
                lastSentData = "${modifiedStrength/5}, $modifiedAngle"
                retryCount = 0 // Reset retry count on new movement
                bleSend(lastSentData!!)
                prevAngle = modifiedAngle
                prevStrength = modifiedStrength
            }
        }, 100)



        receivedText = findViewById(R.id.received_text)
        batteryMeter = findViewById(R.id.batteryMeter)
        batteryLevelText = findViewById(R.id.tv_batteryLevel)
        temperatureText = findViewById(R.id.tv_temperatureLevel)
        humidityText = findViewById(R.id.tv_humidityLevel)

        batteryMeter?.setOnClickListener {
            val batteryCharacteristic = bluetoothGatt?.getService(mServiceUUID)
                ?.getCharacteristic(mBatteryCharacteristicUUID)

            if (batteryCharacteristic != null) {
                bluetoothGatt?.readCharacteristic(batteryCharacteristic)
            } else {
                Log.e("BLE", "❌ Cannot read, characteristic is null")
            }
        }

        mAddress = intent.getStringExtra("Device_address").toString()
        ConnectToDevice(this).execute()
        val mbtn_disconnect = findViewById<Button>(R.id.btn_disconnect)
        mbtn_disconnect.setOnClickListener{
            if(mIsConnected){
                bluetoothGatt?.disconnect()
                handler.removeCallbacksAndMessages(null)
            }
            webSocketClient?.close()
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.fade_in, R.anim.fade_out) // Add fade-out animation
            finish()
        }

        mbtn_sendVoice = findViewById(R.id.btn_sendVoice)
        mbtn_sendVoice?.setOnTouchListener { _, event ->
            if(mbtn_sendVoice?.isEnabled == false) return@setOnTouchListener true
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    mbtn_sendVoice?.setText("Phone Recording...")
                    if (!isAudioTransRunning && checkMicrophonePermission()) {
                        Log.d("AUDIO", "WebSocket: Starting voice transmission...")
                        try {
                            startWebSocketAudio()  // Start WebSocket-based transmission
                        }
                        catch (e: Exception){
                            Log.e("AUDIO", "WebSocket: Error starting voice transmission: ${e.message}")
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    mbtn_sendVoice?.setText("Send Phone Voice")
                    if (isAudioTransRunning) {
                        Log.d("AUDIO", "WebSocket: Stopping voice transmission...")
                        try {
                            stopWebSocketAudio()   // Stop WebSocket-based transmission
                        } catch (e: Exception) {
                            Log.e("AUDIO", "WebSocket: Error stopping voice transmission: ${e.message}")
                        }
                    }
                }
            }
            false
        }

        mbtn_esp32record = findViewById(R.id.btn_recordVoice)
        mbtn_esp32record?.setOnTouchListener { _, event ->
            if(mbtn_esp32record?.isEnabled == false) return@setOnTouchListener true

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    Log.d("BUTTON", "Recording Start (Android)")
                    webSocketClient?.send("START_RECORD")
                    mbtn_esp32record?.text = "Robot Recording..."
                }
                MotionEvent.ACTION_UP -> {
                    Log.d("BUTTON", "Recording Stop (Android)")
                    webSocketClient?.send("STOP_RECORD")
                    mbtn_esp32record?.text = "Receive Robot Voice"
                }
            }
            false
        }

        cameraWebView = findViewById(R.id.cameraWebView)
        cameraWebView?.settings?.javaScriptEnabled = true
        cameraWebView?.webViewClient = object : WebViewClient() {
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                view.loadUrl(url)
                return true
            }
        }

        edittextcamIP = findViewById(R.id.edittext_camIP)
        edittextaudIP = findViewById(R.id.edittext_audIP)
        cameraWebView?.loadUrl("http://" + edittextcamIP?.text.toString())

        edittextcamIP?.doOnTextChanged { text, _, _, _ ->
            cameraWebView?.loadUrl("http://" + text.toString())
        }
        edittextaudIP?.doOnTextChanged { text, _, _, _ ->
            ESP32_IP = "ws://" + text.toString() + ":12345"
            connectWebSocket()
        }

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

//        val py = Python.getInstance()
//        val pyModule = py.getModule("objdetect")
//        pyModule.callAttr("run1")

        recognizedTextView = findViewById(R.id.recognizedTextView)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("STT", "Ready for speech")
            }

            override fun onBeginningOfSpeech() {
                Log.d("STT", "Speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {} // Not used

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d("STT", "Speech ended")
                isSTTListening = false // Reset flag so we can listen again
                speechToTextButton.setBackgroundColor(getColor(R.color.gray))
                speechToTextButton.text = "Voice Control"
            }

            override fun onError(error: Int) {
                Log.e("STT", "Error: $error")
                isSTTListening = false
                speechToTextButton.setBackgroundColor(getColor(R.color.gray))
                speechToTextButton.text = "Voice Control"
                Toast.makeText(this@RobotControlActivity, "Did not catch that. Try again.", Toast.LENGTH_SHORT).show()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0].lowercase(Locale.getDefault()) // Convert to lowercase
                    recognizedTextView?.setText(recognizedText)
                    Log.d("STT", "Recognized: $recognizedText")

                    // Process voice command
                    processVoiceCommand(recognizedText)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechToTextButton = findViewById(R.id.btn_speech_to_text)
        speechToTextButton.setOnClickListener {
            startSpeechRecognition()
            speechToTextButton.setBackgroundColor(getColor(R.color.light_green))
            speechToTextButton.text = "Command Listening..."
        }
        // Connect to WebSocket server to ESP32-A1S
        ESP32_IP = "ws://" + edittextaudIP?.text.toString() + ":12345"
        connectWebSocket()
    }

    private fun startSpeechRecognition() {
        if (!isSTTListening) {
            isSTTListening = true
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false) // Ensure online mode

            speechRecognizer.startListening(intent) // Start listening in the background
        }
    }

    private fun processVoiceCommand(command: String) {
        when {
            command.contains("forward") -> {
                joystickView?.isAutoReCenterButton = false
                joystickView?.apply { setJoystickPosition(this, 90, 40) }
            }
            command.contains("backward") -> {
                joystickView?.isAutoReCenterButton = false
                joystickView?.apply { setJoystickPosition(this, 270, 40) }
            }
            command.contains("left") -> {
                joystickView?.isAutoReCenterButton = false
                joystickView?.apply { setJoystickPosition(this, 180, 40) }
            }
            command.contains("right") -> {
                joystickView?.isAutoReCenterButton = false
                joystickView?.apply { setJoystickPosition(this, 0, 40) }
            }
            command.contains("stop") -> {
                joystickView?.isAutoReCenterButton = false
                joystickView?.apply { setJoystickPosition(this, 0, 0) }
            }
        }
        joystickView?.isAutoReCenterButton = true
    }

    fun setJoystickPosition(joystickView: JoystickView, angle: Int, strength: Int) {
        if (joystickView.width == 0 || joystickView.height == 0) {
            return // Joystick is not yet measured, prevent crashes
        }

        val centerX = joystickView.width / 2f
        val centerY = joystickView.height / 2f
        val maxRadius = joystickView.width / 2f // Assuming square layout

        // Convert angle to radians
        val radianAngle = Math.toRadians(angle.toDouble())

        // Calculate new touch position based on strength percentage
        val xOffset = (-cos(radianAngle) * maxRadius * (strength / 100.0)).toFloat()
        val yOffset = (sin(radianAngle) * maxRadius * (strength / 100.0)).toFloat()

        val touchX = centerX + xOffset
        val touchY = centerY + yOffset

        val downTime = SystemClock.uptimeMillis()

        // Simulate user touching the joystick
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, touchX, touchY, 0)
        joystickView.dispatchTouchEvent(downEvent)

        // Simulate joystick movement
        val moveEvent = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, touchX, touchY, 0)
        joystickView.dispatchTouchEvent(moveEvent)

        // Optional: Simulate user releasing the joystick (auto-recenter)
        val upEvent = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, centerX, centerY, 0)
        joystickView.dispatchTouchEvent(upEvent)

        // Recycle MotionEvents
        downEvent.recycle()
        moveEvent.recycle()
        upEvent.recycle()
    }

    fun connectWebSocket() {
        webSocketClient = object : WebSocketClient(URI(ESP32_IP)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d("WebSocket", "Connected to ESP32 WebSocket server.")
                mbtn_sendVoice?.alpha = 1.0f
                mbtn_sendVoice?.setText("Send Phone Voice")
                mbtn_esp32record?.alpha = 1.0f
                mbtn_esp32record?.setText("Receive Robot Voice")
                mIsWebSocketConnected = true
                runOnUiThread {
                    mbtn_esp32record?.isEnabled = true
                    mbtn_sendVoice?.isEnabled = true
                }
            }

            override fun onMessage(message: String) {
                Log.d("WebSocket", "Received Audio Message (Base64 Length: ${message.length})")

                try {
                    val decodedAudio: ByteArray = Base64.decode(message, Base64.DEFAULT)
                    playReceivedAudio(decodedAudio)
                } catch (e: Exception) {
                    Log.e("WebSocket", "Error decoding audio: ${e.message}")
                }
            }

            override fun onMessage(bytes: ByteBuffer) {
                Log.d("WebSocket", "Received Binary Message (Size: ${bytes.remaining()} bytes)")
                val audioData = ByteArray(bytes.remaining())
                bytes.get(audioData)
                playReceivedAudio(audioData)
            }

            override fun onClose(code: Int, reason: String, remote: Boolean) {
                Log.e("WebSocket", "Disconnected: $reason. Trying to reconnect...")
                mIsWebSocketConnected = false
                reconnectWebSocket()
            }

            override fun onError(ex: Exception) {
                Log.e("WebSocket", "WebSocket Error: ${ex.message}")
                mIsWebSocketConnected = false
            }
        }
        webSocketClient?.connect()
    }

    fun reconnectWebSocket() {
        Thread {
            try {
                Thread.sleep(2000)  // Wait before reconnecting
                connectWebSocket()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }.start()
    }

    fun startWebSocketAudio() {
        if (isAudioTransRunning) return // Prevent multiple starts
        if (webSocketClient == null || !webSocketClient!!.isOpen) {
            Log.e("WebSocket", "WebSocket is NOT connected. Cannot start audio transmission!")
            Toast.makeText(this, "WebSocket is NOT connected. Cannot start audio transmission!", Toast.LENGTH_SHORT).show()
            mbtn_sendVoice?.setText("Waiting for connection...")
            mbtn_sendVoice?.alpha = 0.5f
            mbtn_esp32record?.setText("Waiting for connection...")
            mbtn_esp32record?.alpha = 0.5f
            runOnUiThread{
                mbtn_sendVoice?.isEnabled = false
                mbtn_esp32record?.isEnabled = false
            }
            return
        }

        isAudioTransRunning = true

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            BUFFER_SIZE
        )

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        for (device in devices) {
            if (device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                audioRecord?.preferredDevice = device
                Log.d("AUDIO", "Using built-in microphone for recording")
                break
            }
        }

        // Enable Acoustic Echo Canceler
        if (AcousticEchoCanceler.isAvailable()) {
            val aec = AcousticEchoCanceler.create(audioRecord!!.audioSessionId)
            if (aec != null) {
                aec.enabled = true
                Log.d("AUDIO", "Acoustic Echo Canceler Enabled!")
            }
        } else {
            Log.w("AUDIO", "Acoustic Echo Canceler NOT available on this device!")
        }

        // Enable Noise Suppressor (NS)
        if (NoiseSuppressor.isAvailable()) {
            val ns = NoiseSuppressor.create(audioRecord!!.audioSessionId)
            ns?.enabled = true
            Log.d("AUDIO", "Noise Suppressor Enabled!")
        }

        audioRecord?.startRecording()

        // Start recording and send audio data via WebSocket
        Thread {
            val buffer = ByteArray(BUFFER_SIZE)
            while (isAudioTransRunning) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    if (webSocketClient?.isOpen == true) {
                        webSocketClient?.send(buffer)
                    } else {
                        Log.e("WebSocket", "WebSocket disconnected! Stopping audio transmission...")
                        stopWebSocketAudio() // Stop audio if WebSocket is disconnected
                    }
                }
            }
        }.start()

        Log.d("AUDIO", "Audio transmission started.")
    }

    fun stopWebSocketAudio() {
        isAudioTransRunning = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    fun playReceivedAudio(audioData: ByteArray) {
        if (audioTrack == null) {
            audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                BUFFER_SIZE,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            audioTrack?.play()
        }

        // Swap bytes before playing (Big-Endian → Little-Endian)
        for (i in audioData.indices step 2) {
            val temp = audioData[i]
            audioData[i] = audioData[i + 1]
            audioData[i + 1] = temp
        }

        audioTrack?.write(audioData, 0, audioData.size)
    }


    private fun checkMicrophonePermission() : Boolean {
        if (ActivityCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(android.Manifest.permission.RECORD_AUDIO), 100)
            return false
        }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        if(mIsConnected){
            bluetoothGatt?.disconnect()
            handler.removeCallbacksAndMessages(null)
        }
        webSocketClient?.close()
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.fade_in, R.anim.fade_out) // Add fade-out animation
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocketClient?.close()
        speechRecognizer.destroy()
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
                        (weakContext.get() as? RobotControlActivity)?.bluetoothGatt = gatt
                        gatt.requestMtu(250)
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        mIsConnected = false
                        Log.i("BLE", "Disconnected from GATT server.")
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            // Handle unexpected disconnection (server closed connection)
                            Log.w("BLE", "Unexpected disconnection! Status: $status")
                            (weakContext.get() as? RobotControlActivity)?.runOnUiThread {
                                showToast(weakContext.get()!!, "Controller disconnected")
                                (weakContext.get() as? Activity)?.overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.fade_in, R.anim.fade_out)
                                (weakContext.get() as? Activity)?.finish() // Finish the activity
                            }
                        }
                        gatt.close()
                        (weakContext.get() as? RobotControlActivity)?.bluetoothGatt = null
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
                    val responseCharacteristic = service?.getCharacteristic(mResponseCharacteristicUUID)
                    if(responseCharacteristic != null){
                        gatt.setCharacteristicNotification(responseCharacteristic, true)
                        val resp_descriptor = responseCharacteristic.getDescriptor(mDescriptorUUID)
                        if (resp_descriptor != null) {
                            resp_descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(resp_descriptor)
                        }
                    }
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (descriptor.characteristic.uuid == mResponseCharacteristicUUID) {
                        Log.d("BLE", "✅ Response Characteristic Notify Enabled, Now Enabling Battery Notify...")

                        val batteryCharacteristic = gatt.getService(mServiceUUID)?.getCharacteristic(mBatteryCharacteristicUUID)
                        if (batteryCharacteristic != null) {
                            gatt.setCharacteristicNotification(batteryCharacteristic, true)
                            val batt_descriptor = batteryCharacteristic.getDescriptor(mDescriptorUUID)
                            if (batt_descriptor != null) {
                                batt_descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(batt_descriptor)
                            }
                        }
                    } else if (descriptor.characteristic.uuid == mBatteryCharacteristicUUID) {
                        Log.d("BLE", "✅ Battery Characteristic Notify Enabled")
                    }
                } else {
                    Log.e("BLE", "❌ Failed to write descriptor for ${descriptor.characteristic.uuid}")
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == mResponseCharacteristicUUID) {
                    val receivedData = characteristic.value.decodeToString()
                    val robotControlContext = weakContext.get() as? RobotControlActivity
                    robotControlContext?.runOnUiThread {
                        robotControlContext.processReceivedData(receivedData)
                    }
                }

                if (characteristic.uuid == mBatteryCharacteristicUUID) {
                    val battInfo = characteristic.value.decodeToString().split(", ")
                    val batteryLvl = battInfo[0]
                    val temperatureLvl = battInfo[1]
                    val humidityLvl = battInfo[2]
                    val robotControlContext = weakContext.get() as? RobotControlActivity
                    robotControlContext?.runOnUiThread {
                        robotControlContext.processBattInfo(batteryLvl, temperatureLvl, humidityLvl)
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == mBatteryCharacteristicUUID) {
                    val battInfo = characteristic.value.decodeToString().split(", ")
                    val batteryLvl = battInfo[0]
                    val temperatureLvl = battInfo[1]
                    val humidityLvl = battInfo[2]
                    val robotControlContext = weakContext.get() as? RobotControlActivity
                    robotControlContext?.runOnUiThread {
                        robotControlContext.processBattInfo(batteryLvl, temperatureLvl, humidityLvl)
                    }
                }
            }
        }
    }

    private fun processReceivedData(data: String) {
        receivedText?.text = data
    }

    private fun processBattInfo(batteryLvl: String, temperatureLvl: String, humidityLvl: String) {
        var batteryLevel = (batteryLvl.toFloat() - MIN_VOLTAGE) / (MAX_VOLTAGE - MIN_VOLTAGE) * 100
        if (batteryLevel < 0) batteryLevel = 0.0
        else if (batteryLevel > 100) batteryLevel = 100.0
        batteryLevelText?.text = String.format("%.0f", batteryLevel) + "%"
        temperatureText?.text = "Temperature: ${temperatureLvl}°C"
        humidityText?.text = "Humidity: ${humidityLvl}%"
        batteryMeter?.chargeLevel = batteryLevel.toInt()
    }

    private val handler = Handler(Looper.getMainLooper())
    // BLE Send Function with Retry Mechanism
    private fun bleSend(data: String) {
        if (!mIsConnected) return // Prevent sending if disconnected

        val service = bluetoothGatt?.getService(mServiceUUID)
        val controlCharacteristic = service?.getCharacteristic(mControlCharacteristicUUID)

        if (controlCharacteristic != null) {
            // Log.e("BLE", "Attempting to send: $data")

            controlCharacteristic.value = data.toByteArray()
            val success = bluetoothGatt?.writeCharacteristic(controlCharacteristic)

            if (success == false) {
                Log.e("BLE", "Write failed: Prior command not finished, retrying...")
                if (retryCount < maxRetries) {
                    retryCount++
                    handler.postDelayed({ bleSend(data) }, 200) // Retry after 200ms
                } else {
                    Log.e("BLE", "Max retry attempts reached. Dropping command: $data")
                }
            } else {
                retryCount = 0 // Reset retry count on success
            }
        }
    }
}