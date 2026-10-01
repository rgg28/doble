package com.wowmobile.client;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
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
import android.widget.LinearLayout;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private FrameLayout mainContainer;
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    
    private Socket socket;
    private InputStream videoStream;
    private OutputStream commandStream;
    private boolean isRunning = false;

    // --- LA IP DE TU PC EN TU RED LOCAL OFFLINE ---
    private static final String PC_IP = "192.168.1.50"; 

    // --- TUS COLORES Y ESTILOS NATIVOS EXACTOS ---
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

        // 1. Iniciar la pantalla de video para recibir la instancia reducida de WoW
        surfaceView = new SurfaceView(this);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);
        mainContainer.addView(surfaceView);

        // 2. Superponer tus controles táctiles nativos
        setupInterfaceControls();

        // 3. Conectar al servidor de la PC en un hilo independiente
        isRunning = true;
        new Thread(this::connectAndStream).start();
    }

    private void setupInterfaceControls() {
        FrameLayout overlay = new FrameLayout(this);

        // D-PAD Izquierdo (W, A, S, D) con tu formato panel2
        GridLayout dPad = new GridLayout(this);
        dPad.setColumnCount(3);
        dPad.setRowCount(3);
        FrameLayout.LayoutParams dPadParams = new FrameLayout.LayoutParams(dp(165), dp(165));
        dPadParams.gravity = Gravity.BOTTOM | Gravity.LEFT;
        dPadParams.setMargins(dp(25), 0, 0, dp(25));
        dPad.setLayoutParams(dPadParams);

        dPad.addView(new View(this)); dPad.addView(createTouchButton("W")); dPad.addView(new View(this));
        dPad.addView(createTouchButton("A")); dPad.addView(new View(this)); dPad.addView(createTouchButton("D"));
        dPad.addView(new View(this)); dPad.addView(createTouchButton("S")); dPad.addView(new View(this));

        // Botones de acción derechos (1, 2)
        LinearLayout actionPanel = new LinearLayout(this);
        FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        actionParams.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        actionParams.setMargins(0, 0, dp(25), dp(25));
        actionPanel.setLayoutParams(actionParams);

        actionPanel.addView(createTouchButton("1"));
        actionPanel.addView(createTouchButton("2"));

        overlay.addView(dPad);
        overlay.addView(actionPanel);
        mainContainer.addView(overlay);
    }

    private Button createTouchButton(final String key) {
        Button b = new Button(this);
        b.setText(key);
        b.setTextColor(goldBright());
        b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        b.setBackgroundColor(panel2());
        
        b.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                sendStroke(key, true); // Presionado (KeyDown en PC)
                b.setBackgroundColor(goldBright());
                b.setTextColor(bg());
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                sendStroke(key, false); // Soltado (KeyUp en PC)
                b.setBackgroundColor(panel2());
                b.setTextColor(goldBright());
            }
            return true;
        });
        return b;
    }

    private void sendStroke(String key, boolean pressed) {
        if (commandStream == null) return;
        new Thread(() -> {
            try {
                // Envía la ráfaga de 2 bytes: [Acción][Tecla]
                commandStream.write(new byte[]{ (byte)(pressed ? 1 : 0), (byte)key.charAt(0) });
                commandStream.flush();
            } catch (Exception ignored) {}
        }).start();
    }

    private void connectAndStream() {
        try {
            socket = new Socket(PC_IP, 8888);
            videoStream = socket.getInputStream();
            commandStream = socket.getOutputStream();

            byte[] sizeBuffer = new byte[4];
            while (isRunning) {
                // Leer el tamaño del fotograma JPEG que redujo la PC
                int bytesRead = videoStream.read(sizeBuffer, 0, 4);
                if (bytesRead == -1) break;
                
                int size = ByteBuffer.wrap(sizeBuffer).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
                if (size <= 0) continue;

                // Leer los bytes de la imagen reducida
                byte[] imgBuffer = new byte[size];
                int read = 0;
                while (read < size) {
                    int result = videoStream.read(imgBuffer, read, size - read);
                    if (result == -1) break;
                    read += result;
                }

                // Renderizar el fotograma de WoW directamente debajo de tus controles nativos
                Bitmap bmp = BitmapFactory.decodeByteArray(imgBuffer, 0, imgBuffer.length);
                if (bmp != null && surfaceHolder.getSurface().isValid()) {
                    android.graphics.Canvas canvas = surfaceHolder.lockCanvas();
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
