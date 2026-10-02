using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Net.WebSockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

using SIPSorcery.Net;
using SIPSorceryMedia.Abstractions;
using Vpx.Net;

class Program
{
    private static IntPtr _wowHandle = IntPtr.Zero;
    private static Process? _wowProcess;

    private static RTCPeerConnection? _peerConnection;
    private static ClientWebSocket? _signalingWebSocket;

    private static VP8Codec? _vp8Codec;
    private static Vp8NetVideoEncoderEndPoint? _videoEncoder;

    private static bool _isStreaming = false;

    // Componentes del panel gráfico.
    private static Form? _mainForm;
    private static TextBox? _txtWowPath;
    private static TextBox? _txtConnectionId;
    private static Button? _btnBrowse;
    private static Button? _btnStart;
    private static Label? _lblStatus;

    [DllImport("user32.dll")]
    private static extern bool PostMessage(
        IntPtr hWnd,
        uint Msg,
        IntPtr wParam,
        IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern bool GetClientRect(
        IntPtr hWnd,
        out RECT lpRect);

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
    private const uint WM_LBUTTONUP = 0x0202;

    private const uint WM_RBUTTONDOWN = 0x0204;
    private const uint WM_RBUTTONUP = 0x0205;

    [STAThread]
    static void Main(string[] args)
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        _mainForm = new Form
        {
            Text = "WoW OBS-Cast WebRTC Server",
            Width = 520,
            Height = 220,
            FormBorderStyle = FormBorderStyle.FixedSingle,
            MaximizeBox = false,
            StartPosition = FormStartPosition.CenterScreen,
            BackColor = Color.FromArgb(20, 24, 30)
        };

        Label lblPath = new Label
        {
            Text = "Ruta de Wow.exe:",
            Left = 20,
            Top = 15,
            Width = 120,
            ForeColor = Color.White,
            Font = new Font(
                "Segoe UI",
                9,
                FontStyle.Bold)
        };

        _txtWowPath = new TextBox
        {
            Left = 20,
            Top = 38,
            Width = 360,
            Text = @"C:\Program Files (x86)\World of Warcraft\_retail_\Wow.exe",
            BackColor = Color.FromArgb(40, 44, 52),
            ForeColor = Color.White,
            BorderStyle = BorderStyle.FixedSingle
        };

        _btnBrowse = new Button
        {
            Text = "Buscar...",
            Left = 390,
            Top = 36,
            Width = 90,
            Height = 25,
            BackColor = Color.FromArgb(60, 65, 75),
            ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat
        };

        _btnBrowse.Click += BtnBrowse_Click;

        Label lblId = new Label
        {
            Text = "ID de Conexión Global (Crea un código para conectar desde fuera):",
            Left = 20,
            Top = 75,
            Width = 450,
            ForeColor = Color.White,
            Font = new Font(
                "Segoe UI",
                9,
                FontStyle.Bold)
        };

        _txtConnectionId = new TextBox
        {
            Left = 20,
            Top = 98,
            Width = 200,
            Text = "WowSala777",
            BackColor = Color.FromArgb(40, 44, 52),
            ForeColor = Color.Cyan,
            BorderStyle = BorderStyle.FixedSingle,
            Font = new Font(
                "Segoe UI",
                10,
                FontStyle.Bold)
        };

        _btnStart = new Button
        {
            Text = "INICIAR OBS-CAST",
            Left = 20,
            Top = 135,
            Width = 200,
            Height = 35,
            BackColor = Color.FromArgb(75, 100, 205),
            ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat,
            Font = new Font(
                "Segoe UI",
                10,
                FontStyle.Bold)
        };

        _btnStart.Click += BtnStart_Click;

        _lblStatus = new Label
        {
            Text = "Estado: Desconectado.",
            Left = 230,
            Top = 145,
            Width = 250,
            ForeColor = Color.Gray,
            Font = new Font(
                "Segoe UI",
                9,
                FontStyle.Italic)
        };

        _mainForm.Controls.Add(lblPath);
        _mainForm.Controls.Add(_txtWowPath);
        _mainForm.Controls.Add(_btnBrowse);

        _mainForm.Controls.Add(lblId);
        _mainForm.Controls.Add(_txtConnectionId);

        _mainForm.Controls.Add(_btnStart);
        _mainForm.Controls.Add(_lblStatus);

        Application.Run(_mainForm);
    }

    private static void BtnBrowse_Click(
        object? sender,
        EventArgs e)
    {
        using (OpenFileDialog ofd = new OpenFileDialog())
        {
            ofd.Filter = "Ejecutable de WoW (*.exe)|*.exe";

            if (ofd.ShowDialog() == DialogResult.OK)
            {
                _txtWowPath!.Text = ofd.FileName;
            }
        }
    }

    private static async void BtnStart_Click(
        object? sender,
        EventArgs e)
    {
        string wowPath = _txtWowPath?.Text ?? "";
        string connectionId = _txtConnectionId?.Text ?? "";

        if (!File.Exists(wowPath) ||
            string.IsNullOrWhiteSpace(connectionId))
        {
            MessageBox.Show(
                "Comprueba la ruta de Wow.exe y el ID de conexión.",
                "Datos inválidos",
                MessageBoxButtons.OK,
                MessageBoxIcon.Warning);

            return;
        }

        _btnStart!.Enabled = false;
        _btnBrowse!.Enabled = false;
        _txtWowPath!.Enabled = false;
        _txtConnectionId!.Enabled = false;

        _lblStatus!.Text =
            "Abriendo WoW y enlazando nube...";

        _lblStatus.ForeColor = Color.Orange;

        try
        {
            ProcessStartInfo startInfo =
                new ProcessStartInfo
                {
                    FileName = wowPath,
                    Arguments = "-windowed",
                    UseShellExecute = true
                };

            _wowProcess = Process.Start(startInfo);

            if (_wowProcess != null)
            {
                await Task.Delay(3000);

                try
                {
                    _wowProcess.Refresh();
                    _wowHandle =
                        _wowProcess.MainWindowHandle;
                }
                catch
                {
                    _wowHandle = IntPtr.Zero;
                }
            }

            _ = Task.Run(
                () => SetupWebRtcConnection(connectionId));
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                ex.Message,
                "Error iniciando WoW",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);

            ResetUI();
        }
    }

    private static async Task SetupWebRtcConnection(
        string roomId)
    {
        try
        {
            /*
             * IMPORTANTE:
             *
             * Esta URL debe ser reemplazada por el WebSocket
             * REAL de tu servidor de señalización.
             *
             * El valor anterior:
             *
             *     wss://://piesocket.com
             *
             * era una URL inválida.
             */
            string signalingUrl =
                "wss://TU-SERVIDOR-DE-SEÑALIZACION/ws";

            if (!Uri.TryCreate(
                    signalingUrl,
                    UriKind.Absolute,
                    out Uri? signalingUri))
            {
                throw new InvalidOperationException(
                    "La URL del servidor de señalización no es válida.");
            }

            _signalingWebSocket =
                new ClientWebSocket();

            await _signalingWebSocket.ConnectAsync(
                signalingUri,
                CancellationToken.None);

            var config = new RTCConfiguration
            {
                iceServers =
                    new System.Collections.Generic.List<RTCIceServer>
                    {
                        new RTCIceServer
                        {
                            urls =
                                "stun:stun.l.google.com:19302"
                        }
                    }
            };

            _peerConnection =
                new RTCPeerConnection(config);

            /*
             * Encoder VP8.
             *
             * SIPSorcery.VP8 10.0.14 proporciona
             * Vp8NetVideoEncoderEndPoint.
             */
            _vp8Codec = new VP8Codec();

            _videoEncoder =
                new Vp8NetVideoEncoderEndPoint(
                    _vp8Codec);

            /*
             * La pista anuncia los formatos VP8 que
             * soporta el encoder.
             */
            var videoTrack =
                new MediaStreamTrack(
                    _videoEncoder.GetVideoSourceFormats(),
                    MediaStreamStatusEnum.SendOnly);

            _peerConnection.addTrack(videoTrack);

            /*
             * Cada frame que el encoder convierte a VP8
             * se entrega directamente a WebRTC.
             */
            _videoEncoder.OnVideoSourceEncodedSample +=
                _peerConnection.SendVideo;

            /*
             * Cuando WebRTC termina la negociación de
             * formatos, seleccionamos el formato negociado.
             */
            _peerConnection.OnVideoFormatsNegotiated +=
                formats =>
                {
                    if (formats != null &&
                        formats.Count > 0 &&
                        _videoEncoder != null)
                    {
                        _videoEncoder.SetVideoSourceFormat(
                            formats[0]);
                    }
                };

            /*
             * Canal de datos para controles WoW.
             */
            var dataChannel =
                await _peerConnection.createDataChannel(
                    "wow_controls");

            dataChannel.onmessage +=
                (dc, type, data) =>
                {
                    HandleIncomingWebRtcControls(data);
                };

            /*
             * Crear SDP offer.
             */
            var offer =
                _peerConnection.createOffer();

            await _peerConnection.setLocalDescription(
                offer);

            string base64Sdp =
                Convert.ToBase64String(
                    Encoding.UTF8.GetBytes(
                        offer.sdp.ToString()));

            string offerJson =
                "{\"room\":\"" +
                EscapeJson(roomId) +
                "\",\"type\":\"offer\",\"sdp\":\"" +
                base64Sdp +
                "\"}";

            byte[] offerBytes =
                Encoding.UTF8.GetBytes(
                    offerJson);

            await _signalingWebSocket.SendAsync(
                new ArraySegment<byte>(offerBytes),
                WebSocketMessageType.Text,
                true,
                CancellationToken.None);

            _mainForm?.Invoke(
                (MethodInvoker)delegate
                {
                    _lblStatus!.Text =
                        "¡Emitiendo! Coloca el ID en tu app móvil.";

                    _lblStatus.ForeColor =
                        Color.LightGreen;
                });

            _isStreaming = true;

            _ = Task.Run(
                VideoStreamingLoop);

            _ = Task.Run(
                ListenForSignalingMessages);
        }
        catch (Exception ex)
        {
            Debug.WriteLine(
                "Fallo en WebRTC: " + ex);

            _mainForm?.Invoke(
                (MethodInvoker)delegate
                {
                    MessageBox.Show(
                        "Fallo en WebRTC:\r\n\r\n" +
                        ex.Message,
                        "WebRTC",
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Error);

                    ResetUI();
                });
        }
    }

    private static async Task VideoStreamingLoop()
    {
        const int width = 1280;
        const int height = 720;

        while (_isStreaming &&
               _wowProcess != null &&
               !_wowProcess.HasExited &&
               _videoEncoder != null)
        {
            try
            {
                using (Bitmap bmp =
                       new Bitmap(
                           width,
                           height,
                           PixelFormat.Format24bppRgb))
                {
                    using (Graphics g =
                           Graphics.FromImage(bmp))
                    {
                        /*
                         * Captura la pantalla.
                         *
                         * Actualmente captura desde 0,0,
                         * manteniendo el comportamiento del
                         * código original.
                         */
                        g.CopyFromScreen(
                            0,
                            0,
                            0,
                            0,
                            bmp.Size);
                    }

                    Rectangle rect =
                        new Rectangle(
                            0,
                            0,
                            bmp.Width,
                            bmp.Height);

                    BitmapData data =
                        bmp.LockBits(
                            rect,
                            ImageLockMode.ReadOnly,
                            PixelFormat.Format24bppRgb);

                    try
                    {
                        int stride =
                            Math.Abs(data.Stride);

                        int bufferSize =
                            stride * bmp.Height;

                        byte[] rawBgr =
                            new byte[bufferSize];

                        Marshal.Copy(
                            data.Scan0,
                            rawBgr,
                            0,
                            bufferSize);

                        /*
                         * Entregamos BGR crudo al encoder.
                         *
                         * El encoder genera VP8 y su evento
                         * OnVideoSourceEncodedSample lo pasa
                         * automáticamente a RTCPeerConnection.
                         */
                        _videoEncoder.ExternalVideoSourceRawSample(
                            33,
                            bmp.Width,
                            bmp.Height,
                            rawBgr,
                            VideoPixelFormatsEnum.Bgr);
                    }
                    finally
                    {
                        bmp.UnlockBits(data);
                    }
                }

                /*
                 * Aproximadamente 30 FPS.
                 */
                await Task.Delay(33);
            }
            catch (Exception ex)
            {
                Debug.WriteLine(
                    "VideoStreamingLoop: " +
                    ex.Message);

                break;
            }
        }
    }

    private static async Task ListenForSignalingMessages()
    {
        byte[] buffer =
            new byte[64 * 1024];

        while (_signalingWebSocket != null &&
               _signalingWebSocket.State ==
                   WebSocketState.Open)
        {
            try
            {
                using (MemoryStream messageStream =
                       new MemoryStream())
                {
                    WebSocketReceiveResult result;

                    do
                    {
                        result =
                            await _signalingWebSocket.ReceiveAsync(
                                new ArraySegment<byte>(
                                    buffer),
                                CancellationToken.None);

                        if (result.MessageType ==
                            WebSocketMessageType.Close)
                        {
                            return;
                        }

                        if (result.Count > 0)
                        {
                            messageStream.Write(
                                buffer,
                                0,
                                result.Count);
                        }
                    }
                    while (!result.EndOfMessage);

                    string message =
                        Encoding.UTF8.GetString(
                            messageStream.ToArray());

                    /*
                     * Respuesta SDP.
                     */
                    if (message.Contains(
                            "\"type\":\"answer\"",
                            StringComparison.OrdinalIgnoreCase))
                    {
                        string base64Sdp =
                            ExtractJsonValue(
                                message,
                                "sdp");

                        if (string.IsNullOrWhiteSpace(
                                base64Sdp))
                        {
                            continue;
                        }

                        string sdpStr;

                        try
                        {
                            sdpStr =
                                Encoding.UTF8.GetString(
                                    Convert.FromBase64String(
                                        base64Sdp));
                        }
                        catch (FormatException)
                        {
                            /*
                             * Permite también un servidor que
                             * entregue el SDP directamente.
                             */
                            sdpStr = base64Sdp;
                        }

                        if (_peerConnection != null)
                        {
                            /*
                             * API de SIPSorcery 10.x:
                             *
                             * RTCSessionDescriptionInit
                             * RTCSdpType.answer
                             *
                             * No usar:
                             *
                             * RTCSessionDescriptionTypesEnum
                             * SDP.ParseSDPString
                             */
                            var remoteDescription =
                                new RTCSessionDescriptionInit
                                {
                                    type =
                                        RTCSdpType.answer,

                                    sdp =
                                        sdpStr
                                };

                            SetDescriptionResultEnum resultCode =
                                _peerConnection.setRemoteDescription(
                                    remoteDescription);

                            Debug.WriteLine(
                                "setRemoteDescription: " +
                                resultCode);
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine(
                    "ListenForSignalingMessages: " +
                    ex.Message);

                break;
            }
        }
    }

    private static void HandleIncomingWebRtcControls(
        byte[] data)
    {
        if (data == null ||
            data.Length < 3 ||
            _wowHandle == IntPtr.Zero)
        {
            return;
        }

        byte type = data[0];

        /*
         * Teclado.
         *
         * [0] = tipo
         * [1] = acción
         * [2] = tecla
         */
        if (type == 0)
        {
            byte action = data[1];
            byte keyChar = data[2];

            uint msg =
                (action == 1)
                    ? WM_KEYDOWN
                    : WM_KEYUP;

            PostMessage(
                _wowHandle,
                msg,
                (IntPtr)keyChar,
                IntPtr.Zero);
        }

        /*
         * Mouse.
         *
         * [0] = tipo
         * [1] = acción
         * [2..5] = X float
         * [6..9] = Y float
         */
        else if (type == 1 &&
                 data.Length >= 10)
        {
            byte mouseAction = data[1];

            float pctX =
                BitConverter.ToSingle(
                    data,
                    2);

            float pctY =
                BitConverter.ToSingle(
                    data,
                    6);

            if (float.IsNaN(pctX) ||
                float.IsInfinity(pctX) ||
                float.IsNaN(pctY) ||
                float.IsInfinity(pctY))
            {
                return;
            }

            pctX =
                Math.Clamp(
                    pctX,
                    0.0f,
                    1.0f);

            pctY =
                Math.Clamp(
                    pctY,
                    0.0f,
                    1.0f);

            if (GetClientRect(
                    _wowHandle,
                    out RECT rect))
            {
                int width =
                    rect.Right - rect.Left;

                int height =
                    rect.Bottom - rect.Top;

                if (width <= 0 ||
                    height <= 0)
                {
                    return;
                }

                int x =
                    (int)(pctX * width);

                int y =
                    (int)(pctY * height);

                IntPtr lParam =
                    (IntPtr)(
                        (y << 16) |
                        (x & 0xFFFF));

                /*
                 * Mantiene la lógica original:
                 * zona inferior = botón izquierdo,
                 * resto = botón derecho.
                 */
                uint mouseMsg =
                    (pctY > 0.70f)
                        ? (
                            mouseAction == 1
                                ? WM_LBUTTONDOWN
                                : WM_LBUTTONUP
                          )
                        : (
                            mouseAction == 1
                                ? WM_RBUTTONDOWN
                                : WM_RBUTTONUP
                          );

                PostMessage(
                    _wowHandle,
                    mouseMsg,
                    IntPtr.Zero,
                    lParam);
            }
        }
    }

    private static string ExtractJsonValue(
        string json,
        string key)
    {
        try
        {
            using (
                System.Text.Json.JsonDocument doc =
                    System.Text.Json.JsonDocument.Parse(
                        json))
            {
                if (doc.RootElement.TryGetProperty(
                        key,
                        out System.Text.Json.JsonElement element))
                {
                    return element.GetString() ?? "";
                }
            }
        }
        catch
        {
            // El llamador decide cómo manejar un valor ausente.
        }

        return "";
    }

    private static string EscapeJson(
        string value)
    {
        return value
            .Replace("\\", "\\\\")
            .Replace("\"", "\\\"")
            .Replace("\r", "\\r")
            .Replace("\n", "\\n");
    }

    private static void ResetUI()
    {
        _isStreaming = false;

        try
        {
            _videoEncoder?.Dispose();
        }
        catch
        {
        }

        _videoEncoder = null;
        _vp8Codec = null;

        try
        {
            _signalingWebSocket?.Dispose();
        }
        catch
        {
        }

        _signalingWebSocket = null;

        try
        {
            _peerConnection?.Close(
                "Reset UI");
        }
        catch
        {
        }

        _peerConnection = null;

        if (_btnStart != null &&
            _btnStart.IsHandleCreated)
        {
            _btnStart.Invoke(
                (MethodInvoker)(() =>
                {
                    _btnStart.Enabled = true;

                    _btnBrowse!.Enabled = true;

                    _txtWowPath!.Enabled = true;

                    _txtConnectionId!.Enabled = true;

                    _lblStatus!.Text =
                        "Estado: Desconectado.";

                    _lblStatus.ForeColor =
                        Color.Gray;
                }));
        }
    }
}
