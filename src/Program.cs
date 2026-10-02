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
    private static TcpListener? _streamServer;
    private static bool _isDiscoverable = true;
    private static IntPtr _wowHandle = IntPtr.Zero;
    private static Process? _wowProcess;

    // Componentes de la Interfaz Gráfica
    private static Form? _mainForm;
    private static TextBox? _txtWowPath;
    private static Button? _btnBrowse;
    private static Button? _btnStart;
    private static Label? _lblStatus;

    // ============================================================
    // MÉTODOS NATIVOS WIN32 (USER32.DLL)
    // ============================================================

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

    // ============================================================
    // ARRANQUE DE LA APLICACIÓN
    // ============================================================

    [STAThread] 
    static void Main(string[] args)
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        _mainForm = new Form
        {
            Text = "WoW Dual Stream Server",
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

        _btnBrowse = new Button
        {
            Text = "Buscar...",
            Left = 390,
            Top = 43,
            Width = 90,
            Height = 25,
            BackColor = Color.FromArgb(65, 75, 85),
            ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat
        };
        _btnBrowse.Click += BtnBrowse_Click;

        _btnStart = new Button
        {
            Text = "INICIAR SERVIDOR",
            Left = 20,
            Top = 90,
            Width = 200,
            Height = 35,
            BackColor = Color.FromArgb(75, 150, 205),
            ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat,
            Font = new Font("Segoe UI", 10, FontStyle.Bold)
        };
        _btnStart.Click += BtnStart_Click;

        _lblStatus = new Label
        {
            Text = "Estado: Servidor detenido.",
            Left = 230,
            Top = 100,
            Width = 250,
            ForeColor = Color.Gray,
            Font = new Font("Segoe UI", 9, FontStyle.Italic)
        };

        _mainForm.Controls.Add(lblPath);
        _mainForm.Controls.Add(_txtWowPath);
        _mainForm.Controls.Add(_btnBrowse);
        _mainForm.Controls.Add(_btnStart);
        _mainForm.Controls.Add(_lblStatus);

        Application.Run(_mainForm);
    }

    private static void BtnBrowse_Click(object? sender, EventArgs e)
    {
        using (OpenFileDialog openFileDialog = new OpenFileDialog())
        {
            openFileDialog.Filter = "Ejecutable de WoW (*.exe)|*.exe|Todos los archivos (*.*)|*.*";
            openFileDialog.Title = "Selecciona el archivo ejecutable de tu World of Warcraft";

            if (openFileDialog.ShowDialog() == DialogResult.OK)
            {
                if (_txtWowPath != null)
                {
                    _txtWowPath.Text = openFileDialog.FileName;
                }
            }
        }
    }

    private static void BtnStart_Click(object? sender, EventArgs e)
    {
        string wowPath = _txtWowPath?.Text ?? "";

        if (!File.Exists(wowPath))
        {
            MessageBox.Show("La ruta seleccionada no es válida o el archivo ejecutable no existe.", "Error de Ruta", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }

        try
        {
            _btnStart!.Enabled = false;
            _btnBrowse!.Enabled = false;
            _txtWowPath!.Enabled = false;
            _lblStatus!.Text = "Estado: Iniciando WoW e hilos de red...";
            _lblStatus.ForeColor = Color.Orange;

            ProcessStartInfo startInfo = new ProcessStartInfo 
            { 
                FileName = wowPath, 
                Arguments = "-windowed",
                UseShellExecute = true 
            };
            
            _wowProcess = Process.Start(startInfo);
            
            Thread serverThread = new Thread(RunServerNetworkLogic) { IsBackground = true };
            serverThread.Start();
        }
        catch (Exception ex)
        {
            MessageBox.Show($"Error crítico al arrancar el proceso del juego:\n\n{ex.Message}", "Fallo de Ejecución", MessageBoxButtons.OK, MessageBoxIcon.Error);
            ResetUI();
        }
    }

    private static void RunServerNetworkLogic()
    {
        try
        {
            if (_wowProcess != null)
            {
                Thread.Sleep(3000); 
                _wowProcess.Refresh();
                _wowHandle = _wowProcess.MainWindowHandle;
                
                try { _wowProcess.ProcessorAffinity = (IntPtr)0x30; } catch { /* Ignorar si falla */ }
            }

            _streamServer = new TcpListener(IPAddress.Any, 8888);
            _streamServer.Start();

            Thread udpThread = new Thread(StartUdpBeacon) { IsBackground = true };
            udpThread.Start();

            _mainForm?.Invoke((MethodInvoker)delegate {
                _lblStatus!.Text = "Estado: ¡En línea! Esperando móvil...";
                _lblStatus.ForeColor = Color.LightGreen;
            });

            while (true)
            {
                TcpClient client = _streamServer.AcceptTcpClient();
                
                _mainForm?.Invoke((MethodInvoker)delegate {
                    _lblStatus!.Text = "Estado: ¡Celular Conectado!";
                    _lblStatus.ForeColor = Color.Cyan;
                });
                
                ThreadPool.QueueUserWorkItem(state => ProcessAndStreamVideo(client, _wowProcess ?? new Process()));
                ThreadPool.QueueUserWorkItem(state => HandleIncomingControls(client, _wowHandle));
            }
        }
        catch (Exception ex)
        {
            MessageBox.Show($"Error detallado del Servidor de Red:\n\n{ex.Message}\n\nTarget: {ex.StackTrace}", "Fallo de Inicialización", MessageBoxButtons.OK, MessageBoxIcon.Warning);

            _mainForm?.Invoke((MethodInvoker)delegate {
                _lblStatus!.Text = "Estado: Error en la red.";
                _lblStatus.ForeColor = Color.Red;
                ResetUI();
            });
        }
    }

    private static void ResetUI()
    {
        if (_mainForm != null && _mainForm.IsHandleCreated)
        {
            _mainForm.Invoke((MethodInvoker)delegate {
                _btnStart!.Enabled = true;
                _btnBrowse!.Enabled = true;
                _txtWowPath!.Enabled = true;
            });
        }
    }

    // ============================================================
    // FARO DE AUTODESCUBRIMIENTO UDP
    // ============================================================
    private static void StartUdpBeacon()
    {
        using UdpClient udpClient = new UdpClient();
        udpClient.EnableBroadcast = true;
        IPEndPoint endPoint = new IPEndPoint(IPAddress.Broadcast, 8889);
        byte[] responseData = Encoding.UTF8.GetBytes("WOW_SERVER_HERE");

        while (_isDiscoverable)
        {
            try
            {
                udpClient.Send(responseData, responseData.Length, endPoint);
                Thread.Sleep(2000); 
            }
            catch { Thread.Sleep(5000); }
        }
    }

    // ============================================================
    // CAPTURA Y STREAMING DE VIDEO
    // ============================================================
    private static void ProcessAndStreamVideo(TcpClient client, Process process)
    {
        using NetworkStream stream = client.GetStream();
        while (client.Connected && (process == null || !process.HasExited))
        {
            try
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
encoderParams.Param = new EncoderParameter[] { new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 60L) };
ImageCodecInfo? jpegCodec = GetEncoder(ImageFormat.Jpeg);
if (jpegCodec != null)
{
bmp.Save(ms, jpegCodec, encoderParams);
byte[] buffer = ms.ToArray();
byte[] sizeBytes = BitConverter.GetBytes(buffer.Length);
stream.Write(sizeBytes, 0, sizeBytes.Length);
stream.Write(buffer, 0, buffer.Length);
}
}
}
Thread.Sleep(33);
}
catch { break; }
}
}
// ============================================================
// GESTIÓN DE CONTROLES ENTRANTES
// ============================================================
private static void HandleIncomingControls(TcpClient client, IntPtr wowWindowHandle)
{
using NetworkStream stream = client.GetStream();
while (client.Connected && wowWindowHandle != IntPtr.Zero)
{
try
{
int typeByte = stream.ReadByte();
if (typeByte == -1) break;
byte commandType = (byte)typeByte;
if (commandType == 0)
{
// CORRECCIÓN CS1526: Inicialización explícita del búfer con tamaño fijo para teclado (2 bytes)
byte[] kbBuffer = new byte[2];
int read = ReadExactly(stream, kbBuffer, 2);
if (read != 2) break;
byte action = kbBuffer[0];
byte keyChar = kbBuffer[1];
uint msg = (action == 1) ? WM_KEYDOWN : WM_KEYUP;
PostMessage(wowWindowHandle, msg, (IntPtr)keyChar, IntPtr.Zero);
}
else if (commandType == 1)
{
// CORRECCIÓN CS1526: Inicialización explícita del búfer con tamaño fijo para ratón (9 bytes)
byte[] mouseBuffer = new byte[9];
int read = ReadExactly(stream, mouseBuffer, 9);
if (read != 9) break;
byte mouseAction = mouseBuffer[0];
float pctX = BitConverter.ToSingle(mouseBuffer, 1);
float pctY = BitConverter.ToSingle(mouseBuffer, 5);
if (GetClientRect(wowWindowHandle, out RECT rect))
{
int width = rect.Right - rect.Left;
int height = rect.Bottom - rect.Top;
int localX = (int)(pctX * width);
int localY = (int)(pctY * height);
IntPtr lParam = (IntPtr)((localY << 16) | (localX & 0xFFFF));
uint mouseMsg;
if (pctY > 0.70f)
{
mouseMsg = (mouseAction == 1) ? WM_LBUTTONDOWN : WM_LBUTTONUP;
}
else
{
mouseMsg = (mouseAction == 1) ? WM_RBUTTONDOWN : WM_RBUTTONUP;
}
PostMessage(wowWindowHandle, mouseMsg, IntPtr.Zero, lParam);
}
}
}
catch { break; }
}
}
private static int ReadExactly(NetworkStream stream, byte[] buffer, int count)
{
int totalRead = 0;
while (totalRead < count)
{
int read = stream.Read(buffer, totalRead, count - totalRead);
if (read == 0) return totalRead;
totalRead += read;
}
return totalRead;
}
private static ImageCodecInfo? GetEncoder(ImageFormat format)
{
ImageCodecInfo[] codecs = ImageCodecInfo.GetImageEncoders();
foreach (ImageCodecInfo codec in codecs) { if (codec.FormatID == format.Guid) return codec; }
return null;
}
}
