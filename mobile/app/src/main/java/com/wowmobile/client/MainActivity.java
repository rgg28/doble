package com.wowmobile.client;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoTrack;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class MainActivity extends Activity implements PeerConnection.Observer {

    private static final String TAG = "WoWStream";

    /*
     * WoWStream escucha por defecto en TCP 8080.
     *
     * Ejemplo:
     *
     * PC IP:     192.168.1.50
     * Room ID:   ABC12345
     *
     * WebSocket:
     * ws://192.168.1.50:8080/ws?room=ABC12345&client=android-xxxx
     */
    private static final int SIGNALING_PORT = 8080;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private FrameLayout mainContainer;
    private SurfaceView surfaceView;

    private LinearLayout loginLayout;
    private EditText txtHost;
    private EditText txtRoomId;
    private Button btnConnect;
    private TextView lblStreamStatus;

    private DPadView dPadView;
    private JumpButtonView jumpButtonView;
    private MapButtonView mapButtonView;

    private WebSocketClient webSocketClient;

    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private DataChannel dataChannel;

    private EglBase eglBase;
    private SurfaceTextureHelper surfaceTextureHelper;

    private volatile boolean isConnected = false;
    private volatile boolean remoteDescriptionSet = false;

    private String targetRoomId = "";
    private String clientId = "";

    private final List<IceCandidate> pendingRemoteIceCandidates =
            Collections.synchronizedList(new ArrayList<>());

    private final List<IceCandidate> localIceCandidates =
            Collections.synchronizedList(new ArrayList<>());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setStatusBarColor(Color.BLACK);

        mainContainer = new FrameLayout(this);
        mainContainer.setBackgroundColor(Color.rgb(15, 18, 22));
        mainContainer.setMotionEventSplittingEnabled(true);

        setContentView(mainContainer);

        initializeWebRTC();
        setupLoginUserInterface();
    }

    // ============================================================
    // WEBRTC
    // ============================================================

    private void initializeWebRTC() {

        try {
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                            .builder(getApplicationContext())
                            .setEnableInternalTracer(false)
                            .createInitializationOptions()
            );

            eglBase = EglBase.create();

            VideoEncoderFactory encoderFactory =
                    new DefaultVideoEncoderFactory(
                            eglBase.getEglBaseContext(),
                            true,
                            true
                    );

            VideoDecoderFactory decoderFactory =
                    new DefaultVideoDecoderFactory(
                            eglBase.getEglBaseContext()
                    );

            peerConnectionFactory =
                    PeerConnectionFactory.builder()
                            .setVideoEncoderFactory(encoderFactory)
                            .setVideoDecoderFactory(decoderFactory)
                            .createPeerConnectionFactory();

        } catch (Exception e) {
            e.printStackTrace();

            Toast.makeText(
                    this,
                    "Error inicializando WebRTC: " + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void createPeerConnection() {

        if (peerConnectionFactory == null) {
            updateStatus("WebRTC no está inicializado", Color.RED);
            return;
        }

        if (peerConnection != null) {
            peerConnection.close();
            peerConnection.dispose();
            peerConnection = null;
        }

        List<PeerConnection.IceServer> iceServers =
                new ArrayList<>();

        /*
         * STUN público.
         *
         * Para LAN normalmente no hace falta.
         * Se mantiene para permitir negociación de ICE.
         */
        iceServers.add(
                PeerConnection.IceServer
                        .builder("stun:stun.l.google.com:19302")
                        .createIceServer()
        );

        PeerConnection.RTCConfiguration rtcConfig =
                new PeerConnection.RTCConfiguration(iceServers);

        rtcConfig.sdpSemantics =
                PeerConnection.SdpSemantics.UNIFIED_PLAN;

        rtcConfig.continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        peerConnection =
                peerConnectionFactory.createPeerConnection(
                        rtcConfig,
                        this
                );

        if (peerConnection == null) {
            updateStatus(
                    "No se pudo crear RTCPeerConnection",
                    Color.RED
            );
            return;
        }

        updateStatus(
                "RTCPeerConnection creada",
                Color.YELLOW
        );
    }

    // ============================================================
    // INTERFAZ LOGIN
    // ============================================================

    private void setupLoginUserInterface() {

        loginLayout = new LinearLayout(this);
        loginLayout.setOrientation(LinearLayout.VERTICAL);
        loginLayout.setGravity(Gravity.CENTER);
        loginLayout.setPadding(
                dp(25),
                dp(25),
                dp(25),
                dp(25)
        );
        loginLayout.setBackgroundColor(
                Color.rgb(22, 26, 32)
        );

        TextView title = new TextView(this);
        title.setText("WoWStream");
        title.setTextSize(26);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(
                android.graphics.Typeface.DEFAULT_BOLD
        );

        loginLayout.addView(
                title,
                new LinearLayout.LayoutParams(
                        dp(320),
                        dp(55)
                )
        );

        TextView subtitle = new TextView(this);
        subtitle.setText(
                "Streaming WebRTC desde tu PC"
        );
        subtitle.setTextSize(14);
        subtitle.setTextColor(Color.LTGRAY);
        subtitle.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams subtitleParams =
                new LinearLayout.LayoutParams(
                        dp(320),
                        dp(45)
                );

        loginLayout.addView(
                subtitle,
                subtitleParams
        );

        txtHost = new EditText(this);
        txtHost.setHint(
                "IP de la PC (ej. 192.168.1.50)"
        );
        txtHost.setHintTextColor(Color.GRAY);
        txtHost.setTextColor(Color.WHITE);
        txtHost.setSingleLine(true);
        txtHost.setPadding(
                dp(15),
                dp(10),
                dp(15),
                dp(10)
        );
        txtHost.setBackgroundColor(
                Color.rgb(40, 45, 54)
        );

        LinearLayout.LayoutParams hostParams =
                new LinearLayout.LayoutParams(
                        dp(320),
                        dp(52)
                );

        hostParams.bottomMargin = dp(12);

        loginLayout.addView(
                txtHost,
                hostParams
        );

        txtRoomId = new EditText(this);
        txtRoomId.setHint(
                "ID de sala (ej. ABC12345)"
        );
        txtRoomId.setHintTextColor(Color.GRAY);
        txtRoomId.setTextColor(Color.CYAN);
        txtRoomId.setSingleLine(true);
        txtRoomId.setPadding(
                dp(15),
                dp(10),
                dp(15),
                dp(10)
        );
        txtRoomId.setBackgroundColor(
                Color.rgb(40, 45, 54)
        );

        LinearLayout.LayoutParams roomParams =
                new LinearLayout.LayoutParams(
                        dp(320),
                        dp(52)
                );

        roomParams.bottomMargin = dp(15);

        loginLayout.addView(
                txtRoomId,
                roomParams
        );

        btnConnect = new Button(this);
        btnConnect.setText("CONECTAR A WOWSTREAM");
        btnConnect.setTextColor(Color.WHITE);
        btnConnect.setBackgroundColor(
                Color.rgb(55, 95, 180)
        );

        btnConnect.setOnClickListener(
                v -> startConnection()
        );

        loginLayout.addView(
                btnConnect,
                new LinearLayout.LayoutParams(
                        dp(320),
                        dp(52)
                )
        );

        lblStreamStatus = new TextView(this);
        lblStreamStatus.setText(
                "Estado: esperando conexión"
        );
        lblStreamStatus.setTextColor(Color.GRAY);
        lblStreamStatus.setGravity(Gravity.CENTER);
        lblStreamStatus.setPadding(
                0,
                dp(18),
                0,
                0
        );

        loginLayout.addView(
                lblStreamStatus,
                new LinearLayout.LayoutParams(
                        dp(320),
                        dp(55)
                )
        );

        mainContainer.addView(
                loginLayout,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );
    }

    // ============================================================
    // CONEXIÓN SIGNALING
    // ============================================================

    private void startConnection() {

        String host =
                txtHost.getText()
                        .toString()
                        .trim();

        String room =
                txtRoomId.getText()
                        .toString()
                        .trim();

        if (host.isEmpty()) {
            Toast.makeText(
                    this,
                    "Escribe la IP de la PC",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        if (room.isEmpty()) {
            Toast.makeText(
                    this,
                    "Escribe el ID de sala",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        if (host.startsWith("ws://")) {
            host = host.substring(5);
        }

        if (host.startsWith("http://")) {
            host = host.substring(7);
        }

        if (host.endsWith("/")) {
            host = host.substring(
                    0,
                    host.length() - 1
            );
        }

        targetRoomId = room;

        clientId =
                "android-" +
                UUID.randomUUID()
                        .toString()
                        .substring(0, 8);

        btnConnect.setEnabled(false);
        txtHost.setEnabled(false);
        txtRoomId.setEnabled(false);

        updateStatus(
                "Conectando con WoWStream...",
                Color.YELLOW
        );

        createPeerConnection();

        connectWebSocket(
                host,
                room
        );
    }

    private void connectWebSocket(
            String host,
            String room
    ) {

        try {

            String wsUrl =
                    "ws://" +
                    host +
                    ":" +
                    SIGNALING_PORT +
                    "/ws?room=" +
                    java.net.URLEncoder.encode(
                            room,
                            "UTF-8"
                    ) +
                    "&client=" +
                    java.net.URLEncoder.encode(
                            clientId,
                            "UTF-8"
                    );

            android.util.Log.d(
                    TAG,
                    "WebSocket: " + wsUrl
            );

            final java.net.URI uri =
                    java.net.URI.create(wsUrl);

            webSocketClient =
                    new WebSocketClient(uri) {

                        @Override
                        public void onOpen(
                                ServerHandshake handshake
                        ) {

                            runOnUiThread(() ->
                                    updateStatus(
                                            "Señalización conectada. Esperando oferta...",
                                            Color.YELLOW
                                    )
                            );
                        }

                        @Override
                        public void onMessage(
                                String message
                        ) {

                            handleSignalingMessage(
                                    message
                            );
                        }

                        @Override
                        public void onClose(
                                int code,
                                String reason,
                                boolean remote
                        ) {

                            runOnUiThread(() ->
                                    updateStatus(
                                            "Señalización cerrada: " +
                                            reason,
                                            Color.RED
                                    )
                            );
                        }

                        @Override
                        public void onError(
                                Exception ex
                        ) {

                            android.util.Log.e(
                                    TAG,
                                    "WebSocket error",
                                    ex
                            );

                            runOnUiThread(() ->
                                    updateStatus(
                                            "Error WebSocket: " +
                                            ex.getMessage(),
                                            Color.RED
                                    )
                            );
                        }
                    };

            webSocketClient.connect();

        } catch (Exception e) {

            android.util.Log.e(
                    TAG,
                    "No se pudo crear WebSocket",
                    e
            );

            updateStatus(
                    "Error: " + e.getMessage(),
                    Color.RED
            );
        }
    }

    // ============================================================
    // SIGNALING
    // ============================================================

    private void handleSignalingMessage(
            String message
    ) {

        try {

            JSONObject root =
                    new JSONObject(message);

            String type =
                    root.optString("type", "");

            android.util.Log.d(
                    TAG,
                    "SIGNAL: " + type
            );

            switch (type) {

                case "joined":

                    runOnUiThread(() ->
                            updateStatus(
                                    "Sala conectada. Esperando WoWStream...",
                                    Color.YELLOW
                            )
                    );

                    break;

                case "peer-joined":

                    runOnUiThread(() ->
                            updateStatus(
                                    "PC encontrada. Esperando oferta...",
                                    Color.YELLOW
                            )
                    );

                    break;

                case "offer":

                    handleOffer(root);

                    break;

                case "ice-candidate":

                    handleRemoteIceCandidate(root);

                    break;

                case "error":

                    final String error =
                            root.optString(
                                    "message",
                                    "Error de señalización"
                            );

                    runOnUiThread(() ->
                            updateStatus(
                                    error,
                                    Color.RED
                            )
                    );

                    break;

                default:

                    android.util.Log.d(
                            TAG,
                            "Mensaje ignorado: " + type
                    );

                    break;
            }

        } catch (Exception e) {

            android.util.Log.e(
                    TAG,
                    "Error procesando signaling",
                    e
            );
        }
    }

    private void handleOffer(
            JSONObject root
    ) {

        try {

            String encodedSdp =
                    root.optString(
                            "sdp",
                            ""
                    );

            if (encodedSdp.isEmpty()) {
                return;
            }

            byte[] decoded =
                    android.util.Base64.decode(
                            encodedSdp,
                            android.util.Base64.DEFAULT
                    );

            String sdp =
                    new String(
                            decoded,
                            java.nio.charset.StandardCharsets.UTF_8
                    );

            SessionDescription offer =
                    new SessionDescription(
                            SessionDescription.Type.OFFER,
                            sdp
                    );

            if (peerConnection == null) {
                createPeerConnection();
            }

            runOnUiThread(() ->
                    updateStatus(
                            "Oferta recibida. Creando respuesta...",
                            Color.YELLOW
                    )
            );

            peerConnection.setRemoteDescription(
                    new SimpleSdpObserver() {

                        @Override
                        public void onSetSuccess() {

                            remoteDescriptionSet = true;

                            flushPendingIceCandidates();

                            createAnswer();
                        }

                        @Override
                        public void onSetFailure(
                                String error
                        ) {

                            android.util.Log.e(
                                    TAG,
                                    "setRemoteDescription: " +
                                    error
                            );

                            runOnUiThread(() ->
                                    updateStatus(
                                            "Error SDP: " +
                                            error,
                                            Color.RED
                                    )
                            );
                        }
                    },
                    offer
            );

        } catch (Exception e) {

            android.util.Log.e(
                    TAG,
                    "Error procesando offer",
                    e
            );
        }
    }

    private void createAnswer() {

        MediaConstraints constraints =
                new MediaConstraints();

        constraints.mandatory.add(
                new MediaConstraints.KeyValuePair(
                        "OfferToReceiveAudio",
                        "false"
                )
        );

        constraints.mandatory.add(
                new MediaConstraints.KeyValuePair(
                        "OfferToReceiveVideo",
                        "true"
                )
        );

        peerConnection.createAnswer(
                new SimpleSdpObserver() {

                    @Override
                    public void onCreateSuccess(
                            SessionDescription answer
                    ) {

                        peerConnection.setLocalDescription(
                                new SimpleSdpObserver() {

                                    @Override
                                    public void onSetSuccess() {

                                        sendAnswer(
                                                answer
                                        );
                                    }

                                    @Override
                                    public void onSetFailure(
                                            String error
                                    ) {

                                        updateStatus(
                                                "Error local SDP: " +
                                                error,
                                                Color.RED
                                        );
                                    }
                                },
                                answer
                        );
                    }

                    @Override
                    public void onCreateFailure(
                            String error
                    ) {

                        updateStatus(
                                "Error creando Answer: " +
                                error,
                                Color.RED
                        );
                    }
                },
                constraints
        );
    }

    private void sendAnswer(
            SessionDescription answer
    ) {

        try {

            String encoded =
                    android.util.Base64.encodeToString(
                            answer.description.getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8
                            ),
                            android.util.Base64.NO_WRAP
                    );

            JSONObject json =
                    new JSONObject();

            json.put(
                    "type",
                    "answer"
            );

            json.put(
                    "room",
                    targetRoomId
            );

            json.put(
                    "sdp",
                    encoded
            );

            sendSignaling(
                    json.toString()
            );

            runOnUiThread(() ->
                    updateStatus(
                            "Answer enviado. Negociando WebRTC...",
                            Color.YELLOW
                    )
            );

        } catch (Exception e) {

            android.util.Log.e(
                    TAG,
                    "Error enviando answer",
                    e
            );
        }
    }

    private void handleRemoteIceCandidate(
            JSONObject root
    ) {

        try {

            String candidate =
                    root.optString(
                            "candidate",
                            ""
                    );

            String sdpMid =
                    root.optString(
                            "sdpMid",
                            ""
                    );

            int sdpMLineIndex =
                    root.optInt(
                            "sdpMLineIndex",
                            0
                    );

            if (candidate.isEmpty()) {
                return;
            }

            IceCandidate iceCandidate =
                    new IceCandidate(
                            sdpMid,
                            sdpMLineIndex,
                            candidate
                    );

            if (!remoteDescriptionSet ||
                    peerConnection == null) {

                pendingRemoteIceCandidates.add(
                        iceCandidate
                );

                return;
            }

            peerConnection.addIceCandidate(
                    iceCandidate
            );

        } catch (Exception e) {

            android.util.Log.e(
                    TAG,
                    "Error ICE remoto",
                    e
            );
        }
    }

    private void flushPendingIceCandidates() {

        if (peerConnection == null) {
            return;
        }

        synchronized (
                pendingRemoteIceCandidates
        ) {

            for (
                    IceCandidate candidate :
                    pendingRemoteIceCandidates
            ) {

                peerConnection.addIceCandidate(
                        candidate
                );
            }

            pendingRemoteIceCandidates.clear();
        }
    }

    private void sendIceCandidate(
            IceCandidate candidate
    ) {

        try {

            JSONObject json =
                    new JSONObject();

            json.put(
                    "type",
                    "ice-candidate"
            );

            json.put(
                    "room",
                    targetRoomId
            );

            json.put(
                    "candidate",
                    candidate.sdp
            );

            json.put(
                    "sdpMid",
                    candidate.sdpMid
            );

            json.put(
                    "sdpMLineIndex",
                    candidate.sdpMLineIndex
            );

            sendSignaling(
                    json.toString()
            );

        } catch (Exception e) {

            android.util.Log.e(
                    TAG,
                    "Error enviando ICE",
                    e
            );
        }
    }

    private void sendSignaling(
            String message
    ) {

        if (webSocketClient == null) {
            return;
        }

        if (!webSocketClient.isOpen()) {
            return;
        }

        webSocketClient.send(message);
    }

    // ============================================================
    // VIDEO / DATACHANNEL
    // ============================================================

    private void setupVideoTrack(
            VideoTrack videoTrack
    ) {

        runOnUiThread(() -> {

            if (surfaceView == null) {
                buildStreamingGameInterface();
            }

            if (eglBase == null) {
                eglBase = EglBase.create();
            }

            videoTrack.addSink(
                    new org.webrtc.SurfaceViewRenderer(
                            getApplicationContext()
                    )
            );
        });
    }

    private org.webrtc.SurfaceViewRenderer videoRenderer;

    private void attachVideoRenderer(
            VideoTrack videoTrack
    ) {

        runOnUiThread(() -> {

            if (videoRenderer != null) {

                videoTrack.removeSink(
                        videoRenderer
                );

                videoRenderer.release();
                videoRenderer = null;
            }

            if (mainContainer == null) {
                return;
            }

            videoRenderer =
                    new org.webrtc.SurfaceViewRenderer(
                            this
                    );

            videoRenderer.init(
                    eglBase.getEglBaseContext(),
                    null
            );

            videoRenderer.setEnableHardwareScaler(
                    true
            );

            videoRenderer.setMirror(false);

            FrameLayout.LayoutParams params =
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                    );

            mainContainer.addView(
                    videoRenderer,
                    0,
                    params
            );

            videoTrack.addSink(
                    videoRenderer
            );

            if (loginLayout != null) {
                mainContainer.removeView(
                        loginLayout
                );
            }

            buildControlsOnly();

            isConnected = true;

            updateStatus(
                    "Streaming conectado",
                    Color.GREEN
            );
        });
    }

    private void buildStreamingGameInterface() {

        if (videoRenderer == null) {

            videoRenderer =
                    new org.webrtc.SurfaceViewRenderer(
                            this
                    );

            videoRenderer.init(
                    eglBase.getEglBaseContext(),
                    null
            );

            videoRenderer.setEnableHardwareScaler(
                    true
            );

            FrameLayout.LayoutParams params =
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                    );

            mainContainer.addView(
                    videoRenderer,
                    0,
                    params
            );
        }

        buildControlsOnly();
    }

    private void buildControlsOnly() {

        if (dPadView == null) {

            dPadView =
                    new DPadView(this);

            FrameLayout.LayoutParams p =
                    new FrameLayout.LayoutParams(
                            dp(150),
                            dp(150),
                            Gravity.BOTTOM |
                            Gravity.LEFT
                    );

            p.leftMargin = dp(22);
            p.bottomMargin = dp(22);

            mainContainer.addView(
                    dPadView,
                    p
            );
        }

        if (jumpButtonView == null) {

            jumpButtonView =
                    new JumpButtonView(this);

            FrameLayout.LayoutParams p =
                    new FrameLayout.LayoutParams(
                            dp(75),
                            dp(75),
                            Gravity.BOTTOM |
                            Gravity.RIGHT
                    );

            p.rightMargin = dp(35);
            p.bottomMargin = dp(35);

            mainContainer.addView(
                    jumpButtonView,
                    p
            );
        }

        if (mapButtonView == null) {

            mapButtonView =
                    new MapButtonView(this);

            FrameLayout.LayoutParams p =
                    new FrameLayout.LayoutParams(
                            dp(60),
                            dp(60),
                            Gravity.BOTTOM |
                            Gravity.RIGHT
                    );

            p.rightMargin = dp(42);
            p.bottomMargin = dp(125);

            mainContainer.addView(
                    mapButtonView,
                    p
            );
        }
    }

    // ============================================================
    // INPUT
    // ============================================================

    private void sendMouseClickEventViaWebRtc(
            float pctX,
            float pctY,
            boolean pressed
    ) {

        ByteBuffer buffer =
                ByteBuffer.allocate(10)
                        .order(ByteOrder.LITTLE_ENDIAN);

        buffer.put((byte) 1);
        buffer.put((byte) (pressed ? 1 : 0));
        buffer.putFloat(
                Math.max(0f, Math.min(1f, pctX))
        );
        buffer.putFloat(
                Math.max(0f, Math.min(1f, pctY))
        );

        sendDataChannel(buffer);
    }

    public synchronized void sendKeyboardStrokeViaWebRtc(
            String key,
            boolean pressed
    ) {

        if (key == null ||
                key.isEmpty()) {
            return;
        }

        ByteBuffer buffer =
                ByteBuffer.allocate(3)
                        .order(ByteOrder.LITTLE_ENDIAN);

        buffer.put((byte) 0);
        buffer.put((byte) (pressed ? 1 : 0));

        char c =
                Character.toUpperCase(
                        key.charAt(0)
                );

        buffer.put(
                (byte) c
        );

        sendDataChannel(buffer);
    }

    private void sendDataChannel(
            ByteBuffer buffer
    ) {

        if (dataChannel == null) {
            return;
        }

        if (!dataChannel.state()
                .equals(
                        DataChannel.State.OPEN
                )) {
            return;
        }

        buffer.flip();

        dataChannel.send(
                new DataChannel.Buffer(
                        buffer,
                        false
                )
        );
    }

    // ============================================================
    // PEER CONNECTION CALLBACKS
    // ============================================================

    @Override
    public void onIceCandidate(
            IceCandidate candidate
    ) {

        localIceCandidates.add(
                candidate
        );

        sendIceCandidate(
                candidate
        );
    }

    @Override
    public void onTrack(
            RtpTransceiver transceiver
    ) {

        RtpReceiver receiver =
                transceiver.getReceiver();

        if (receiver == null) {
            return;
        }

        org.webrtc.MediaStreamTrack track =
                receiver.track();

        if (track instanceof VideoTrack) {

            VideoTrack videoTrack =
                    (VideoTrack) track;

            attachVideoRenderer(
                    videoTrack
            );
        }
    }

    @Override
    public void onAddStream(
            MediaStream stream
    ) {

        if (stream.videoTracks != null &&
                !stream.videoTracks.isEmpty()) {

            attachVideoRenderer(
                    stream.videoTracks.get(0)
            );
        }
    }

    @Override
    public void onDataChannel(
            DataChannel channel
    ) {

        android.util.Log.d(
                TAG,
                "DataChannel recibido: " +
                channel.label()
        );

        if ("wow_controls".equals(
                channel.label()
        )) {

            dataChannel = channel;

            dataChannel.registerObserver(
                    new DataChannel.Observer() {

                        @Override
                        public void onBufferedAmountChange(
                                long previousAmount
                        ) {
                        }

                        @Override
                        public void onStateChange() {

                            android.util.Log.d(
                                    TAG,
                                    "DataChannel state: " +
                                    dataChannel.state()
                            );
                        }

                        @Override
                        public void onMessage(
                                DataChannel.Buffer buffer
                        ) {
                        }
                    }
            );
        }
    }

    @Override
    public void onIceConnectionChange(
            PeerConnection.IceConnectionState state
    ) {

        android.util.Log.d(
                TAG,
                "ICE state: " + state
        );

        runOnUiThread(() -> {

            switch (state) {

                case CHECKING:
                    updateStatus(
                            "ICE: comprobando conexión...",
                            Color.YELLOW
                    );
                    break;

                case CONNECTED:
                case COMPLETED:
                    isConnected = true;

                    updateStatus(
                            "WebRTC conectado",
                            Color.GREEN
                    );
                    break;

                case DISCONNECTED:
                    updateStatus(
                            "WebRTC desconectado",
                            Color.YELLOW
                    );
                    break;

                case FAILED:
                    updateStatus(
                            "WebRTC ICE falló",
                            Color.RED
                    );
                    break;

                case CLOSED:
                    updateStatus(
                            "WebRTC cerrado",
                            Color.RED
                    );
                    break;

                default:
                    break;
            }
        });
    }

    @Override
    public void onConnectionChange(
            PeerConnection.PeerConnectionState state
    ) {

        android.util.Log.d(
                TAG,
                "PeerConnection: " + state
        );
    }

    @Override
    public void onSignalingChange(
            PeerConnection.SignalingState state
    ) {
    }

    @Override
    public void onIceConnectionReceivingChange(
            boolean receiving
    ) {
    }

    @Override
    public void onIceGatheringChange(
            PeerConnection.IceGatheringState state
    ) {
    }

    @Override
    public void onIceCandidatesRemoved(
            IceCandidate[] candidates
    ) {
    }

    @Override
    public void onRemoveStream(
            MediaStream stream
    ) {
    }

    @Override
    public void onRenegotiationNeeded() {
    }

    @Override
    public void onAddTrack(
            RtpReceiver receiver,
            MediaStream[] mediaStreams
    ) {
    }

    // ============================================================
    // UI STATUS
    // ============================================================

    private void updateStatus(
            String text,
            int color
    ) {

        runOnUiThread(() -> {

            if (lblStreamStatus != null) {

                lblStreamStatus.setText(
                        "Estado: " + text
                );

                lblStreamStatus.setTextColor(
                        color
                );
            }
        });
    }

    // ============================================================
    // DENSITY
    // ============================================================

    public int dp(float value) {

        return Math.round(
                value *
                getResources()
                        .getDisplayMetrics()
                        .density
        );
    }

    public float dpf(float value) {

        return value *
                getResources()
                        .getDisplayMetrics()
                        .density;
    }

    // ============================================================
    // CLEANUP
    // ============================================================

    @Override
    protected void onDestroy() {

        if (webSocketClient != null) {

            try {
                webSocketClient.close();
            } catch (Exception ignored) {
            }

            webSocketClient = null;
        }

        if (dataChannel != null) {

            try {
                dataChannel.close();
            } catch (Exception ignored) {
            }

            dataChannel.dispose();
            dataChannel = null;
        }

        if (peerConnection != null) {

            try {
                peerConnection.close();
            } catch (Exception ignored) {
            }

            peerConnection.dispose();
            peerConnection = null;
        }

        if (videoRenderer != null) {

            videoRenderer.release();
            videoRenderer = null;
        }

        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
        }

        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }

        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
            peerConnectionFactory = null;
        }

        super.onDestroy();
    }

    // ============================================================
    // SDP OBSERVER
    // ============================================================

    private static class SimpleSdpObserver
            implements PeerConnection.SdpObserver {

        @Override
        public void onCreateSuccess(
                SessionDescription sessionDescription
        ) {
        }

        @Override
        public void onSetSuccess() {
        }

        @Override
        public void onCreateFailure(
                String error
        ) {
        }

        @Override
        public void onSetFailure(
                String error
        ) {
        }
    }

    // ============================================================
    // MAP BUTTON
    // ============================================================

    private static class MapButtonView
            extends View {

        private final MainActivity act;

        private final Paint pBase =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint pBorder =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint pText =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private boolean isPressed;

        MapButtonView(Context context) {

            super(context);

            act =
                    (MainActivity) context;

            setLayerType(
                    View.LAYER_TYPE_SOFTWARE,
                    null
            );

            pBase.setStyle(
                    Paint.Style.FILL
            );

            pBorder.setStyle(
                    Paint.Style.STROKE
            );

            pBorder.setStrokeWidth(
                    act.dpf(1.1f)
            );

            pText.setTextAlign(
                    Paint.Align.CENTER
            );

            pText.setTypeface(
                    android.graphics.Typeface.create(
                            android.graphics.Typeface.DEFAULT,
                            android.graphics.Typeface.BOLD
                    )
            );

            pText.setTextSize(
                    act.dpf(11f)
            );
        }

        @Override
        protected void onDraw(
                Canvas canvas
        ) {

            super.onDraw(canvas);

            float radius =
                    Math.min(
                            getWidth(),
                            getHeight()
                    ) * 0.44f;

            pBase.setColor(
                    isPressed ?
                            UI_ACTIVE :
                            UI_BG_DARK
            );

            pBorder.setColor(
                    isPressed ?
                            UI_ACTIVE_BRIGHT :
                            UI_BORDER
            );

            pText.setColor(
                    isPressed ?
                            Color.WHITE :
                            UI_TEXT
            );

            canvas.drawCircle(
                    getWidth() / 2f,
                    getHeight() / 2f,
                    radius,
                    pBase
            );

            canvas.drawCircle(
                    getWidth() / 2f,
                    getHeight() / 2f,
                    radius,
                    pBorder
            );

            canvas.drawText(
                    "MAPA",
                    getWidth() / 2f,
                    (getHeight() / 2f) -
                            (
                                    pText.getFontMetrics().ascent +
                                    pText.getFontMetrics().descent
                            ) / 2f,
                    pText
            );
        }

        @Override
        public boolean onTouchEvent(
                MotionEvent event
        ) {

            int action =
                    event.getActionMasked();

            if (action ==
                    MotionEvent.ACTION_DOWN) {

                isPressed = true;

                act.sendKeyboardStrokeViaWebRtc(
                        "M",
                        true
                );

                invalidate();

                return true;
            }

            if (action ==
                    MotionEvent.ACTION_UP ||
                    action ==
                    MotionEvent.ACTION_CANCEL) {

                isPressed = false;

                act.sendKeyboardStrokeViaWebRtc(
                        "M",
                        false
                );

                invalidate();

                return true;
            }

            return true;
        }
    }

    // ============================================================
    // JUMP BUTTON
    // ============================================================

    private static class JumpButtonView
            extends View {

        private final MainActivity act;

        private final Paint pBase =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint pBorder =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint pText =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private boolean isPressed;

        JumpButtonView(Context context) {

            super(context);

            act =
                    (MainActivity) context;

            setLayerType(
                    View.LAYER_TYPE_SOFTWARE,
                    null
            );

            pBase.setStyle(
                    Paint.Style.FILL
            );

            pBorder.setStyle(
                    Paint.Style.STROKE
            );

            pBorder.setStrokeWidth(
                    act.dpf(1.3f)
            );

            pText.setTextAlign(
                    Paint.Align.CENTER
            );

            pText.setTypeface(
                    android.graphics.Typeface.create(
                            android.graphics.Typeface.DEFAULT,
                            android.graphics.Typeface.BOLD
                    )
            );

            pText.setTextSize(
                    act.dpf(12f)
            );
        }

        @Override
        protected void onDraw(
                Canvas canvas
        ) {

            super.onDraw(canvas);

            float radius =
                    Math.min(
                            getWidth(),
                            getHeight()
                    ) * 0.44f;

            pBase.setColor(
                    isPressed ?
                            UI_ACTIVE :
                            UI_BG_DARK
            );

            pBorder.setColor(
                    isPressed ?
                            UI_ACTIVE_BRIGHT :
                            UI_BORDER
            );

            pText.setColor(
                    isPressed ?
                            Color.WHITE :
                            UI_TEXT
            );

            canvas.drawCircle(
                    getWidth() / 2f,
                    getHeight() / 2f,
                    radius,
                    pBase
            );

            canvas.drawCircle(
                    getWidth() / 2f,
                    getHeight() / 2f,
                    radius,
                    pBorder
            );

            canvas.drawText(
                    "SALTAR",
                    getWidth() / 2f,
                    (getHeight() / 2f) -
                            (
                                    pText.getFontMetrics().ascent +
                                    pText.getFontMetrics().descent
                            ) / 2f,
                    pText
            );
        }

        @Override
        public boolean onTouchEvent(
                MotionEvent event
        ) {

            int action =
                    event.getActionMasked();

            if (action ==
                    MotionEvent.ACTION_DOWN) {

                isPressed = true;

                act.sendKeyboardStrokeViaWebRtc(
                        " ",
                        true
                );

                invalidate();

                return true;
            }

            if (action ==
                    MotionEvent.ACTION_UP ||
                    action ==
                    MotionEvent.ACTION_CANCEL) {

                isPressed = false;

                act.sendKeyboardStrokeViaWebRtc(
                        " ",
                        false
                );

                invalidate();

                return true;
            }

            return true;
        }
    }

    // ============================================================
    // DPAD
    // ============================================================

    private static class DPadView
            extends View {

        private final MainActivity act;

        private final Paint pBase =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint pBorder =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint pStick =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint pCenter =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint pAxis =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private float centerX;
        private float centerY;
        private float outerRadius;
        private float innerRadius;
        private float stickRadius;
        private float stickX;
        private float stickY;

        private boolean touchActive;

        private boolean wDown;
        private boolean aDown;
        private boolean sDown;
        private boolean dDown;

        private int pointerId =
                MotionEvent.INVALID_POINTER_ID;

        DPadView(Context context) {

            super(context);

            act =
                    (MainActivity) context;

            setLayerType(
                    View.LAYER_TYPE_SOFTWARE,
                    null
            );

            pBase.setStyle(
                    Paint.Style.FILL
            );

            pBorder.setStyle(
                    Paint.Style.STROKE
            );

            pBorder.setStrokeWidth(
                    act.dpf(1.2f)
            );

            pStick.setStyle(
                    Paint.Style.FILL
            );

            pStick.setColor(
                    Color.argb(
                            150,
                            70,
                            105,
                            130
                    )
            );

            pCenter.setStyle(
                    Paint.Style.FILL
            );

            pCenter.setColor(
                    Color.argb(
                            180,
                            20,
                            30,
                            40
                    )
            );

            pAxis.setStyle(
                    Paint.Style.STROKE
            );

            pAxis.setStrokeWidth(
                    act.dpf(1.0f)
            );

            pAxis.setColor(
                    Color.argb(
                            55,
                            220,
                            230,
                            240
                    )
            );
        }

        @Override
        protected void onSizeChanged(
                int w,
                int h,
                int oldw,
                int oldh
        ) {

            centerX = w / 2f;
            centerY = h / 2f;

            outerRadius =
                    Math.min(w, h) * 0.47f;

            innerRadius =
                    outerRadius * 0.74f;

            stickRadius =
                    outerRadius * 0.30f;

            stickX = centerX;
            stickY = centerY;
        }

        @Override
        protected void onDraw(
                Canvas canvas
        ) {

            super.onDraw(canvas);

            pBase.setColor(
                    UI_BG_DARK
            );

            pBorder.setColor(
                    touchActive ?
                            UI_BORDER_ACTIVE :
                            UI_BORDER
            );

            canvas.drawCircle(
                    centerX,
                    centerY,
                    outerRadius,
                    pBase
            );

            canvas.drawCircle(
                    centerX,
                    centerY,
                    outerRadius,
                    pBorder
            );

            canvas.drawCircle(
                    centerX,
                    centerY,
                    innerRadius,
                    pAxis
            );

            canvas.drawLine(
                    centerX - innerRadius,
                    centerY,
                    centerX + innerRadius,
                    centerY,
                    pAxis
            );

            canvas.drawLine(
                    centerX,
                    centerY - innerRadius,
                    centerX,
                    centerY + innerRadius,
                    pAxis
            );

            canvas.drawCircle(
                    centerX,
                    centerY,
                    outerRadius * 0.12f,
                    pCenter
            );

            canvas.drawCircle(
                    stickX,
                    stickY,
                    stickRadius,
                    pStick
            );

            canvas.drawCircle(
                    stickX,
                    stickY,
                    stickRadius,
                    pBorder
            );
        }

        @Override
        public boolean onTouchEvent(
                MotionEvent event
        ) {

            int action =
                    event.getActionMasked();

            if (action ==
                    MotionEvent.ACTION_DOWN) {

                pointerId =
                        event.getPointerId(0);

                touchActive = true;

                updateStick(
                        event.getX(0),
                        event.getY(0)
                );

                return true;
            }

            if (action ==
                    MotionEvent.ACTION_MOVE) {

                int index =
                        event.findPointerIndex(
                                pointerId
                        );

                if (index >= 0) {

                    updateStick(
                            event.getX(index),
                            event.getY(index)
                    );
                }

                return true;
            }

            if (action ==
                    MotionEvent.ACTION_UP ||
                    action ==
                    MotionEvent.ACTION_CANCEL) {

                releaseAll();

                pointerId =
                        MotionEvent.INVALID_POINTER_ID;

                return true;
            }

            return true;
        }

        private void updateStick(
                float x,
                float y
        ) {

            float dx =
                    x - centerX;

            float dy =
                    y - centerY;

            float dist =
                    (float) Math.sqrt(
                            dx * dx +
                            dy * dy
                    );

            float maxDist =
                    outerRadius * 0.70f;

            if (dist > maxDist &&
                    dist > 0f) {

                dx *= maxDist / dist;
                dy *= maxDist / dist;
            }

            stickX =
                    centerX + dx;

            stickY =
                    centerY + dy;

            boolean newW =
                    dy < -outerRadius * 0.20f;

            boolean newS =
                    dy > outerRadius * 0.20f;

            boolean newA =
                    dx < -outerRadius * 0.20f;

            boolean newD =
                    dx > outerRadius * 0.20f;

            if (newW != wDown) {
                act.sendKeyboardStrokeViaWebRtc(
                        "W",
                        newW
                );
            }

            if (newA != aDown) {
                act.sendKeyboardStrokeViaWebRtc(
                        "A",
                        newA
                );
            }

            if (newS != sDown) {
                act.sendKeyboardStrokeViaWebRtc(
                        "S",
                        newS
                );
            }

            if (newD != dDown) {
                act.sendKeyboardStrokeViaWebRtc(
                        "D",
                        newD
                );
            }

            wDown = newW;
            aDown = newA;
            sDown = newS;
            dDown = newD;

            invalidate();
        }

        private void releaseAll() {

            if (wDown) {
                act.sendKeyboardStrokeViaWebRtc(
                        "W",
                        false
                );
            }

            if (aDown) {
                act.sendKeyboardStrokeViaWebRtc(
                        "A",
                        false
                );
            }

            if (sDown) {
                act.sendKeyboardStrokeViaWebRtc(
                        "S",
                        false
                );
            }

            if (dDown) {
                act.sendKeyboardStrokeViaWebRtc(
                        "D",
                        false
                );
            }

            wDown = false;
            aDown = false;
            sDown = false;
            dDown = false;

            stickX = centerX;
            stickY = centerY;

            touchActive = false;

            invalidate();
        }
    }

    // ============================================================
    // COLORES
    // ============================================================

    public static final int UI_BG_DARK =
            Color.argb(
                    155,
                    5,
                    8,
                    13
            );

    public static final int UI_BORDER =
            Color.argb(
                    120,
                    180,
                    195,
                    210
            );

    public static final int UI_BORDER_ACTIVE =
            Color.argb(
                    215,
                    225,
                    235,
                    245
            );

    public static final int UI_ACTIVE =
            Color.argb(
                    145,
                    75,
                    150,
                    205
            );

    public static final int UI_ACTIVE_BRIGHT =
            Color.argb(
                    205,
                    100,
                    185,
                    235
            );

    public static final int UI_TEXT =
            Color.argb(
                    235,
                    235,
                    240,
                    245
            );
}
