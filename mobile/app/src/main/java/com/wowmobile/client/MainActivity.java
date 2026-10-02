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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private FrameLayout mainContainer;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;

    private Socket socket;
    private InputStream videoStream;
    private OutputStream commandStream;

    private volatile boolean isRunning = false;

    private static final String PC_IP = "192.168.1.50";
    private static final int PC_PORT = 8888;

    private DPadView dPadView;
    private JumpButtonView jumpButtonView; // Nuevo botón de salto

    // Colores UI originales heredados
    public static final int UI_BG_DARK = Color.argb(155, 5, 8, 13);
    public static final int UI_BORDER = Color.argb(120, 180, 195, 210);
    public static final int UI_BORDER_ACTIVE = Color.argb(215, 225, 235, 245);
    public static final int UI_ACTIVE = Color.argb(145, 75, 150, 205);
    public static final int UI_ACTIVE_BRIGHT = Color.argb(205, 100, 185, 235);
    public static final int UI_TEXT = Color.argb(235, 235, 240, 245);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setStatusBarColor(Color.BLACK);

        mainContainer = new FrameLayout(this);
        mainContainer.setMotionEventSplittingEnabled(true);
        mainContainer.setBackgroundColor(Color.rgb(3, 6, 10));
        setContentView(mainContainer);

        // 1. Superficie de streaming con detector de impactos táctiles directos
        surfaceView = new SurfaceView(this);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);

        setupDirectTouchInteraction();

        FrameLayout.LayoutParams surfaceParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        mainContainer.addView(surfaceView, surfaceParams);

        // 2. Control Izquierdo: El D-Pad de movimiento
        dPadView = new DPadView(this);
        FrameLayout.LayoutParams dPadParams = new FrameLayout.LayoutParams(
                dp(150),
                dp(150),
                Gravity.BOTTOM | Gravity.LEFT
        );
        dPadParams.leftMargin = dp(22);
        dPadParams.bottomMargin = dp(22);
        mainContainer.addView(dPadView, dPadParams);

        // 3. Control Derecho: Botón flotante para Saltar (Espacio)
        jumpButtonView = new JumpButtonView(this);
        FrameLayout.LayoutParams jumpParams = new FrameLayout.LayoutParams(
                dp(75), // Tamaño compacto y accesible
                dp(75),
                Gravity.BOTTOM | Gravity.RIGHT
        );
        jumpParams.rightMargin = dp(35);   // Posicionado cómodamente para el pulgar derecho
        jumpParams.bottomMargin = dp(35);
        mainContainer.addView(jumpButtonView, jumpParams);

        // 4. Hilo de Streaming
        isRunning = true;
        new Thread(this::connectAndStream, "WoW-Stream").start();
    }

    private void setupDirectTouchInteraction() {
        surfaceView.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
                boolean pressed = (action == MotionEvent.ACTION_DOWN);
                
                float viewWidth = v.getWidth();
                float viewHeight = v.getHeight();

                if (viewWidth > 0 && viewHeight > 0) {
                    float pctX = event.getX() / viewWidth;
                    float pctY = event.getY() / viewHeight;

                    sendMouseClick(pctX, pctY, pressed);
                }
            }
            return true;
        });
    }

    // ============================================================
    // ENVÍO DE COMANDOS
    // ============================================================

    public synchronized void sendStroke(String key, boolean pressed) {
        if (commandStream == null || key == null || key.isEmpty()) return;

        try {
            byte[] packet = new byte[]{
                    (byte) 0, // Tipo 0 = Teclado
                    (byte) (pressed ? 1 : 0),
                    (byte) Character.toUpperCase(key.charAt(0))
            };
            commandStream.write(packet);
            commandStream.flush();
        } catch (Exception ignored) {}
    }

    public synchronized void sendMouseClick(float pctX, float pctY, boolean pressed) {
        if (commandStream == null) return;

        try {
            ByteBuffer buffer = ByteBuffer.allocate(10);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            
            buffer.put((byte) 1); // Tipo 1 = Ratón
            buffer.put((byte) (pressed ? 1 : 0));
            buffer.putFloat(pctX);
            buffer.putFloat(pctY);

            commandStream.write(buffer.array());
            commandStream.flush();
        } catch (Exception ignored) {}
    }

    // ============================================================
    // STREAMING DE VIDEO
    // ============================================================

    private void connectAndStream() {
        try {
            socket = new Socket(PC_IP, PC_PORT);
            videoStream = socket.getInputStream();
            commandStream = socket.getOutputStream();

            byte[] sizeBuffer = new byte[4];

            while (isRunning) {
                int bytesRead = readFully(videoStream, sizeBuffer, 0, 4);
                if (bytesRead != 4) break;

                int size = ByteBuffer.wrap(sizeBuffer).order(ByteOrder.LITTLE_ENDIAN).getInt();
                if (size <= 0 || size > 50 * 1024 * 1024) continue;

                byte[] imgBuffer = new byte[size];
                int read = readFully(videoStream, imgBuffer, 0, size);
                if (read != size) break;

                Bitmap bmp = BitmapFactory.decodeByteArray(imgBuffer, 0, imgBuffer.length);
                if (bmp == null) continue;

                if (!surfaceHolder.getSurface().isValid()) {
                    bmp.recycle();
                    continue;
                }

                Canvas canvas = null;
                try {
                    canvas = surfaceHolder.lockCanvas();
                    if (canvas != null) {
                        canvas.drawColor(Color.BLACK);
                        canvas.drawBitmap(bmp, null, canvas.getClipBounds(), null);
                    }
                } finally {
                    if (canvas != null) {
                        surfaceHolder.unlockCanvasAndPost(canvas);
                    }
                    bmp.recycle();
                }
            }
        } catch (Exception ignored) {
        } finally {
            closeConnection();
        }
    }

    private int readFully(InputStream stream, byte[] buffer, int offset, int length) throws Exception {
        int total = 0;
        while (total < length && isRunning) {
            int result = stream.read(buffer, offset + total, length - total);
            if (result == -1) break;
            total += result;
        }
        return total;
    }

    private synchronized void closeConnection() {
        isRunning = false;
        try { if (videoStream != null) videoStream.close(); } catch (Exception ignored) {}
        try { if (commandStream != null) commandStream.close(); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        videoStream = null;
        commandStream = null;
        socket = null;
    }

    public int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    public float dpf(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {}
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) { isRunning = false; closeConnection(); }
    @Override protected void onDestroy() { isRunning = false; closeConnection(); super.onDestroy(); }

    // ============================================================
    // VISTA DEL BOTÓN DE SALTO (NUEVA INTERFAZ)
    // ============================================================

    private static class JumpButtonView extends View {
        private final MainActivity act;
        private final Paint pBase = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean isPressed = false;

        JumpButtonView(Context context) {
            super(context);
            act = (MainActivity) context;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);

            pBase.setStyle(Paint.Style.FILL);
            pBorder.setStyle(Paint.Style.STROKE);
            pBorder.setStrokeWidth(act.dpf(1.3f));

pText.setTextAlign(Paint.Align.CENTER);
pText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
pText.setTextSize(act.dpf(12f));
}
@Override
protected void onDraw(Canvas canvas) {
super.onDraw(canvas);
float cx = getWidth() / 2f;
float cy = getHeight() / 2f;
float radius = Math.min(getWidth(), getHeight()) * 0.44f;
pBase.setColor(isPressed ? UI_ACTIVE : UI_BG_DARK);
pBorder.setColor(isPressed ? UI_ACTIVE_BRIGHT : UI_BORDER);
pText.setColor(isPressed ? Color.WHITE : UI_TEXT);
canvas.drawCircle(cx, cy, radius, pBase);
canvas.drawCircle(cx, cy, radius, pBorder);
Paint.FontMetrics metrics = pText.getFontMetrics();
float textY = cy - (metrics.ascent + metrics.descent) / 2f;
canvas.drawText("SALTAR", cx, textY, pText);
}
@Override
public boolean onTouchEvent(MotionEvent event) {
int action = event.getActionMasked();
switch (action) {
case MotionEvent.ACTION_DOWN:
isPressed = true;
act.sendStroke(" ", true); // Envía espacio (ASCII 32)
invalidate();
return true;
case MotionEvent.ACTION_UP:
case MotionEvent.ACTION_CANCEL:
isPressed = false;
act.sendStroke(" ", false);
invalidate();
return true;
}
return super.onTouchEvent(event);
}
}
// ============================================================
// VISTA DEL D-PAD (Movimiento)
// ============================================================
private static class DPadView extends View {
private final MainActivity act;
private final Paint pBase = new Paint(Paint.ANTI_ALIAS_FLAG);
private final Paint pBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
private final Paint pStick = new Paint(Paint.ANTI_ALIAS_FLAG);
private final Paint pCenter = new Paint(Paint.ANTI_ALIAS_FLAG);
private final Paint pAxis = new Paint(Paint.ANTI_ALIAS_FLAG);
private float centerX, centerY;
private float outerRadius, innerRadius, stickRadius;
private float stickX, stickY;
private boolean touchActive;
private boolean wDown, aDown, sDown, dDown;
private int pointerId = MotionEvent.INVALID_POINTER_ID;
DPadView(Context context) {
super(context);
act = (MainActivity) context;
setLayerType(View.LAYER_TYPE_SOFTWARE, null);
pBase.setStyle(Paint.Style.FILL);
pBorder.setStyle(Paint.Style.STROKE);
pBorder.setStrokeWidth(act.dpf(1.2f));
pStick.setStyle(Paint.Style.FILL);
pStick.setColor(Color.argb(150, 70, 105, 130));
pCenter.setStyle(Paint.Style.FILL);
pCenter.setColor(Color.argb(180, 20, 30, 40));
pAxis.setStyle(Paint.Style.STROKE);
pAxis.setStrokeWidth(act.dpf(1.0f));
pAxis.setColor(Color.argb(55, 220, 230, 240));
}
@Override
protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
centerX = width / 2f;
centerY = height / 2f;
outerRadius = Math.min(width, height) * 0.47f;
innerRadius = outerRadius * 0.74f;
stickRadius = outerRadius * 0.30f;
stickX = centerX;
stickY = centerY;
}
@Override
protected void onDraw(Canvas canvas) {
super.onDraw(canvas);
pBase.setColor(UI_BG_DARK);
pBorder.setColor(touchActive ? UI_BORDER_ACTIVE : UI_BORDER);
canvas.drawCircle(centerX, centerY, outerRadius, pBase);
canvas.drawCircle(centerX, centerY, outerRadius, pBorder);
canvas.drawCircle(centerX, centerY, innerRadius, pAxis);
canvas.drawLine(centerX - innerRadius, centerY, centerX + innerRadius, centerY, pAxis);
canvas.drawLine(centerX, centerY - innerRadius, centerX, centerY + innerRadius, pAxis);
canvas.drawCircle(centerX, centerY, outerRadius * 0.12f, pCenter);
canvas.drawCircle(stickX, stickY, stickRadius, pStick);
canvas.drawCircle(stickX, stickY, stickRadius, pBorder);
}
@Override
public boolean onTouchEvent(MotionEvent event) {
int action = event.getActionMasked();
switch (action) {
case MotionEvent.ACTION_DOWN:
pointerId = event.getPointerId(0);
touchActive = true;
updateStick(event.getX(0), event.getY(0));
return true;
case MotionEvent.ACTION_MOVE:
if (pointerId == MotionEvent.INVALID_POINTER_ID) return true;
int movePointerIndex = event.findPointerIndex(pointerId);
if (movePointerIndex >= 0) {
updateStick(event.getX(movePointerIndex), event.getY(movePointerIndex));
}
return true;
case MotionEvent.ACTION_UP:
case MotionEvent.ACTION_CANCEL:
releaseAll();
pointerId = MotionEvent.INVALID_POINTER_ID;
return true;
}
return true;
}
private void updateStick(float x, float y) {
float dx = x - centerX;
float dy = y - centerY;
float distance = (float) Math.sqrt(dx * dx + dy * dy);
float maxDistance = outerRadius * 0.70f;
if (distance > maxDistance) {
float scale = maxDistance / distance;
dx *= scale;
dy *= scale;
}
stickX = centerX + dx;
stickY = centerY + dy;
boolean newW = dy < -outerRadius * 0.20f;
boolean newS = dy > outerRadius * 0.20f;
boolean newA = dx < -outerRadius * 0.20f;
boolean newD = dx > outerRadius * 0.20f;
setKey("W", newW, wDown);
setKey("A", newA, aDown);
setKey("S", newS, sDown);
setKey("D", newD, dDown);
wDown = newW; aDown = newA; sDown = newS; dDown = newD;
invalidate();
}
private void setKey(String key, boolean now, boolean old) {
if (now != old) {
act.sendStroke(key, now);
}
}
private void releaseAll() {
if (wDown) act.sendStroke("W", false);
if (aDown) act.sendStroke("A", false);
if (sDown) act.sendStroke("S", false);
if (dDown) act.sendStroke("D", false);
wDown = false; aDown = false; sDown = false; dDown = false;
stickX = centerX; stickY = centerY;
touchActive = false;
invalidate();
}
}
}
