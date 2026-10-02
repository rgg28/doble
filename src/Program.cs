using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;

class Program
{
    private static HttpListener? _httpServer;
    private static IntPtr _wowHandle = IntPtr.Zero;
    private static Process? _wowProcess;

    private static Form? _mainForm;
    private static TextBox? _txtWowPath;
    private static Button? _btnBrowse;
    private static Button? _btnStart;
    private static Label? _lblStatus;

    [DllImport("user32.dll")]
    private static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern bool GetClientRect(IntPtr hWnd, out RECT lpRect);

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    private const uint WM_KEYDOWN = 0x0100;
    private const uint WM_KEYUP = 0x0101;
    private const uint WM_LBUTTONDOWN = 0x0201; 
    private const uint WM_LBUTTONUP   = 0x0202; 
    private const uint WM_RBUTTONDOWN = 0x0204; 
    private const uint WM_RBUTTONUP   = 0x0205; 

    [STAThread] 
    static void Main(string[] args)
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        _mainForm = new Form
        {
            Text = "WoW AirCast Server (HTTP)",
            Width = 520,
            Height = 180,
            FormBorderStyle = FormBorderStyle.FixedSingle,
            MaximizeBox = false,
            StartPosition = FormStartPosition.CenterScreen,
            BackColor = Color.FromArgb(28, 32, 38)
        };

        Label lblPath = new Label
        {
            Text = "Ruta de Wow.exe:",
            Left = 20,
            Top = 20,
            Width = 120,
            ForeColor = Color.White,
            Font = new Font("Segoe UI", 9, FontStyle.Bold)
        };

        _txtWowPath = new TextBox
        {
            Left = 20,
            Top = 45,
            Width = 360,
            Text = @"C:\Program Files (x86)\World of Warcraft\_retail_\Wow.exe",
            BackColor = Color.FromArgb(45, 50, 58),
            ForeColor = Color.White,
            BorderStyle = BorderStyle.FixedSingle,
            Font = new Font("Segoe UI", 9)
        };

        _btnBrowse = new Button { Text = "Buscar...", Left = 390, Top = 43, Width = 90, Height = 25, BackColor = Color.FromArgb(65, 75, 85), ForeColor = Color.White, FlatStyle = FlatStyle.Flat };
        _btnBrowse.Click += BtnBrowse_Click;

        _btnStart = new Button { Text = "INICIAR AIR-CAST", Left = 20, Top = 90, Width = 200, Height = 35, BackColor = Color.FromArgb(75, 152, 105), ForeColor = Color.White, FlatStyle = FlatStyle.Flat, Font = new Font("Segoe UI", 10, FontStyle.Bold) };
        _btnStart.Click += BtnStart_Click;

        _lblStatus = new Label { Text = "Estado: AirCast detenido.", Left = 230, Top = 100, Width = 250, ForeColor = Color.Gray, Font = new Font("Segoe UI", 9, FontStyle.Italic) };

        _mainForm.Controls.Add(lblPath); _mainForm.Controls.Add(_txtWowPath); _mainForm.Controls.Add(_btnBrowse); _mainForm.Controls.Add(_btnStart); _mainForm.Controls.Add(_lblStatus);
        Application.Run(_mainForm);
    }

    private static void BtnBrowse_Click(object? sender, EventArgs e)
    {
        using (OpenFileDialog openFileDialog = new OpenFileDialog())
        {
            openFileDialog.Filter = "Ejecutable de WoW (*.exe)|*.exe";
            if (openFileDialog.ShowDialog() == DialogResult.OK) _txtWowPath!.Text = openFileDialog.FileName;
        }
    }

    private static void BtnStart_Click(object? sender, EventArgs e)
    {
        string wowPath = _txtWowPath?.Text ?? "";
        if (!File.Exists(wowPath)) return;

        try
        {
            _btnStart!.Enabled = false; _btnBrowse!.Enabled = false; _txtWowPath!.Enabled = false;
            _lblStatus!.Text = "Estado: Levantando Web Cast..."; _lblStatus.ForeColor = Color.Orange;

            ProcessStartInfo startInfo = new ProcessStartInfo { FileName = wowPath, Arguments = "-windowed", UseShellExecute = true };
            _wowProcess = Process.Start(startInfo);
            
            Thread webThread = new Thread(StartHttpServerLogic) { IsBackground = true };
            webThread.Start();
        }
        catch (Exception ex) { MessageBox.Show(ex.Message); ResetUI(); }
    }

    // ============================================================
    // MOTOR HTTP DE TRANSMISIÓN DIRECTA (TIPO AIRDROID CAST WEB)
    // ============================================================
    private static void StartHttpServerLogic()
    {
        try
        {
            if (_wowProcess != null)
            {
                Thread.Sleep(3000);
                _wowProcess.Refresh();
                _wowHandle = _wowProcess.MainWindowHandle;
            }

            _httpServer = new HttpListener();
            // Abre la puerta web en el puerto 8080 para cualquier dispositivo de la casa
            _httpServer.Prefixes.Add("http://*:8080/");
            _httpServer.Start();

            _mainForm?.Invoke((MethodInvoker)delegate {
                _lblStatus!.Text = "¡Web en línea! Entra a http://TU_PC_IP:8080";
                _lblStatus.ForeColor = Color.LightGreen;
            });

            while (_httpServer.IsListening)
            {
                HttpListenerContext context = _httpServer.GetContext();
                ThreadPool.QueueUserWorkItem(o => HandleClientWebRequest(context));
            }
        }
        catch (Exception ex) { MessageBox.Show(ex.Message); }
    }

    private static void HandleClientWebRequest(HttpListenerContext context)
    {
        HttpListenerRequest request = context.Request;
        HttpListenerResponse response = context.Response;

        try
        {
            // PROCESADOR DE ENTRADAS DEL NAVEGADOR (Clicks del móvil)
            if (request.Url?.AbsolutePath == "/input")
            {
                string pctX = request.QueryString["x"] ?? "0";
                string pctY = request.QueryString["y"] ?? "0";
                string action = request.QueryString["a"] ?? "0"; // 1=Down, 0=Up

                float x = float.Parse(pctX, System.Globalization.CultureInfo.InvariantCulture);
                float y = float.Parse(pctY, System.Globalization.CultureInfo.InvariantCulture);

                if (GetClientRect(_wowHandle, out RECT rect))
                {
                    int w = rect.Right - rect.Left;
                    int h = rect.Bottom - rect.Top;
                    IntPtr lParam = (IntPtr)(((int)(y * h) << 16) | ((int)(x * w) & 0xFFFF));
                    
                    uint msg = (y > 0.70f) ? 
                        ((action == "1") ? WM_LBUTTONDOWN : WM_LBUTTONUP) : 
                        ((action == "1") ? WM_RBUTTONDOWN : WM_RBUTTONUP);

                    PostMessage(_wowHandle, msg, IntPtr.Zero, lParam);
                }

                response.StatusCode = (int)HttpStatusCode.OK;
                response.Close();
                return;
            }

            // TRANSMISIÓN DE FOTOGRAMAS JPEG EN BUCLE (MJPEG Streamer nativo)
            if (request.Url?.AbsolutePath == "/stream")
            {
                response.ContentType = "multipart/x-mixed-replace; boundary=--frame";
                response.StatusCode = (int)HttpStatusCode.OK;

                using (Stream output = response.OutputStream)
                {
                    while (_wowProcess != null && !_wowProcess.HasExited)
                    {
                        using (Bitmap bmp = new Bitmap(1280, 720))
                        {
                            using (Graphics g = Graphics.FromImage(bmp))
                            {
                                g.CopyFromScreen(0, 0, 0, 0, bmp.Size);
                            }

                            using (MemoryStream ms = new MemoryStream())
                            {
                                EncoderParameters encoderParams = new EncoderParameters(1);
                                encoderParams.Param = new EncoderParameter[] { new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 50L) };
                                ImageCodecInfo? jpegCodec = GetEncoder(ImageFormat.Jpeg);
                                
                                if (jpegCodec != null)
                                {
                                    bmp.Save(ms, jpegCodec, encoderParams);
                                    byte[] imgBytes = ms.ToArray();

                                    string header = $"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: {imgBytes.Length}\r\n\r\n";
                                    byte[] headerBytes = Encoding.ASCII.GetBytes(header);
                                    
                                    output.Write(headerBytes, 0, headerBytes.Length);
                                    output.Write(imgBytes, 0, imgBytes.Length);
                                    output.Write(Encoding.ASCII.GetBytes("\r\n"), 0, 2);
                                    output.Flush();
                                }
                            }
                        }
                        Thread.Sleep(45); // ~22 FPS estables sin latencia por Wi-Fi
                    }
                }
                return;
            }

            // INTERFAZ DE USUARIO (HTML5 + Javascript táctil nativo para móviles)
            string html = @"
            <!DOCTYPE html>
            <html>
            <head>
                <meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>
                <style>
body, html { margin:0; padding:0; width:100%; height:100%; background:#000; overflow:hidden; }
#screen { width:100vw; height:100vh; object-fit:contain; display:block; }





const screen = document.getElementById('screen');
function sendInput(e, action) {
const rect = screen.getBoundingClientRect();
const touch = e.touches[0] || e.changedTouches[0];
const x = (touch.clientX - rect.left) / rect.width;
const y = (touch.clientY - rect.top) / rect.height;
if(x >= 0 && x <= 1 && y >= 0 && y <= 1) {
fetch(/input?x=${x}&y=${y}&a=${action});
}
}
screen.addEventListener('touchstart', (e) => { e.preventDefault(); sendInput(e, 1); });
screen.addEventListener('touchend', (e) => { e.preventDefault(); sendInput(e, 0); });


";
byte[] htmlBytes = Encoding.UTF8.GetBytes(html);
response.ContentType = "text/html";
response.ContentLength64 = htmlBytes.Length;
response.OutputStream.Write(htmlBytes, 0, htmlBytes.Length);
response.Close();
}
catch { try { response.Close(); } catch {} }
}
private static void ResetUI() { _btnStart!.Invoke((MethodInvoker)(() => { _btnStart.Enabled = true; _btnBrowse!.Enabled = true; _txtWowPath!.Enabled = true; })); }
private static ImageCodecInfo? GetEncoder(ImageFormat format) { foreach (ImageCodecInfo codec in ImageCodecInfo.GetImageEncoders()) { if (codec.FormatID == format.Guid) return codec; } return null; }
}
