package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/driver/desktop"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"

	"github.com/esrrhs/gohome/common"
	"github.com/esrrhs/gohome/loggo"
	"github.com/esrrhs/gohome/thirdparty"
	"github.com/esrrhs/pingtunnel"
)

func main() {
	defer common.CrashLog()

	a := app.NewWithID("com.esrrhs.pingtunnel-qt")
	w := a.NewWindow("pingtunnel-qt v" + Version)

	serverw := widget.NewEntry()
	serverw.SetText("www.yourserver.com")
	portw := widget.NewEntry()
	portw.SetText("4455")
	targetw := widget.NewEntry()
	targetw.SetText("www.yourserver.com:4455")
	timeoutw := widget.NewEntry()
	timeoutw.SetText("60")
	keyw := widget.NewEntry()
	keyw.SetText("0")
	tcpbsw := widget.NewEntry()
	tcpbsw.SetText("1048576")
	tcpmww := widget.NewEntry()
	tcpmww.SetText("10000")
	tcprstw := widget.NewEntry()
	tcprstw.SetText("400")
	tcpgzw := widget.NewEntry()
	tcpgzw.SetText("0")
	loglevelw := widget.NewEntry()
	loglevelw.SetText("info")
	maxconnw := widget.NewEntry()
	maxconnw.SetText("1000")
	s5filterw := widget.NewEntry()
	s5filterw.SetText("CN")
	s5ftfilew := widget.NewEntry()
	s5ftfilew.SetText("GeoLite2-Country.mmdb")

	var check func()
	tcpw := widget.NewCheck("", func(bool) {
		if check != nil {
			check()
		}
	})
	tcpstatw := widget.NewCheck("", nil)
	nologw := widget.NewCheck("", nil)
	sock5w := widget.NewCheck("", func(bool) {
		if check != nil {
			check()
		}
	})

	pingLabel := widget.NewLabel("stop")
	statLabel := widget.NewLabel(statText(0, 0, 0, 0, 0, 0))
	statLabel.Wrapping = fyne.TextWrapWord

	entries := []*widget.Entry{
		serverw, portw, targetw, timeoutw, keyw,
		tcpbsw, tcpmww, tcprstw, tcpgzw, loglevelw,
		maxconnw, s5filterw, s5ftfilew,
	}
	checks := []*widget.Check{tcpw, tcpstatw, nologw, sock5w}

	checking := false
	check = func() {
		if checking {
			return
		}
		checking = true
		defer func() { checking = false }()
		if sock5w.Checked {
			tcpw.SetChecked(true)
			targetw.Disable()
			s5filterw.Enable()
			s5ftfilew.Enable()
		} else {
			targetw.Enable()
			s5filterw.Disable()
			s5ftfilew.Disable()
		}
		if tcpw.Checked {
			tcpbsw.Enable()
			tcpmww.Enable()
			tcprstw.Enable()
			tcpgzw.Enable()
			tcpstatw.Enable()
		} else {
			sock5w.SetChecked(false)
			tcpbsw.Disable()
			tcpmww.Disable()
			tcprstw.Disable()
			tcpgzw.Disable()
			tcpstatw.Disable()
		}
	}
	tcpw.SetChecked(true)

	setIdle := func() {
		for _, e := range entries {
			e.Enable()
		}
		for _, c := range checks {
			c.Enable()
		}
		check()
	}
	setBusy := func() {
		for _, e := range entries {
			e.Disable()
		}
		for _, c := range checks {
			c.Disable()
		}
	}

	var gclient *pingtunnel.Client
	var stopStat chan struct{}
	var goButton *widget.Button

	notify := func(msg string) {
		a.SendNotification(fyne.NewNotification("pingtunnel-qt", msg))
	}

	stopClient := func() {
		if stopStat != nil {
			close(stopStat)
			stopStat = nil
		}
		if gclient != nil {
			gclient.Stop()
			gclient = nil
		}
		setIdle()
		goButton.SetText("GO")
		pingLabel.SetText("stop")
		statLabel.SetText(statText(0, 0, 0, 0, 0, 0))
	}

	goButton = widget.NewButton("GO", func() {
		if gclient != nil {
			stopClient()
			notify("stop ok")
			return
		}

		port, err := strconv.Atoi(portw.Text)
		if err != nil {
			dialog.ShowInformation("提示", "listen port "+err.Error(), w)
			return
		}
		timeout, err := strconv.Atoi(timeoutw.Text)
		if err != nil {
			dialog.ShowInformation("提示", "timeout "+err.Error(), w)
			return
		}
		key, err := strconv.Atoi(keyw.Text)
		if err != nil {
			dialog.ShowInformation("提示", "key "+err.Error(), w)
			return
		}
		maxconn, err := strconv.Atoi(maxconnw.Text)
		if err != nil {
			dialog.ShowInformation("提示", "max connections "+err.Error(), w)
			return
		}

		tcpmode := 0
		if tcpw.Checked {
			tcpmode = 1
		}
		tcpmodeBuffersize := 0
		tcpmodeMaxwin := 0
		tcpmodeResend := 0
		tcpmodeCompress := 0
		tcpmodeStat := 0
		if tcpmode != 0 {
			tcpmodeBuffersize, err = strconv.Atoi(tcpbsw.Text)
			if err != nil {
				dialog.ShowInformation("提示", "tcp buffer size "+err.Error(), w)
				return
			}
			tcpmodeMaxwin, err = strconv.Atoi(tcpmww.Text)
			if err != nil {
				dialog.ShowInformation("提示", "tcp max win "+err.Error(), w)
				return
			}
			tcpmodeResend, err = strconv.Atoi(tcprstw.Text)
			if err != nil {
				dialog.ShowInformation("提示", "tcp resend time "+err.Error(), w)
				return
			}
			tcpmodeCompress, err = strconv.Atoi(tcpgzw.Text)
			if err != nil {
				dialog.ShowInformation("提示", "tcp compress "+err.Error(), w)
				return
			}
			if tcpstatw.Checked {
				tcpmodeStat = 1
			}
		}

		nolog := 0
		if nologw.Checked {
			nolog = 1
		}
		level := loggo.LEVEL_INFO
		if lv := loggo.NameToLevel(loglevelw.Text); lv >= 0 {
			level = lv
		}
		loggo.Ini(loggo.Config{
			Level:     level,
			Prefix:    "pingtunnel",
			MaxDay:    3,
			NoLogFile: nolog > 0,
		})

		openSock5 := 0
		if sock5w.Checked {
			openSock5 = 1
		}

		var filter func(addr string) bool
		if openSock5 != 0 {
			s5filter := s5filterw.Text
			s5ftfile := s5ftfilew.Text
			if len(s5filter) > 0 {
				if err := thirdparty.LoadGeoip2(s5ftfile); err != nil {
					dialog.ShowInformation("提示", "sock5 filter file "+err.Error(), w)
					return
				}
			}
			filter = func(addr string) bool {
				if len(s5filter) <= 0 {
					return true
				}
				taddr, err := net.ResolveTCPAddr("tcp", addr)
				if err != nil {
					return false
				}
				ret, err := thirdparty.GetGeoipCountryIsoCode(taddr.IP.String())
				if err != nil || len(ret) <= 0 {
					return false
				}
				return ret != s5filter
			}
		}

		listen := ":" + strconv.Itoa(port)
		c, err := pingtunnel.NewClient(listen, serverw.Text, targetw.Text, timeout, key, "0.0.0.0",
			tcpmode, tcpmodeBuffersize, tcpmodeMaxwin, tcpmodeResend, tcpmodeCompress,
			tcpmodeStat, openSock5, maxconn, &filter, nil)
		if err != nil {
			loggo.Error("ERROR: %s", err.Error())
			dialog.ShowInformation("提示", "NewClient "+err.Error(), w)
			return
		}

		cfg := Config{
			Serverw:   serverw.Text,
			Portw:     portw.Text,
			Targetw:   targetw.Text,
			Timeoutw:  timeoutw.Text,
			Keyw:      keyw.Text,
			Tcpbsw:    tcpbsw.Text,
			Tcpmww:    tcpmww.Text,
			Tcprstw:   tcprstw.Text,
			Tcpgzw:    tcpgzw.Text,
			Loglevelw: loglevelw.Text,
			Maxconnw:  maxconnw.Text,
			S5filterw: s5filterw.Text,
			S5ftfilew: s5ftfilew.Text,
		}
		if tcpw.Checked {
			cfg.Tcpw = "true"
		}
		if tcpstatw.Checked {
			cfg.Tcpstatw = "true"
		}
		if nologw.Checked {
			cfg.Nologw = "true"
		}
		if sock5w.Checked {
			cfg.Sock5w = "true"
		}
		saveJson(cfg)

		loggo.Info("Client Listen %s (%s) Server %s (%s) TargetPort %s:", c.Addr(), c.IPAddr(),
			c.ServerAddr(), c.ServerIPAddr(), c.TargetAddr())

		if err := c.Run(); err != nil {
			notify("start fail " + err.Error())
			return
		}
		notify("start ok")

		setBusy()
		goButton.SetText("STOP")
		gclient = c
		stopStat = make(chan struct{})
		go func(c *pingtunnel.Client, stop <-chan struct{}) {
			t := time.NewTicker(time.Second)
			defer t.Stop()
			for {
				select {
				case <-stop:
					return
				case <-t.C:
					fyne.Do(func() {
						if c.RTT() != 0 {
							pingLabel.SetText(c.RTT().String())
						} else {
							pingLabel.SetText("ping fail")
						}
						statLabel.SetText(statText(
							c.SendPacket(), c.SendPacketSize()/1024,
							c.RecvPacket(), c.RecvPacketSize()/1024,
							c.LocalIdToConnMapSize(), c.LocalAddrToConnMapSize()))
					})
				}
			}
		}(c, stopStat)
	})

	exitButton := widget.NewButton("EXIT", func() {
		if gclient != nil {
			stopClient()
		}
		a.Quit()
	})

	form := container.New(&colGrid{cols: 2},
		widget.NewLabel("server："), serverw,
		widget.NewLabel("listen port："), portw,
		widget.NewLabel("target addr："), targetw,
		widget.NewLabel("timeout："), timeoutw,
		widget.NewLabel("key："), keyw,
		widget.NewLabel("tcp mode："), tcpw,
		widget.NewLabel("tcp buffer size："), tcpbsw,
		widget.NewLabel("tcp max win："), tcpmww,
		widget.NewLabel("tcp resend time："), tcprstw,
		widget.NewLabel("tcp compress："), tcpgzw,
		widget.NewLabel("tcp statistic："), tcpstatw,
		widget.NewLabel("no log："), nologw,
		widget.NewLabel("log level："), loglevelw,
		widget.NewLabel("sock5："), sock5w,
		widget.NewLabel("max conn："), maxconnw,
		widget.NewLabel("except country："), s5filterw,
		widget.NewLabel("ip2country file："), s5ftfilew,
		pingLabel, goButton,
	)
	content := container.NewVBox(form, statLabel, exitButton)

	if lg := loadJson(); lg != nil {
		if lg.Serverw != "" {
			serverw.SetText(lg.Serverw)
		}
		if lg.Portw != "" {
			portw.SetText(lg.Portw)
		}
		if lg.Targetw != "" {
			targetw.SetText(lg.Targetw)
		}
		if lg.Timeoutw != "" {
			timeoutw.SetText(lg.Timeoutw)
		}
		if lg.Keyw != "" {
			keyw.SetText(lg.Keyw)
		}
		tcpw.SetChecked(lg.Tcpw == "true")
		if lg.Tcpbsw != "" {
			tcpbsw.SetText(lg.Tcpbsw)
		}
		if lg.Tcpmww != "" {
			tcpmww.SetText(lg.Tcpmww)
		}
		if lg.Tcprstw != "" {
			tcprstw.SetText(lg.Tcprstw)
		}
		if lg.Tcpgzw != "" {
			tcpgzw.SetText(lg.Tcpgzw)
		}
		tcpstatw.SetChecked(lg.Tcpstatw == "true")
		nologw.SetChecked(lg.Nologw == "true")
		if lg.Loglevelw != "" {
			loglevelw.SetText(lg.Loglevelw)
		}
		sock5w.SetChecked(lg.Sock5w == "true")
		if lg.Maxconnw != "" {
			maxconnw.SetText(lg.Maxconnw)
		}
		if lg.S5filterw != "" {
			s5filterw.SetText(lg.S5filterw)
		}
		if lg.S5ftfilew != "" {
			s5ftfilew.SetText(lg.S5ftfilew)
		}
	}
	check()

	if desk, ok := a.(desktop.App); ok {
		desk.SetSystemTrayIcon(theme.ComputerIcon())
		show := fyne.NewMenuItem("Show", func() { w.Show() })
		desk.SetSystemTrayMenu(fyne.NewMenu("pingtunnel-qt", show))
		desk.SetSystemTrayWindow(w)
	}
	w.SetCloseIntercept(func() { w.Hide() })

	w.SetPadded(true)
	w.SetContent(content)
	w.Resize(fyne.NewSize(520, content.MinSize().Height+theme.Padding()*2))
	w.SetFixedSize(true)
	w.ShowAndRun()

	if gclient != nil {
		stopClient()
	}
}

func statText(sendPkt, sendKB, recvPkt, recvKB uint64, idConn, addrConn int) string {
	return fmt.Sprintf("send %dPacket/s %dKB/s recv %dPacket/s %dKB/s %d/%dConnections",
		sendPkt, sendKB, recvPkt, recvKB, idConn, addrConn)
}

// colGrid 两列对齐：左列按最宽标签定宽，右列拉伸，同一行等高。
type colGrid struct {
	cols int
}

func (g *colGrid) minColRow(objects []fyne.CanvasObject) (colW []float32, rowH []float32) {
	cols := g.cols
	if cols < 1 {
		cols = 2
	}
	rows := (len(objects) + cols - 1) / cols
	colW = make([]float32, cols)
	rowH = make([]float32, rows)
	for i, o := range objects {
		if o == nil || !o.Visible() {
			continue
		}
		m := o.MinSize()
		c := i % cols
		r := i / cols
		if m.Width > colW[c] {
			colW[c] = m.Width
		}
		if m.Height > rowH[r] {
			rowH[r] = m.Height
		}
	}
	return
}

func (g *colGrid) MinSize(objects []fyne.CanvasObject) fyne.Size {
	colW, rowH := g.minColRow(objects)
	pad := theme.Padding()
	var w, h float32
	for i, cw := range colW {
		w += cw
		if i > 0 {
			w += pad
		}
	}
	for i, rh := range rowH {
		h += rh
		if i > 0 {
			h += pad
		}
	}
	return fyne.NewSize(w, h)
}

func (g *colGrid) Layout(objects []fyne.CanvasObject, size fyne.Size) {
	cols := g.cols
	if cols < 1 {
		cols = 2
	}
	colW, rowH := g.minColRow(objects)
	pad := theme.Padding()
	var minW float32
	for i, cw := range colW {
		minW += cw
		if i > 0 {
			minW += pad
		}
	}
	if extra := size.Width - minW; extra > 0 && len(colW) >= 2 {
		colW[1] += extra
	}
	y := float32(0)
	for r, rh := range rowH {
		x := float32(0)
		for c := 0; c < cols; c++ {
			i := r*cols + c
			if i >= len(objects) {
				break
			}
			if o := objects[i]; o != nil {
				o.Move(fyne.NewPos(x, y))
				o.Resize(fyne.NewSize(colW[c], rh))
			}
			x += colW[c] + pad
		}
		y += rh + pad
	}
}

type Config struct {
	Serverw   string `json:"serverw"`
	Portw     string `json:"portw"`
	Targetw   string `json:"targetw"`
	Timeoutw  string `json:"timeoutw"`
	Keyw      string `json:"keyw"`
	Tcpw      string `json:"tcpw"`
	Tcpbsw    string `json:"tcpbsw"`
	Tcpmww    string `json:"tcpmww"`
	Tcprstw   string `json:"tcprstw"`
	Tcpgzw    string `json:"tcpgzw"`
	Tcpstatw  string `json:"tcpstatw"`
	Nologw    string `json:"nologw"`
	Loglevelw string `json:"loglevelw"`
	Sock5w    string `json:"sock5w"`
	Maxconnw  string `json:"maxconnw"`
	S5filterw string `json:"s5filterw"`
	S5ftfilew string `json:"s5ftfilew"`
}

func configPath() string {
	exe, err := os.Executable()
	if err != nil {
		return ".pingtunnel-qt.json"
	}
	return filepath.Join(filepath.Dir(exe), ".pingtunnel-qt.json")
}

func saveJson(c Config) {
	f, err := os.OpenFile(configPath(), os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0666)
	if err != nil {
		return
	}
	defer f.Close()
	if err := json.NewEncoder(f).Encode(&c); err != nil {
		loggo.Error("saveJson fail %s", err)
	}
}

func loadJson() *Config {
	f, err := os.Open(configPath())
	if err != nil {
		return nil
	}
	defer f.Close()
	data, err := io.ReadAll(f)
	if err != nil {
		return nil
	}
	var c Config
	if err := json.Unmarshal(data, &c); err != nil {
		return nil
	}
	return &c
}
