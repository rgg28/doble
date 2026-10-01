using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Threading;

class Program
{
    private static TcpListener? _streamServer;

    [DllImport("user32.dll")]
    private static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);

    private const uint WM_KEYDOWN = 0x0100;
    private const uint WM_KEYUP = 0x0101;

    static void Main(string[] args)
    {
        Console.WriteLine("=== WoW Dual Stream & Reduction Server ===");
        string wowPath = @"C:\Program Files (x86)\World of Warcraft\_retail_\Wow.exe";
        
        // CORRECCIÓN 1: Leer el primer elemento del arreglo de argumentos
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

                _streamServer = new TcpListener(IPAddress.Any, 8888);
                _streamServer.Start();
                Console.WriteLine("[OK] Servidor en línea. Esperando conexión de la pantalla móvil...");

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
                        // CORRECCIÓN 2: Asignar un arreglo que contenga el parámetro de calidad
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

    private static void HandleIncomingControls(TcpClient client, IntPtr wowWindowHandle)
    {
        using NetworkStream stream = client.GetStream();
        byte[] buffer = new byte[2]; // Inicializar tamaño correcto del buffer de ráfaga

        while (client.Connected && wowWindowHandle != IntPtr.Zero)
        {
            try
            {
                int bytesRead = stream.Read(buffer, 0, buffer.Length);
                if (bytesRead == 0) break;

                byte action = buffer[0];  // 1 = Presionado, 0 = Soltado
                byte keyChar = buffer[1]; // Letra asignada ('W', '1', etc.)

                uint msg = (action == 1) ? WM_KEYDOWN : WM_KEYUP;
                PostMessage(wowWindowHandle, msg, (IntPtr)keyChar, IntPtr.Zero);
            }
            catch { break; }
        }
    }

    private static ImageCodecInfo? GetEncoder(ImageFormat format)
    {
        ImageCodecInfo[] codecs = ImageCodecInfo.GetImageEncoders();
        foreach (ImageCodecInfo codec in codecs) { if (codec.FormatID == format.Guid) return codec; }
        return null;
    }
}
