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

namespace WoWStream;

internal static class Program
{
    // ============================================================
    // CONFIGURACIÓN
    // ============================================================

    private const int SignalingPort = 8080;

    private static readonly string SignalingPrefix =
        $"http://localhost:{SignalingPort}/";

    private static readonly HttpListener SignalingServer =
        new();

    private static readonly ConcurrentDictionary<
        string,
        ConcurrentDictionary<string, WebSocket>
    > Rooms = new();

    private static HttpListenerContext? _httpContext;

    // ============================================================
    // WOW
    // ============================================================

    private static Process? _wowProcess;
    private static IntPtr _wowHandle = IntPtr.Zero;

    // ============================================================
    // WEBRTC
    // ============================================================

    private static RTCPeerConnection? _peerConnection;

    private static Vp8NetVideoEncoderEndPoint? _videoEncoder;
    private static VP8Codec? _vp8Codec;

    private static MediaStreamTrack? _videoTrack;

    private static RTCDataChannel? _controlDataChannel;

    private static readonly object PeerLock = new();

    private static bool _answerReceived;
    private static bool _offerSent;
    private static bool _streaming;

    // ============================================================
    // SIGNALING
    // ============================================================

    private static string _roomId = "";
    private static string _hostClientId = "host";

    // ============================================================
    // UI
    // ============================================================

    private static Form? _form;

    private static TextBox? _wowPathText;
    private static TextBox? _roomText;
    private static TextBox? _statusText;
    private static Button? _startButton;

    // ============================================================
    // WIN32
    // ============================================================

    [DllImport("user32.dll")]
    private static extern bool PostMessage(
        IntPtr hWnd,
        uint msg,
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
    private static void Main()
    {
        ApplicationConfiguration.Initialize();

        CreateUserInterface();

        StartSignalingServer();

        Application.Run(_form);

        Shutdown();
    }

    // ============================================================
    // UI
    // ============================================================

    private static void CreateUserInterface()
    {
        _form = new Form
        {
            Text = "WoWStream",
            Width = 620,
            Height = 420,
            StartPosition = FormStartPosition.CenterScreen,
            FormBorderStyle = FormBorderStyle.FixedSingle,
            MaximizeBox = false
        };

        var title = new Label
        {
            Text = "WoWStream",
            AutoSize = true,
            Font = new Font(
                "Segoe UI",
                20,
                FontStyle.Bold),
            Location = new Point(25, 20)
        };

        _form.Controls.Add(title);

        var wowLabel = new Label
        {
            Text = "Ruta de WoW.exe:",
            AutoSize = true,
            Location = new Point(25, 80)
        };

        _form.Controls.Add(wowLabel);

        _wowPathText = new TextBox
        {
            Location = new Point(25, 105),
            Width = 450,
            Text = @"C:\World of Warcraft\Wow.exe"
        };

        _form.Controls.Add(_wowPathText);

        var browseButton = new Button
        {
            Text = "Examinar",
            Location = new Point(485, 103),
            Width = 90
        };

        browseButton.Click += (_, _) =>
        {
            using var dialog = new OpenFileDialog
            {
                Filter = "World of Warcraft|Wow.exe|Executable|*.exe"
            };

            if (dialog.ShowDialog() ==
                DialogResult.OK)
            {
                _wowPathText!.Text =
                    dialog.FileName;
            }
        };

        _form.Controls.Add(browseButton);

        var roomLabel = new Label
        {
            Text = "ID de sala:",
            AutoSize = true,
            Location = new Point(25, 155)
        };

        _form.Controls.Add(roomLabel);

        _roomText = new TextBox
        {
            Location = new Point(25, 180),
            Width = 220,
            Text = GenerateRoomId()
        };

        _form.Controls.Add(_roomText);

        _startButton = new Button
        {
            Text = "INICIAR WOWSTREAM",
            Location = new Point(265, 178),
            Width = 180,
            Height = 30
        };

        _startButton.Click += (_, _) =>
        {
            StartStreaming();
        };

        _form.Controls.Add(_startButton);

        var info = new Label
        {
            Text =
                "Android debe conectarse a:\r\n" +
                "ws://IP-DE-ESTA-PC:8080/ws\r\n\r\n" +
                "Puerto de señalización: 8080",
            AutoSize = true,
            Location = new Point(25, 235)
        };

        _form.Controls.Add(info);

        _statusText = new TextBox
        {
            Location = new Point(25, 315),
            Width = 550,
            Height = 45,
            Multiline = true,
            ReadOnly = true,
            ScrollBars = ScrollBars.Vertical
        };

        _form.Controls.Add(_statusText);

        SetStatus(
            "WoWStream listo. Sala: " +
            _roomText.Text);
    }

    private static void SetStatus(
        string text)
    {
        if (_statusText == null)
            return;

        void Update()
        {
            _statusText.Text =
                DateTime.Now.ToString("HH:mm:ss") +
                "  " +
                text;
        }

        if (_statusText.InvokeRequired)
            _statusText.BeginInvoke(Update);
        else
            Update();
    }

    // ============================================================
    // SIGNALING SERVER
    // ============================================================

    private static void StartSignalingServer()
    {
        try
        {
            SignalingServer.Prefixes.Add(
                SignalingPrefix);

            SignalingServer.Start();

            SetStatus(
                $"Servidor de señalización activo en " +
                $"127.0.0.1:{SignalingPort}");

            _ = Task.Run(
                SignalingAcceptLoop);
        }
        catch (Exception ex)
        {
            SetStatus(
                "ERROR iniciando señalización: " +
                ex.Message);
        }
    }

    private static async Task SignalingAcceptLoop()
    {
        while (
            SignalingServer.IsListening)
        {
            try
            {
                var context =
                    await SignalingServer.GetContextAsync();

                _ = Task.Run(
                    () => HandleHttpContext(
                        context));
            }
            catch
            {
                if (!SignalingServer.IsListening)
                    break;
            }
        }
    }

    private static async Task HandleHttpContext(
        HttpListenerContext context)
    {
        try
        {
            if (!context.Request.IsWebSocketRequest)
            {
                context.Response.StatusCode = 400;
                context.Response.Close();
                return;
            }

            var wsContext =
                await context.AcceptWebSocketAsync(
                    null);

            var socket =
                wsContext.WebSocket;

            var room =
                context.Request.QueryString["room"];

            var client =
                context.Request.QueryString["client"];

            if (string.IsNullOrWhiteSpace(room))
                room = "DEFAULT";

            if (string.IsNullOrWhiteSpace(client))
                client =
                    Guid.NewGuid()
                        .ToString("N");

            var roomSockets =
                Rooms.GetOrAdd(
                    room,
                    _ =>
                        new ConcurrentDictionary<
                            string,
                            WebSocket>());

            roomSockets[client] = socket;

            SetStatus(
                $"Cliente conectado: {client} / sala {room}");

            await SendJson(
                socket,
                new
                {
                    type = "joined",
                    room,
                    client
                });

            await BroadcastExcept(
                room,
                client,
                new
                {
                    type = "peer-joined",
                    room,
                    client
                });

            await WebSocketReceiveLoop(
                room,
                client,
                socket);
        }
        catch (Exception ex)
        {
            SetStatus(
                "WebSocket error: " +
                ex.Message);
        }
    }

    private static async Task WebSocketReceiveLoop(
        string room,
        string client,
        WebSocket socket)
    {
        var buffer =
            new byte[1024 * 256];

        try
        {
            while (
                socket.State ==
                WebSocketState.Open)
            {
                using var ms =
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
                        break;
                    }

                    ms.Write(
                        buffer,
                        0,
                        result.Count);

                } while (!result.EndOfMessage);

                if (result.MessageType ==
                    WebSocketMessageType.Close)
                {
                    break;
                }

                string message =
                    Encoding.UTF8.GetString(
                        ms.ToArray());

                await ProcessSignalingMessage(
                    room,
                    client,
                    message);
            }
        }
        catch
        {
        }
        finally
        {
            if (Rooms.TryGetValue(
                    room,
                    out var roomSockets))
            {
                roomSockets.TryRemove(
                    client,
                    out _);

                if (roomSockets.IsEmpty)
                {
                    Rooms.TryRemove(
                        room,
                        out _);
                }
            }

            SetStatus(
                $"Cliente desconectado: {client}");
        }
    }

    private static async Task ProcessSignalingMessage(
        string room,
        string sender,
        string message)
    {
        try
        {
            using JsonDocument document =
                JsonDocument.Parse(message);

            var root =
                document.RootElement;

            string type =
                root.TryGetProperty(
                    "type",
                    out var typeElement)
                    ? typeElement.GetString() ?? ""
                    : "";

            if (string.Equals(
                    type,
                    "answer",
                    StringComparison.OrdinalIgnoreCase))
            {
                await HandleAnswer(root);

                await BroadcastExcept(
                    room,
                    sender,
                    message);

                return;
            }

            if (string.Equals(
                    type,
                    "ice-candidate",
                    StringComparison.OrdinalIgnoreCase))
            {
                await HandleRemoteIceCandidate(
                    root);

                await BroadcastExcept(
                    room,
                    sender,
                    message);

                return;
            }

            await BroadcastExcept(
                room,
                sender,
                message);
        }
        catch (Exception ex)
        {
            SetStatus(
                "Error signaling: " +
                ex.Message);
        }
    }

    private static async Task BroadcastExcept(
        string room,
        string exceptClient,
        object message)
    {
        string json =
            JsonSerializer.Serialize(message);

        await BroadcastExcept(
            room,
            exceptClient,
            json);
    }

    private static async Task BroadcastExcept(
        string room,
        string exceptClient,
        string json)
    {
        if (!Rooms.TryGetValue(
                room,
                out var sockets))
            return;

        byte[] data =
            Encoding.UTF8.GetBytes(json);

        foreach (var pair in sockets)
        {
            if (pair.Key == exceptClient)
                continue;

            if (pair.Value.State !=
                WebSocketState.Open)
                continue;

            try
            {
                await pair.Value.SendAsync(
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

    private static async Task SendJson(
        WebSocket socket,
        object message)
    {
        string json =
            JsonSerializer.Serialize(message);

        byte[] data =
            Encoding.UTF8.GetBytes(json);

        await socket.SendAsync(
            new ArraySegment<byte>(data),
            WebSocketMessageType.Text,
            true,
            CancellationToken.None);
    }

    // ============================================================
    // START WOW
    // ============================================================

    private static void StartStreaming()
    {
        if (_streaming)
            return;

        if (_wowPathText == null ||
            !File.Exists(
                _wowPathText.Text))
        {
            MessageBox.Show(
                "No se encontró Wow.exe.",
                "WoWStream",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);

            return;
        }

        _roomId =
            _roomText?.Text.Trim() ??
            GenerateRoomId();

        if (string.IsNullOrWhiteSpace(
                _roomId))
        {
            _roomId =
                GenerateRoomId();
        }

        try
        {
            StartWoW(
                _wowPathText.Text);

            SetupWebRtc();

            _streaming = true;

            if (_startButton != null)
                _startButton.Enabled = false;

            SetStatus(
                $"WoW iniciado. Sala: {_roomId}");
        }
        catch (Exception ex)
        {
            SetStatus(
                "ERROR iniciando WoWStream: " +
                ex.Message);

            MessageBox.Show(
                ex.ToString(),
                "WoWStream",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
        }
    }

    private static void StartWoW(
        string path)
    {
        _wowProcess =
            new Process
            {
                StartInfo =
                    new ProcessStartInfo
                    {
                        FileName = path,
                        Arguments =
                            "-windowed",
                        UseShellExecute = true,
                        WorkingDirectory =
                            Path.GetDirectoryName(
                                path) ??
                            Environment.CurrentDirectory
                    }
            };

        _wowProcess.Start();

        _wowProcess.WaitForInputIdle(10000);

        Thread.Sleep(1500);

        _wowHandle =
            _wowProcess.MainWindowHandle;

        if (_wowHandle ==
            IntPtr.Zero)
        {
            Thread.Sleep(2000);

            _wowHandle =
                _wowProcess.MainWindowHandle;
        }

        SetStatus(
            "WoW.exe listo.");
    }

    // ============================================================
    // WEBRTC SETUP
    // ============================================================

    private static void SetupWebRtc()
    {
        lock (PeerLock)
        {
            _peerConnection?.close();

            _peerConnection =
                new RTCPeerConnection(
                    new RTCConfiguration
                    {
                        iceServers =
                            new List<RTCIceServer>
                            {
                                new RTCIceServer
                                {
                                    urls =
                                        "stun:stun.l.google.com:19302"
                                }
                            }
                    });

            _answerReceived = false;
            _offerSent = false;

            _peerConnection.onicecandidate +=
                OnLocalIceCandidate;

            _peerConnection.oniceconnectionstatechange +=
                state =>
                {
                    SetStatus(
                        "ICE: " +
                        state);
                };

            _peerConnection.onconnectionstatechange +=
                state =>
                {
                    SetStatus(
                        "WebRTC: " +
                        state);
                };

            _peerConnection.ondatachannel +=
                OnDataChannel;

            _vp8Codec =
                new VP8Codec();

            // IMPORTANTE:
            // SIPSorcery.VP8 10.0.14 usa
            // constructor sin argumentos.
            _videoEncoder =
                new Vp8NetVideoEncoderEndPoint();

            _videoEncoder.OnVideoSourceEncodedSample +=
                OnEncodedVideoSample;

            _videoTrack =
                new MediaStreamTrack(
                    _videoEncoder
                        .GetVideoSourceFormats(),
                    MediaStreamStatusEnum.SendOnly);

            _peerConnection.addTrack(
                _videoTrack);

            _peerConnection.OnVideoFormatsNegotiated +=
                formats =>
                {
                    if (formats.Count > 0)
                    {
                        _videoEncoder
                            .SetVideoSourceFormat(
                                formats[0]);
                    }
                };

            SetStatus(
                "WebRTC preparado. Esperando Android...");
        }

        _ = Task.Run(
            WaitForAndroidAndCreateOffer);
    }

    private static async Task WaitForAndroidAndCreateOffer()
    {
        while (_streaming)
        {
            if (Rooms.TryGetValue(
                    _roomId,
                    out var clients))
            {
                foreach (var pair in clients)
                {
                    if (pair.Key !=
                        _hostClientId &&
                        pair.Value.State ==
                        WebSocketState.Open)
                    {
                        await CreateAndSendOffer();
                        return;
                    }
                }
            }

            await Task.Delay(250);
        }
    }

    // ============================================================
    // OFFER
    // ============================================================

    private static async Task CreateAndSendOffer()
    {
        lock (PeerLock)
        {
            if (_offerSent)
                return;

            _offerSent = true;
        }

        try
        {
            if (_peerConnection == null)
                return;

            var offer =
                _peerConnection.createOffer(
                    null);

            await _peerConnection
                .setLocalDescription(
                    offer);

            /*
             * setLocalDescription comienza
             * la recopilación ICE.
             *
             * Esperamos brevemente para que
             * los candidatos principales queden
             * incluidos en el SDP.
             */
            await Task.Delay(1500);

            string sdp =
                _peerConnection
                    .localDescription
                    .sdp;

            string encoded =
                Convert.ToBase64String(
                    Encoding.UTF8.GetBytes(
                        sdp));

            var message =
                new
                {
                    type = "offer",
                    room = _roomId,
                    sdp = encoded
                };

            await BroadcastToRoom(
                _roomId,
                _hostClientId,
                message);

            SetStatus(
                "Offer WebRTC enviada a Android.");
        }
        catch (Exception ex)
        {
            SetStatus(
                "Error creando offer: " +
                ex.Message);
        }
    }

    // ============================================================
    // ANSWER
    // ============================================================

    private static async Task HandleAnswer(
        JsonElement root)
    {
        try
        {
            if (_peerConnection == null)
                return;

            if (!root.TryGetProperty(
                    "sdp",
                    out var sdpElement))
                return;

            string encoded =
                sdpElement.GetString() ??
                "";

            if (string.IsNullOrWhiteSpace(
                    encoded))
                return;

            string sdp =
                Encoding.UTF8.GetString(
                    Convert.FromBase64String(
                        encoded));

            var answer =
                new RTCSessionDescriptionInit
                {
                    type =
                        RTCSdpType.answer,
                    sdp = sdp
                };

            await _peerConnection
                .setRemoteDescription(
                    answer);

            _answerReceived = true;

            SetStatus(
                "Answer recibido. WebRTC negociado.");

            _ = Task.Run(
                StartCaptureLoop);
        }
        catch (Exception ex)
        {
            SetStatus(
                "Error aplicando answer: " +
                ex.Message);
        }
    }

    // ============================================================
    // ICE
    // ============================================================

    private static void OnLocalIceCandidate(
        RTCIceCandidate candidate)
    {
        _ = Task.Run(
            async () =>
            {
                try
                {
                    var message =
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
                        };

                    await BroadcastToRoom(
                        _roomId,
                        _hostClientId,
                        message);
                }
                catch (Exception ex)
                {
                    SetStatus(
                        "Error ICE local: " +
                        ex.Message);
                }
            });
    }

    private static async Task HandleRemoteIceCandidate(
        JsonElement root)
    {
        try
        {
            if (_peerConnection == null)
                return;

            if (!root.TryGetProperty(
                    "candidate",
                    out var candidateElement))
                return;

            string candidate =
                candidateElement.GetString() ??
                "";

            if (string.IsNullOrWhiteSpace(
                    candidate))
                return;

            string sdpMid = "";

            if (root.TryGetProperty(
                    "sdpMid",
                    out var midElement))
            {
                sdpMid =
                    midElement.GetString() ??
                    "";
            }

            int sdpMLineIndex = 0;

            if (root.TryGetProperty(
                    "sdpMLineIndex",
                    out var indexElement))
            {
                sdpMLineIndex =
                    indexElement.GetInt32();
            }

            await _peerConnection.addIceCandidate(
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
            SetStatus(
                "Error ICE remoto: " +
                ex.Message);
        }
    }

    private static async Task BroadcastToRoom(
        string room,
        string sender,
        object message)
    {
        if (!Rooms.TryGetValue(
                room,
                out var sockets))
            return;

        string json =
            JsonSerializer.Serialize(message);

        byte[] data =
            Encoding.UTF8.GetBytes(json);

        foreach (var pair in sockets)
        {
            if (pair.Key == sender)
                continue;

            if (pair.Value.State !=
                WebSocketState.Open)
                continue;

            try
            {
                await pair.Value.SendAsync(
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

    // ============================================================
    // DATA CHANNEL
    // ============================================================

    private static void OnDataChannel(
        RTCDataChannel channel)
    {
        SetStatus(
            "DataChannel recibido: " +
            channel.label);

        if (!string.Equals(
                channel.label,
                "wow_controls",
                StringComparison.Ordinal))
        {
            return;
        }

        _controlDataChannel =
            channel;

        channel.onmessage +=
            (
                RTCDataChannel dc,
                DataChannelPayloadProtocols protocol,
                byte[] data
            ) =>
            {
                ProcessControlPacket(
                    data);
            };

        channel.onopen +=
            () =>
            {
                SetStatus(
                    "DataChannel wow_controls abierto.");
            };
    }

    private static void ProcessControlPacket(
        byte[] data)
    {
        try
        {
            if (data == null ||
                data.Length < 2)
                return;

            byte type =
                data[0];

            byte action =
                data[1];

            bool pressed =
                action != 0;

            if (type == 0)
            {
                if (data.Length < 3)
                    return;

                byte key =
                    data[2];

                SendKeyboard(
                    key,
                    pressed);

                return;
            }

            if (type == 1)
            {
                if (data.Length < 10)
                    return;

                float x =
                    BitConverter.ToSingle(
                        data,
                        2);

                float y =
                    BitConverter.ToSingle(
                        data,
                        6);

                SendMouse(
                    x,
                    y,
                    pressed);
            }
        }
        catch (Exception ex)
        {
            SetStatus(
                "Input error: " +
                ex.Message);
        }
    }

    // ============================================================
    // KEYBOARD
    // ============================================================

    private static void SendKeyboard(
        byte key,
        bool pressed)
    {
        if (_wowHandle ==
            IntPtr.Zero)
            return;

        uint vk =
            key switch
            {
                (byte)'W' => 0x57,
                (byte)'A' => 0x41,
                (byte)'S' => 0x53,
                (byte)'D' => 0x44,
                (byte)'M' => 0x4D,

                // Espacio.
                0x20 => 0x20,

                _ => key
            };

        PostMessage(
            _wowHandle,
            pressed
                ? WM_KEYDOWN
                : WM_KEYUP,
            new IntPtr(vk),
            IntPtr.Zero);
    }

    // ============================================================
    // MOUSE
    // ============================================================

    private static void SendMouse(
        float normalizedX,
        float normalizedY,
        bool pressed)
    {
        if (_wowHandle ==
            IntPtr.Zero)
            return;

        normalizedX =
            Math.Clamp(
                normalizedX,
                0f,
                1f);

        normalizedY =
            Math.Clamp(
                normalizedY,
                0f,
                1f);

        if (!GetClientRect(
                _wowHandle,
                out RECT rect))
            return;

        int width =
            rect.Right -
            rect.Left;

        int height =
            rect.Bottom -
            rect.Top;

        if (width <= 0 ||
            height <= 0)
            return;

        int x =
            (int)(
                normalizedX *
                (width - 1));

        int y =
            (int)(
                normalizedY *
                (height - 1));

        IntPtr lParam =
            new IntPtr(
                (y << 16) |
                (x & 0xFFFF));

        /*
         * Mantiene el comportamiento
         * del código original:
         *
         * zona inferior = botón izquierdo
         * resto = botón derecho
         */
        bool useLeftButton =
            normalizedY > 0.70f;

        uint downMessage =
            useLeftButton
                ? WM_LBUTTONDOWN
                : WM_RBUTTONDOWN;

        uint upMessage =
            useLeftButton
                ? WM_LBUTTONUP
                : WM_RBUTTONUP;

        PostMessage(
            _wowHandle,
            pressed
                ? downMessage
                : upMessage,
            IntPtr.Zero,
            lParam);
    }

    // ============================================================
    // CAPTURA DE PANTALLA
    // ============================================================

    private static async Task StartCaptureLoop()
    {
        while (
            _streaming &&
            _answerReceived)
        {
            try
            {
                if (_videoEncoder == null)
                {
                    await Task.Delay(100);
                    continue;
                }

                CaptureFrame();

                await Task.Delay(
                    33);
            }
            catch (Exception ex)
            {
                SetStatus(
                    "Error captura: " +
                    ex.Message);

                await Task.Delay(
                    250);
            }
        }
    }

    private static void CaptureFrame()
    {
        const int width = 1280;
        const int height = 720;

        using var bitmap =
            new Bitmap(
                width,
                height,
                PixelFormat.Format24bppRgb);

        using (
            Graphics graphics =
                Graphics.FromImage(bitmap))
        {
            graphics.CopyFromScreen(
                0,
                0,
                0,
                0,
                new Size(
                    width,
                    height),
                CopyPixelOperation.SourceCopy);
        }

        Rectangle rect =
            new Rectangle(
                0,
                0,
                width,
                height);

        BitmapData bitmapData =
            bitmap.LockBits(
                rect,
                ImageLockMode.ReadOnly,
                PixelFormat.Format24bppRgb);

        try
        {
            int stride =
                Math.Abs(
                    bitmapData.Stride);

            int bytes =
                stride *
                height;

            byte[] bgr =
                new byte[
                    width *
                    height *
                    3];

            for (int y = 0; y < height; y++)
            {
                IntPtr source =
                    IntPtr.Add(
                        bitmapData.Scan0,
                        y * bitmapData.Stride);

                Marshal.Copy(
                    source,
                    bgr,
                    y * width * 3,
                    width * 3);
            }

            _videoEncoder
                .ExternalVideoSourceRawSample(
                    33,
                    width,
                    height,
                    bgr,
                    VideoPixelFormatsEnum.Bgr);
        }
        finally
        {
            bitmap.UnlockBits(
                bitmapData);
        }
    }

    private static void OnEncodedVideoSample(
        uint durationRtpTimestamp,
        byte[] encodedSample)
    {
        if (_peerConnection == null)
            return;

        if (!_answerReceived)
            return;

        try
        {
            _peerConnection.SendVideo(
                durationRtpTimestamp,
                encodedSample);
        }
        catch
        {
        }
    }

    // ============================================================
    // UTILIDADES
    // ============================================================

    private static string GenerateRoomId()
    {
        return Guid.NewGuid()
            .ToString("N")
            .Substring(0, 8)
            .ToUpperInvariant();
    }

    // ============================================================
    // SHUTDOWN
    // ============================================================

    private static void Shutdown()
    {
        _streaming = false;

        try
        {
            _controlDataChannel?.close();
        }
        catch
        {
        }

        try
        {
            _peerConnection?.close();
        }
        catch
        {
        }

        try
        {
            _videoEncoder?.CloseVideo();
        }
        catch
        {
        }

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
            SignalingServer.Stop();
            SignalingServer.Close();
        }
        catch
        {
        }

        foreach (var room in Rooms)
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

        Rooms.Clear();

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
    }
}
