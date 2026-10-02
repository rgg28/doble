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
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private FrameLayout mainContainer;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;

    private Socket socket;
    private InputStream videoStream;
    private OutputStream commandStream;

    private volatile boolean isRunning = false;

    // ============================================================
    // MODIFICADORES
    // ============================================================

    private boolean _modL1Activo = false;
    private boolean _modR1Activo = false;

    // ============================================================
    // CONTROLES
    // ============================================================

    private DualRadialMenuView dualRadialMenu;
    private DPadView dPadView;
    private ActionButtonsView actionButtonsView;
    private TriggerButtonsView triggerButtonsView;

    // ============================================================
    // CONEXIÓN
    // ============================================================

    private static final String PC_IP = "192.168.1.50";
    private static final int PC_PORT = 8888;

    // ============================================================
    // PALETA MOBA
    // ============================================================

    public static final int UI_BG = Color.argb(105, 8, 12, 18);
    public static final int UI_BG_DARK = Color.argb(145, 5, 8, 13);

    public static final int UI_BORDER = Color.argb(105, 180, 195, 210);
    public static final int UI_BORDER_ACTIVE = Color.argb(190, 225, 235, 245);

    public static final int UI_TEXT = Color.argb(225, 235, 240, 245);
    public static final int UI_TEXT_DIM = Color.argb(150, 210, 220, 230);

    public static final int UI_ACTIVE = Color.argb(145, 75, 150, 205);
    public static final int UI_ACTIVE_BRIGHT = Color.argb(190, 100, 185, 235);

    // ============================================================
    // ACTIVITY
    // ============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        mainContainer = new FrameLayout(this);
        mainContainer.setBackgroundColor(Color.rgb(3, 6, 10));

        setContentView(mainContainer);

        // --------------------------------------------------------
        // VIDEO
        // --------------------------------------------------------

        surfaceView = new SurfaceView(this);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);

        mainContainer.addView(
                surfaceView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        // --------------------------------------------------------
        // CONTROLES
        // --------------------------------------------------------

        setupNativeGameControls();

        isRunning = true;

        new Thread(this::connectAndStream, "WoW-Stream").start();
    }

    // ============================================================
    // CONFIGURACIÓN DE CONTROLES
    // ============================================================

    private void setupNativeGameControls() {

        // --------------------------------------------------------
        // D-PAD
        // --------------------------------------------------------

        dPadView = new DPadView(this);

        FrameLayout.LayoutParams dPadParams =
                new FrameLayout.LayoutParams(
                        dp(142),
                        dp(142),
                        Gravity.BOTTOM | Gravity.LEFT
                );

        dPadParams.setMargins(
                dp(24),
                0,
                0,
                dp(24)
        );

        mainContainer.addView(dPadView, dPadParams);

        // --------------------------------------------------------
        // BOTONES DE ACCIÓN
        // --------------------------------------------------------

        actionButtonsView = new ActionButtonsView(this);

        FrameLayout.LayoutParams actionParams =
                new FrameLayout.LayoutParams(
                        dp(182),
                        dp(182),
                        Gravity.BOTTOM | Gravity.RIGHT
                );

        actionParams.setMargins(
                0,
                0,
                dp(24),
                dp(24)
        );

        mainContainer.addView(actionButtonsView, actionParams);

        // --------------------------------------------------------
        // L1 / R1
        // --------------------------------------------------------

        triggerButtonsView = new TriggerButtonsView(this);

        mainContainer.addView(
                triggerButtonsView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        // --------------------------------------------------------
        // MENÚ RADIAL
        // --------------------------------------------------------

        dualRadialMenu = new DualRadialMenuView(this);

        mainContainer.addView(
                dualRadialMenu,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );
    }

    // ============================================================
    // ENVÍO DE COMANDOS
    // ============================================================

    public synchronized void sendStroke(String key, boolean pressed) {

        if (commandStream == null || key == null || key.isEmpty()) {
            return;
        }

        try {

            commandStream.write(
                    new byte[]{
                            (byte) (pressed ? 1 : 0),
                            (byte) key.charAt(0)
                    }
            );

            commandStream.flush();

        } catch (Exception ignored) {
        }
    }

    // ============================================================
    // MODIFICADORES
    // ============================================================

    public void setModL1(boolean active) {
        _modL1Activo = active;

        if (actionButtonsView != null) {
            actionButtonsView.invalidate();
        }
    }

    public void setModR1(boolean active) {
        _modR1Activo = active;

        if (actionButtonsView != null) {
            actionButtonsView.invalidate();
        }
    }

    public boolean isModL1() {
        return _modL1Activo;
    }

    public boolean isModR1() {
        return _modR1Activo;
    }

    // ============================================================
    // STREAMING
    // ============================================================

    private void connectAndStream() {

        try {

            socket = new Socket(PC_IP, PC_PORT);

            videoStream = socket.getInputStream();
            commandStream = socket.getOutputStream();

            byte[] sizeBuffer = new byte[4];

            while (isRunning) {

                int bytesRead = readFully(
                        videoStream,
                        sizeBuffer,
                        0,
                        4
                );

                if (bytesRead != 4) {
                    break;
                }

                int size = ByteBuffer
                        .wrap(sizeBuffer)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .getInt();

                if (size <= 0 || size > 50 * 1024 * 1024) {
                    continue;
                }

                byte[] imgBuffer = new byte[size];

                int read = readFully(
                        videoStream,
                        imgBuffer,
                        0,
                        size
                );

                if (read != size) {
                    break;
                }

                final Bitmap bmp =
                        BitmapFactory.decodeByteArray(
                                imgBuffer,
                                0,
                                imgBuffer.length
                        );

                if (bmp == null) {
                    continue;
                }

                if (!surfaceHolder.getSurface().isValid()) {
                    bmp.recycle();
                    continue;
                }

                Canvas canvas = null;

                try {

                    canvas = surfaceHolder.lockCanvas();

                    if (canvas != null) {

                        canvas.drawColor(Color.BLACK);

                        canvas.drawBitmap(
                                bmp,
                                null,
                                canvas.getClipBounds(),
                                null
                        );
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

    private int readFully(
            InputStream stream,
            byte[] buffer,
            int offset,
            int length
    ) throws Exception {

        int total = 0;

        while (total < length && isRunning) {

            int result = stream.read(
                    buffer,
                    offset + total,
                    length - total
            );

            if (result == -1) {
                break;
            }

            total += result;
        }

        return total;
    }

    private synchronized void closeConnection() {

        isRunning = false;

        try {
            if (videoStream != null) {
                videoStream.close();
            }
        } catch (Exception ignored) {
        }

        try {
            if (commandStream != null) {
                commandStream.close();
            }
        } catch (Exception ignored) {
        }

        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }

        videoStream = null;
        commandStream = null;
        socket = null;
    }

    // ============================================================
    // DP
    // ============================================================

    public int dp(int value) {

        return (int) (
                value *
                getResources()
                        .getDisplayMetrics()
                        .density
                + 0.5f
        );
    }

    // ============================================================
    // SURFACE
    // ============================================================

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(
            SurfaceHolder holder,
            int format,
            int width,
            int height
    ) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {

        isRunning = false;
        closeConnection();
    }

    @Override
    protected void onDestroy() {

        isRunning = false;
        closeConnection();

        super.onDestroy();
    }
}


// ============================================================================
// D-PAD MOBA
// ============================================================================

class DPadView extends View {

    private final MainActivity act;

    private final Paint pBase = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pStick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCenter = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float cX;
    private float cY;

    private float sX;
    private float sY;

    private float rBase;
    private float rStick;

    private String lastX = "";
    private String lastY = "";

    public DPadView(Context context) {

        super(context);

        act = (MainActivity) context;

        setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        pBase.setColor(MainActivity.UI_BG);

        pBorder.setColor(MainActivity.UI_BORDER);
        pBorder.setStyle(Paint.Style.STROKE);
        pBorder.setStrokeWidth(act.dp(1.2f));

        pStick.setColor(Color.argb(115, 170, 190, 210));

        pCenter.setColor(Color.argb(75, 225, 235, 245));
    }

    @Override
    protected void onSizeChanged(
            int w,
            int h,
            int oldw,
            int oldh
    ) {

        super.onSizeChanged(w, h, oldw, oldh);

        cX = w / 2f;
        cY = h / 2f;

        sX = cX;
        sY = cY;

        rBase = Math.min(w, h) / 2.25f;
        rStick = Math.min(w, h) / 5.2f;
    }

    @Override
    protected void onDraw(Canvas canvas) {

        super.onDraw(canvas);

        // Base
        canvas.drawCircle(
                cX,
                cY,
                rBase,
                pBase
        );

        canvas.drawCircle(
                cX,
                cY,
                rBase,
                pBorder
        );

        // Anillo interno
        canvas.drawCircle(
                cX,
                cY,
                rBase * 0.55f,
                pBorder
        );

        // Ejes muy sutiles
        canvas.drawLine(
                cX - rBase * 0.72f,
                cY,
                cX + rBase * 0.72f,
                cY,
                pBorder
        );

        canvas.drawLine(
                cX,
                cY - rBase * 0.72f,
                cX,
                cY + rBase * 0.72f,
                pBorder
        );

        // Stick
        canvas.drawCircle(
                sX,
                sY,
                rStick,
                pStick
        );

        canvas.drawCircle(
                sX,
                sY,
                rStick,
                pBorder
        );

        // Centro
        canvas.drawCircle(
                sX,
                sY,
                rStick * 0.28f,
                pCenter
        );
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {

        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {

            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:

                float absX = x - cX;
                float absY = y - cY;

                float dist = (float) Math.sqrt(
                        absX * absX +
                        absY * absY
                );

                if (dist <= rBase) {

                    sX = x;
                    sY = y;

                } else if (dist > 0) {

                    sX = cX +
                            (absX / dist) *
                            rBase;

                    sY = cY +
                            (absY / dist) *
                            rBase;
                }

                invalidate();

                float xPct =
                        (sX - cX) / rBase;

                float yPct =
                        -(sY - cY) / rBase;

                String curX =
                        xPct > 0.35f
                                ? "D"
                                : xPct < -0.35f
                                ? "A"
                                : "";

                String curY =
                        yPct > 0.35f
                                ? "W"
                                : yPct < -0.35f
                                ? "S"
                                : "";

                if (!curX.equals(lastX)) {

                    if (!lastX.isEmpty()) {
                        act.sendStroke(lastX, false);
                    }

                    if (!curX.isEmpty()) {
                        act.sendStroke(curX, true);
                    }

                    lastX = curX;
                }

                if (!curY.equals(lastY)) {

                    if (!lastY.isEmpty()) {
                        act.sendStroke(lastY, false);
                    }

                    if (!curY.isEmpty()) {
                        act.sendStroke(curY, true);
                    }

                    lastY = curY;
                }

                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:

                sX = cX;
                sY = cY;

                invalidate();

                if (!lastX.isEmpty()) {
                    act.sendStroke(lastX, false);
                }

                if (!lastY.isEmpty()) {
                    act.sendStroke(lastY, false);
                }

                lastX = "";
                lastY = "";

                return true;
        }

        return false;
    }
}


// ============================================================================
// BOTONERA MOBA
// ============================================================================

class ActionButtonsView extends View {

    private final MainActivity act;

    private final Paint pButton =
            new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint pBorder =
            new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint pText =
            new Paint(Paint.ANTI_ALIAS_FLAG);

    private float[][] positions;

    private final String[] tags = {
            "1",
            "2",
            "3",
            "4"
    };

    private int pressedIdx = -1;

    private float radius;

    public ActionButtonsView(Context context) {

        super(context);

        act = (MainActivity) context;

        setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        pBorder.setColor(MainActivity.UI_BORDER);
        pBorder.setStyle(Paint.Style.STROKE);
        pBorder.setStrokeWidth(act.dp(1.2f));

        pText.setColor(MainActivity.UI_TEXT);
        pText.setTextSize(act.dp(14));
        pText.setTypeface(
                Typeface.create(
                        "sans-serif-medium",
                        Typeface.NORMAL
                )
        );
        pText.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onSizeChanged(
            int w,
            int h,
            int oldw,
            int oldh
    ) {

        super.onSizeChanged(
                w,
                h,
                oldw,
                oldh
        );

        radius = act.dp(27);

        positions = new float[4][2];

        positions[0] =
                new float[]{
                        radius + act.dp(4),
                        h / 2f
                };

        positions[1] =
                new float[]{
                        w / 2f,
                        radius + act.dp(4)
                };

        positions[2] =
                new float[]{
                        w - radius - act.dp(4),
                        h / 2f
                };

        positions[3] =
                new float[]{
                        w / 2f,
                        h - radius - act.dp(4)
                };
    }

    @Override
    protected void onDraw(Canvas canvas) {

        super.onDraw(canvas);

        for (int i = 0; i < 4; i++) {

            boolean pressed =
                    i == pressedIdx;

            if (pressed) {

                pButton.setColor(
                        MainActivity.UI_ACTIVE_BRIGHT
                );

                pText.setColor(Color.WHITE);

            } else {

                pButton.setColor(
                        Color.argb(
                                85,
                                10,
                                16,
                                24
                        )
                );

                pText.setColor(
                        MainActivity.UI_TEXT
                );
            }

            float x = positions[i][0];
            float y = positions[i][1];

            canvas.drawCircle(
                    x,
                    y,
                    radius,
                    pButton
            );

            pBorder.setColor(
                    pressed
                            ? MainActivity.UI_BORDER_ACTIVE
                            : MainActivity.UI_BORDER
            );

            canvas.drawCircle(
                    x,
                    y,
                    radius,
                    pBorder
            );

            String label;

            if (act.isModL1()) {

                label =
                        i == 0 ? "5" :
                        i == 1 ? "6" :
                        i == 2 ? "7" :
                        "8";

            } else if (act.isModR1()) {

                label =
                        i == 0 ? "9" :
                        i == 1 ? "0" :
                        i == 2 ? "F" :
                        "E";

            } else {

                label = tags[i];
            }

            canvas.drawText(
                    label,
                    x,
                    y - (pText.ascent() + pText.descent()) / 2f,
                    pText
            );
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {

        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {

            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:

                int oldIdx = pressedIdx;

                pressedIdx = -1;

                for (int i = 0; i < 4; i++) {

                    float dx =
                            x - positions[i][0];

                    float dy =
                            y - positions[i][1];

                    if (Math.sqrt(
                            dx * dx +
                            dy * dy
                    ) <= radius) {

                        pressedIdx = i;
                        break;
                    }
                }

                if (pressedIdx != oldIdx) {

                    if (oldIdx != -1) {
                        act.sendStroke(
                                getMapKey(oldIdx),
                                false
                        );
                    }

                    if (pressedIdx != -1) {
                        act.sendStroke(
                                getMapKey(pressedIdx),
                                true
                        );
                    }

                    invalidate();
                }

                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:

                if (pressedIdx != -1) {

                    act.sendStroke(
                            getMapKey(pressedIdx),
                            false
                    );
                }

                pressedIdx = -1;

                invalidate();

                return true;
        }

        return false;
    }

    private String getMapKey(int idx) {

        if (!act.isModL1() && !act.isModR1()) {

            return idx == 0 ? "1" :
                    idx == 1 ? "2" :
                    idx == 2 ? "3" :
                    "4";
        }

        if (act.isModL1()) {

            return idx == 0 ? "5" :
                    idx == 1 ? "6" :
                    idx == 2 ? "7" :
                    "8";
        }

        return idx == 0 ? "9" :
                idx == 1 ? "0" :
                idx == 2 ? "F" :
                "E";
    }
}


// ============================================================================
// L1 / R1
// ============================================================================

class TriggerButtonsView extends View {

    private final MainActivity act;

    private final Paint pBox =
            new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint pBorder =
            new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint pText =
            new Paint(Paint.ANTI_ALIAS_FLAG);

    private float l;
    private float t;
    private float r;
    private float b;

    private float rL;
    private float rT;
    private float rR;
    private float rB;

    private boolean l1Click = false;
    private boolean r1Click = false;

    public TriggerButtonsView(Context context) {

        super(context);

        act = (MainActivity) context;

        pBorder.setStyle(Paint.Style.STROKE);
        pBorder.setStrokeWidth(act.dp(1.2f));

        pText.setColor(MainActivity.UI_TEXT);
        pText.setTextSize(act.dp(13));
        pText.setTypeface(
                Typeface.create(
                        "sans-serif-medium",
                        Typeface.NORMAL
                )
        );
        pText.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onSizeChanged(
            int w,
            int h,
            int oldw,
            int oldh
    ) {

        super.onSizeChanged(
                w,
                h,
                oldw,
                oldh
        );

        float width = act.dp(72);
        float height = act.dp(34);

        l = act.dp(18);
        t = act.dp(16);

        r = l + width;
        b = t + height;

        rR = w - act.dp(18);
        rT = act.dp(16);

        rL = rR - width;
        rB = rT + height;
    }

    @Override
    protected void onDraw(Canvas canvas) {

        super.onDraw(canvas);

        drawTrigger(
                canvas,
                l,
                t,
                r,
                b,
                l1Click,
                "L1"
        );

        drawTrigger(
                canvas,
                rL,
                rT,
                rR,
                rB,
                r1Click,
                "R1"
        );
    }

    private void drawTrigger(
            Canvas canvas,
            float left,
            float top,
            float right,
            float bottom,
            boolean active,
            String text
    ) {

        pBox.setColor(
                active
                        ? Color.argb(145, 75, 150, 205)
                        : Color.argb(55, 10, 16, 24)
        );

        pBorder.setColor(
                active
                        ? MainActivity.UI_BORDER_ACTIVE
                        : MainActivity.UI_BORDER
        );

        canvas.drawRoundRect(
                left,
                top,
                right,
                bottom,
                act.dp(17),
                act.dp(17),
                pBox
        );

        canvas.drawRoundRect(
                left,
                top,
                right,
                bottom,
                act.dp(17),
                act.dp(17),
                pBorder
        );

        pText.setColor(
                active
                        ? Color.WHITE
                        : MainActivity.UI_TEXT_DIM
        );

        canvas.drawText(
                text,
                (left + right) / 2f,
                (top + bottom) / 2f
                        - (pText.ascent() + pText.descent()) / 2f,
                pText
        );
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {

        float x = event.getX();
        float y = event.getY();

        boolean insideL1 =
                x >= l &&
                x <= r &&
                y >= t &&
                y <= b;

        boolean insideR1 =
                x >= rL &&
                x <= rR &&
                y >= rT &&
                y <= rB;

        switch (event.getActionMasked()) {

            case MotionEvent.ACTION_DOWN:

                if (!insideL1 && !insideR1) {
                    return false;
                }

                l1Click = insideL1;
                r1Click = insideR1;

                act.setModL1(l1Click);
                act.setModR1(r1Click);

                invalidate();

                return true;

            case MotionEvent.ACTION_MOVE:

                l1Click = insideL1;
                r1Click = insideR1;

                act.setModL1(l1Click);
                act.setModR1(r1Click);

                invalidate();

                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:

                l1Click = false;
                r1Click = false;

                act.setModL1(false);
                act.setModR1(false);

                invalidate();

                return true;
        }

        return false;
    }
}


// ============================================================================
// MENÚ RADIAL
// ============================================================================

class DualRadialMenuView extends View {

    private final MainActivity act;

    private boolean activo = false;

    private float mX;
    private float mY;

    private int selectedIdx = -1;

    private boolean esMenuSuperior = false;

    private final List<String> optsInferior =
            new ArrayList<>();

    private final List<Character> keysInferior =
            new ArrayList<>();

    private final List<String> optsSuperior =
            new ArrayList<>();

    private final List<Character> keysSuperior =
            new ArrayList<>();

    private final Paint pBase =
            new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint pBorder =
            new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint pTxt =
            new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint pSel =
            new Paint(Paint.ANTI_ALIAS_FLAG);

    public DualRadialMenuView(Context context) {

        super(context);

        act = (MainActivity) context;

        // --------------------------------------------------------
        // MENÚ INFERIOR
        // --------------------------------------------------------

        optsInferior.add("Personaje");
        keysInferior.add('C');

        optsInferior.add("Inventario");
        keysInferior.add('I');

        optsInferior.add("Hechizos");
        keysInferior.add('P');

        optsInferior.add("Mazmorras");
        keysInferior.add('L');

        optsInferior.add("BGs");
        keysInferior.add('H');

        // --------------------------------------------------------
        // MENÚ SUPERIOR
        // --------------------------------------------------------

        optsSuperior.add("Saltar");
        keysSuperior.add('J');

        optsSuperior.add("Montura");
        keysSuperior.add('M');

        optsSuperior.add("Poción");
        keysSuperior.add('P');

        // --------------------------------------------------------
        // PALETA
        // --------------------------------------------------------

        pBase.setColor(
                Color.argb(190, 8, 13, 20)
        );

        pBorder.setColor(
                MainActivity.UI_BORDER
        );

        pBorder.setStyle(Paint.Style.STROKE);
        pBorder.setStrokeWidth(act.dp(1.2f));

        pTxt.setColor(
                MainActivity.UI_TEXT
        );

        pTxt.setTextSize(
                act.dp(13)
        );

        pTxt.setTypeface(
                Typeface.create(
                        "sans-serif-medium",
                        Typeface.NORMAL
                )
        );

        pTxt.setTextAlign(
                Paint.Align.CENTER
        );

        pSel.setColor(
                Color.argb(170, 75, 150, 205)
        );
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {

        float x = event.getX();
        float y = event.getY();

        float third =
                getWidth() / 3f;

        if (!activo &&
                (x < third ||
                 x > third * 2)) {

            return false;
        }

        switch (event.getActionMasked()) {

            case MotionEvent.ACTION_DOWN:

                mX = x;
                mY = y;

                activo = true;

                esMenuSuperior =
                        y < getHeight() / 2f;

                selectedIdx = -1;

                invalidate();

                return true;

            case MotionEvent.ACTION_MOVE:

                if (!activo) {
                    return false;
                }

                float dx = x - mX;
                float dy = y - mY;

                float dist =
                        (float) Math.sqrt(
                                dx * dx +
                                dy * dy
                        );

                int totalOpts =
                        esMenuSuperior
                                ? optsSuperior.size()
                                : optsInferior.size();

                if (dist > act.dp(18)) {

                    float angle =
                            (float) Math.atan2(
                                    dy,
                                    dx
                            );

                    if (angle < 0) {
                        angle +=
                                (float) (
                                        Math.PI * 2
                                );
                    }

                    float step =
                            (float) (
                                    Math.PI * 2 /
                                    totalOpts
                            );

                    selectedIdx =
                            (int) (
                                    angle / step
                            );

                    if (selectedIdx >= totalOpts) {
                        selectedIdx =
                                totalOpts - 1;
                    }

                } else {

                    selectedIdx = -1;
                }

                invalidate();

                return true;

            case MotionEvent.ACTION_UP:

                if (!activo) {
                    return false;
                }

                activo = false;

                if (selectedIdx != -1) {

                    char key =
                            esMenuSuperior
                                    ? keysSuperior.get(selectedIdx)
                                    : keysInferior.get(selectedIdx);

                    act.sendStroke(
                            String.valueOf(key),
                            true
                    );

                    act.postDelayed(
                            () -> act.sendStroke(
                                    String.valueOf(key),
                                    false
                            ),
                            35
                    );
                }

                selectedIdx = -1;

                invalidate();

                return true;

            case MotionEvent.ACTION_CANCEL:

                activo = false;
                selectedIdx = -1;

                invalidate();

                return true;
        }

        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {

        super.onDraw(canvas);

        if (!activo) {
            return;
        }

        // Punto central
        canvas.drawCircle(
                mX,
                mY,
                act.dp(22),
                pBase
        );

        canvas.drawCircle(
                mX,
                mY,
                act.dp(22),
                pBorder
        );

        List<String> list =
                esMenuSuperior
                        ? optsSuperior
                        : optsInferior;

        float step =
                (float) (
                        Math.PI * 2 /
                        list.size()
                );

        float menuRadius =
                act.dp(82);

        for (int i = 0; i < list.size(); i++) {

            float angle =
                    i * step;

            float px =
                    mX +
                    (float) Math.cos(angle) *
                    menuRadius;

            float py =
                    mY +
                    (float) Math.sin(angle) *
                    menuRadius;

            if (i == selectedIdx) {

                canvas.drawCircle(
                        px,
                        py,
                        act.dp(40),
                        pSel
                );

                pTxt.setColor(Color.WHITE);

            } else {

                canvas.drawCircle(
                        px,
                        py,
                        act.dp(34),
                        pBase
                );

                canvas.drawCircle(
                        px,
                        py,
                        act.dp(34),
                        pBorder
                );

                pTxt.setColor(
                        MainActivity.UI_TEXT
                );
            }

            canvas.drawText(
                    list.get(i),
                    px,
                    py -
                            (pTxt.ascent() +
                             pTxt.descent()) / 2f,
                    pTxt
            );
        }
    }
}
