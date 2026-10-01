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
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private FrameLayout mainContainer;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    
    private Socket socket;
    private InputStream videoStream;
    private OutputStream commandStream;
    private boolean isRunning = false;

    // Estados de modificadores nativos extraídos de tu lógica
    private boolean _modL1Activo = false;
    private boolean _modR1Activo = false;

    // Ecosistema avanzado de controles visuales nativos rediseñados
    private DualRadialMenuView dualRadialMenu;
    private DPadView dPadView;
    private ActionButtonsView actionButtonsView;
    private TriggerButtonsView triggerButtonsView;

    private static final String PC_IP = "192.168.1.50"; 

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // CORREGIDO: Método clásico e infalible para pantalla completa compatible con todo Android
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        
        mainContainer = new FrameLayout(this);
        mainContainer.setBackgroundColor(Color.rgb(5, 10, 20));
        setContentView(mainContainer);

        // 1. Capa de renderizado de video espejo de la PC
        surfaceView = new SurfaceView(this);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);
        mainContainer.addView(surfaceView);

        // 2. Inicializar los controles estilizados nativos de juego
        setupNativeGameControls();

        isRunning = true;
        new Thread(this::connectAndStream).start();
    }

    private void setupNativeGameControls() {
        // Inicializar D-PAD direccional (Abajo Izquierda)
        dPadView = new DPadView(this);
        FrameLayout.LayoutParams dPadParams = new FrameLayout.LayoutParams(dp(180), dp(180), Gravity.BOTTOM | Gravity.LEFT);
        dPadParams.setMargins(dp(40), 0, 0, dp(40));
        mainContainer.addView(dPadView, dPadParams);

        // Inicializar Botonera en Rombo fija (Abajo Derecha)
        actionButtonsView = new ActionButtonsView(this);
        FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(dp(220), dp(220), Gravity.BOTTOM | Gravity.RIGHT);
        actionParams.setMargins(0, 0, dp(40), dp(40));
        mainContainer.addView(actionButtonsView, actionParams);

        // Inicializar Gatillos ovalados L1/R1 de interfaz (Arriba)
        triggerButtonsView = new TriggerButtonsView(this);
        mainContainer.addView(triggerButtonsView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Inicializar Capa de Menús Radiales Dobles Invisibles (Centro)
        dualRadialMenu = new DualRadialMenuView(this);
        mainContainer.addView(dualRadialMenu, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    public void sendStroke(String key, boolean pressed) {
        if (commandStream == null) return;
        new Thread(() -> {
            try {
                commandStream.write(new byte[]{ (byte)(pressed ? 1 : 0), (byte)key.charAt(0) });
                commandStream.flush();
            } catch (Exception ignored) {}
        }).start();
    }

    public void setModL1(boolean active) { this._modL1Activo = active; }
    public void setModR1(boolean active) { this._modR1Activo = active; }
    public boolean isModL1() { return _modL1Activo; }
    public boolean isModR1() { return _modR1Activo; }

    private void connectAndStream() {
        try {
            socket = new Socket(PC_IP, 8888);
            videoStream = socket.getInputStream();
            commandStream = socket.getOutputStream();
            byte[] sizeBuffer = new byte[4];
            while (isRunning) {
                int bytesRead = videoStream.read(sizeBuffer, 0, 4);
                if (bytesRead == -1) break;
                int size = ByteBuffer.wrap(sizeBuffer).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
                if (size <= 0) continue;
                byte[] imgBuffer = new byte[size];
                int read = 0;
                while (read < size) {
                    int result = videoStream.read(imgBuffer, read, size - read);
                    if (result == -1) break;
                    read += result;
                }
                Bitmap bmp = BitmapFactory.decodeByteArray(imgBuffer, 0, imgBuffer.length);
                if (bmp != null && surfaceHolder.getSurface().isValid()) {
                    Canvas canvas = surfaceHolder.lockCanvas();
                    if (canvas != null) {
                        canvas.drawBitmap(bmp, 0, 0, null);
                        surfaceHolder.unlockCanvasAndPost(canvas);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    @Override public void surfaceCreated(SurfaceHolder holder) {}
    @Override public void surfaceChanged(SurfaceHolder holder, int f, int w, int h) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) { isRunning = false; }
}

// ============================================================
// 🕹️ VISTA D-PAD ANALÓGICA NATAL DE ANDROID (Estilo Consola)
// ============================================================
class DPadView extends View {
    private MainActivity act;
    private Paint pBase, pStick;
    private float cX, cY, sX, sY, rBase, rStick;
    private String lastX = "", lastY = "";

    public DPadView(Context context) {
        super(context);
        act = (MainActivity) context;
        pBase = new Paint(Paint.ANTI_ALIAS_FLAG);
        pBase.setColor(Color.argb(40, 255, 255, 255));
        pStick = new Paint(Paint.ANTI_ALIAS_FLAG);
        pStick.setColor(Color.argb(120, 15, 28, 48));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cX = w / 2f; cY = h / 2f; sX = cX; sY = cY;
        rBase = Math.min(w, h) / 2.2f; rStick = Math.min(w, h) / 4.5f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawCircle(cX, cY, rBase, pBase);
        canvas.drawCircle(sX, sY, rStick, pStick);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
            float absX = event.getX() - cX;
            float absY = event.getY() - cY;
            float dist = (float) Math.sqrt(absX * absX + absY * absY);
            if (dist < rBase) {
                sX = event.getX(); sY = event.getY();
            } else {
                sX = cX + (absX / dist) * rBase; sY = cY + (absY / dist) * rBase;
            }
            invalidate();

            float xPct = (sX - cX) / rBase;
            float yPct = -(sY - cY) / rBase;

            String curX = (xPct > 0.35f) ? "D" : (xPct < -0.35f) ? "A" : "";
            String curY = (yPct > 0.35f) ? "W" : (yPct < -0.35f) ? "S" : "";

            if (!curX.equals(lastX)) {
                if (!lastX.isEmpty()) act.sendStroke(lastX, false);
                if (!curX.isEmpty()) act.sendStroke(curX, true);
                lastX = curX;
            }
            if (!curY.equals(lastY)) {
                if (!lastY.isEmpty()) act.sendStroke(lastY, false);
                if (!curY.isEmpty()) act.sendStroke(curY, true);
                lastY = curY;
            }
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            sX = cX; sY = cY;
            invalidate();
            if (!lastX.isEmpty()) act.sendStroke(lastX, false);
            if (!lastY.isEmpty()) act.sendStroke(lastY, false);
            lastX = ""; lastY = "";
            return true;
        }
        return false;
    }
}
// ============================================================
// 🔘 BOTONERA DE JUEGO REDONDA (X, Y, B) EXCLUSIVA DE HABILIDADES
// ============================================================
class ActionButtonsView extends View {
    private MainActivity act;
    private Paint pBtn, pText;
    private float[][] bPos; 
    private String[] tags = {"X", "Y", "B"};
    private float radius;
    private int pressedIdx = -1;

    public ActionButtonsView(Context context) {
        super(context);
        act = (MainActivity) context;
        pBtn = new Paint(Paint.ANTI_ALIAS_FLAG);
        pText = new Paint(Paint.ANTI_ALIAS_FLAG);
        pText.setColor(Color.rgb(245, 200, 95));
        pText.setTextSize(32);
        pText.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        pText.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        radius = act.dp(36);
        bPos = new float[3][2]; 
        bPos[0] = new float[]{radius, h / 2f};              // X (Izquierda)
        bPos[1] = new float[]{w / 2f, radius};              // Y (Arriba)
        bPos[2] = new float[]{w - radius, h / 2f};          // B (Derecha)
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (int i = 0; i < 3; i++) {
            if (i == pressedIdx) {
                pBtn.setColor(Color.argb(180, 245, 200, 95));
                pText.setColor(Color.rgb(5, 10, 20));
            } else {
                pBtn.setColor(Color.argb(100, 15, 28, 48));
                pText.setColor(Color.rgb(245, 200, 95));
            }
            canvas.drawCircle(bPos[i][0], bPos[i][1], radius, pBtn);
            
            String label = tags[i];
            if (act.isModL1()) label = (i == 0) ? "L5" : (i == 1) ? "L6" : "L7";
            else if (act.isModR1()) label = (i == 0) ? "R9" : (i == 1) ? "R10" : "R11";
            
            canvas.drawText(label, bPos[i][0], bPos[i][1] + 11f, pText);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX(); float y = event.getY();
        if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
            int oldIdx = pressedIdx;
            pressedIdx = -1;
            for (int i = 0; i < 3; i++) {
                float dx = x - bPos[i][0]; float dy = y - bPos[i][1];
                if (Math.sqrt(dx * dx + dy * dy) < radius) {
                    pressedIdx = i; break;
                }
            }
            if (pressedIdx != oldIdx) {
                if (oldIdx != -1) act.sendStroke(getMapKey(oldIdx), false);
                if (pressedIdx != -1) act.sendStroke(getMapKey(pressedIdx), true);
                invalidate();
            }
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            if (pressedIdx != -1) act.sendStroke(getMapKey(pressedIdx), false);
            pressedIdx = -1;
            invalidate();
            return true;
        }
        return false;
    }

    private String getMapKey(int idx) {
        if (!act.isModL1() && !act.isModR1()) return (idx == 0) ? "1" : (idx == 1) ? "2" : "3";
        if (act.isModL1()) return (idx == 0) ? "5" : (idx == 1) ? "6" : "7";
        return (idx == 0) ? "9" : (idx == 1) ? "0" : "F";
    }
}

// ============================================================
// 🕹️ GATILLOS MODIFICADORES OVALADOS ERGONÓMICOS (L1 / R1)
// ============================================================
class TriggerButtonsView extends View {
    private MainActivity act;
    private Paint pBox, pTxt;
    private float l, t, r, b, r_l, r_t, r_r, r_b;
    private boolean l1Click = false, r1Click = false;

    public TriggerButtonsView(Context context) {
        super(context);
        act = (MainActivity) context;
        pBox = new Paint(Paint.ANTI_ALIAS_FLAG);
        pTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pTxt.setColor(Color.rgb(245, 200, 95));
        pTxt.setTextSize(28);
        pTxt.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        pTxt.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float wB = act.dp(130); float hB = act.dp(55);
        l = act.dp(40); t = act.dp(30); r = l + wB; b = t + hB;
        r_r = w - act.dp(40); r_t = act.dp(30); r_l = r_r - wB; r_b = r_t + hB;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        pBox.setColor(l1Click ? Color.argb(160, 245, 200, 95) : Color.argb(80, 15, 28, 48));
        canvas.drawRoundRect(l, t, r, b, 25f, 25f, pBox);
        canvas.drawText("L1", (l + r) / 2f, (t + b) / 2f + 10f, pTxt);

        pBox.setColor(r1Click ? Color.argb(160, 245, 200, 95) : Color.argb(80, 15, 28, 48));
        canvas.drawRoundRect(r_l, r_t, r_r, r_b, 25f, 25f, pBox);
        canvas.drawText("R1", (r_l + r_r) / 2f, (r_t + r_b) / 2f + 10f, pTxt);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX(); float y = event.getY();
        if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
            boolean curL1 = (x >= l && x <= r && y >= t && y <= b);
            boolean curR1 = (x >= r_l && x <= r_r && y >= r_t && y <= r_b);
            if (curL1 != l1Click || curR1 != r1Click) {
                l1Click = curL1; r1Click = curR1;
                act.setModL1(l1Click); act.setModR1(r1Click);
                invalidate();
            }
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            l1Click = false; r1Click = false;
            act.setModL1(false); act.setModR1(false);
            invalidate();
            return true;
        }
        return false;
    }
}

// ============================================================
// 👑 CAPA DE MENÚS RADIALES DOBLES INVISIBLES (ARRIBA Y ABAJO)
// ============================================================
class DualRadialMenuView extends View {
    private MainActivity act;
    private boolean activo = false;
    private float mX, mY;
    private int selectedIdx = -1;
    private boolean esMenuSuperior = false;

    private List<String> optsInferior = new ArrayList<>();
    private List<Character> keysInferior = new ArrayList<>();
    
    private List<String> optsSuperior = new ArrayList<>();
    private List<Character> keysSuperior = new ArrayList<>();

    private Paint pBase, pTxt, pSel;

    public DualRadialMenuView(Context context) {
        super(context);
        act = (MainActivity) context;

        // Menú Centro Abajo (Interfaz de WoW)
        optsInferior.add("Personaje");  keysInferior.add('C');
        optsInferior.add("Inventario"); keysInferior.add('I');
        optsInferior.add("Hechizos");   keysInferior.add('P');
        optsInferior.add("Mazmorras");  keysInferior.add('L');
        optsInferior.add("BGs");        keysInferior.add('H');

        // Menú Centro Arriba (Utilidades Desacopladas de Habilidades)
        optsSuperior.add("Saltar");     keysSuperior.add('J');
        optsSuperior.add("Montura");    keysSuperior.add('M');
        optsSuperior.add("Poción");     keysSuperior.add('P');

        pBase = new Paint(Paint.ANTI_ALIAS_FLAG);
        pBase.setColor(Color.argb(190, 15, 28, 48));
        pTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pTxt.setColor(Color.rgb(245, 200, 95));
        pTxt.setTextSize(28);
        pTxt.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        pTxt.setTextAlign(Paint.Align.CENTER);
        pSel = new Paint(Paint.ANTI_ALIAS_FLAG);
        pSel.setColor(Color.argb(150, 245, 200, 95));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX(); float y = event.getY();
        float tercioX = getWidth() / 3f;

        if (!activo && (x < tercioX || x > tercioX * 2)) return false;

        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            mX = x; mY = y; activo = true;
            esMenuSuperior = (y < getHeight() / 2f);
            selectedIdx = -1;
            invalidate();
            return true;
        }

        if (event.getAction() == MotionEvent.ACTION_MOVE && activo) {
            float dx = x - mX; float dy = y - mY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            int totalOpts = esMenuSuperior ? optsSuperior.size() : optsInferior.size();

            if (dist > act.dp(20)) {
                float ang = (float) Math.atan2(dy, dx);
                if (ang < 0) ang += (float) (Math.PI * 2);
                float step = (float) (Math.PI * 2 / totalOpts);
                selectedIdx = (int) (ang / step);
                if (selectedIdx >= totalOpts) selectedIdx = totalOpts - 1;
            } else {
                selectedIdx = -1;
            }
            invalidate();
            return true;
        }

        if (event.getAction() == MotionEvent.ACTION_UP && activo) {
            activo = false;
            if (selectedIdx != -1) {
                char k = esMenuSuperior ? keysSuperior.get(selectedIdx) : keysInferior.get(selectedIdx);
                act.sendStroke(String.valueOf(k), true);
                post(() -> act.sendStroke(String.valueOf(k), false));
            }
            selectedIdx = -1;
            invalidate();
            return true;
        }
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!activo) return;

        canvas.drawCircle(mX, mY, act.dp(25), pBase);
List<String> list = esMenuSuperior ? optsSuperior : optsInferior; // CORREGIDO: Declaración de tipo genérico estricta
float step = (float) (Math.PI * 2 / list.size());
float rMenu = act.dp(100);
for (int i = 0; i < list.size(); i++) {
float ang = i * step;
float pX = mX + (float) Math.cos(ang) * rMenu;
float pY = mY + (float) Math.sin(ang) * rMenu;
if (i == selectedIdx) {
canvas.drawCircle(pX, pY, act.dp(55), pSel);
pTxt.setColor(Color.rgb(5, 10, 20));
} else {
canvas.drawCircle(pX, pY, act.dp(48), pBase);
pTxt.setColor(Color.rgb(245, 200, 95));
}
canvas.drawText(list.get(i), pX, pY + 10f, pTxt);
}
}
}
