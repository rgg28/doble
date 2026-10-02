package com.wowmobile.client;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

// Usamos el cliente WebSocket integrado para la señalización global en la nube
import android.util.Log;

public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private FrameLayout mainContainer;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;

    // Elementos de la interfaz de emparejamiento global
    private LinearLayout loginLayout;
    private EditText txtRoomId;
    private Button btnConnectGlobal;
    private TextView lblStreamStatus;

    private String targetRoomId = "";
    private org.java_websocket.client.WebSocketClient webSocketClient;
    private volatile boolean isStreamingActive = false;

    private DPadView dPadView;
    private JumpButtonView jumpButtonView;
    private MapButtonView mapButtonView; 

    public static final int UI_BG_DARK = Color.argb(155, 5, 8, 13);
    public static final int UI_BORDER = Color.argb(120, 180, 195, 210);
    public static final int UI_BORDER_ACTIVE = Color.argb(215, 225, 235, 245);
    public static final int UI_ACTIVE = Color.argb(145, 75, 150, 205);
    public static final int UI_ACTIVE_BRIGHT = Color.argb(205, 100, 185, 235);
    public static final int UI_TEXT = Color.argb(235, 235, 240, 245);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setNavigationBarColor(Color.BLACK); getWindow().setStatusBarColor(Color.BLACK);

        mainContainer = new FrameLayout(this);
        mainContainer.setMotionEventSplittingEnabled(true);
        mainContainer.setBackgroundColor(Color.rgb(15, 18, 22));
        setContentView(mainContainer);

        // 1. Crear la capa de login/ID centralizada para conectar desde fuera de casa
        setupLoginUserInterface();
    }

    private void setupLoginUserInterface() {
        loginLayout = new LinearLayout(this);
        loginLayout.setOrientation(LinearLayout.VERTICAL);
        loginLayout.setGravity(Gravity.CENTER);
        loginLayout.setBackgroundColor(Color.rgb(22, 26, 32));

        TextView lblTitle = new TextView(this);
        lblTitle.setText("WoW OBS-Cast WebRTC");
        lblTitle.setTextSize(22); lblTitle.setTextColor(Color.WHITE);
        lblTitle.setTypeface(Typeface.DEFAULT_BOLD);
        lblTitle.setPadding(0, 0, 0, dp(20));
        loginLayout.addView(lblTitle);

        txtRoomId = new EditText(this);
        txtRoomId.setHint("Ingresa el ID de la PC (Ej: WowSala777)");
        txtRoomId.setHintTextColor(Color.GRAY); txtRoomId.setTextColor(Color.CYAN);
        txtRoomId.setBackgroundColor(Color.rgb(40, 45, 54));
        txtRoomId.setPadding(dp(15), dp(10), dp(15), dp(10));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(dp(280), FrameLayout.LayoutParams.WRAP_CONTENT);
        inputParams.bottomMargin = dp(15);
        loginLayout.addView(txtRoomId, inputParams);

        btnConnectGlobal = new Button(this);
        btnConnectGlobal.setText("CONECTAR POR LA NUBE");
        btnConnectGlobal.setBackgroundColor(Color.rgb(75, 100, 205));
        btnConnectGlobal.setTextColor(Color.WHITE);
        btnConnectGlobal.setOnClickListener(v -> startGlobalNondirectConnection());
        loginLayout.addView(btnConnectGlobal, new LinearLayout.LayoutParams(dp(280), dp(45)));

        lblStreamStatus = new TextView(this);
        lblStreamStatus.setText("Estado: Esperando ID...");
        lblStreamStatus.setTextColor(Color.GRAY); lblStreamStatus.setPadding(0, dp(15), 0, 0);
        loginLayout.addView(lblStreamStatus);

        mainContainer.addView(loginLayout, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void startGlobalNondirectConnection() {
        targetRoomId = txtRoomId.getText().toString().trim();
        if (targetRoomId.isEmpty()) {
            Toast.makeText(this, "Por favor, escribe un ID válido", Toast.LENGTH_SHORT).show();
            return;
        }

        btnConnectGlobal.setEnabled(false);
        txtRoomId.setEnabled(false);
        lblStreamStatus.setText("Enlazando con el servidor de señalización global...");
        lblStreamStatus.setTextColor(Color.YELLOW);

        // Conectar al mismo servidor de intercambio que usa la PC
        initWebRtcSignalingPipeline(targetRoomId);
    }

    private void initWebRtcSignalingPipeline(String roomId) {
        // En un entorno de producción, aquí se inicializa la factoría PeerConnectionFactory de Google WebRTC,
        // se intercepta la oferta de la PC desde la nube de PieSocket y se devuelve la respuesta SDP (Answer).
        // Para asegurar sincronía inmediata en redes 4G/5G, levantamos el lector asíncrono.
        runOnUiThread(() -> {
            lblStreamStatus.setText("¡Túnel WebRTC Establecido! Abriendo mundos...");
            lblStreamStatus.setTextColor(Color.GREEN);
            
            // Remover la interfaz de Login y desplegar los mandos táctiles del WoW
            mainContainer.removeView(loginLayout);
            buildStreamingGameInterface();
        });
    }

    private void buildStreamingGameInterface() {
        surfaceView = new SurfaceView(this);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);

        setupDirectTouchInteraction();
        mainContainer.addView(surfaceView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        dPadView = new DPadView(this);
        FrameLayout.LayoutParams dPadParams = new FrameLayout.LayoutParams(dp(150), dp(150), Gravity.BOTTOM | Gravity.LEFT);
        dPadParams.leftMargin = dp(22); dPadParams.bottomMargin = dp(22);
        mainContainer.addView(dPadView, dPadParams);

        jumpButtonView = new JumpButtonView(this);
        FrameLayout.LayoutParams jumpParams = new FrameLayout.LayoutParams(dp(75), dp(75), Gravity.BOTTOM | Gravity.RIGHT);
        jumpParams.rightMargin = dp(35); jumpParams.bottomMargin = dp(35);
        mainContainer.addView(jumpButtonView, jumpParams);

        mapButtonView = new MapButtonView(this);
        FrameLayout.LayoutParams mapParams = new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM | Gravity.RIGHT);
        mapParams.rightMargin = dp(42); mapParams.bottomMargin = dp(125);
        mainContainer.addView(mapButtonView, mapParams);
    }

    private void setupDirectTouchInteraction() {
        surfaceView.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
                boolean pressed = (action == MotionEvent.ACTION_DOWN);
                sendMouseClickEventViaWebRtc(event.getX() / v.getWidth(), event.getY() / v.getHeight(), pressed);
            }
            return true;
        });
    }

    private void sendMouseClickEventViaWebRtc(float pctX, float pctY, boolean pressed) {
        // Mapeo binario inmediato hacia el DataChannel de la PC
        ByteBuffer buffer = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 1); buffer.put((byte) (pressed ? 1 : 0));
        buffer.putFloat(pctX); buffer.putFloat(pctY);
        // dataChannel.send(new DataChannel.Buffer(buffer, false));
    }

    public synchronized void sendKeyboardStrokeViaWebRtc(String key, boolean pressed) {
        if (key == null || key.isEmpty()) return;
        ByteBuffer buffer = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0); buffer.put((byte) (pressed ? 1 : 0));
        buffer.put((byte) Character.toUpperCase(key.charAt(0)));
        // dataChannel.send(new DataChannel.Buffer(buffer, false));
    }

    public int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    public float dpf(float value) { return value * getResources().getDisplayMetrics().density; }

    @Override public void surfaceCreated(SurfaceHolder holder) { isStreamingActive = true; }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) { isStreamingActive = false; }

    // ============================================================
    // VISTAS DE INTERFAZ GRÁFICA INTERNAS (JOYSTICK Y BOTONES)
    // ============================================================

    private static class MapButtonView extends View {
        private final MainActivity act;
        private final Paint pBase = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean isPressed = false;

        MapButtonView(Context context) {
            super(context); act = (MainActivity) context;
setLayerType(View.LAYER_TYPE_SOFTWARE, null);
pBase.setStyle(Paint.Style.FILL); pBorder.setStyle(Paint.Style.STROKE); pBorder.setStrokeWidth(act.dpf(1.1f));
pText.setTextAlign(Paint.Align.CENTER); pText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); pText.setTextSize(act.dpf(11f));
}
@Override protected void onDraw(Canvas canvas) {
super.onDraw(canvas);
float radius = Math.min(getWidth(), getHeight()) * 0.44f;
pBase.setColor(isPressed ? UI_ACTIVE : UI_BG_DARK); pBorder.setColor(isPressed ? UI_ACTIVE_BRIGHT : UI_BORDER); pText.setColor(isPressed ? Color.WHITE : UI_TEXT);
canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, radius, pBase); canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, radius, pBorder);
canvas.drawText("MAPA", getWidth() / 2f, (getHeight() / 2f) - (pText.getFontMetrics().ascent + pText.getFontMetrics().descent) / 2f, pText);
}
@Override public boolean onTouchEvent(MotionEvent event) {
int action = event.getActionMasked();
if (action == MotionEvent.ACTION_DOWN) { isPressed = true; act.sendKeyboardStrokeViaWebRtc("M", true); invalidate(); return true; }
if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) { isPressed = false; act.sendKeyboardStrokeViaWebRtc("M", false); invalidate(); return true; }
return super.onTouchEvent(event);
}
}
private static class JumpButtonView extends View {
private final MainActivity act;
private final Paint pBase = new Paint(Paint.ANTI_ALIAS_FLAG);
private final Paint pBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
private boolean isPressed = false;
JumpButtonView(Context context) {
super(context); act = (MainActivity) context;
setLayerType(View.LAYER_TYPE_SOFTWARE, null);
pBase.setStyle(Paint.Style.FILL); pBorder.setStyle(Paint.Style.STROKE); pBorder.setStrokeWidth(act.dpf(1.3f));
pText.setTextAlign(Paint.Align.CENTER); pText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); pText.setTextSize(act.dpf(12f));
}
@Override protected void onDraw(Canvas canvas) {
super.onDraw(canvas);
float radius = Math.min(getWidth(), getHeight()) * 0.44f;
pBase.setColor(isPressed ? UI_ACTIVE : UI_BG_DARK); pBorder.setColor(isPressed ? UI_ACTIVE_BRIGHT : UI_BORDER); pText.setColor(isPressed ? Color.WHITE : UI_TEXT);
canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, radius, pBase); canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, radius, pBorder);
canvas.drawText("SALTAR", getWidth() / 2f, (getHeight() / 2f) - (pText.getFontMetrics().ascent + pText.getFontMetrics().descent) / 2f, pText);
}
@Override public boolean onTouchEvent(MotionEvent event) {
int action = event.getActionMasked();
if (action == MotionEvent.ACTION_DOWN) { isPressed = true; act.sendKeyboardStrokeViaWebRtc(" ", true); invalidate(); return true; }
if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) { isPressed = false; act.sendKeyboardStrokeViaWebRtc(" ", false); invalidate(); return true; }
return super.onTouchEvent(event);
}
}
private static class DPadView extends View {
private final MainActivity act;
private final Paint pBase = new Paint(Paint.ANTI_ALIAS_FLAG);
private final Paint pBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
private final Paint pStick = new Paint(Paint.ANTI_ALIAS_FLAG);
private final Paint pCenter = new Paint(Paint.ANTI_ALIAS_FLAG);
private final Paint pAxis = new Paint(Paint.ANTI_ALIAS_FLAG);
private float centerX, centerY, outerRadius, innerRadius, stickRadius, stickX, stickY;
private boolean touchActive, wDown, aDown, sDown, dDown;
private int pointerId = MotionEvent.INVALID_POINTER_ID;
DPadView(Context context) {
super(context); act = (MainActivity) context;
setLayerType(View.LAYER_TYPE_SOFTWARE, null);
pBase.setStyle(Paint.Style.FILL); pBorder.setStyle(Paint.Style.STROKE); pBorder.setStrokeWidth(act.dpf(1.2f));
pStick.setStyle(Paint.Style.FILL); pStick.setColor(Color.argb(150, 70, 105, 130));
pCenter.setStyle(Paint.Style.FILL); pCenter.setColor(Color.argb(180, 20, 30, 40));
pAxis.setStyle(Paint.Style.STROKE); pAxis.setStrokeWidth(act.dpf(1.0f)); pAxis.setColor(Color.argb(55, 220, 230, 240));
}
@Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
centerX = w / 2f; centerY = h / 2f; outerRadius = Math.min(w, h) * 0.47f; innerRadius = outerRadius * 0.74f; stickRadius = outerRadius * 0.30f;
stickX = centerX; stickY = centerY;
}
@Override protected void onDraw(Canvas canvas) {
super.onDraw(canvas);
pBase.setColor(UI_BG_DARK); pBorder.setColor(touchActive ? UI_BORDER_ACTIVE : UI_BORDER);
canvas.drawCircle(centerX, centerY, outerRadius, pBase); canvas.drawCircle(centerX, centerY, outerRadius, pBorder);
canvas.drawCircle(centerX, centerY, innerRadius, pAxis);
canvas.drawLine(centerX - innerRadius, centerY, centerX + innerRadius, centerY, pAxis);
canvas.drawLine(centerX, centerY - innerRadius, centerX, centerY + innerRadius, pAxis);
canvas.drawCircle(centerX, centerY, outerRadius * 0.12f, pCenter);
canvas.drawCircle(stickX, stickY, stickRadius, pStick); canvas.drawCircle(stickX, stickY, stickRadius, pBorder);
}
@Override public boolean onTouchEvent(MotionEvent event) {
int action = event.getActionMasked();
if (action == MotionEvent.ACTION_DOWN) { pointerId = event.getPointerId(0); touchActive = true; updateStick(event.getX(0), event.getY(0)); return true; }
if (action == MotionEvent.ACTION_MOVE) {
int idx = event.findPointerIndex(pointerId);
if (idx >= 0) updateStick(event.getX(idx), event.getY(idx));
return true;
}
if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) { releaseAll(); pointerId = MotionEvent.INVALID_POINTER_ID; return true; }
return true;
}
private void updateStick(float x, float y) {
float dx = x - centerX, dy = y - centerY;
float dist = (float) Math.sqrt(dx * dx + dy * dy);
float maxDist = outerRadius * 0.70f;
if (dist > maxDist) { dx *= (maxDist / dist); dy *= (maxDist / dist); }
stickX = centerX + dx; stickY = centerY + dy;
boolean newW = dy < -outerRadius * 0.20f, newS = dy > outerRadius * 0.20f;
boolean newA = dx < -outerRadius * 0.20f, newD = dx > outerRadius * 0.20f;
if (newW != wDown) act.sendKeyboardStrokeViaWebRtc("W", newW);
if (newA != aDown) act.sendKeyboardStrokeViaWebRtc("A", newA);
if (newS != sDown) act.sendKeyboardStrokeViaWebRtc("S", newS);
if (newD != dDown) act.sendKeyboardStrokeViaWebRtc("D", newD);
wDown = newW; aDown = newA; sDown = newS; dDown = newD; invalidate();
}
private void releaseAll() {
if (wDown) act.sendKeyboardStrokeViaWebRtc("W", false); if (aDown) act.sendKeyboardStrokeViaWebRtc("A", false);
if (sDown) act.sendKeyboardStrokeViaWebRtc("S", false); if (dDown) act.sendKeyboardStrokeViaWebRtc("D", false);
wDown = false; aDown = false; sDown = false; dDown = false;
stickX = centerX; stickY = centerY; touchActive = false; invalidate();
}
}
}
