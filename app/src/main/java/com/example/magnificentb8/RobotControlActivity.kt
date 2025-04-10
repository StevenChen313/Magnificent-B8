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
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Switch
import android.widget.Toast
import androidx.core.widget.doOnTextChanged
import com.google.android.material.materialswitch.MaterialSwitch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt


@SuppressLint("MissingPermission")
class RobotControlActivity : AppCompatActivity() {

    val handler = Handler(Looper.getMainLooper())
    private var bleRetryRunnable: Runnable? = null
    private var voiceControlRunnable: Runnable? = null

    private var bluetoothGatt: BluetoothGatt? = null
    private var receivedText: TextView? = null
    private var mbtn_sendVoice: Button? = null
    private var mbtn_esp32record: Button? = null
    private var cameraWebView: WebView? = null
    private var recognizedTextView: TextView? = null
    private var edittextcamIP: EditText? = null
    private var edittextaudIP: EditText? = null
    private var batteryMeter: eo.view.batterymeter.BatteryMeterView? = null
    private var batteryLevelText: TextView? = null
    private var temperatureText: TextView? = null
    private var humidityText: TextView? = null
    private var joystickView: JoystickView? = null
    private var switch_lineTrack: MaterialSwitch? = null
    private var switch_face: Switch? = null


    // For Audio Recording
    private val SAMPLE_RATE = 44100
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

    private var IRvoltage = 0.0
    private var backingMode = false
    private var tv_tooClose: TextView? = null

    // For camera
    private var FACE_RECO_IP = "192.168.4.2"
    private var facePollDebounce = true
    private var trackTurn = true
    private var moveRunnable: Runnable? = null
    private var upRunnable: Runnable? = null

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

        tv_tooClose = findViewById(R.id.tv_tooClose)
        switch_face = findViewById(R.id.switch_face)

        var prevAngle = -1
        var prevStrength = -1
        var lastSentData: String? // Store last sent data for retry
        joystickView?.setOnMoveListener({ angle, strength ->
            var modifiedAngle = angle
            var modifiedStrength = strength
            if(modifiedAngle > 180){
                modifiedStrength = -modifiedStrength
            }
            if(modifiedAngle < 180) modifiedAngle = 90 - modifiedAngle
            else if(modifiedAngle <= 360) modifiedAngle -= 270
            else throw Exception("Invalid angle")

            if(modifiedStrength == 0) modifiedAngle = 0

            //transform the strength to elliptical
            // modifiedStrength = (modifiedStrength.toDouble() * sqrt(1 - 0.55) / sqrt(1 - 0.55 * sin(angle.toDouble() * Math.PI / 180.0).pow(2.0))).toInt()

            if(IRvoltage > 1.48 && modifiedStrength > 0 && !(switch_lineTrack?.isChecked!!)) {
                modifiedStrength = -10
                modifiedAngle = 0
                backingMode =  true
            }

            if(backingMode) {
                if(IRvoltage < 0.8 || modifiedStrength <= 0) {
                    backingMode = false
                }
            }

            // Only send if values have changed
            if (modifiedAngle != prevAngle || modifiedStrength != prevStrength) {
                bleRetryRunnable?.let { handler.removeCallbacks(it) }
                lastSentData = "${(modifiedStrength * 0.45).toInt()}, $modifiedAngle"
                retryCount = 0
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
            bleSend("B")
        }

        mAddress = intent.getStringExtra("Device_address").toString()
        ConnectToDevice(this).execute()
        val mbtn_disconnect = findViewById<Button>(R.id.btn_disconnect)
        mbtn_disconnect.setOnClickListener{
            if(mIsConnected){
                bluetoothGatt?.disconnect()
                bleRetryRunnable?.let { handler.removeCallbacks(it) }
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
                    try {
                        webSocketClient?.send("START_RECORD")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    mbtn_esp32record?.text = "Robot Recording..."
                }
                MotionEvent.ACTION_UP -> {
                    Log.d("BUTTON", "Recording Stop (Android)")
                    try {
                        webSocketClient?.send("STOP_RECORD")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    mbtn_esp32record?.text = "Receive Robot Voice"
                }
            }
            false
        }

        val btn_servoUp = findViewById<Button>(R.id.btn_servoUp)
        val btn_servoDown = findViewById<Button>(R.id.btn_servoDown)
        btn_servoUp.setOnClickListener {
            bleSend("U")
        }
        btn_servoDown.setOnClickListener {
            bleSend("D")
        }

        edittextcamIP = findViewById(R.id.edittext_camIP)
        edittextaudIP = findViewById(R.id.edittext_audIP)
        FACE_RECO_IP = edittextcamIP?.text.toString()

        cameraWebView = findViewById(R.id.cameraWebView)
        cameraWebView?.settings?.javaScriptEnabled = true
        cameraWebView?.setInitialScale(165)
        loadVideoStream(edittextcamIP?.text.toString())
        cameraWebView?.webViewClient = object : WebViewClient() {
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                view.loadUrl(url)
                return true
            }
        }

        edittextcamIP?.doOnTextChanged { text, _, _, _ ->
            text?.toString()?.let {
                loadVideoStream(it)
                FACE_RECO_IP = it
            }
        }
        edittextaudIP?.doOnTextChanged { text, _, _, _ ->
            ESP32_IP = "ws://" + text.toString() + ":12345"
            connectWebSocket()
        }

        val btn_retryaudIP = findViewById<Button>(R.id.btn_retryaudIP)
        btn_retryaudIP.setOnClickListener {
            ESP32_IP = "ws://" + edittextaudIP?.text.toString() + ":12345"
            connectWebSocket()
        }

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
                    processMoveCommand(recognizedText, 30, 70)
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

        val facePoller = object : Runnable {
            override fun run() {
                try {
                    fetchHTTP("count")
                }
                catch (e: Exception){
                    Log.e("HTTP", "❌ Error fetching face count: ${e.message}")
                }
                handler.postDelayed(this, 500)
            }
        }

        val lineTrackPoller = object : Runnable {
            override fun run() {
                try {
                    fetchHTTP("direction")
                }
                catch (e: Exception){
                    Log.e("HTTP", "❌ Error fetching line direction: ${e.message}")
                }
                handler.postDelayed(this, 2000)
            }
        }

        switch_lineTrack = findViewById(R.id.switch_lineTrack)
        switch_lineTrack?.setOnCheckedChangeListener { _, isChecked ->
            if(isChecked) {
                bleSend("T")
                facePoller.let { handler.removeCallbacks(it) }
                handler.post(lineTrackPoller)
                // cameraWebView?.loadUrl("about:blank")
            }
            else {
                bleSend("N")
                lineTrackPoller.let { handler.removeCallbacks(it) }
                handler.post(facePoller)
                bleRetryRunnable?.let { handler.removeCallbacks(it) }
                voiceControlRunnable?.let { handler.removeCallbacks(it) }
                processMoveCommand("stop", 0, 0)
                // loadVideoStream(edittextcamIP?.text.toString())
                handler.postDelayed(
                    {runOnUiThread {
                    joystickView?.isAutoReCenterButton = true
                }}, 2000)
            }
        }

        handler.post(facePoller)
        bleSend("B")

        val btn_cruiseTest = findViewById<Button>(R.id.btn_cruiseTest)
        btn_cruiseTest.setOnClickListener {
            btn_cruiseTest.text = "Running"
            btn_cruiseTest.alpha = 0.5f
            runOnUiThread {
                btn_cruiseTest.isEnabled = false
            }

            val forwardRunnable = Runnable {
                processMoveCommand("forward", 30, 0)
            }
            val backwardRunnable = Runnable {
                processMoveCommand("backward", 30, 0)
            }
            val leftRunnable = Runnable {
                processMoveCommand("left", 0, 30)
            }
            val rightRunnable = Runnable {
                processMoveCommand("right", 0, 30)
            }
            val stopRunnable = Runnable {
                processMoveCommand("stop", 0, 0)
            }
            handler.postDelayed(forwardRunnable, 5000)
            handler.postDelayed(stopRunnable, 10000)
            handler.postDelayed(backwardRunnable, 15000)
            handler.postDelayed(stopRunnable, 20000)
//            handler.postDelayed(leftRunnable, 25000)
//            handler.postDelayed(stopRunnable, 25000 + 1000)
//            handler.postDelayed(leftRunnable, 30000 + 1000)
//            handler.postDelayed(stopRunnable, 30000 + 1000 + 1000)
//            handler.postDelayed(rightRunnable, 35000 + 1000 + 1000)
//            handler.postDelayed(stopRunnable, 35000 + 1000 + 1000 + 1000)
//            handler.postDelayed(rightRunnable, 40000 + 1000 + 1000 + 1000)
//            handler.postDelayed(stopRunnable, 40000 + 1000 + 1000 + 1000 + 1000)
            handler.postDelayed({
                for (r: Runnable in arrayOf(forwardRunnable, backwardRunnable, leftRunnable, rightRunnable, stopRunnable)) {
                    handler.removeCallbacks(r)
                }
                btn_cruiseTest.text = "Test"
                btn_cruiseTest.alpha = 1.0f
                runOnUiThread {
                    btn_cruiseTest.isEnabled = true
                } }, 20010)
        }
    }

    private fun loadVideoStream(ip: String) {
         cameraWebView?.loadUrl("http://$ip:5000/video_feed")
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

    private fun processMoveCommand(command: String, forwardstrength: Int, turnstrength: Int) {
        bleRetryRunnable?.let { handler.removeCallbacks(it) }
        voiceControlRunnable?.let { handler.removeCallbacks(it) }
        when {
            command.contains("forward") || command.contains("ford") -> {
                joystickView?.isAutoReCenterButton = false
                joystickView?.apply { setJoystickPosition(this, 0, 0) }
                voiceControlRunnable = Runnable {
                    joystickView?.apply {
                        setJoystickPosition(this, 90, forwardstrength, pressDuration = 2000)
                    }
                }
            }
            command.contains("backward") -> {
                joystickView?.isAutoReCenterButton = false
                joystickView?.apply { setJoystickPosition(this, 0, 0) }
                voiceControlRunnable = Runnable {
                    joystickView?.apply {
                        setJoystickPosition(this, 270, forwardstrength, pressDuration = 2000)
                    }
                }
            }
            command.contains("left") -> {
                joystickView?.isAutoReCenterButton = false
                joystickView?.apply { setJoystickPosition(this, 0, 0) }
                voiceControlRunnable = Runnable {
                    joystickView?.apply {
                        setJoystickPosition(this, 180, turnstrength, pressDuration = 2000)
                    }
                }
            }
            command.contains("right") -> {
                joystickView?.isAutoReCenterButton = false
                joystickView?.apply {setJoystickPosition(this, 0, 0) }
                voiceControlRunnable = Runnable {
                    joystickView?.apply {
                        setJoystickPosition(this, 0, turnstrength, pressDuration = 2000)
                    }
                }
            }
            command.contains("stop") -> {
                joystickView?.isAutoReCenterButton = false
                bleRetryRunnable?.let { handler.removeCallbacks(it) }
                voiceControlRunnable?.let { handler.removeCallbacks(it) }
                joystickView?.apply {
                    setJoystickPosition(this, 0, 0, duration = 0, steps = 1, pressDuration = 0)
                }
            }
            command.contains("TRACK") -> {
                var moveValue = command.replace("TRACK", "").toDouble()
                var moveStrength = 20
                var seeNothing = false
                if(moveValue > 100) {
                    seeNothing = true
                }
                joystickView?.isAutoReCenterButton = false
                if (!seeNothing) {
                    when {
                        moveValue > 25.0 -> {
                            moveStrength = 35
                            moveValue = 60.0
                            trackTurn = true
                        }
                        moveValue < -25.0 -> {
                            moveStrength = 35
                            moveValue = -60.0
                            trackTurn = true
                        }
                        else -> {
                            moveStrength = 20
                            moveValue = 0.0
                            trackTurn = false
                        }
                    }
                } else {
                    moveValue = -180.0
                    moveStrength = 25
                }
                voiceControlRunnable = Runnable {
                    joystickView?.apply {
                        if(trackTurn){
                            setJoystickPosition(this, 90 - moveValue.toInt(), moveStrength, steps = 3, pressDuration = 1100)
                        } else {
                            setJoystickPosition(this, 90 - moveValue.toInt(), moveStrength, pressDuration = 1000)
                        }
                    }
                }
            }
        }
        voiceControlRunnable?.let {
            handler.postDelayed(it, 10)
        }
        voiceControlRunnable = null
    }

    fun setJoystickPosition(joystickView: JoystickView, angle: Int, strength: Int, duration: Long = 100L, steps: Int = 5, pressDuration: Int = 1000) {
        if (joystickView.width == 0 || joystickView.height == 0) {
            return // Joystick is not yet measured
        }

        moveRunnable?.let { handler.removeCallbacks(it) }
        upRunnable?.let { handler.removeCallbacks(it) }

        val centerX = joystickView.width / 2f
        val centerY = joystickView.height / 2f
        val maxRadius = joystickView.width / 2f

        val radianAngle = Math.toRadians(angle.toDouble())

        val targetXOffset = (cos(radianAngle) * maxRadius * (strength / 100.0)).toFloat()
        val targetYOffset = (-sin(radianAngle) * maxRadius * (strength / 100.0)).toFloat()

        val downTime = SystemClock.uptimeMillis()

        // Touch down at center
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, centerX, centerY, 0)
        joystickView.dispatchTouchEvent(downEvent)
        downEvent.recycle()

        for (i in 1..steps) {
            val fraction = i / steps.toFloat()
            val interpolatedX = centerX + targetXOffset * fraction
            val interpolatedY = centerY + targetYOffset * fraction
            val eventTime = downTime + (duration * fraction).toLong()


            moveRunnable = Runnable {
                val moveEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, interpolatedX, interpolatedY, 0)
                joystickView.dispatchTouchEvent(moveEvent)
                moveEvent.recycle()
            }
            moveRunnable?.let{handler.postDelayed(it, (duration * fraction).toLong())}
        }

        // Release at target (or center, depending on behavior)
        upRunnable = Runnable {
            val upEvent = MotionEvent.obtain(downTime, downTime + duration, MotionEvent.ACTION_UP, centerX, centerY, 0)
            joystickView.dispatchTouchEvent(upEvent)
            upEvent.recycle()
            joystickView.apply {
                this.isAutoReCenterButton = true
            }
        }
        upRunnable?.let {handler.postDelayed(it, duration + pressDuration)}
    }


    private fun sendStandByToESP32() {
        if (!checkStoragePermission()) {
            Toast.makeText(this, "Storage permission required!", Toast.LENGTH_SHORT).show()
            return
        }
        val filePath = "/storage/emulated/0/ELEC391/plz_standby.wav" // Adjust if needed
        val file = File(filePath)

        if (!file.exists()) {
            Log.e("FILE", "Audio file not found!")
            Toast.makeText(this, "Audio file not found!", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val inputStream = FileInputStream(file)
            val buffer = ByteArray(BUFFER_SIZE) // Chunk size for sending

            while (inputStream.read(buffer) != -1) {
                if (webSocketClient?.isOpen == true) {
                    webSocketClient?.send(buffer) // Send chunks over WebSocket
                } else {
                    Log.e("WebSocket", "WebSocket disconnected! Stopping audio transmission...")
                    break
                }
            }

            inputStream.close()
            Log.d("FILE", "Finished sending file!")
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
                // reconnectWebSocket()
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
                Thread.sleep(5000)
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
                        try {
                            webSocketClient?.send(buffer)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
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

    private fun checkStoragePermission(): Boolean {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(android.Manifest.permission.READ_MEDIA_AUDIO), 101)
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
            bleRetryRunnable?.let { handler.removeCallbacks(it) }
        }
        webSocketClient?.close()
        handler.removeCallbacksAndMessages(null)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.fade_in, R.anim.fade_out) // Add fade-out animation
        finish()
    }

    override fun onDestroy() {
        webSocketClient?.close()
        speechRecognizer.destroy()
        cameraWebView?.loadUrl("about:blank")
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
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
        val dist = when {
            data.toDouble() > 2.54 -> "< 5cm"
            data.toDouble() in 1.48..2.54 -> "5cm ~ 10cm"
            data.toDouble() in 1.0..1.48 -> "10cm ~ 15cm"
            data.toDouble() in 0.8..1.0 -> "15cm ~ 20cm"
            data.toDouble() < 0.8 -> "> 20cm"
            else -> ""
        }
        if(data.toDouble() > 1.48) {
            tv_tooClose?.visibility = View.VISIBLE
        } else {
            tv_tooClose?.visibility = View.INVISIBLE
        }
        receivedText?.text = dist
        IRvoltage = data.toDouble()
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

    // BLE Send Function with Retry Mechanism
    fun bleSend(data: String) {
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
                    bleRetryRunnable = Runnable { bleSend(data) } // Retry after 200ms
                    handler.postDelayed( bleRetryRunnable!!, 200)
                } else {
                    Log.e("BLE", "Max retry attempts reached. Dropping command: $data")
                }
            } else {
                retryCount = 0 // Reset retry count on success
                bleRetryRunnable = null
            }
        }
    }

    fun fetchHTTP(mode: String) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("http://" + FACE_RECO_IP + ":5000/" + mode)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("HTTP", "Failed to fetch HTTP: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body()?.string()
                if (response.isSuccessful && responseBody != null) {
                    val json = JSONObject(responseBody)
                    when (mode) {
                        "count" -> {
                            val count = json.getInt("count")
                            if(count > 0 && facePollDebounce) {
                                Log.d("Face Recognition", "Face detected!")
                                if (switch_face?.isChecked == true){
                                    sendStandByToESP32()
                                }
                                facePollDebounce = false
                                handler.postDelayed({ facePollDebounce = true }, 5000)
                            }
                        }
                        "direction" -> {
                            val moveDirection = json.getString("direction")
                            Log.d("Line Following", "Direction: $moveDirection")
                            processMoveCommand("TRACK$moveDirection",-1,-1)
                        }
                    }

                } else {
                    Log.e("HTTP", "Response failed or body is null")
                }
            }
        })
    }
}