using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Net;
using System.Net.Sockets;
using System.Text;

class Program
{
    private static TcpListener? _streamServer;
    private static bool _isDiscoverable = true;

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
    // MÉTODO PRINCIPAL
    // ============================================================

    static void Main(string[] args)
    {
        Console.WriteLine("=== WoW Dual Stream & Reduction Server ===");
        string wowPath = @"C:\Program Files (x86)\World of Warcraft\_retail_\Wow.exe";
        
        if (args.Length > 0) wowPath = args[0];

        try
        {
            ProcessStartInfo startInfo = new ProcessStartInfo { FileName = wowPath, Arguments = "-windowed" };
            Process? wowProcess = Process.Start(startInfo);
            
            if (wowProcess != null)
            {
                wowProcess.WaitForInputIdle();
                Thread.Sleep(2000);
                wowProcess.ProcessorAffinity = (IntPtr)0x30;
                IntPtr wowHandle = wowProcess.MainWindowHandle;
                Console.WriteLine("[OK] Segunda instancia de WoW ejecutándose de manera aislada.");

                // Iniciar el faro de autodescubrimiento UDP
                Thread udpThread = new Thread(StartUdpBeacon) { IsBackground = true };
                udpThread.Start();

                _streamServer = new TcpListener(IPAddress.Any, 8888);
                _streamServer.Start();
                Console.WriteLine("[OK] Servidor listo. ¡Abre la app en tu móvil para conectar automáticamente!");

                while (true)
                {
                    TcpClient client = _streamServer.AcceptTcpClient();
                    Console.WriteLine("[INFO] Celular conectado para recibir stream e inputs.");
                    
                    ThreadPool.QueueUserWorkItem(state => ProcessAndStreamVideo(client, wowProcess));
                    ThreadPool.QueueUserWorkItem(state => HandleIncomingControls(client, wowHandle));
                }
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[ERROR] Fallo crítico: {ex.Message}");
            Console.ReadKey();
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
                Thread.Sleep(2000); // Anunciarse cada 2 segundos
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
        while (client.Connected && !process.HasExited)
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
                        encoderParams.Param = new EncoderParameter[] { new EncoderParameter(Encoder.Quality, 60L) }; 
                        
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
