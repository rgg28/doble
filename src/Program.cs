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

class Program
{
    private static IntPtr _wowHandle = IntPtr.Zero;
    private static Process? _wowProcess;
    
    private static RTCPeerConnection? _peerConnection;
    private static ClientWebSocket? _signalingWebSocket;
    private static bool _isStreaming = false;

    // Componentes del panel gráfico
    private static Form? _mainForm;
    private static TextBox? _txtWowPath;
    private static TextBox? _txtConnectionId; 
    private static Button? _btnBrowse;
    private static Button? _btnStart;
    private static Label? _lblStatus;

    [DllImport("user32.dll")]
    private static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")]
    private static extern bool GetClientRect(IntPtr hWnd, out RECT lpRect);

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }

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

        _mainForm = new Form {
            Text = "WoW OBS-Cast WebRTC Server",
            Width = 520, Height = 220,
            FormBorderStyle = FormBorderStyle.FixedSingle, MaximizeBox = false,
            StartPosition = FormStartPosition.CenterScreen, BackColor = Color.FromArgb(20, 24, 30)
        };

        Label lblPath = new Label { Text = "Ruta de Wow.exe:", Left = 20, Top = 15, Width = 120, ForeColor = Color.White, Font = new Font("Segoe UI", 9, FontStyle.Bold) };
        _txtWowPath = new TextBox { Left = 20, Top = 38, Width = 360, Text = @"C:\Program Files (x86)\World of Warcraft\_retail_\Wow.exe", BackColor = Color.FromArgb(40, 44, 52), ForeColor = Color.White, BorderStyle = BorderStyle.FixedSingle };
        
        _btnBrowse = new Button { Text = "Buscar...", Left = 390, Top = 36, Width = 90, Height = 25, BackColor = Color.FromArgb(60, 65, 75), ForeColor = Color.White, FlatStyle = FlatStyle.Flat };
        _btnBrowse.Click += BtnBrowse_Click;

        Label lblId = new Label { Text = "ID de Conexión Global (Crea un código para conectar desde fuera):", Left = 20, Top = 75, Width = 450, ForeColor = Color.White, Font = new Font("Segoe UI", 9, FontStyle.Bold) };
        _txtConnectionId = new TextBox { Left = 20, Top = 98, Width = 200, Text = "WowSala777", BackColor = Color.FromArgb(40, 44, 52), ForeColor = Color.Cyan, BorderStyle = BorderStyle.FixedSingle, Font = new Font("Segoe UI", 10, FontStyle.Bold) };

        _btnStart = new Button { Text = "INICIAR OBS-CAST", Left = 20, Top = 135, Width = 200, Height = 35, BackColor = Color.FromArgb(75, 100, 205), ForeColor = Color.White, FlatStyle = FlatStyle.Flat, Font = new Font("Segoe UI", 10, FontStyle.Bold) };
        _btnStart.Click += BtnStart_Click;

        _lblStatus = new Label { Text = "Estado: Desconectado.", Left = 230, Top = 145, Width = 250, ForeColor = Color.Gray, Font = new Font("Segoe UI", 9, FontStyle.Italic) };

        _mainForm.Controls.Add(lblPath); _mainForm.Controls.Add(_txtWowPath); _mainForm.Controls.Add(_btnBrowse);
        _mainForm.Controls.Add(lblId); _mainForm.Controls.Add(_txtConnectionId); _mainForm.Controls.Add(_btnStart); _mainForm.Controls.Add(_lblStatus);
        
        Application.Run(_mainForm);
    }

    private static void BtnBrowse_Click(object? sender, EventArgs e)
    {
        using (OpenFileDialog ofd = new OpenFileDialog()) {
            ofd.Filter = "Ejecutable de WoW (*.exe)|*.exe";
            if (ofd.ShowDialog() == DialogResult.OK) _txtWowPath!.Text = ofd.FileName;
        }
    }

    private static async void BtnStart_Click(object? sender, EventArgs e)
    {
        string wowPath = _txtWowPath?.Text ?? "";
        string connectionId = _txtConnectionId?.Text ?? "";

        if (!File.Exists(wowPath) || string.IsNullOrEmpty(connectionId)) return;

        _btnStart!.Enabled = false; _btnBrowse!.Enabled = false; _txtWowPath!.Enabled = false; _txtConnectionId!.Enabled = false;
        _lblStatus!.Text = "Abriendo WoW y enlazando nube..."; _lblStatus.ForeColor = Color.Orange;

        try
        {
            ProcessStartInfo startInfo = new ProcessStartInfo { FileName = wowPath, Arguments = "-windowed", UseShellExecute = true };
            _wowProcess = Process.Start(startInfo);
            
            if (_wowProcess != null)
            {
                await Task.Delay(3000);
                _wowProcess.Refresh();
                _wowHandle = _wowProcess.MainWindowHandle;
            }

            _ = Task.Run(() => SetupWebRtcConnection(connectionId));
        }
        catch (Exception ex) { MessageBox.Show(ex.Message); ResetUI(); }
    }

    private static async Task SetupWebRtcConnection(string roomId)
    {
        try
        {
            string signalingUrl = "wss://://piesocket.com";
            
            _signalingWebSocket = new ClientWebSocket();
            await _signalingWebSocket.ConnectAsync(new Uri(signalingUrl), CancellationToken.None);

            var config = new RTCConfiguration {
                iceServers = new System.Collections.Generic.List<RTCIceServer> {
                    new RTCIceServer { urls = "stun:://google.com" }
                }
            };

            _peerConnection = new RTCPeerConnection(config);

            // CORRECCIÓN COMPLETA: Inicialización de pistas usando las enumeraciones nativas de SIPSorcery (SDPMediaTypesEnum)
            var videoTrack = new MediaStreamTrack(SDPMediaTypesEnum.video, false, new System.Collections.Generic.List<SDPAudioVideoMediaFormat> { new SDPAudioVideoMediaFormat(new VideoFormat(VideoCodecsEnum.VP8, 96)) }, MediaStreamStatusEnum.SendOnly);
            _peerConnection.addTrack(videoTrack);

            var dataChannel = await _peerConnection.createDataChannel("wow_controls");
            dataChannel.onmessage += (hc, type, data) => HandleIncomingWebRtcControls(data);

            var offer = _peerConnection.createOffer();
            await _peerConnection.setLocalDescription(offer);

            string base64Sdp = Convert.ToBase64String(Encoding.UTF8.GetBytes(offer.sdp));
            string offerJson = "{\"room\":\"" + roomId + "\", \"type\":\"offer\", \"sdp\":\"" + base64Sdp + "\"}";
            byte[] offerBytes = Encoding.UTF8.GetBytes(offerJson);
            await _signalingWebSocket.SendAsync(new ArraySegment<byte>(offerBytes), WebSocketMessageType.Text, true, CancellationToken.None);

            _mainForm?.Invoke((MethodInvoker)delegate {
                _lblStatus!.Text = "¡Emitiendo! Coloca el ID en tu app móvil.";
                _lblStatus.ForeColor = Color.LightGreen;
            });

            _isStreaming = true;
            _ = Task.Run(VideoStreamingLoop);
            _ = Task.Run(ListenForSignalingMessages);
        }
        catch (Exception ex)
        {
            MessageBox.Show("Fallo en WebRTC: " + ex.Message);
            _mainForm?.Invoke((MethodInvoker)delegate { ResetUI(); });
        }
    }

    private static async Task VideoStreamingLoop()
    {
        while (_isStreaming && _wowProcess != null && !_wowProcess.HasExited)
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
                        bmp.Save(ms, ImageFormat.Bmp);
                        byte[] rawBmpBytes = ms.ToArray();
                        
                        // CORRECCIÓN COMPLETA: Envío de video nativo a través de la tubería del peer connection sin pasar por formatos abstractos
                        _peerConnection?.SendVideo(1280, 720, rawBmpBytes, VideoPixelFormatsEnum.Bgr, VideoCodecsEnum.VP8);
                    }
                }
                await Task.Delay(33); 
            }
            catch { break; }
        }
    }

    private static async Task ListenForSignalingMessages()
    {
        byte[] buffer = new byte[2048];
        while (_signalingWebSocket?.State == WebSocketState.Open)
        {
            try
            {
                var result = await _signalingWebSocket.ReceiveAsync(new ArraySegment<byte>(buffer), CancellationToken.None);
                if (result.MessageType == WebSocketMessageType.Close) break;

                string message = Encoding.UTF8.GetString(buffer, 0, result.Count);
                if (message.Contains("\"type\":\"answer\""))
                {
                    string base64Sdp = ExtractJsonValue(message, "sdp");
                    string sdpStr = Encoding.UTF8.GetString(Convert.FromBase64String(base64Sdp));
                    
                    // CORRECCIÓN COMPLETA: Parseo nativo de la sesión SDP remota
                    _peerConnection?.setRemoteDescription(new RTCSessionDescription { type = RTCSessionDescriptionTypesEnum.answer, sdp = SDP.ParseSDPString(sdpStr) });
                }
            }
            catch { break; }
        }
    }

    private static void HandleIncomingWebRtcControls(byte[] data)
    {
if (data.Length < 3 || _wowHandle == IntPtr.Zero) return;
byte type = data[0];
if (type == 0)
{
byte action = data[1];
byte keyChar = data[2];
uint msg = (action == 1) ? WM_KEYDOWN : WM_KEYUP;
PostMessage(_wowHandle, msg, (IntPtr)keyChar, IntPtr.Zero);
}
else if (type == 1 && data.Length >= 10)
{
byte mouseAction = data[1];
float pctX = BitConverter.ToSingle(data, 2);
float pctY = BitConverter.ToSingle(data, 6);
if (GetClientRect(_wowHandle, out RECT rect))
{
int width = rect.Right - rect.Left;
int height = rect.Bottom - rect.Top;
IntPtr lParam = (IntPtr)(((int)(pctY * height) << 16) | ((int)(pctX * width) & 0xFFFF));
uint mouseMsg = (pctY > 0.70f) ?
((mouseAction == 1) ? WM_LBUTTONDOWN : WM_LBUTTONUP) :
((mouseAction == 1) ? WM_RBUTTONDOWN : WM_RBUTTONUP);
PostMessage(_wowHandle, mouseMsg, IntPtr.Zero, lParam);
}
}
}
private static string ExtractJsonValue(string json, string key)
{
string search = """ + key + "":"";
int start = json.IndexOf(search);
if (start == -1) return "";
start += search.Length;
int end = json.IndexOf(""", start);
if (end == -1) return "";
return json.Substring(start, end - start);
}
private static void ResetUI()
{
if (_btnStart != null && _btnStart.IsHandleCreated)
{
_btnStart.Invoke((MethodInvoker)(() => {
_btnStart.Enabled = true;
_btnBrowse!.Enabled = true;
_txtWowPath!.Enabled = true;
_txtConnectionId!.Enabled = true;
_lblStatus!.Text = "Estado: Desconectado.";
_lblStatus.ForeColor = Color.Gray;
}));
}
}
}
