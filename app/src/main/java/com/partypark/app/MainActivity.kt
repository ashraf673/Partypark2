package com.partypark.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import org.webrtc.*
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private val prefs by lazy { getSharedPreferences("partypark", Context.MODE_PRIVATE) }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var eglBase: EglBase? = null

    private val pendingViewerCandidates = mutableListOf<IceCandidate>()
    private var remoteAnswerSet = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        auth = Firebase.auth

        requestBasicPermissions()

        val activateCodeBtn = findViewById<MaterialButton>(R.id.activate_code_btn)
        val nameInput = findViewById<TextInputEditText>(R.id.name_input)
        val doneBtn = findViewById<MaterialButton>(R.id.done_btn)
        val scannerContainer = findViewById<android.widget.FrameLayout>(R.id.scanner_container)

        val isActivated = prefs.getBoolean("activated", false)
        if (isActivated) {
            activateCodeBtn.text = "Activated"
            activateCodeBtn.isEnabled = false
            activateCodeBtn.alpha = 0.5f
        }

        activateCodeBtn.setOnClickListener {
            if (!prefs.getBoolean("activated", false)) {
                showActivationDialog()
            }
        }

        scannerContainer.setOnClickListener {
            if (!prefs.getBoolean("activated", false)) {
                Toast.makeText(this, "QR scanning coming soon. Use code for now.", Toast.LENGTH_SHORT).show()
            }
        }

        doneBtn.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Please enter your name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ownerUid = prefs.getString("ownerUid", null)
            if (ownerUid != null) {
                registerDevice(ownerUid, name)
                Toast.makeText(this, "Name saved — starting stream", Toast.LENGTH_SHORT).show()
                startCameraStream()
            } else {
                Toast.makeText(this, "Activate the app first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCameraStream() {
        try {
            val deviceId = prefs.getString("deviceId", null) ?: return
            val signalingRef = Firebase.database.reference.child("signaling").child(deviceId)

            signalingRef.removeValue()
            remoteAnswerSet = false
            pendingViewerCandidates.clear()

            // Initialize WebRTC
            val options = PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)

            eglBase = EglBase.create()

            val encoderFactory = DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglBase?.eglBaseContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            // Audio
            val audioConstraints = MediaConstraints()
            val audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
            localAudioTrack = peerConnectionFactory?.createAudioTrack("audio", audioSource)

            // Video
            videoCapturer = createCameraCapturer()
            if (videoCapturer == null) {
                Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show()
                return
            }

            val videoSource = peerConnectionFactory?.createVideoSource(false)
            val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)
            videoCapturer?.initialize(surfaceTextureHelper, this, videoSource?.capturerObserver)
            videoCapturer?.startCapture(640, 480, 30)
            localVideoTrack = peerConnectionFactory?.createVideoTrack("video", videoSource)

            // PeerConnection
            val iceServers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers)

            peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    signalingRef.child("senderCandidates").push().setValue(mapOf(
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

            // Listen for viewer ICE candidates
            signalingRef.child("viewerCandidates").addChildEventListener(object : ChildEventListener {
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
                override fun onCancelled(error: DatabaseError) {
                    Log.e("Partypark", "Viewer ICE listener failed", error.toException())
                }
            })

            // Create offer
            val constraints = MediaConstraints()
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            signalingRef.child("offer").setValue(mapOf(
                                "type" to sdp.type.canonicalForm(),
                                "sdp" to sdp.description
                            ))
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Streaming started", Toast.LENGTH_SHORT).show()
                            }
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

            // Listen for answer
            signalingRef.child("answer").addValueEventListener(object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
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
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            })

        } catch (e: Exception) {
            Log.e("Partypark", "Stream start failed", e)
            Toast.makeText(this, "Stream failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
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

    private fun showActivationDialog() {
        val input = EditText(this)
        input.hint = "Enter activation code"

        AlertDialog.Builder(this)
            .setTitle("Activate with Code")
            .setView(input)
            .setPositiveButton("Activate") { _, _ ->
                val code = input.text.toString().trim().uppercase()
                if (code.isNotEmpty()) {
                    verifyActivationCode(code)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun verifyActivationCode(code: String) {
        val db = Firebase.database.reference
        db.child("activationCodes").child(code).get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                val ownerUid = snapshot.child("uid").value as? String
                if (ownerUid != null) {
                    registerDevice(ownerUid, null)
                    prefs.edit()
                        .putBoolean("activated", true)
                        .putString("ownerUid", ownerUid)
                        .apply()

                    val activateCodeBtn = findViewById<MaterialButton>(R.id.activate_code_btn)
                    activateCodeBtn.text = "Activated"
                    activateCodeBtn.isEnabled = false
                    activateCodeBtn.alpha = 0.5f

                    Toast.makeText(this, "App activated successfully", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Invalid activation code", Toast.LENGTH_SHORT).show()
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Error: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerDevice(ownerUid: String, name: String?) {
        val deviceId = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }

        val model = Build.MODEL ?: "Unknown Device"
        val displayName = if (name != null) "$name • $model" else model

        val deviceData = mapOf(
            "name" to displayName,
            "online" to true,
            "lastSeen" to System.currentTimeMillis()
        )

        Firebase.database.reference
            .child("users")
            .child(ownerUid)
            .child("devices")
            .child(deviceId)
            .setValue(deviceData)
    }

    private fun requestBasicPermissions() {
        val permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
        } catch (e: Exception) {}
        peerConnection?.close()
        peerConnectionFactory?.dispose()
        eglBase?.release()
    }
}