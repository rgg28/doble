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
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
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

    // --- ESTADOS DE LOS MODIFICADORES EXTRAÍDOS DE MOBILEUI.CS ---
    private boolean _modL1Activo = false;
    private boolean _modR1Activo = false;

    // --- COMPONENTES DEL SET DE CONTROLES UNIFICADO ---
    private RadialMenuView radialMenu;
    private Button btnX, btnY, btnB, btnA;
    private boolean isRadialMenuActive = false;

    // --- LA IP DE TU PC EN TU RED LOCAL OFFLINE ---
    private static final String PC_IP = "192.168.1.50"; 

    // --- TUS COLORES Y ESTILOS NATIVOS CORPORATIVOS ---
    private int bg() { return Color.rgb(5, 10, 20); }
    private int panel2() { return Color.rgb(15, 28, 48); }
    private int goldBright() { return Color.rgb(245, 200, 95); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        
        mainContainer = new FrameLayout(this);
        mainContainer.setBackgroundColor(bg());
        setContentView(mainContainer);

        // 1. Iniciar la pantalla de video para recibir la instancia de WoW desde la PC
        surfaceView = new SurfaceView(this);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);
        mainContainer.addView(surfaceView);

        // 2. Superponer absolutamente todos los controles táctiles nativos de los 2 archivos
        setupInterfaceControls();

        // 3. Agregar la capa superior transparente para el Menú Radial (MobileUIRadialMenu.cs)
        radialMenu = new RadialMenuView(this);
        mainContainer.addView(radialMenu, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Enlazar la selección angular del menú radial con tu flujo de red por bytes
        radialMenu.setOnRadialSelectListener(keyChar -> {
            sendStroke(String.valueOf(keyChar), true);
            mainContainer.postDelayed(() -> sendStroke(String.valueOf(keyChar), false), 50);
        });

        // Configurar el capturador de gestos globales para el tercio central de la pantalla
        setupRadialTouchTrigger();

        // 4. Conectar al servidor de la PC en un hilo independiente
        isRunning = true;
        new Thread(this::connectAndStream).start();
    }

    private void setupInterfaceControls() {
        FrameLayout overlay = new FrameLayout(this);

        // ============================================================
        // 🚀 GATILLO MODIFICADOR IZQUIERDO: L1 (Posición exacta de MobileUI.cs)
        // ============================================================
        Button btnL1 = new Button(this);
        btnL1.setText("MOD (L1)");
        btnL1.setTextColor(goldBright());
        btnL1.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        btnL1.setBackgroundColor(panel2());
        FrameLayout.LayoutParams l1Params = new FrameLayout.LayoutParams(dp(140), dp(70));
        l1Params.gravity = Gravity.TOP | Gravity.LEFT;
        l1Params.setMargins(dp(60), dp(40), 0, 0);
        btnL1.setLayoutParams(l1Params);

        btnL1.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                _modL1Activo = true;
                actualizarTextosBotones();
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                _modL1Activo = false;
                actualizarTextosBotones();
            }
            return true;
        });
        overlay.addView(btnL1);

        // ============================================================
        // 🚀 GATILLO MODIFICADOR DERECHO: R1 (Posición exacta de MobileUI.cs)
        // ============================================================
        Button btnR1 = new Button(this);
        btnR1.setText("MOD (R1)");
        btnR1.setTextColor(goldBright());
        btnR1.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        btnR1.setBackgroundColor(panel2());
        FrameLayout.LayoutParams r1Params = new FrameLayout.LayoutParams(dp(140), dp(70));
        r1Params.gravity = Gravity.TOP | Gravity.RIGHT;
        r1Params.setMargins(0, dp(40), dp(60), 0);
        btnR1.setLayoutParams(r1Params);

        btnR1.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                _modR1Activo = true;
                actualizarTextosBotones();
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                _modR1Activo = false;
                actualizarTextosBotones();
            }
            return true;
        });
        overlay.addView(btnR1);

        // ============================================================
        // 🚀 D-PAD DIRECCIONAL DE MOVIMIENTO (W, A, S, D)
        // ============================================================
        GridLayout dPad = new GridLayout(this);
        dPad.setColumnCount(3);
        dPad.setRowCount(3);
        FrameLayout.LayoutParams dPadParams = new FrameLayout.LayoutParams(dp(165), dp(165));
        dPadParams.gravity = Gravity.BOTTOM | Gravity.LEFT;
        dPadParams.setMargins(dp(100), 0, 0, dp(100)); // Alineación de tu base nativa
        dPad.setLayoutParams(dPadParams);

        dPad.addView(new View(this)); dPad.addView(createTouchButton("W")); dPad.addView(new View(this));
        dPad.addView(createTouchButton("A")); dPad.addView(new View(this)); dPad.addView(createTouchButton("D"));
        dPad.addView(new View(this)); dPad.addView(createTouchButton("S")); dPad.addView(new View(this));
        overlay.addView(dPad);

        // ============================================================
        // 🚀 BOTONERA EN ROMBO DERECHA (X, Y, B, A) DE TU ARCHIVO ORIGINAL
        // ============================================================
        FrameLayout buttonsContainer = new FrameLayout(this);
        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(dp(240), dp(240));
        containerParams.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        containerParams.setMargins(0, 0, dp(100), dp(100));
        buttonsContainer.setLayoutParams(containerParams);

        btnX = createGameButton("X");
        btnY = createGameButton("Y");
        btnB = createGameButton("B");
        btnA = createGameButton("A");

        // Distribución en cruz en base a las posiciones que definiste en Godot
        setButtonFramePosition(btnX, dp(0), dp(70));
        setButtonFramePosition(btnY, dp(80), dp(0));
        setButtonFramePosition(btnB, dp(160), dp(70));
        setButtonFramePosition(btnA, dp(80), dp(140));

        buttonsContainer.addView(btnX);
        buttonsContainer.addView(btnY);
        buttonsContainer.addView(btnB);
        buttonsContainer.addView(btnA);
        overlay.addView(buttonsContainer);

        mainContainer.addView(overlay);
        actualizarTextosBotones();
    }

    private void setupRadialTouchTrigger() {
        mainContainer.setOnTouchListener((v, event) -> {
            float screenWidth = v.getWidth();
            float tercio = screenWidth / 3f;
            float touchX = event.getX();

            // Interceptar toques en el tercio central exacto (Lógica estricta de MobileUI.cs)
            if (touchX > tercio && touchX < (tercio * 2)) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    isRadialMenuActive = true;
                    radialMenu.mostrarMenu(event.getX(), event.getY());
                    return true;
                }
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                isRadialMenuActive = false;
            }
            return radialMenu.dispatchTouchEvent(event);
        });
    }
    // ============================================================
    // 🚀 SISTEMA DE ENTRADA TÁCTIL NATIVO PARA EL D-PAD (W, A, S, D)
    // ============================================================
    private Button createTouchButton(final String key) {
        Button b = new Button(this);
        b.setText(key);
        b.setTextColor(goldBright());
        b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        b.setBackgroundColor(panel2());
        
        b.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                sendStroke(key, true); // Envía KeyDown directo a la PC
                b.setBackgroundColor(goldBright()); 
                b.setTextColor(bg());
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                sendStroke(key, false); // Envía KeyUp directo a la PC
                b.setBackgroundColor(panel2()); 
                b.setTextColor(goldBright());
            }
            return true;
        });
        return b;
    }

    // ============================================================
    // 🚀 CREACIÓN DINÁMICA DE LA BOTONERA EN ROMBO (X, Y, B, A)
    // ============================================================
    private Button createGameButton(final String tag) {
        Button b = new Button(this);
        b.setTextColor(goldBright());
        b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        b.setBackgroundColor(panel2());
        b.setFocusable(false);

        b.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                String teclaAEnviar = obtenerTeclaMapeadaActual(tag);
                sendStroke(teclaAEnviar, true);
                b.setBackgroundColor(goldBright()); 
                b.setTextColor(bg());
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                String teclaAEnviar = obtenerTeclaMapeadaActual(tag);
                sendStroke(teclaAEnviar, false);
                b.setBackgroundColor(panel2()); 
                b.setTextColor(goldBright());
            }
            return true;
        });
        return b;
    }

    // --- REPRODUCCIÓN MATEMÁTICA EXACTA DE TU ARCHIVO MOBILEUI.CS ---
    private void actualizarTextosBotones() {
        if (btnX == null || btnY == null || btnB == null || btnA == null) return;

        if (!_modL1Activo && !_modR1Activo) {
            btnX.setText("Hab 1"); btnY.setText("Hab 2"); btnB.setText("Hab 3"); btnA.setText("Saltar");
        } else if (_modL1Activo && !_modR1Activo) {
            btnX.setText("Spell L5"); btnY.setText("Spell L6"); btnB.setText("Spell L7"); btnA.setText("Montura");
        } else if (!_modL1Activo && _modR1Activo) {
            btnX.setText("Spell R9"); btnY.setText("Spell R10"); btnB.setText("Spell R11"); btnA.setText("Poción");
        }
    }

    private String obtenerTeclaMapeadaActual(String tag) {
        if (!_modL1Activo && !_modR1Activo) {
            switch (tag) { 
                case "X": return "1"; case "Y": return "2"; case "B": return "3"; case "A": return "J"; // J = Jump
            } 
        } else if (_modL1Activo && !_modR1Activo) {
            switch (tag) { 
                case "X": return "5"; case "Y": return "6"; case "B": return "7"; case "A": return "M"; // M = Montura
            } 
        } else if (!_modL1Activo && _modR1Activo) {
            switch (tag) { 
                case "X": return "9"; case "Y": return "0"; case "B": return "F"; case "A": return "P"; // P = Poción
            } 
        }
        return "1";
    }

    private void setButtonFramePosition(Button b, int x, int y) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(80), dp(80));
        p.leftMargin = x; 
        p.topMargin = y;
        b.setLayoutParams(p);
    }

    // ============================================================
    // 🚀 PROTOCOLO DE RED: ENVÍO DE RÁFAGAS POR SOCKET BINARIO
    // ============================================================
    private void sendStroke(String key, boolean pressed) {
        if (commandStream == null) return;
        new Thread(() -> {
            try {
                // Inyecta el buffer binario de 2 bytes exigido por tu servidor en PC: [Acción][Tecla]
                commandStream.write(new byte[]{ (byte)(pressed ? 1 : 0), (byte)key.charAt(0) });
                commandStream.flush();
            } catch (Exception ignored) {}
        }).start();
    }

    // ============================================================
    // 🚀 BUCLE RECEPTOR DE STREAMING DE VIDEO (DECÓDER DE BUFFERS JPEG)
    // ============================================================
    private void connectAndStream() {
        try {
            socket = new Socket(PC_IP, 8888);
            videoStream = socket.getInputStream();
            commandStream = socket.getOutputStream();

            byte[] sizeBuffer = new byte[4];
            while (isRunning) {
                // Leer el tamaño del encabezado Little Endian de 4 bytes enviado por el PC
                int bytesRead = videoStream.read(sizeBuffer, 0, 4);
                if (bytesRead == -1) break;
                
                int size = ByteBuffer.wrap(sizeBuffer).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
                if (size <= 0) continue;

                // Leer secuencialmente el cuerpo binario de la imagen reducida
                byte[] imgBuffer = new byte[size];
                int read = 0;
                while (read < size) {
                    int result = videoStream.read(imgBuffer, read, size - read);
                    if (result == -1) break;
                    read += result;
                }

                // Renderizado directo en el Canvas de bajo nivel por debajo de los controles táctiles
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

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    @Override public void surfaceCreated(SurfaceHolder holder) {}
    @Override public void surfaceChanged(SurfaceHolder holder, int f, int w, int h) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) { isRunning = false; }
}

// ============================================================
// 🎡 CLASE GRÁFICA DEL MENÚ RADIAL COMPLETO (MobileUIRadialMenu.cs)
// ============================================================
class RadialMenuView extends View {
    public interface OnRadialSelectListener { void onSelect(char keyChar); }
    private OnRadialSelectListener selectListener;

    private boolean activo = false;
    private float mX, mY;
    private int seleccionadoIndex = -1;
    private final float radioMenu = 175f;

    private final List<String> opciones = new ArrayList<>();
    private final List<Character> teclasMapeadas = new ArrayList<>();
    private Paint pBase, pText, pSelect;

    public RadialMenuView(Context context) {
        super(context);
        // Tus 5 opciones del menú radial con los opcodes mapeados al teclado físico de la PC
        opciones.add("Personaje");   teclasMapeadas.add('C'); // C = TOGGLE_CHARACTER_SHEET
        opciones.add("Inventario");  teclasMapeadas.add('I'); // I = TOGGLE_BAGS
        opciones.add("Hechizos");    teclasMapeadas.add('P'); // P = TOGGLE_SPELLBOOK
        opciones.add("Mazmorras");   teclasMapeadas.add('L'); // L = TOGGLE_LFG_PARENT
        opciones.add("BGs");         teclasMapeadas.add('H'); // H = TOGGLE_BATTLEGROUND

        pBase = new Paint(Paint.ANTI_ALIAS_FLAG);
        pBase.setColor(Color.rgb(15, 28, 48)); // Color panel2 corporativo

        pText = new Paint(Paint.ANTI_ALIAS_FLAG);
        pText.setColor(Color.rgb(245, 200, 95)); // Color goldBright corporativo
        pText.setTextSize(30);
        pText.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        pText.setTextAlign(Paint.Align.CENTER);

        pSelect = new Paint(Paint.ANTI_ALIAS_FLAG);
        pSelect.setColor(Color.argb(140, 245, 200, 95)); // Resaltado de selección en oro
    }

    public void setOnRadialSelectListener(OnRadialSelectListener listener) { this.selectListener = listener; }

    public void mostrarMenu(float x, float y) {
        this.mX = x; this.mY = y; this.activo = true; this.seleccionadoIndex = -1;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!activo) return;

        // Núcleo o eje central del control táctil radial
        canvas.drawCircle(mX, mY, 45f, pBase);
        float anguloPaso = (float) (Math.PI * 2 / opciones.size());

        for (int i = 0; i < opciones.size(); i++) {
            float anguloActual = i * anguloPaso;
            float posX = mX + (float) Math.cos(anguloActual) * radioMenu;
            float posY = mY + (float) Math.sin(anguloActual) * radioMenu;

            // Iluminación selectiva de los gajos en 360 grados según el arrastre del dedo
            if (i == seleccionadoIndex) {
                canvas.drawCircle(posX, posY, 80f, pSelect);
                pText.setColor(Color.rgb(5, 10, 20));
            } else {
                canvas.drawCircle(posX, posY, 70f, pBase);
                pText.setColor(Color.rgb(245, 200, 95));
            }
            canvas.drawText(opciones.get(i), posX, posY + 10f, pText);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!activo) return false;

if (event.getAction() == MotionEvent.ACTION_MOVE) {
float dragX = event.getX() - mX;
float dragY = event.getY() - mY;
float distancia = (float) Math.sqrt(dragX * dragX + dragY * dragY);
// Umbral matemático exacto de tu zona muerta central para evitar fallos por oscilación
if (distancia > 50f) {
float angulo = (float) Math.atan2(dragY, dragX);
if (angulo < 0) angulo += (float) (Math.PI * 2);
float tamanoSector = (float) (Math.PI * 2 / opciones.size());
seleccionadoIndex = (int) (angulo / tamanoSector);
if (seleccionadoIndex >= opciones.size()) seleccionadoIndex = opciones.size() - 1;
} else {
seleccionadoIndex = -1;
}
invalidate();
return true;
}
else if (event.getAction() == MotionEvent.ACTION_UP) {
activo = false;
if (selectListener != null && seleccionadoIndex != -1) {
selectListener.onSelect(teclasMapeadas.get(seleccionadoIndex));
}
seleccionadoIndex = -1;
invalidate();
return true;
}
return false;
}
}
