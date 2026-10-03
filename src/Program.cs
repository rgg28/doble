using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Net;
using System.Net.WebSockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

using SIPSorcery.Net;
using SIPSorceryMedia.Abstractions;
using Vpx.Net;

internal static class Program
{
    // ============================================================
    // WoWStream
    // ============================================================

    private const int SignalingPort = 8080;

    private static readonly string SignalingHost =
        "127.0.0.1";

    private static IntPtr _wowHandle = IntPtr.Zero;
    private static Process? _wowProcess;

    private static RTCPeerConnection? _peerConnection;
    private static ClientWebSocket? _signalingWebSocket;

    private static VP8Codec? _vp8Codec;
    private static Vp8NetVideoEncoderEndPoint? _videoEncoder;

    private static bool _isStreaming;
    private static bool _shuttingDown;

    private static string _roomId = "";
    private static string _localIp = "127.0.0.1";

    private static HttpListener? _signalingServer;
    private static CancellationTokenSource? _serverCancellation;

    /*
     * Cada sala contiene las conexiones WebSocket.
     *
     * host    = WoWStream
     * viewer  = cliente remoto
     */
    private static readonly ConcurrentDictionary<
        string,
        ConcurrentDictionary<string, WebSocket>>
        _rooms = new();

    // ============================================================
    // UI
    // ============================================================

    private static Form? _mainForm;
    private static TextBox? _txtWowPath;
    private static TextBox? _txtConnectionId;
    private static Button? _btnBrowse;
    private static Button? _btnStart;
    private static Label? _lblStatus;
    private static Label? _lblRoom;
    private static Label? _lblServer;

    // ============================================================
    // Windows API
    // ============================================================

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
    private struct RECT
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

    // ============================================================
    // MAIN
    // ============================================================

    [STAThread]
    private static void Main(string[] args)
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        _roomId = GenerateRoomId();
        _localIp = GetLocalIPv4();

        CreateMainForm();

        Application.ApplicationExit +=
            (_, _) =>
            {
                _ = ShutdownAsync();
            };

        Application.Run(_mainForm);
    }

    // ============================================================
    // UI
    // ============================================================

    private static void CreateMainForm()
    {
        _mainForm = new Form
        {
            Text = "WoWStream",
            Width = 560,
            Height = 310,
            FormBorderStyle = FormBorderStyle.FixedSingle,
            MaximizeBox = false,
            StartPosition = FormStartPosition.CenterScreen,
            BackColor = Color.FromArgb(20, 24, 30)
        };

        Label lblTitle = new Label
        {
            Text = "WoWStream",
            Left = 20,
            Top = 12,
            Width = 500,
            Height = 30,
            ForeColor = Color.Cyan,
            Font = new Font(
                "Segoe UI",
                16,
                FontStyle.Bold)
        };

        Label lblPath = new Label
        {
            Text = "Ruta de Wow.exe:",
            Left = 20,
            Top = 50,
            Width = 130,
            ForeColor = Color.White,
            Font = new Font(
                "Segoe UI",
                9,
                FontStyle.Bold)
        };

        _txtWowPath = new TextBox
        {
            Left = 20,
            Top = 73,
            Width = 390,
            Text =
                @"C:\Program Files (x86)\World of Warcraft\_retail_\Wow.exe",
            BackColor = Color.FromArgb(40, 44, 52),
            ForeColor = Color.White,
            BorderStyle = BorderStyle.FixedSingle
        };

        _btnBrowse = new Button
        {
            Text = "Buscar...",
            Left = 420,
            Top = 71,
            Width = 100,
            Height = 27,
            BackColor = Color.FromArgb(60, 65, 75),
            ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat
        };

        _btnBrowse.Click += BtnBrowse_Click;

        Label lblId = new Label
        {
            Text = "ID de conexión:",
            Left = 20,
            Top = 112,
            Width = 130,
            ForeColor = Color.White,
            Font = new Font(
                "Segoe UI",
                9,
                FontStyle.Bold)
        };

        _txtConnectionId = new TextBox
        {
            Left = 20,
            Top = 135,
            Width = 230,
            Text = _roomId,
            BackColor = Color.FromArgb(40, 44, 52),
            ForeColor = Color.Cyan,
            BorderStyle = BorderStyle.FixedSingle,
            Font = new Font(
                "Segoe UI",
                10,
                FontStyle.Bold)
        };

        _txtConnectionId.TextChanged +=
            (_, _) =>
            {
                string value =
                    _txtConnectionId?.Text.Trim() ?? "";

                if (!string.IsNullOrWhiteSpace(value))
                {
                    _roomId = value;
                }
            };

        _lblRoom = new Label
        {
            Text = "Sala: " + _roomId,
            Left = 270,
            Top = 138,
            Width = 250,
            ForeColor = Color.LightGreen,
            Font = new Font(
                "Segoe UI",
                9,
                FontStyle.Bold)
        };

        _btnStart = new Button
        {
            Text = "INICIAR WoWStream",
            Left = 20,
            Top = 180,
            Width = 230,
            Height = 38,
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
            Text = "Estado: Detenido.",
            Left = 270,
            Top = 190,
            Width = 250,
            Height = 25,
            ForeColor = Color.Gray,
            Font = new Font(
                "Segoe UI",
                9,
                FontStyle.Bold)
        };

        _lblServer = new Label
        {
            Text =
                $"Signaling: ws://{_localIp}:{SignalingPort}/ws",
            Left = 20,
            Top = 235,
            Width = 500,
            Height = 25,
            ForeColor = Color.LightSkyBlue,
            Font = new Font(
                "Segoe UI",
                8,
                FontStyle.Regular)
        };

        _mainForm.Controls.Add(lblTitle);
        _mainForm.Controls.Add(lblPath);
        _mainForm.Controls.Add(_txtWowPath);
        _mainForm.Controls.Add(_btnBrowse);
        _mainForm.Controls.Add(lblId);
        _mainForm.Controls.Add(_txtConnectionId);
        _mainForm.Controls.Add(_lblRoom);
        _mainForm.Controls.Add(_btnStart);
        _mainForm.Controls.Add(_lblStatus);
        _mainForm.Controls.Add(_lblServer);
    }

    private static void BtnBrowse_Click(
        object? sender,
        EventArgs e)
    {
        using OpenFileDialog ofd = new OpenFileDialog
        {
            Filter =
                "Ejecutable de WoW (*.exe)|*.exe|Todos los archivos (*.*)|*.*"
        };

        if (ofd.ShowDialog() == DialogResult.OK)
        {
            _txtWowPath!.Text = ofd.FileName;
        }
    }

    // ============================================================
    // START
    // ============================================================

    private static async void BtnStart_Click(
        object? sender,
        EventArgs e)
    {
        string wowPath =
            _txtWowPath?.Text.Trim() ?? "";

        string connectionId =
            _txtConnectionId?.Text.Trim() ?? "";

        if (!File.Exists(wowPath))
        {
            MessageBox.Show(
                "No se encontró Wow.exe.",
                "WoWStream",
                MessageBoxButtons.OK,
                MessageBoxIcon.Warning);

            return;
        }

        if (string.IsNullOrWhiteSpace(connectionId))
        {
            MessageBox.Show(
                "Introduce un ID de conexión.",
                "WoWStream",
                MessageBoxButtons.OK,
                MessageBoxIcon.Warning);

            return;
        }

        _roomId = connectionId;

        SetUIBusy(true);

        try
        {
            SetStatus(
                "Iniciando servidor de señalización...",
                Color.Orange);

            await StartSignalingServer();

            SetStatus(
                "Iniciando World of Warcraft...",
                Color.Orange);

            ProcessStartInfo startInfo =
                new ProcessStartInfo
                {
                    FileName = wowPath,
                    Arguments = "-windowed",
                    UseShellExecute = true,
                    WorkingDirectory =
                        Path.GetDirectoryName(wowPath)
                        ?? Environment.CurrentDirectory
                };

            _wowProcess =
                Process.Start(startInfo);

            if (_wowProcess == null)
            {
                throw new InvalidOperationException(
                    "No se pudo iniciar Wow.exe.");
            }

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

            SetStatus(
                "Conectando WoWStream al signaling...",
                Color.Orange);

            await ConnectHostToSignaling();

            SetStatus(
                "Esperando cliente remoto...",
                Color.LightGreen);
        }
        catch (Exception ex)
        {
            Debug.WriteLine(ex);

            MessageBox.Show(
                "No se pudo iniciar WoWStream:\r\n\r\n" +
                ex.Message,
                "WoWStream",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);

            await ShutdownAsync();

            SetUIBusy(false);
        }
    }

    // ============================================================
    // SIGNALING SERVER
    // ============================================================

    private static async Task StartSignalingServer()
    {
        if (_signalingServer != null)
        {
            return;
        }

        _serverCancellation =
            new CancellationTokenSource();

        _signalingServer =
            new HttpListener();

        /*
         * Escucha en todas las interfaces IPv4.
         *
         * Esto permite que otro dispositivo de la LAN
         * pueda conectarse al servidor.
         */
        _signalingServer.Prefixes.Add(
            $"http://+:{SignalingPort}/ws/");

        _signalingServer.Prefixes.Add(
            $"http://+:{SignalingPort}/");

        try
        {
            _signalingServer.Start();
        }
        catch
        {
            _signalingServer.Close();
            _signalingServer = null;

            throw new InvalidOperationException(
                $"No se pudo abrir el puerto {SignalingPort}. " +
                $"Comprueba que ningún otro programa lo esté utilizando.");
        }

        _ = Task.Run(
            () => SignalingAcceptLoop(
                _serverCancellation.Token));
    }

    private static async Task SignalingAcceptLoop(
        CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested &&
               _signalingServer != null)
        {
            try
            {
                HttpListenerContext context =
                    await _signalingServer.GetContextAsync();

                if (!context.Request.IsWebSocketRequest)
                {
                    context.Response.StatusCode = 400;

                    byte[] response =
                        Encoding.UTF8.GetBytes(
                            "WoWStream signaling server");

                    await context.Response.OutputStream.WriteAsync(
                        response,
                        0,
                        response.Length);

                    context.Response.Close();

                    continue;
                }

                string path =
                    context.Request.Url?.AbsolutePath ?? "";

                if (!path.Equals(
                        "/ws",
                        StringComparison.OrdinalIgnoreCase))
                {
                    context.Response.StatusCode = 404;
                    context.Response.Close();
                    continue;
                }

                HttpListenerWebSocketContext wsContext =
                    await context.AcceptWebSocketAsync(
                        null);

                WebSocket socket =
                    wsContext.WebSocket;

                _ = Task.Run(
                    () => HandleSignalingClient(
                        socket,
                        context.Request.QueryString));
            }
            catch (HttpListenerException)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch (Exception ex)
            {
                Debug.WriteLine(
                    "SignalingAcceptLoop: " +
                    ex.Message);
            }
        }
    }

    private static async Task HandleSignalingClient(
        WebSocket socket,
        System.Collections.Specialized.NameValueCollection query)
    {
        string room =
            query["room"] ?? "";

        string client =
            query["client"] ?? Guid.NewGuid().ToString("N");

        if (string.IsNullOrWhiteSpace(room))
        {
            await SendText(
                socket,
                CreateError(
                    "Falta el parámetro room."));

            await CloseSocket(socket);

            return;
        }

        var roomSockets =
            _rooms.GetOrAdd(
                room,
                _ =>
                    new ConcurrentDictionary<
                        string,
                        WebSocket>());

        /*
         * Evita dos clientes con exactamente el mismo ID.
         */
        string originalClient = client;

        int suffix = 1;

        while (!roomSockets.TryAdd(
                   client,
                   socket))
        {
            client =
                originalClient +
                "-" +
                suffix++;

            if (suffix > 1000)
            {
                await CloseSocket(socket);
                return;
            }
        }

        Debug.WriteLine(
            $"[SIGNALING] JOIN room={room} client={client}");

        await SendText(
            socket,
            JsonSerializer.Serialize(
                new
                {
                    type = "joined",
                    room,
                    client
                }));

        /*
         * Informamos a los demás clientes.
         */
        await Broadcast(
            roomSockets,
            client,
            JsonSerializer.Serialize(
                new
                {
                    type = "peer-joined",
                    client
                }));

        byte[] buffer =
            new byte[64 * 1024];

        try
        {
            while (socket.State ==
                   WebSocketState.Open)
            {
                string? message =
                    await ReceiveText(
                        socket,
                        buffer);

                if (message == null)
                {
                    break;
                }

                /*
                 * El signaling server no interpreta SDP/ICE.
                 *
                 * Simplemente retransmite los mensajes al
                 * otro participante de la sala.
                 */
                await Broadcast(
                    roomSockets,
                    client,
                    message);
            }
        }
        catch (Exception ex)
        {
            Debug.WriteLine(
                $"[SIGNALING] {ex.Message}");
        }
        finally
        {
            roomSockets.TryRemove(
                client,
                out _);

            await Broadcast(
                roomSockets,
                client,
                JsonSerializer.Serialize(
                    new
                    {
                        type = "peer-left",
                        client
                    }));

            if (roomSockets.IsEmpty)
            {
                _rooms.TryRemove(
                    room,
                    out _);
            }

            await CloseSocket(socket);
        }
    }

    // ============================================================
    // HOST SIGNALING CONNECTION
    // ============================================================

    private static async Task ConnectHostToSignaling()
    {
        _signalingWebSocket =
            new ClientWebSocket();

        string url =
            $"ws://{SignalingHost}:{SignalingPort}/ws" +
            $"?room={Uri.EscapeDataString(_roomId)}" +
            $"&client=host";

        await _signalingWebSocket.ConnectAsync(
            new Uri(url),
            CancellationToken.None);

        Debug.WriteLine(
            "[SIGNALING] Host connected.");

        _ = Task.Run(
            HostSignalingReceiveLoop);
    }

    private static async Task HostSignalingReceiveLoop()
    {
        if (_signalingWebSocket == null)
        {
            return;
        }

        byte[] buffer =
            new byte[64 * 1024];

        while (_signalingWebSocket.State ==
               WebSocketState.Open)
        {
            try
            {
                string? message =
                    await ReceiveText(
                        _signalingWebSocket,
                        buffer);

                if (message == null)
                {
                    break;
                }

                Debug.WriteLine(
                    "[SIGNALING RX] " +
                    message);

                using JsonDocument doc =
                    JsonDocument.Parse(message);

                if (!doc.RootElement.TryGetProperty(
                        "type",
                        out JsonElement typeElement))
                {
                    continue;
                }

                string type =
                    typeElement.GetString() ?? "";

                switch (type)
                {
                    case "peer-joined":

                        SetStatus(
                            "Cliente encontrado. " +
                            "Negociando WebRTC...",
                            Color.Orange);

                        await CreateAndSendOffer();

                        break;

                    case "answer":

                        await ProcessAnswer(
                            doc.RootElement);

                        break;

                    case "ice-candidate":

                        await ProcessRemoteIceCandidate(
                            doc.RootElement);

                        break;

                    case "peer-left":

                        _isStreaming = false;

                        SetStatus(
                            "Cliente desconectado. " +
                            "Esperando otro cliente...",
                            Color.Orange);

                        break;
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine(
                    "HostSignalingReceiveLoop: " +
                    ex.Message);

                break;
            }
        }
    }

    // ============================================================
    // WEBRTC
    // ============================================================

    private static async Task CreateAndSendOffer()
    {
        try
        {
            if (_peerConnection != null)
            {
                try
                {
                    _peerConnection.Close(
                        "New negotiation");
                }
                catch
                {
                }

                _peerConnection = null;
            }

            if (_videoEncoder != null)
            {
                try
                {
                    _videoEncoder.Dispose();
                }
                catch
                {
                }

                _videoEncoder = null;
            }

            _vp8Codec = new VP8Codec();

            var config =
                new RTCConfiguration
                {
                    iceServers =
                        new System.Collections.Generic.List<
                            RTCIceServer>
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
             * VP8.
             *
             * IMPORTANTE:
             * En SIPSorcery.VP8 10.0.14 el constructor
             * correcto es el constructor sin argumentos.
             */
            _videoEncoder =
                new Vp8NetVideoEncoderEndPoint();

            var videoTrack =
                new MediaStreamTrack(
                    _videoEncoder.GetVideoSourceFormats(),
                    MediaStreamStatusEnum.SendOnly);

            _peerConnection.addTrack(
                videoTrack);

            _videoEncoder.OnVideoSourceEncodedSample +=
                _peerConnection.SendVideo;

            _peerConnection.OnVideoFormatsNegotiated +=
                formats =>
                {
                    try
                    {
                        if (formats != null &&
                            formats.Count > 0 &&
                            _videoEncoder != null)
                        {
                            _videoEncoder.SetVideoSourceFormat(
                                formats[0]);
                        }
                    }
                    catch (Exception ex)
                    {
                        Debug.WriteLine(
                            "Video negotiation: " +
                            ex.Message);
                    }
                };

            /*
             * DataChannel para teclado y mouse.
             */
            var dataChannel =
                await _peerConnection.createDataChannel(
                    "wow_controls");

            dataChannel.onmessage +=
                (dc, type, data) =>
                {
                    HandleIncomingWebRtcControls(
                        data);
                };

            /*
             * Offer.
             */
            var offer =
                _peerConnection.createOffer();

            await _peerConnection.setLocalDescription(
                offer);

            string sdp =
                offer.sdp.ToString();

            string base64Sdp =
                Convert.ToBase64String(
                    Encoding.UTF8.GetBytes(
                        sdp));

            await SendSignalingMessage(
                JsonSerializer.Serialize(
                    new
                    {
                        type = "offer",
                        room = _roomId,
                        sdp = base64Sdp
                    }));

            SetStatus(
                "Oferta WebRTC enviada. " +
                "Esperando respuesta...",
                Color.Orange);
        }
        catch (Exception ex)
        {
            Debug.WriteLine(
                "CreateAndSendOffer: " +
                ex);

            ShowError(
                "No se pudo crear la conexión WebRTC:\r\n\r\n" +
                ex.Message);
        }
    }

    private static async Task ProcessAnswer(
        JsonElement root)
    {
        if (_peerConnection == null)
        {
            return;
        }

        if (!root.TryGetProperty(
                "sdp",
                out JsonElement sdpElement))
        {
            return;
        }

        string encodedSdp =
            sdpElement.GetString() ?? "";

        if (string.IsNullOrWhiteSpace(encodedSdp))
        {
            return;
        }

        string sdp;

        try
        {
            sdp =
                Encoding.UTF8.GetString(
                    Convert.FromBase64String(
                        encodedSdp));
        }
        catch
        {
            sdp = encodedSdp;
        }

        var remoteDescription =
            new RTCSessionDescriptionInit
            {
                type =
                    RTCSdpType.answer,

                sdp =
                    sdp
            };

        SetDescriptionResultEnum result =
            _peerConnection.setRemoteDescription(
                remoteDescription);

        Debug.WriteLine(
            "setRemoteDescription(answer): " +
            result);

        SetStatus(
            "Respuesta recibida. " +
            "Estableciendo WebRTC...",
            Color.Orange);

        _isStreaming = true;

        _ = Task.Run(
            VideoStreamingLoop);
    }

    private static async Task ProcessRemoteIceCandidate(
        JsonElement root)
    {
        /*
         * Esta versión deja preparado el mensaje ICE
         * para clientes que hagan trickle ICE.
         *
         * La SDP de SIPSorcery puede contener los candidates
         * necesarios durante la negociación inicial.
         *
         * Si el cliente remoto envía ICE adicional,
         * aquí podemos incorporar su API específica sin
         * modificar el resto de WoWStream.
         */
        await Task.CompletedTask;
    }

    // ============================================================
    // VIDEO
    // ============================================================

    private static async Task VideoStreamingLoop()
    {
        const int width = 1280;
        const int height = 720;

        while (_isStreaming &&
               !_shuttingDown &&
               _wowProcess != null &&
               !_wowProcess.HasExited &&
               _videoEncoder != null)
        {
            try
            {
                using Bitmap bmp =
                    new Bitmap(
                        width,
                        height,
                        PixelFormat.Format24bppRgb);

                using Graphics g =
                    Graphics.FromImage(bmp);

                /*
                 * Captura la pantalla.
                 */
                g.CopyFromScreen(
                    0,
                    0,
                    0,
                    0,
                    bmp.Size);

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

    // ============================================================
    // WEBRTC DATA CHANNEL
    // ============================================================

    private static void HandleIncomingWebRtcControls(
        byte[] data)
    {
        if (data == null ||
            data.Length < 3 ||
            _wowHandle == IntPtr.Zero)
        {
            return;
        }

        byte type =
            data[0];

        /*
         * Keyboard:
         *
         * [0] = tipo
         * [1] = acción
         * [2] = tecla
         */
        if (type == 0)
        {
            byte action =
                data[1];

            byte keyChar =
                data[2];

            uint msg =
                action == 1
                    ? WM_KEYDOWN
                    : WM_KEYUP;

            PostMessage(
                _wowHandle,
                msg,
                (IntPtr)keyChar,
                IntPtr.Zero);

            return;
        }

        /*
         * Mouse:
         *
         * [0] = tipo
         * [1] = acción
         * [2..5] = X
         * [6..9] = Y
         */
        if (type == 1 &&
            data.Length >= 10)
        {
            byte mouseAction =
                data[1];

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

            if (!GetClientRect(
                    _wowHandle,
                    out RECT rect))
            {
                return;
            }

            int width =
                rect.Right -
                rect.Left;

            int height =
                rect.Bottom -
                rect.Top;

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

            uint mouseMsg =
                pctY > 0.70f
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

    // ============================================================
    // SIGNALING HELPERS
    // ============================================================

    private static async Task SendSignalingMessage(
        string message)
    {
        if (_signalingWebSocket == null ||
            _signalingWebSocket.State !=
                WebSocketState.Open)
        {
            throw new InvalidOperationException(
                "El WebSocket de signaling no está conectado.");
        }

        byte[] data =
            Encoding.UTF8.GetBytes(
                message);

        await _signalingWebSocket.SendAsync(
            new ArraySegment<byte>(data),
            WebSocketMessageType.Text,
            true,
            CancellationToken.None);
    }

    private static async Task Broadcast(
        ConcurrentDictionary<
            string,
            WebSocket> clients,
        string senderId,
        string message)
    {
        byte[] data =
            Encoding.UTF8.GetBytes(
                message);

        foreach (var pair in clients)
        {
            if (pair.Key == senderId)
            {
                continue;
            }

            WebSocket socket =
                pair.Value;

            if (socket.State !=
                WebSocketState.Open)
            {
                continue;
            }

            try
            {
                await socket.SendAsync(
                    new ArraySegment<byte>(
                        data),
                    WebSocketMessageType.Text,
                    true,
                    CancellationToken.None);
            }
            catch
            {
                // El cliente puede haberse desconectado.
            }
        }
    }

    private static async Task<string?> ReceiveText(
        WebSocket socket,
        byte[] buffer)
    {
        using MemoryStream stream =
            new MemoryStream();

        WebSocketReceiveResult result;

        do
        {
            result =
                await socket.ReceiveAsync(
                    new ArraySegment<byte>(
                        buffer),
                    CancellationToken.None);

            if (result.MessageType ==
                WebSocketMessageType.Close)
            {
                return null;
            }

            if (result.Count > 0)
            {
                stream.Write(
                    buffer,
                    0,
                    result.Count);
            }
        }
        while (!result.EndOfMessage);

        return Encoding.UTF8.GetString(
            stream.ToArray());
    }

    private static async Task SendText(
        WebSocket socket,
        string message)
    {
        if (socket.State !=
            WebSocketState.Open)
        {
            return;
        }

        byte[] data =
            Encoding.UTF8.GetBytes(
                message);

        await socket.SendAsync(
            new ArraySegment<byte>(
                data),
            WebSocketMessageType.Text,
            true,
            CancellationToken.None);
    }

    private static async Task CloseSocket(
        WebSocket socket)
    {
        try
        {
            if (socket.State ==
                WebSocketState.Open)
            {
                await socket.CloseAsync(
                    WebSocketCloseStatus.NormalClosure,
                    "WoWStream closing",
                    CancellationToken.None);
            }
        }
        catch
        {
        }
    }

    private static string CreateError(
        string message)
    {
        return JsonSerializer.Serialize(
            new
            {
                type = "error",
                message
            });
    }

    // ============================================================
    // UTILITIES
    // ============================================================

    private static string GenerateRoomId()
    {
        return Guid.NewGuid()
            .ToString("N")
            .Substring(0, 8)
            .ToUpperInvariant();
    }

    private static string GetLocalIPv4()
    {
        try
        {
            foreach (
                System.Net.NetworkInformation.NetworkInterface nic
                in
                System.Net.NetworkInformation.NetworkInterface
                    .GetAllNetworkInterfaces())
            {
                if (nic.OperationalStatus !=
                    System.Net.NetworkInformation
                        .OperationalStatus.Up)
                {
                    continue;
                }

                if (nic.NetworkInterfaceType ==
                    System.Net.NetworkInformation
                        .NetworkInterfaceType.Loopback)
                {
                    continue;
                }

                foreach (
                    System.Net.NetworkInformation
                        .UnicastIPAddressInformation address
                    in nic.GetIPProperties()
                        .UnicastAddresses)
                {
                    if (address.Address.AddressFamily ==
                        System.Net.Sockets.AddressFamily.InterNetwork)
                    {
                        return address.Address.ToString();
                    }
                }
            }
        }
        catch
        {
        }

        return "127.0.0.1";
    }

    private static void SetStatus(
        string text,
        Color color)
    {
        if (_mainForm == null ||
            _lblStatus == null)
        {
            return;
        }

        try
        {
            if (_mainForm.InvokeRequired)
            {
                _mainForm.BeginInvoke(
                    (MethodInvoker)(() =>
                    {
                        _lblStatus.Text = text;
                        _lblStatus.ForeColor = color;
                    }));

                return;
            }

            _lblStatus.Text =
                text;

            _lblStatus.ForeColor =
                color;
        }
        catch
        {
        }
    }

    private static void ShowError(
        string message)
    {
        if (_mainForm == null)
        {
            return;
        }

        try
        {
            _mainForm.BeginInvoke(
                (MethodInvoker)(() =>
                {
                    MessageBox.Show(
                        _mainForm,
                        message,
                        "WoWStream",
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Error);
                }));
        }
        catch
        {
        }
    }

    private static void SetUIBusy(
        bool busy)
    {
        if (_mainForm == null)
        {
            return;
        }

        try
        {
            _mainForm.Invoke(
                (MethodInvoker)(() =>
                {
                    if (_btnStart != null)
                    {
                        _btnStart.Enabled =
                            !busy;
                    }

                    if (_btnBrowse != null)
                    {
                        _btnBrowse.Enabled =
                            !busy;
                    }

                    if (_txtWowPath != null)
                    {
                        _txtWowPath.Enabled =
                            !busy;
                    }

                    if (_txtConnectionId != null)
                    {
                        _txtConnectionId.Enabled =
                            !busy;
                    }
                }));
        }
        catch
        {
        }
    }

    // ============================================================
    // SHUTDOWN
    // ============================================================

    private static async Task ShutdownAsync()
    {
        if (_shuttingDown)
        {
            return;
        }

        _shuttingDown = true;
        _isStreaming = false;

        try
        {
            _serverCancellation?.Cancel();
        }
        catch
        {
        }

        try
        {
            if (_signalingWebSocket != null)
            {
                await CloseSocket(
                    _signalingWebSocket);

                _signalingWebSocket.Dispose();
                _signalingWebSocket = null;
            }
        }
        catch
        {
        }

        try
        {
            _signalingServer?.Stop();
            _signalingServer?.Close();
            _signalingServer = null;
        }
        catch
        {
        }

        try
        {
            if (_videoEncoder != null)
            {
                _videoEncoder.Dispose();
                _videoEncoder = null;
            }
        }
        catch
        {
        }

        try
        {
            _peerConnection?.Close(
                "WoWStream shutdown");
        }
        catch
        {
        }

        _peerConnection = null;
        _vp8Codec = null;

        try
        {
            if (_wowProcess != null &&
                !_wowProcess.HasExited)
            {
                _wowProcess.CloseMainWindow();
            }
        }
        catch
        {
        }

        _wowProcess = null;
        _wowHandle = IntPtr.Zero;
    }
}
