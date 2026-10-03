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

    private static readonly ConcurrentDictionary<
        string,
        ConcurrentDictionary<string, WebSocket>>
        _rooms = new();

    private static Form? _mainForm;
    private static TextBox? _txtWowPath;
    private static TextBox? _txtConnectionId;
    private static Button? _btnBrowse;
    private static Button? _btnStart;
    private static Label? _lblStatus;
    private static Label? _lblRoom;
    private static Label? _lblServer;

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

    [STAThread]
    private static void Main(string[] args)
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        _roomId =
            GenerateRoomId();

        _localIp =
            GetLocalIPv4();

        CreateMainForm();

        Application.ApplicationExit +=
            (_, _) =>
            {
                _ = ShutdownAsync();
            };

        Application.Run(_mainForm!);
    }

    private static void CreateMainForm()
    {
        _mainForm =
            new Form
            {
                Text = "WoWStream",
                Width = 560,
                Height = 310,
                FormBorderStyle =
                    FormBorderStyle.FixedSingle,
                MaximizeBox = false,
                StartPosition =
                    FormStartPosition.CenterScreen,
                BackColor =
                    Color.FromArgb(20, 24, 30)
            };

        Label lblTitle =
            new Label
            {
                Text = "WoWStream",
                Left = 20,
                Top = 12,
                Width = 500,
                Height = 30,
                ForeColor = Color.Cyan,
                Font =
                    new Font(
                        "Segoe UI",
                        16,
                        FontStyle.Bold)
            };

        Label lblPath =
            new Label
            {
                Text = "Ruta de Wow.exe:",
                Left = 20,
                Top = 50,
                Width = 130,
                ForeColor = Color.White,
                Font =
                    new Font(
                        "Segoe UI",
                        9,
                        FontStyle.Bold)
            };

        _txtWowPath =
            new TextBox
            {
                Left = 20,
                Top = 73,
                Width = 390,
                Text =
                    @"C:\Program Files (x86)\World of Warcraft\_retail_\Wow.exe",
                BackColor =
                    Color.FromArgb(40, 44, 52),
                ForeColor = Color.White,
                BorderStyle =
                    BorderStyle.FixedSingle
            };

        _btnBrowse =
            new Button
            {
                Text = "Buscar...",
                Left = 420,
                Top = 71,
                Width = 100,
                Height = 27,
                BackColor =
                    Color.FromArgb(60, 65, 75),
                ForeColor = Color.White,
                FlatStyle =
                    FlatStyle.Flat
            };

        _btnBrowse.Click +=
            BtnBrowse_Click;

        Label lblId =
            new Label
            {
                Text = "ID de conexión:",
                Left = 20,
                Top = 112,
                Width = 130,
                ForeColor = Color.White,
                Font =
                    new Font(
                        "Segoe UI",
                        9,
                        FontStyle.Bold)
            };

        _txtConnectionId =
            new TextBox
            {
                Left = 20,
                Top = 135,
                Width = 230,
                Text = _roomId,
                BackColor =
                    Color.FromArgb(40, 44, 52),
                ForeColor = Color.Cyan,
                BorderStyle =
                    BorderStyle.FixedSingle,
                Font =
                    new Font(
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
                    _roomId =
                        value;

                    if (_lblRoom != null)
                    {
                        _lblRoom.Text =
                            "Sala: " +
                            _roomId;
                    }
                }
            };

        _lblRoom =
            new Label
            {
                Text =
                    "Sala: " +
                    _roomId,
                Left = 270,
                Top = 138,
                Width = 250,
                ForeColor =
                    Color.LightGreen,
                Font =
                    new Font(
                        "Segoe UI",
                        9,
                        FontStyle.Bold)
            };

        _btnStart =
            new Button
            {
                Text = "INICIAR WoWStream",
                Left = 20,
                Top = 180,
                Width = 230,
                Height = 38,
                BackColor =
                    Color.FromArgb(
                        75,
                        100,
                        205),
                ForeColor = Color.White,
                FlatStyle =
                    FlatStyle.Flat,
                Font =
                    new Font(
                        "Segoe UI",
                        10,
                        FontStyle.Bold)
            };

        _btnStart.Click +=
            BtnStart_Click;

        _lblStatus =
            new Label
            {
                Text =
                    "Estado: Detenido.",
                Left = 270,
                Top = 190,
                Width = 250,
                Height = 25,
                ForeColor =
                    Color.Gray,
                Font =
                    new Font(
                        "Segoe UI",
                        9,
                        FontStyle.Bold)
            };

        _lblServer =
            new Label
            {
                Text =
                    $"Signaling: ws://{_localIp}:{SignalingPort}/ws",
                Left = 20,
                Top = 235,
                Width = 500,
                Height = 25,
                ForeColor =
                    Color.LightSkyBlue,
                Font =
                    new Font(
                        "Segoe UI",
                        8,
                        FontStyle.Regular)
            };

        _mainForm.Controls.Add(
            lblTitle);

        _mainForm.Controls.Add(
            lblPath);

        _mainForm.Controls.Add(
            _txtWowPath);

        _mainForm.Controls.Add(
            _btnBrowse);

        _mainForm.Controls.Add(
            lblId);

        _mainForm.Controls.Add(
            _txtConnectionId);

        _mainForm.Controls.Add(
            _lblRoom);

        _mainForm.Controls.Add(
            _btnStart);

        _mainForm.Controls.Add(
            _lblStatus);

        _mainForm.Controls.Add(
            _lblServer);
    }

    private static void BtnBrowse_Click(
        object? sender,
        EventArgs e)
    {
        using OpenFileDialog ofd =
            new OpenFileDialog
            {
                Filter =
                    "Ejecutable de WoW (*.exe)|*.exe|Todos los archivos (*.*)|*.*"
            };

        if (ofd.ShowDialog() ==
            DialogResult.OK)
        {
            if (_txtWowPath != null)
            {
                _txtWowPath.Text =
                    ofd.FileName;
            }
        }
    }

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

        if (string.IsNullOrWhiteSpace(
                connectionId))
        {
            MessageBox.Show(
                "Introduce un ID de conexión.",
                "WoWStream",
                MessageBoxButtons.OK,
                MessageBoxIcon.Warning);

            return;
        }

        _roomId =
            connectionId;

        _shuttingDown =
            false;

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
                    FileName =
                        wowPath,
                    Arguments =
                        "-windowed",
                    UseShellExecute =
                        true,
                    WorkingDirectory =
                        Path.GetDirectoryName(
                            wowPath)
                        ?? Environment.CurrentDirectory
                };

            _wowProcess =
                Process.Start(
                    startInfo);

            if (_wowProcess == null)
            {
                throw new InvalidOperationException(
                    "No se pudo iniciar Wow.exe.");
            }

            await Task.Delay(3000);

            Process? process =
                _wowProcess;

            if (process != null)
            {
                try
                {
                    process.Refresh();

                    _wowHandle =
                        process.MainWindowHandle;
                }
                catch
                {
                    _wowHandle =
                        IntPtr.Zero;
                }
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

    private static async Task StartSignalingServer()
    {
        if (_signalingServer != null)
            return;

        _serverCancellation =
            new CancellationTokenSource();

        HttpListener server =
            new HttpListener();

        server.Prefixes.Add(
            $"http://+:{SignalingPort}/ws/");

        server.Prefixes.Add(
            $"http://+:{SignalingPort}/");

        try
        {
            server.Start();

            _signalingServer =
                server;
        }
        catch
        {
            try
            {
                server.Close();
            }
            catch
            {
            }

            throw new InvalidOperationException(
                $"No se pudo abrir el puerto {SignalingPort}. " +
                "Comprueba que ningún otro programa lo esté utilizando " +
                "y que Windows permita la reserva HTTP.");
        }

        CancellationToken token =
            _serverCancellation.Token;

        _ = Task.Run(
            () =>
                SignalingAcceptLoop(
                    token));
    }

    private static async Task SignalingAcceptLoop(
        CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            HttpListener? server =
                _signalingServer;

            if (server == null)
                break;

            try
            {
                HttpListenerContext context =
                    await server.GetContextAsync();

                if (!context.Request.IsWebSocketRequest)
                {
                    context.Response.StatusCode =
                        400;

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
                    context.Request.Url?.AbsolutePath
                    ?? "";

                if (!path.Equals(
                        "/ws",
                        StringComparison.OrdinalIgnoreCase) &&
                    !path.Equals(
                        "/ws/",
                        StringComparison.OrdinalIgnoreCase))
                {
                    context.Response.StatusCode =
                        404;

                    context.Response.Close();

                    continue;
                }

                HttpListenerWebSocketContext wsContext =
                    await context.AcceptWebSocketAsync(
                        null);

                WebSocket socket =
                    wsContext.WebSocket;

                _ = Task.Run(
                    () =>
                        HandleSignalingClient(
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
            query["client"] ??
            Guid.NewGuid().ToString("N");

        if (string.IsNullOrWhiteSpace(room))
        {
            await SendText(
                socket,
                CreateError(
                    "Falta el parámetro room."));

            await CloseSocket(
                socket);

            return;
        }

        ConcurrentDictionary<string, WebSocket> roomSockets =
            _rooms.GetOrAdd(
                room,
                _ =>
                    new ConcurrentDictionary<
                        string,
                        WebSocket>());

        string originalClient =
            client;

        int suffix =
            1;

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
                await CloseSocket(
                    socket);

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
                    type =
                        "joined",
                    room,
                    client
                }));

        await Broadcast(
            roomSockets,
            client,
            JsonSerializer.Serialize(
                new
                {
                    type =
                        "peer-joined",
                    client
                }));

        byte[] buffer =
            new byte[
                64 * 1024];

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
                    break;

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
                        type =
                            "peer-left",
                        client
                    }));

            if (roomSockets.IsEmpty)
            {
                _rooms.TryRemove(
                    room,
                    out _);
            }

            await CloseSocket(
                socket);
        }
    }

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
        ClientWebSocket? socket =
            _signalingWebSocket;

        if (socket == null)
            return;

        byte[] buffer =
            new byte[
                64 * 1024];

        while (socket.State ==
               WebSocketState.Open)
        {
            try
            {
                string? message =
                    await ReceiveText(
                        socket,
                        buffer);

                if (message == null)
                    break;

                Debug.WriteLine(
                    "[SIGNALING RX] " +
                    message);

                using JsonDocument doc =
                    JsonDocument.Parse(
                        message);

                if (!doc.RootElement.TryGetProperty(
                        "type",
                        out JsonElement typeElement))
                {
                    continue;
                }

                string type =
                    typeElement.GetString()
                    ?? "";

                switch (type)
                {
                    case "peer-joined":

                        SetStatus(
                            "Cliente encontrado. Negociando WebRTC...",
                            Color.Orange);

                        await CreateAndSendOffer();

                        break;

                    case "answer":

                        await ProcessAnswer(
                            doc.RootElement);

                        break;

                    case "ice-candidate":

                        ProcessRemoteIceCandidate(
                            doc.RootElement);

                        break;

                    case "peer-left":

                        _isStreaming =
                            false;

                        SetStatus(
                            "Cliente desconectado. Esperando otro cliente...",
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

    private static async Task CreateAndSendOffer()
    {
        try
        {
            _isStreaming =
                false;

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

                _peerConnection =
                    null;
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

                _videoEncoder =
                    null;
            }

            _vp8Codec =
                new VP8Codec();

            RTCConfiguration config =
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

            RTCPeerConnection peerConnection =
                new RTCPeerConnection(
                    config);

            _peerConnection =
                peerConnection;

            peerConnection.onicecandidate +=
                candidate =>
                {
                    SendIceCandidate(
                        candidate);
                };

            peerConnection.oniceconnectionstatechange +=
                state =>
                {
                    SetStatus(
                        "ICE: " +
                        state,
                        Color.LightSkyBlue);
                };

            peerConnection.onconnectionstatechange +=
                state =>
                {
                    SetStatus(
                        "WebRTC: " +
                        state,
                        Color.LightSkyBlue);
                };

            _videoEncoder =
                new Vp8NetVideoEncoderEndPoint();

            MediaStreamTrack videoTrack =
                new MediaStreamTrack(
                    _videoEncoder.GetVideoSourceFormats(),
                    MediaStreamStatusEnum.SendOnly);

            peerConnection.addTrack(
                videoTrack);

            _videoEncoder.OnVideoSourceEncodedSample +=
                peerConnection.SendVideo;

            peerConnection.OnVideoFormatsNegotiated +=
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

            var dataChannel =
                await peerConnection.createDataChannel(
                    "wow_controls");

            dataChannel.onmessage +=
                (
                    RTCDataChannel dc,
                    DataChannelPayloadProtocols type,
                    byte[] data
                ) =>
                {
                    HandleIncomingWebRtcControls(
                        data);
                };

            var offer =
                peerConnection.createOffer();

            /*
             * IMPORTANTE:
             * En la versión de SIPSorcery que está
             * utilizando este proyecto, setLocalDescription
             * devuelve VOID.
             *
             * NO usar await.
             * NO asignar el resultado a SetDescriptionResultEnum.
             */
            peerConnection.setLocalDescription(
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
                        type =
                            "offer",
                        room =
                            _roomId,
                        sdp =
                            base64Sdp
                    }));

            SetStatus(
                "Oferta WebRTC enviada. Esperando respuesta...",
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
        RTCPeerConnection? peerConnection =
            _peerConnection;

        if (peerConnection == null)
            return;

        if (!root.TryGetProperty(
                "sdp",
                out JsonElement sdpElement))
        {
            return;
        }

        string encodedSdp =
            sdpElement.GetString()
            ?? "";

        if (string.IsNullOrWhiteSpace(
                encodedSdp))
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
            sdp =
                encodedSdp;
        }

        RTCSessionDescriptionInit remoteDescription =
            new RTCSessionDescriptionInit
            {
                type =
                    RTCSdpType.answer,
                sdp =
                    sdp
            };

        /*
         * Esta llamada se mantiene como await porque
         * la firma que está utilizando el proyecto para
         * setRemoteDescription devuelve Task<...>.
         */
        SetDescriptionResultEnum result =
            await peerConnection.setRemoteDescription(
                remoteDescription);

        Debug.WriteLine(
            "setRemoteDescription(answer): " +
            result);

        if (result !=
            SetDescriptionResultEnum.OK)
        {
            SetStatus(
                "Error aplicando answer: " +
                result,
                Color.Red);

            return;
        }

        SetStatus(
            "Respuesta recibida. WebRTC establecido.",
            Color.LightGreen);

        _isStreaming =
            true;

        _ = Task.Run(
            VideoStreamingLoop);
    }

    private static void SendIceCandidate(
        RTCIceCandidate candidate)
    {
        _ = Task.Run(
            async () =>
            {
                try
                {
                    await SendSignalingMessage(
                        JsonSerializer.Serialize(
                            new
                            {
                                type =
                                    "ice-candidate",
                                room =
                                    _roomId,
                                candidate =
                                    candidate.candidate,
                                sdpMid =
                                    candidate.sdpMid,
                                sdpMLineIndex =
                                    candidate.sdpMLineIndex
                            }));
                }
                catch (Exception ex)
                {
                    Debug.WriteLine(
                        "SendIceCandidate: " +
                        ex.Message);
                }
            });
    }

    private static void ProcessRemoteIceCandidate(
        JsonElement root)
    {
        RTCPeerConnection? peerConnection =
            _peerConnection;

        if (peerConnection == null)
            return;

        try
        {
            if (!root.TryGetProperty(
                    "candidate",
                    out JsonElement candidateElement))
            {
                return;
            }

            string candidate =
                candidateElement.GetString()
                ?? "";

            if (string.IsNullOrWhiteSpace(
                    candidate))
            {
                return;
            }

            string? sdpMid =
                null;

            if (root.TryGetProperty(
                    "sdpMid",
                    out JsonElement midElement))
            {
                sdpMid =
                    midElement.GetString();
            }

            ushort sdpMLineIndex =
                0;

            if (root.TryGetProperty(
                    "sdpMLineIndex",
                    out JsonElement indexElement))
            {
                int index =
                    indexElement.GetInt32();

                if (index < 0)
                    index = 0;

                if (index > ushort.MaxValue)
                    index =
                        ushort.MaxValue;

                sdpMLineIndex =
                    (ushort)index;
            }

            peerConnection.addIceCandidate(
                new RTCIceCandidateInit
                {
                    candidate =
                        candidate,
                    sdpMid =
                        sdpMid,
                    sdpMLineIndex =
                        sdpMLineIndex
                });
        }
        catch (Exception ex)
        {
            Debug.WriteLine(
                "ProcessRemoteIceCandidate: " +
                ex.Message);
        }
    }

    private static async Task VideoStreamingLoop()
    {
        const int width =
            1280;

        const int height =
            720;

        while (_isStreaming &&
               !_shuttingDown &&
               _videoEncoder != null)
        {
            Process? process =
                _wowProcess;

            if (process == null)
                break;

            try
            {
                if (process.HasExited)
                    break;
            }
            catch
            {
                break;
            }

            try
            {
                using Bitmap bmp =
                    new Bitmap(
                        width,
                        height,
                        PixelFormat.Format24bppRgb);

                using Graphics g =
                    Graphics.FromImage(
                        bmp);

                g.CopyFromScreen(
                    0,
                    0,
                    0,
                    0,
                    bmp.Size,
                    CopyPixelOperation.SourceCopy);

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
                    int rowBytes =
                        bmp.Width * 3;

                    byte[] rawBgr =
                        new byte[
                            rowBytes *
                            bmp.Height];

                    for (int y = 0;
                         y < bmp.Height;
                         y++)
                    {
                        IntPtr source =
                            IntPtr.Add(
                                data.Scan0,
                                y * data.Stride);

                        Marshal.Copy(
                            source,
                            rawBgr,
                            y * rowBytes,
                            rowBytes);
                    }

                    Vp8NetVideoEncoderEndPoint? encoder =
                        _videoEncoder;

                    if (encoder != null)
                    {
                        encoder.ExternalVideoSourceRawSample(
                            33,
                            bmp.Width,
                            bmp.Height,
                            rawBgr,
                            VideoPixelFormatsEnum.Bgr);
                    }
                }
                finally
                {
                    bmp.UnlockBits(
                        data);
                }

                await Task.Delay(
                    33);
            }
            catch (Exception ex)
            {
                Debug.WriteLine(
                    "VideoStreamingLoop: " +
                    ex.Message);

                await Task.Delay(
                    100);
            }
        }

        _isStreaming =
            false;
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

        byte type =
            data[0];

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

            uint virtualKey =
                keyChar switch
                {
                    (byte)'W' =>
                        0x57,

                    (byte)'A' =>
                        0x41,

                    (byte)'S' =>
                        0x53,

                    (byte)'D' =>
                        0x44,

                    (byte)'M' =>
                        0x4D,

                    0x20 =>
                        0x20,

                    _ =>
                        keyChar
                };

            PostMessage(
                _wowHandle,
                msg,
                new IntPtr(
                    virtualKey),
                IntPtr.Zero);

            return;
        }

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
                (int)(
                    pctX *
                    (width - 1));

            int y =
                (int)(
                    pctY *
                    (height - 1));

            IntPtr lParam =
                new IntPtr(
                    (y << 16) |
                    (x & 0xFFFF));

            bool leftButton =
                pctY > 0.70f;

            uint mouseMsg;

            if (leftButton)
            {
                mouseMsg =
                    mouseAction == 1
                        ? WM_LBUTTONDOWN
                        : WM_LBUTTONUP;
            }
            else
            {
                mouseMsg =
                    mouseAction == 1
                        ? WM_RBUTTONDOWN
                        : WM_RBUTTONUP;
            }

            PostMessage(
                _wowHandle,
                mouseMsg,
                IntPtr.Zero,
                lParam);
        }
    }

    private static async Task SendSignalingMessage(
        string message)
    {
        ClientWebSocket? socket =
            _signalingWebSocket;

        if (socket == null ||
            socket.State !=
                WebSocketState.Open)
        {
            throw new InvalidOperationException(
                "El WebSocket de signaling no está conectado.");
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
            if (pair.Key ==
                senderId)
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
            try
            {
                socket.Abort();
            }
            catch
            {
            }
        }
    }

    private static string CreateError(
        string message)
    {
        return JsonSerializer.Serialize(
            new
            {
                type =
                    "error",
                message
            });
    }

    private static string GenerateRoomId()
    {
        return Guid.NewGuid()
            .ToString("N")
            .Substring(
                0,
                8)
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
                    in
                    nic.GetIPProperties()
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
        Form? form =
            _mainForm;

        Label? status =
            _lblStatus;

        if (form == null ||
            status == null)
        {
            return;
        }

        try
        {
            if (form.InvokeRequired)
            {
                form.BeginInvoke(
                    (MethodInvoker)(
                        () =>
                        {
                            if (_lblStatus != null)
                            {
                                _lblStatus.Text =
                                    text;

                                _lblStatus.ForeColor =
                                    color;
                            }
                        }));

                return;
            }

            status.Text =
                text;

            status.ForeColor =
                color;
        }
        catch
        {
        }
    }

    private static void ShowError(
        string message)
    {
        Form? form =
            _mainForm;

        if (form == null)
            return;

        try
        {
            form.BeginInvoke(
                (MethodInvoker)(
                    () =>
                    {
                        MessageBox.Show(
                            form,
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
        Form? form =
            _mainForm;

        if (form == null)
            return;

        try
        {
            if (form.InvokeRequired)
            {
                form.BeginInvoke(
                    (MethodInvoker)(
                        () =>
                        {
                            SetUIBusyInternal(
                                busy);
                        }));

                return;
            }

            SetUIBusyInternal(
                busy);
        }
        catch
        {
        }
    }

    private static void SetUIBusyInternal(
        bool busy)
    {
        if (_btnStart != null)
            _btnStart.Enabled =
                !busy;

        if (_btnBrowse != null)
            _btnBrowse.Enabled =
                !busy;

        if (_txtWowPath != null)
            _txtWowPath.Enabled =
                !busy;

        if (_txtConnectionId != null)
            _txtConnectionId.Enabled =
                !busy;
    }

    private static async Task ShutdownAsync()
    {
        if (_shuttingDown)
            return;

        _shuttingDown =
            true;

        _isStreaming =
            false;

        try
        {
            _serverCancellation?.Cancel();
        }
        catch
        {
        }

        try
        {
            ClientWebSocket? socket =
                _signalingWebSocket;

            if (socket != null)
            {
                await CloseSocket(
                    socket);

                socket.Dispose();

                _signalingWebSocket =
                    null;
            }
        }
        catch
        {
        }

        try
        {
            HttpListener? server =
                _signalingServer;

            _signalingServer =
                null;

            if (server != null)
            {
                server.Stop();
                server.Close();
            }
        }
        catch
        {
        }

        foreach (var room in _rooms)
        {
            foreach (var socket in room.Value)
            {
                try
                {
                    socket.Value.Abort();
                }
                catch
                {
                }
            }
        }

        _rooms.Clear();

        try
        {
            RTCPeerConnection? peer =
                _peerConnection;

            _peerConnection =
                null;

            if (peer != null)
            {
                peer.Close(
                    "WoWStream shutdown");
            }
        }
        catch
        {
        }

        try
        {
            Vp8NetVideoEncoderEndPoint? encoder =
                _videoEncoder;

            _videoEncoder =
                null;

            if (encoder != null)
            {
                encoder.Dispose();
            }
        }
        catch
        {
        }

        _vp8Codec =
            null;

        try
        {
            Process? process =
                _wowProcess;

            _wowProcess =
                null;

            if (process != null)
            {
                if (!process.HasExited)
                {
                    process.CloseMainWindow();
                }
            }
        }
        catch
        {
        }

        _wowHandle =
            IntPtr.Zero;
    }
}

La corrección clave respecto al error que acabas de mostrar es esta:

var offer =
    peerConnection.createOffer();

peerConnection.setLocalDescription(
    offer);
