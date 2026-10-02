package com.wowmobile.client;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
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

    // ============================================================
    // STREAM
    // ============================================================

    private FrameLayout mainContainer;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;

    private Socket socket;
    private InputStream videoStream;
    private OutputStream commandStream;

    private volatile boolean isRunning = false;

    private static final String PC_IP = "192.168.1.50";
    private static final int PC_PORT = 8888;

    // ============================================================
    // MODIFICADORES
    // ============================================================

    private volatile boolean modL1Activo = false;
    private volatile boolean modR1Activo = false;

    // ============================================================
    // CONTROLES
    // ============================================================

    private DPadView dPadView;
    private ActionButtonsView actionButtonsView;
    private TriggerButtonsView triggerButtonsView;
    private InteractButtonView interactButtonView;
    private DualRadialMenuView dualRadialMenu;

    private final Handler handler = new Handler();

    // ============================================================
    // ESTILO UI
    // ============================================================

    public static final int UI_BG =
            Color.argb(105, 8, 12, 18);

    public static final int UI_BG_DARK =
            Color.argb(155, 5, 8, 13);

    public static final int UI_BORDER =
            Color.argb(120, 180, 195, 210);

    public static final int UI_BORDER_ACTIVE =
            Color.argb(215, 225, 235, 245);

    public static final int UI_TEXT =
            Color.argb(235, 235, 240, 245);

    public static final int UI_TEXT_DIM =
            Color.argb(165, 210, 220, 230);

    public static final int UI_ACTIVE =
            Color.argb(145, 75, 150, 205);

    public static final int UI_ACTIVE_BRIGHT =
            Color.argb(205, 100, 185, 235);

    // ============================================================
    // INTERACCIÓN UNIVERSAL
    // ============================================================

    /*
     * El servidor puede interpretar G según el contexto:
     *
     * - abrir cofre
     * - recoger/lootear
     * - hablar con NPC
     * - aceptar misión
     * - completar misión
     * - activar objeto
     * - interactuar con objeto utilizable
     *
     * El cliente solamente envía la tecla G.
     */
    private static final String INTERACT_KEY = "G";

    // ============================================================
    // ON CREATE
    // ============================================================

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

        /*
         * Importante:
         * permite dividir eventos multitáctiles entre controles.
         */
        mainContainer.setMotionEventSplittingEnabled(true);

        mainContainer.setBackgroundColor(
                Color.rgb(3, 6, 10)
        );

        setContentView(mainContainer);

        // ========================================================
        // SUPERFICIE DE VIDEO
        // ========================================================

        surfaceView = new SurfaceView(this);

        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);

        FrameLayout.LayoutParams surfaceParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                );

        mainContainer.addView(
                surfaceView,
                surfaceParams
        );

        // ========================================================
        // CONTROLES
        // ========================================================

        setupNativeGameControls();

        // ========================================================
        // STREAM
        // ========================================================

        isRunning = true;

        new Thread(
                this::connectAndStream,
                "WoW-Stream"
        ).start();
    }

    // ============================================================
    // CONFIGURACIÓN CONTROLES
    // ============================================================

    private void setupNativeGameControls() {

        // --------------------------------------------------------
        // D-PAD / JOYSTICK
        // --------------------------------------------------------

        dPadView = new DPadView(this);

        FrameLayout.LayoutParams dPadParams =
                new FrameLayout.LayoutParams(
                        dp(150),
                        dp(150),
                        Gravity.BOTTOM | Gravity.LEFT
                );

        dPadParams.leftMargin = dp(22);
        dPadParams.bottomMargin = dp(22);

        mainContainer.addView(
                dPadView,
                dPadParams
        );

        // --------------------------------------------------------
        // BOTONES DE HABILIDADES
        // --------------------------------------------------------

        actionButtonsView = new ActionButtonsView(this);

        FrameLayout.LayoutParams actionParams =
                new FrameLayout.LayoutParams(
                        dp(190),
                        dp(190),
                        Gravity.BOTTOM | Gravity.RIGHT
                );

        actionParams.rightMargin = dp(20);
        actionParams.bottomMargin = dp(18);

        mainContainer.addView(
                actionButtonsView,
                actionParams
        );

        // --------------------------------------------------------
        // BOTÓN INTERACT
        // --------------------------------------------------------

        interactButtonView = new InteractButtonView(this);

        FrameLayout.LayoutParams interactParams =
                new FrameLayout.LayoutParams(
                        dp(86),
                        dp(86),
                        Gravity.BOTTOM | Gravity.RIGHT
                );

        /*
         * A la izquierda del grupo de habilidades.
         */
        interactParams.rightMargin = dp(218);
        interactParams.bottomMargin = dp(40);

        mainContainer.addView(
                interactButtonView,
                interactParams
        );

        // --------------------------------------------------------
        // L1 / R1
        // --------------------------------------------------------

        triggerButtonsView = new TriggerButtonsView(this);

        FrameLayout.LayoutParams triggerParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                );

        mainContainer.addView(
                triggerButtonsView,
                triggerParams
        );

        // --------------------------------------------------------
        // MENÚ RADIAL
        // --------------------------------------------------------

        dualRadialMenu = new DualRadialMenuView(this);

        FrameLayout.LayoutParams radialParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                );

        mainContainer.addView(
                dualRadialMenu,
                radialParams
        );
    }

    // ============================================================
    // COMANDOS AL PC
    // ============================================================

    public synchronized void sendStroke(
            String key,
            boolean pressed
    ) {

        if (commandStream == null) {
            return;
        }

        if (key == null || key.isEmpty()) {
            return;
        }

        try {

            byte[] packet = new byte[]{
                    (byte) (pressed ? 1 : 0),
                    (byte) Character.toUpperCase(
                            key.charAt(0)
                    )
            };

            commandStream.write(packet);
            commandStream.flush();

        } catch (Exception ignored) {
        }
    }

    // ============================================================
    // MOD L1
    // ============================================================

    public void setModL1(boolean active) {

        modL1Activo = active;

        if (actionButtonsView != null) {
            actionButtonsView.invalidate();
        }

        if (triggerButtonsView != null) {
            triggerButtonsView.invalidate();
        }
    }

    // ============================================================
    // MOD R1
    // ============================================================

    public void setModR1(boolean active) {

        modR1Activo = active;

        if (actionButtonsView != null) {
            actionButtonsView.invalidate();
        }

        if (triggerButtonsView != null) {
            triggerButtonsView.invalidate();
        }
    }

    public boolean isModL1() {
        return modL1Activo;
    }

    public boolean isModR1() {
        return modR1Activo;
    }

    // ============================================================
    // CONEXIÓN + STREAMING
    // ============================================================

    private void connectAndStream() {

        try {

            socket = new Socket(
                    PC_IP,
                    PC_PORT
            );

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

                if (size <= 0 ||
                        size > 50 * 1024 * 1024) {

                    continue;
                }

                byte[] imgBuffer =
                        new byte[size];

                int read = readFully(
                        videoStream,
                        imgBuffer,
                        0,
                        size
                );

                if (read != size) {
                    break;
                }

                final android.graphics.Bitmap bmp =
                        android.graphics.BitmapFactory
                                .decodeByteArray(
                                        imgBuffer,
                                        0,
                                        imgBuffer.length
                                );

                if (bmp == null) {
                    continue;
                }

                if (!surfaceHolder
                        .getSurface()
                        .isValid()) {

                    bmp.recycle();
                    continue;
                }

                Canvas canvas = null;

                try {

                    canvas =
                            surfaceHolder.lockCanvas();

                    if (canvas != null) {

                        canvas.drawColor(
                                Color.BLACK
                        );

                        canvas.drawBitmap(
                                bmp,
                                null,
                                canvas.getClipBounds(),
                                null
                        );
                    }

                } finally {

                    if (canvas != null) {

                        surfaceHolder
                                .unlockCanvasAndPost(
                                        canvas
                                );
                    }

                    bmp.recycle();
                }
            }

        } catch (Exception ignored) {

        } finally {

            closeConnection();
        }
    }

    // ============================================================
    // READ FULLY
    // ============================================================

    private int readFully(
            InputStream stream,
            byte[] buffer,
            int offset,
            int length
    ) throws Exception {

        int total = 0;

        while (
                total < length &&
                isRunning
        ) {

            int result =
                    stream.read(
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

    // ============================================================
    // CERRAR CONEXIÓN
    // ============================================================

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
    // DENSIDAD
    // ============================================================

    /*
     * Para enteros:
     * tamaños, márgenes, LayoutParams.
     */
    public int dpi(float value) {

        return Math.round(
                value *
                        getResources()
                                .getDisplayMetrics()
                                .density
        );
    }

    /*
     * Para Paint:
     * strokeWidth, textSize, etc.
     */
    public float dpf(float value) {

        return value *
                getResources()
                        .getDisplayMetrics()
                        .density;
    }

    /*
     * Compatibilidad con código existente.
     */
    public int dp(float value) {
        return dpi(value);
    }

    // ============================================================
    // SURFACE
    // ============================================================

    @Override
    public void surfaceCreated(
            SurfaceHolder holder
    ) {
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
    public void surfaceDestroyed(
            SurfaceHolder holder
    ) {

        isRunning = false;
        closeConnection();
    }

    @Override
    protected void onDestroy() {

        isRunning = false;
        closeConnection();

        super.onDestroy();
    }

    // ============================================================
    // ============================================================
    // D-PAD / JOYSTICK
    // ============================================================
    // ============================================================

    private static class DPadView extends View {

        private final MainActivity act;

        private final Paint pBase = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pStick = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pCenter = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pAxis = new Paint(Paint.ANTI_ALIAS_FLAG);

        private float centerX;
        private float centerY;

        private float outerRadius;
        private float innerRadius;
        private float stickRadius;

        private float stickX;
        private float stickY;

        private boolean touchActive = false;

        private boolean wDown = false;
        private boolean aDown = false;
        private boolean sDown = false;
        private boolean dDown = false;

        private int pointerId =
                MotionEvent.INVALID_POINTER_ID;

        DPadView(Context context) {

            super(context);

            act = (MainActivity) context;

            setLayerType(
                    View.LAYER_TYPE_SOFTWARE,
                    null
            );

            pBase.setStyle(Paint.Style.FILL);
            pBase.setColor(
                    UI_BG_DARK
            );

            pBorder.setStyle(Paint.Style.STROKE);
            pBorder.setColor(
                    UI_BORDER
            );
            pBorder.setStrokeWidth(
                    act.dpf(1.2f)
            );

            pStick.setStyle(Paint.Style.FILL);
            pStick.setColor(
                    Color.argb(
                            150,
                            70,
                            105,
                            130
                    )
            );

            pCenter.setStyle(Paint.Style.FILL);
            pCenter.setColor(
                    Color.argb(
                            180,
                            20,
                            30,
                            40
                    )
            );

            pAxis.setStyle(Paint.Style.STROKE);
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
                int width,
                int height,
                int oldWidth,
                int oldHeight
        ) {

            centerX = width / 2f;
            centerY = height / 2f;

            outerRadius =
                    Math.min(
                            width,
                            height
                    ) * 0.47f;

            innerRadius =
                    outerRadius * 0.74f;

            stickRadius =
                    outerRadius * 0.30f;

            stickX = centerX;
            stickY = centerY;
        }

        @Override
        protected void onDraw(Canvas canvas) {

            super.onDraw(canvas);

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

            Paint stroke = pBorder;

            if (touchActive) {
                stroke.setColor(
                        UI_BORDER_ACTIVE
                );
            } else {
                stroke.setColor(
                        UI_BORDER
                );
            }

            canvas.drawCircle(
                    stickX,
                    stickY,
                    stickRadius,
                    stroke
            );
        }

        @Override
        public boolean onTouchEvent(
                MotionEvent event
        ) {

            int action =
                    event.getActionMasked();

            switch (action) {

                case MotionEvent.ACTION_DOWN:

                    pointerId =
                            event.getPointerId(0);

                    touchActive = true;

                    updateStick(
                            event.getX(0),
                            event.getY(0)
                    );

                    return true;

                case MotionEvent.ACTION_MOVE:

                    if (pointerId ==
                            MotionEvent.INVALID_POINTER_ID) {
                        return true;
                    }

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

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:

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

            float distance =
                    (float) Math.sqrt(
                            dx * dx +
                                    dy * dy
                    );

            float maxDistance =
                    outerRadius * 0.70f;

            if (distance > maxDistance) {

                float scale =
                        maxDistance /
                                distance;

                dx *= scale;
                dy *= scale;
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

            setKey(
                    "W",
                    newW,
                    wDown
            );

            setKey(
                    "A",
                    newA,
                    aDown
            );

            setKey(
                    "S",
                    newS,
                    sDown
            );

            setKey(
                    "D",
                    newD,
                    dDown
            );

            wDown = newW;
            aDown = newA;
            sDown = newS;
            dDown = newD;

            invalidate();
        }

        private void setKey(
                String key,
                boolean now,
                boolean old
        ) {

            if (now != old) {
                act.sendStroke(
                        key,
                        now
                );
            }
        }

        private void releaseAll() {

            if (wDown) {
                act.sendStroke(
                        "W",
                        false
                );
            }

            if (aDown) {
                act.sendStroke(
                        "A",
                        false
                );
            }

            if (sDown) {
                act.sendStroke(
                        "S",
                        false
                );
            }

            if (dDown) {
                act.sendStroke(
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
    // ============================================================
    // BOTONES DE HABILIDADES
    // ============================================================
    // ============================================================

    private static class ActionButtonsView extends View {

        private final MainActivity act;

        private final Paint pBase =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint pBorder =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint pText =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint pGlow =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final float[][] positions =
                new float[4][2];

        private int activeButton = -1;

        private int pointerId =
                MotionEvent.INVALID_POINTER_ID;

        ActionButtonsView(Context context) {

            super(context);

            act = (MainActivity) context;

            setLayerType(
                    View.LAYER_TYPE_SOFTWARE,
                    null
            );

            pBase.setStyle(
                    Paint.Style.FILL
            );

            pBase.setColor(
                    UI_BG_DARK
            );

            pBorder.setStyle(
                    Paint.Style.STROKE
            );

            pBorder.setStrokeWidth(
                    act.dpf(1.2f)
            );

            pText.setTypeface(
                    Typeface.create(
                            Typeface.DEFAULT,
                            Typeface.BOLD
                    )
            );

            pText.setTextAlign(
                    Paint.Align.CENTER
            );

            pText.setTextSize(
                    act.dpf(14f)
            );

            pGlow.setStyle(
                    Paint.Style.FILL
            );
        }

        @Override
        protected void onSizeChanged(
                int width,
                int height,
                int oldWidth,
                int oldHeight
        ) {

            float cx = width / 2f;
            float cy = height / 2f;

            float spread =
                    Math.min(
                            width,
                            height
                    ) * 0.28f;

            /*
             * 1 = arriba
             * 2 = izquierda
             * 3 = derecha
             * 4 = abajo
             */

            positions[0][0] =
                    cx;

            positions[0][1] =
                    cy - spread;

            positions[1][0] =
                    cx - spread;

            positions[1][1] =
                    cy;

            positions[2][0] =
                    cx + spread;

            positions[2][1] =
                    cy;

            positions[3][0] =
                    cx;

            positions[3][1] =
                    cy + spread;
        }

        @Override
        protected void onDraw(
                Canvas canvas
        ) {

            super.onDraw(canvas);

            float radius =
                    act.dpf(28f);

            String[] labels;

            if (act.isModL1()) {

                labels = new String[]{
                        "5",
                        "6",
                        "7",
                        "8"
                };

            } else if (act.isModR1()) {

                labels = new String[]{
                        "9",
                        "0",
                        "F",
                        "E"
                };

            } else {

                labels = new String[]{
                        "1",
                        "2",
                        "3",
                        "4"
                };
            }

            for (int i = 0; i < 4; i++) {

                boolean active =
                        i == activeButton;

                pBase.setColor(
                        active
                                ? UI_ACTIVE
                                : UI_BG_DARK
                );

                pBorder.setColor(
                        active
                                ? UI_ACTIVE_BRIGHT
                                : UI_BORDER
                );

                float x =
                        positions[i][0];

                float y =
                        positions[i][1];

                canvas.drawCircle(
                        x,
                        y,
                        radius,
                        pBase
                );

                canvas.drawCircle(
                        x,
                        y,
                        radius,
                        pBorder
                );

                pText.setColor(
                        active
                                ? Color.WHITE
                                : UI_TEXT
                );

                Paint.FontMetrics fm =
                        pText.getFontMetrics();

                float textY =
                        y -
                                (fm.ascent +
                                        fm.descent)
                                        / 2f;

                canvas.drawText(
                        labels[i],
                        x,
                        textY,
                        pText
                );
            }

            /*
             * Indicador de modificador.
             */
            if (act.isModL1() ||
                    act.isModR1()) {

                String modifier =
                        act.isModL1()
                                ? "L1"
                                : "R1";

                pText.setTextSize(
                        act.dpf(10f)
                );

                pText.setColor(
                        UI_TEXT_DIM
                );

                canvas.drawText(
                        modifier,
                        getWidth() / 2f,
                        act.dpf(12f),
                        pText
                );

                pText.setTextSize(
                        act.dpf(14f)
                );
            }
        }

        private int buttonAt(
                float x,
                float y
        ) {

            float radius =
                    act.dpf(35f);

            for (int i = 0; i < 4; i++) {

                float dx =
                        x - positions[i][0];

                float dy =
                        y - positions[i][1];

                if (dx * dx + dy * dy <=
                        radius * radius) {

                    return i;
                }
            }

            return -1;
        }

        @Override
        public boolean onTouchEvent(
                MotionEvent event
        ) {

            int action =
                    event.getActionMasked();

            switch (action) {

                case MotionEvent.ACTION_DOWN:

                    pointerId =
                            event.getPointerId(0);

                    activeButton =
                            buttonAt(
                                    event.getX(0),
                                    event.getY(0)
                            );

                    if (activeButton >= 0) {

                        pressButton(
                                activeButton
                        );

                        invalidate();

                        return true;
                    }

                    return false;

                case MotionEvent.ACTION_MOVE:

                    if (pointerId ==
                            MotionEvent.INVALID_POINTER_ID) {
                        return true;
                    }

                    int index =
                            event.findPointerIndex(
                                    pointerId
                            );

                    if (index < 0) {
                        return true;
                    }

                    int newButton =
                            buttonAt(
                                    event.getX(index),
                                    event.getY(index)
                            );

                    if (newButton !=
                            activeButton) {

                        releaseButton(
                                activeButton
                        );

                        activeButton =
                                newButton;

                        pressButton(
                                activeButton
                        );

                        invalidate();
                    }

                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:

                    releaseButton(
                            activeButton
                    );

                    activeButton = -1;

                    pointerId =
                            MotionEvent.INVALID_POINTER_ID;

                    invalidate();

                    return true;
            }

            return true;
        }

        private void pressButton(
                int index
        ) {

            if (index < 0 ||
                    index > 3) {
                return;
            }

            act.sendStroke(
                    getMapKey(index),
                    true
            );
        }

        private void releaseButton(
                int index
        ) {

            if (index < 0 ||
                    index > 3) {
                return;
            }

            act.sendStroke(
                    getMapKey(index),
                    false
            );
        }

        private String getMapKey(
                int index
        ) {

            if (act.isModL1()) {

                switch (index) {

                    case 0:
                        return "5";

                    case 1:
                        return "6";

                    case 2:
                        return "7";

                    default:
                        return "8";
                }
            }

            if (act.isModR1()) {

                switch (index) {

                    case 0:
                        return "9";

                    case 1:
                        return "0";

                    case 2:
                        return "F";

                    default:
                        return "E";
                }
            }

            switch (index) {

                case 0:
                    return "1";

                case 1:
                    return "2";

                case 2:
                    return "3";

                default:
                    return "4";
            }
        }
    }

    // ============================================================
    // ============================================================
    // BOTÓN INTERACT
    // ============================================================
    // ============================================================

    private static class InteractButtonView extends View {

        private final MainActivity act;

        private final Paint pBase =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint pBorder =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint pText =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private boolean pressed = false;

        InteractButtonView(Context context) {

            super(context);

            act = (MainActivity) context;

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
                    Typeface.create(
                            Typeface.DEFAULT,
                            Typeface.BOLD
                    )
            );
        }

        @Override
        protected void onDraw(
                Canvas canvas
        ) {

            super.onDraw(canvas);

            float cx =
                    getWidth() / 2f;

            float cy =
                    getHeight() / 2f;

            float radius =
                    Math.min(
                            getWidth(),
                            getHeight()
                    ) * 0.42f;

            pBase.setColor(
                    pressed
                            ? UI_ACTIVE
                            : UI_BG_DARK
            );

            pBorder.setColor(
                    pressed
                            ? UI_ACTIVE_BRIGHT
                            : UI_BORDER
            );

            canvas.drawCircle(
                    cx,
                    cy,
                    radius,
                    pBase
            );

            canvas.drawCircle(
                    cx,
                    cy,
                    radius,
                    pBorder
            );

            pText.setColor(
                    pressed
                            ? Color.WHITE
                            : UI_TEXT
            );

            pText.setTextSize(
                    act.dpf(19f)
            );

            Paint.FontMetrics fm =
                    pText.getFontMetrics();

            float keyY =
                    cy -
                            (fm.ascent +
                                    fm.descent) /
                                    2f -
                            act.dpf(5f);

            canvas.drawText(
                    INTERACT_KEY,
                    cx,
                    keyY,
                    pText
            );

            pText.setTextSize(
                    act.dpf(8.5f)
            );

            canvas.drawText(
                    "INTERACT",
                    cx,
                    cy + act.dpf(18f),
                    pText
            );
        }

        @Override
        public boolean onTouchEvent(
                MotionEvent event
        ) {

            switch (
                    event.getActionMasked()
            ) {

                case MotionEvent.ACTION_DOWN:

                    pressed = true;

                    act.sendStroke(
                            INTERACT_KEY,
                            true
                    );

                    invalidate();

                    return true;

                case MotionEvent.ACTION_UP:

                    pressed = false;

                    act.sendStroke(
                            INTERACT_KEY,
                            false
                    );

                    invalidate();

                    return true;

                case MotionEvent.ACTION_CANCEL:

                    pressed = false;

                    act.sendStroke(
                            INTERACT_KEY,
                            false
                    );

                    invalidate();

                    return true;
            }

            return true;
        }
    }

    // ============================================================
    // ============================================================
    // L1 / R1
    // ============================================================
    // ============================================================

    private static class TriggerButtonsView extends View {

        private final MainActivity act;

        private final Paint pBase =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint pBorder =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint pText =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final android.graphics.RectF l1Rect =
                new android.graphics.RectF();

        private final android.graphics.RectF r1Rect =
                new android.graphics.RectF();

        private int l1PointerId =
                MotionEvent.INVALID_POINTER_ID;

        private int r1PointerId =
                MotionEvent.INVALID_POINTER_ID;

        TriggerButtonsView(Context context) {

            super(context);

            act = (MainActivity) context;

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
                    Typeface.create(
                            Typeface.DEFAULT,
                            Typeface.BOLD
                    )
            );
        }

        @Override
        protected void onSizeChanged(
                int width,
                int height,
                int oldWidth,
                int oldHeight
        ) {

            float w =
                    act.dpf(74f);

            float h =
                    act.dpf(36f);

            float margin =
                    act.dpf(18f);

            float top =
                    act.dpf(15f);

            l1Rect.set(
                    margin,
                    top,
                    margin + w,
                    top + h
            );

            r1Rect.set(
                    width - margin - w,
                    top,
                    width - margin,
                    top + h
            );
        }

        @Override
        protected void onDraw(
                Canvas canvas
        ) {

            super.onDraw(canvas);

            drawTrigger(
                    canvas,
                    l1Rect,
                    "L1",
                    act.isModL1()
            );

            drawTrigger(
                    canvas,
                    r1Rect,
                    "R1",
                    act.isModR1()
            );
        }

        private void drawTrigger(
                Canvas canvas,
                android.graphics.RectF rect,
                String text,
                boolean active
        ) {

            pBase.setColor(
                    active
                            ? UI_ACTIVE
                            : UI_BG_DARK
            );

            pBorder.setColor(
                    active
                            ? UI_ACTIVE_BRIGHT
                            : UI_BORDER
            );

            canvas.drawRoundRect(
                    rect,
                    act.dpf(10f),
                    act.dpf(10f),
                    pBase
            );

            canvas.drawRoundRect(
                    rect,
                    act.dpf(10f),
                    act.dpf(10f),
                    pBorder
            );

            pText.setColor(
                    active
                            ? Color.WHITE
                            : UI_TEXT
            );

            pText.setTextSize(
                    act.dpf(11f)
            );

            float y =
                    rect.centerY() -
                            (
                                    pText.getFontMetrics().ascent +
                                            pText.getFontMetrics().descent
                            ) / 2f;

            canvas.drawText(
                    text,
                    rect.centerX(),
                    y,
                    pText
            );
        }

        @Override
        public boolean onTouchEvent(
                MotionEvent event
        ) {

            int action =
                    event.getActionMasked();

            int actionIndex =
                    event.getActionIndex();

            switch (action) {

                case MotionEvent.ACTION_DOWN: {

                    int id =
                            event.getPointerId(0);

                    boolean handled =
                            capturePointer(
                                    id,
                                    event.getX(0),
                                    event.getY(0)
                            );

                    /*
                     * Si la pulsación no fue sobre L1/R1,
                     * NO capturamos el evento.
                     *
                     * Esto evita bloquear D-pad/skills.
                     */
                    return handled;
                }

                case MotionEvent.ACTION_POINTER_DOWN: {

                    int id =
                            event.getPointerId(
                                    actionIndex
                            );

                    float x =
                            event.getX(actionIndex);

                    float y =
                            event.getY(actionIndex);

                    capturePointer(
                            id,
                            x,
                            y
                    );

                    return true;
                }

                case MotionEvent.ACTION_MOVE:

                    /*
                     * Sólo actualizamos los punteros
                     * que pertenecen a L1/R1.
                     */
                    return true;

                case MotionEvent.ACTION_POINTER_UP: {

                    int id =
                            event.getPointerId(
                                    actionIndex
                            );

                    releasePointer(id);

                    return true;
                }

                case MotionEvent.ACTION_UP: {

                    int id =
                            event.getPointerId(0);

                    releasePointer(id);

                    return true;
                }

                case MotionEvent.ACTION_CANCEL:

                    releaseAllTriggers();

                    return true;
            }

            return false;
        }

        private boolean capturePointer(
                int id,
                float x,
                float y
        ) {

            if (l1Rect.contains(x, y) &&
                    l1PointerId ==
                            MotionEvent.INVALID_POINTER_ID) {

                l1PointerId = id;

                act.setModL1(true);

                invalidate();

                return true;
            }

            if (r1Rect.contains(x, y) &&
                    r1PointerId ==
                            MotionEvent.INVALID_POINTER_ID) {

                r1PointerId = id;

                act.setModR1(true);

                invalidate();

                return true;
            }

            return false;
        }

        private void releasePointer(
                int id
        ) {

            if (id == l1PointerId) {

                l1PointerId =
                        MotionEvent.INVALID_POINTER_ID;

                act.setModL1(false);
            }

            if (id == r1PointerId) {

                r1PointerId =
                        MotionEvent.INVALID_POINTER_ID;

                act.setModR1(false);
            }

            invalidate();
        }

        private void releaseAllTriggers() {

            l1PointerId =
                    MotionEvent.INVALID_POINTER_ID;

            r1PointerId =
                    MotionEvent.INVALID_POINTER_ID;

            act.setModL1(false);
            act.setModR1(false);

            invalidate();
        }
    }

    // ============================================================
    // ============================================================
    // MENÚ RADIAL
    // ============================================================
    // ============================================================

    private static class DualRadialMenuView extends View {

        private final MainActivity act;

        private final Paint pBase =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint pBorder =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint pText =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Paint pSelected =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private boolean activo = false;

        private boolean menuSuperior = false;

        private float centerX;
        private float centerY;

        private float startX;
        private float startY;

        private float menuRadius;

        private int selectedIdx = -1;

        private int pointerId =
                MotionEvent.INVALID_POINTER_ID;

        private final String[] lowerLabels = {
                "C",
                "I",
                "P",
                "L",
                "H"
        };

        private final String[] lowerKeys = {
                "C",
                "I",
                "P",
                "L",
                "H"
        };

        private final String[] upperLabels = {
                "J",
                "M",
                "P"
        };

        private final String[] upperKeys = {
                "J",
                "M",
                "P"
        };

        DualRadialMenuView(Context context) {

            super(context);

            act = (MainActivity) context;

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

            pText.setTypeface(
                    Typeface.create(
                            Typeface.DEFAULT,
                            Typeface.BOLD
                    )
            );

            pText.setTextAlign(
                    Paint.Align.CENTER
            );

            pSelected.setStyle(
                    Paint.Style.FILL
            );
        }

        @Override
        protected void onDraw(
                Canvas canvas
        ) {

            super.onDraw(canvas);

            if (!activo) {
                return;
            }

            float buttonRadius =
                    act.dpf(24f);

            /*
             * Se calcula el radio máximo disponible
             * para que ningún botón salga de la pantalla.
             */
            float maxLeft =
                    centerX -
                            buttonRadius -
                            act.dpf(8f);

            float maxRight =
                    getWidth() -
                            centerX -
                            buttonRadius -
                            act.dpf(8f);

            float maxTop =
                    centerY -
                            buttonRadius -
                            act.dpf(8f);

            float maxBottom =
                    getHeight() -
                            centerY -
                            buttonRadius -
                            act.dpf(8f);

            float safe =
                    Math.min(
                            Math.min(
                                    maxLeft,
                                    maxRight
                            ),
                            Math.min(
                                    maxTop,
                                    maxBottom
                            )
                    );

            menuRadius =
                    Math.min(
                            act.dpf(82f),
                            Math.max(
                                    act.dpf(35f),
                                    safe
                            )
                    );

            String[] labels =
                    menuSuperior
                            ? upperLabels
                            : lowerLabels;

            int count =
                    labels.length;

            float step =
                    360f / count;

            for (int i = 0; i < count; i++) {

                double angle =
                        Math.toRadians(
                                -90f +
                                        i * step
                        );

                float x =
                        centerX +
                                (float)
                                        Math.cos(angle)
                                        * menuRadius;

                float y =
                        centerY +
                                (float)
                                        Math.sin(angle)
                                        * menuRadius;

                /*
                 * Segundo seguro por si el dispositivo
                 * tiene una pantalla especialmente pequeña.
                 */
                x = clamp(
                        x,
                        buttonRadius +
                                act.dpf(6f),
                        getWidth() -
                                buttonRadius -
                                act.dpf(6f)
                );

                y = clamp(
                        y,
                        buttonRadius +
                                act.dpf(6f),
                        getHeight() -
                                buttonRadius -
                                act.dpf(6f)
                );

                boolean selected =
                        i == selectedIdx;

                pBase.setColor(
                        selected
                                ? UI_ACTIVE
                                : UI_BG_DARK
                );

                pBorder.setColor(
                        selected
                                ? UI_ACTIVE_BRIGHT
                                : UI_BORDER
                );

                canvas.drawCircle(
                        x,
                        y,
                        buttonRadius,
                        pBase
                );

                canvas.drawCircle(
                        x,
                        y,
                        buttonRadius,
                        pBorder
                );

                pText.setColor(
                        selected
                                ? Color.WHITE
                                : UI_TEXT
                );

                pText.setTextSize(
                        act.dpf(12f)
                );

                float textY =
                        y -
                                (
                                        pText.getFontMetrics().ascent +
                                                pText.getFontMetrics().descent
                                ) / 2f;

                canvas.drawText(
                        labels[i],
                        x,
                        textY,
                        pText
                );
            }

            /*
             * Centro.
             */
            pSelected.setColor(
                    Color.argb(
                            125,
                            10,
                            15,
                            22
                    )
            );

            canvas.drawCircle(
                    centerX,
                    centerY,
                    act.dpf(29f),
                    pSelected
            );

            pBorder.setColor(
                    UI_BORDER
            );

            canvas.drawCircle(
                    centerX,
                    centerY,
                    act.dpf(29f),
                    pBorder
            );
        }

        private float clamp(
                float value,
                float min,
                float max
        ) {

            if (max < min) {
                return (min + max) / 2f;
            }

            return Math.max(
                    min,
                    Math.min(
                            max,
                            value
                    )
            );
        }

        @Override
        public boolean onTouchEvent(
                MotionEvent event
        ) {

            int action =
                    event.getActionMasked();

            if (!activo) {

                if (action !=
                        MotionEvent.ACTION_DOWN) {

                    return false;
                }

                float x =
                        event.getX();

                float y =
                        event.getY();

                /*
                 * El radial sólo puede activarse
                 * en la zona central.
                 *
                 * Esto evita que bloquee los controles
                 * de las esquinas.
                 */
                if (!isRadialActivationZone(
                        x,
                        y
                )) {

                    return false;
                }

                pointerId =
                        event.getPointerId(0);

                startX = x;
                startY = y;

                centerX = x;
                centerY = y;

                /*
                 * Menú inferior / superior según
                 * la mitad de la pantalla.
                 */
                menuSuperior =
                        y <
                                getHeight() / 2f;

                centerX =
                        clamp(
                                centerX,
                                act.dpf(45f),
                                getWidth() -
                                        act.dpf(45f)
                        );

                centerY =
                        clamp(
                                centerY,
                                act.dpf(45f),
                                getHeight() -
                                        act.dpf(45f)
                        );

                activo = true;
                selectedIdx = -1;

                invalidate();

                return true;
            }

            switch (action) {

                case MotionEvent.ACTION_MOVE:

                    if (pointerId ==
                            MotionEvent.INVALID_POINTER_ID) {
                        return true;
                    }

                    int index =
                            event.findPointerIndex(
                                    pointerId
                            );

                    if (index >= 0) {

                        updateSelection(
                                event.getX(index),
                                event.getY(index)
                        );
                    }

                    return true;

                case MotionEvent.ACTION_UP:

                    if (pointerId !=
                            MotionEvent.INVALID_POINTER_ID) {

                        int index =
                                event.findPointerIndex(
                                        pointerId
                                );

                        if (index >= 0) {

                            updateSelection(
                                    event.getX(index),
                                    event.getY(index)
                            );
                        }
                    }

                    executeSelected();

                    closeMenu();

                    return true;

                case MotionEvent.ACTION_CANCEL:

                    closeMenu();

                    return true;
            }

            return true;
        }

        private boolean isRadialActivationZone(
                float x,
                float y
        ) {

            /*
             * Evita las zonas de controles principales.
             */

            float leftSafe =
                    act.dpf(185f);

            float rightSafe =
                    getWidth() -
                            act.dpf(210f);

            float topSafe =
                    act.dpf(65f);

            float bottomSafe =
                    getHeight() -
                            act.dpf(185f);

            return x > leftSafe &&
                    x < rightSafe &&
                    y > topSafe &&
                    y < bottomSafe;
        }

        private void updateSelection(
                float x,
                float y
        ) {

            float dx =
                    x - centerX;

            float dy =
                    y - centerY;

            float distance =
                    (float) Math.sqrt(
                            dx * dx +
                                    dy * dy
                    );

            if (distance <
                    act.dpf(24f)) {

                selectedIdx = -1;

                invalidate();

                return;
            }

            double angle =
                    Math.toDegrees(
                            Math.atan2(
                                    dy,
                                    dx
                            )
                    );

            angle += 90.0;

            if (angle < 0) {
                angle += 360.0;
            }

            String[] labels =
                    menuSuperior
                            ? upperLabels
                            : lowerLabels;

            float step =
                    360f /
                            labels.length;

            selectedIdx =
                    (int)
                            ((angle + step / 2f) /
                                    step)
                            % labels.length;

            invalidate();
        }

        private void executeSelected() {

            if (selectedIdx < 0) {
                return;
            }

            String[] keys =
                    menuSuperior
                            ? upperKeys
                            : lowerKeys;

            if (selectedIdx >=
                    keys.length) {
                return;
            }

            String key =
                    keys[selectedIdx];

            act.sendStroke(
                    key,
                    true
            );

            handlerPostRelease(
                    key
            );
        }

        private void handlerPostRelease(
                final String key
        ) {

            act.handler.postDelayed(
                    () -> act.sendStroke(
                            key,
                            false
                    ),
                    35
            );
        }

        private void closeMenu() {

            activo = false;

            selectedIdx = -1;

            pointerId =
                    MotionEvent.INVALID_POINTER_ID;

            invalidate();
        }
    }
}
