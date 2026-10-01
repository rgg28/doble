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
    private static TcpListener? _server;

    static void Main(string[] args)
    {
        Console.WriteLine("=== WoW Stream Server - Modo Offline ===");
        
        string wowPath = @"C:\Program Files (x86)\World of Warcraft\_retail_\Wow.exe";
        
        if (args.Length > 0)
        {
            wowPath = args[0];
        }

        try
        {
            ProcessStartInfo startInfo = new ProcessStartInfo
            {
                FileName = wowPath,
                Arguments = "-windowed"
            };
            
            Process? wowProcess = Process.Start(startInfo);
            if (wowProcess != null)
            {
                // Asignar afinidad: núcleos 4 y 5 de tu Ryzen 5600GT (0x30 en Hexadecimal)
                wowProcess.ProcessorAffinity = (IntPtr)0x30;
                Console.WriteLine($"[OK] WoW lanzado con afinidad de núcleos reducida.");
                
                _server = new TcpListener(IPAddress.Any, 8888);
                _server.Start();
                Console.WriteLine("[OK] Servidor escuchando en el puerto 8888 de tu red local...");

                while (true)
                {
                    TcpClient client = _server.AcceptTcpClient();
                    Console.WriteLine("[INFO] Dispositivo móvil conectado desde: " + client.Client.RemoteEndPoint);
                    ThreadPool.QueueUserWorkItem(state => StreamWindow(client, wowProcess));
                }
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[ERROR] No se pudo inicializar la herramienta: {ex.Message}");
            Console.WriteLine("Presiona cualquier tecla para salir...");
            Console.ReadKey();
        }
    }

    private static void StreamWindow(TcpClient client, Process process)
    {
        using NetworkStream stream = client.GetStream();
        
        // Bucle de captura optimizado para rendimiento de la gráfica Vega 7
        while (client.Connected && !process.HasExited)
        {
            try
            {
                // Forzamos resolución optimizada de 1280x720 (ligera para streaming)
                using (Bitmap bmp = new Bitmap(1280, 720))
                {
                    using (Graphics g = Graphics.FromImage(bmp))
                    {
                        g.CopyFromScreen(0, 0, 0, 0, bmp.Size);
                    }

                    using (MemoryStream ms = new MemoryStream())
                    {
                        EncoderParameters encoderParams = new EncoderParameters(1);
                        encoderParams.Param[0] = new EncoderParameter(Encoder.Quality, 60L); // Compresión equilibrada al 60%
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
                // Forzar 30 FPS fijos para ahorrar recursos internos
                Thread.Sleep(33); 
            }
            catch
            {
                break;
            }
        }
        Console.WriteLine("[INFO] Dispositivo móvil desconectado.");
    }

    private static ImageCodecInfo? GetEncoder(ImageFormat format)
    {
        ImageCodecInfo[] codecs = ImageCodecInfo.GetImageEncoders();
        foreach (ImageCodecInfo codec in codecs)
        {
            if (codec.FormatID == format.Guid) return codec;
        }
        return null;
    }
}
