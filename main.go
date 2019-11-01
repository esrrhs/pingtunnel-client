package main

import (
	"encoding/json"
	"fmt"
	"github.com/esrrhs/go-engine/src/loggo"
	"github.com/esrrhs/go-engine/src/pingtunnel"
	"github.com/therecipe/qt/core"
	"github.com/therecipe/qt/widgets"
	"io/ioutil"
	"os"
	"strconv"
)

func main() {
	app := widgets.NewQApplication(len(os.Args), os.Args)

	var window = widgets.NewQMainWindow(nil, 0)
	var centralWidget = widgets.NewQWidget(window, 0)

	echoGroup := widgets.NewQGroupBox2("", nil)

	serverLabel := widgets.NewQLabel2("server：", nil, 0)
	serverw := widgets.NewQLineEdit(nil)
	serverw.SetText("www.yourserver.com")

	portLabel := widgets.NewQLabel2("listen port：", nil, 0)
	portw := widgets.NewQLineEdit(nil)
	portw.SetText("4455")

	targetLabel := widgets.NewQLabel2("target addr：", nil, 0)
	targetw := widgets.NewQLineEdit(nil)
	targetw.SetText("www.yourserver.com:4455")

	timeoutLabel := widgets.NewQLabel2("timeout：", nil, 0)
	timeoutw := widgets.NewQLineEdit(nil)
	timeoutw.SetText("60")

	keyLabel := widgets.NewQLabel2("key：", nil, 0)
	keyw := widgets.NewQLineEdit(nil)
	keyw.SetText("0")

	tcpLabel := widgets.NewQLabel2("tcp mode：", nil, 0)
	tcpw := widgets.NewQCheckBox(nil)
	tcpw.SetChecked(true)

	tcpbsLabel := widgets.NewQLabel2("tcp buffer size：", nil, 0)
	tcpbsw := widgets.NewQLineEdit(nil)
	tcpbsw.SetText("10485760")

	tcpmwLabel := widgets.NewQLabel2("tcp max win：", nil, 0)
	tcpmww := widgets.NewQLineEdit(nil)
	tcpmww.SetText("10000")

	tcprstLabel := widgets.NewQLabel2("tcp resend time：", nil, 0)
	tcprstw := widgets.NewQLineEdit(nil)
	tcprstw.SetText("400")

	tcpgzLabel := widgets.NewQLabel2("tcp compress：", nil, 0)
	tcpgzw := widgets.NewQLineEdit(nil)
	tcpgzw.SetText("0")

	tcpstatLabel := widgets.NewQLabel2("tcp statistic：", nil, 0)
	tcpstatw := widgets.NewQCheckBox(nil)
	tcpstatw.SetChecked(false)

	nologLabel := widgets.NewQLabel2("no log：", nil, 0)
	nologw := widgets.NewQCheckBox(nil)
	nologw.SetChecked(false)

	loglevelLabel := widgets.NewQLabel2("log level：", nil, 0)
	loglevelw := widgets.NewQLineEdit(nil)
	loglevelw.SetText("info")

	sock5Label := widgets.NewQLabel2("sock5：", nil, 0)
	sock5w := widgets.NewQCheckBox(nil)
	sock5w.SetChecked(false)

	maxconnLabel := widgets.NewQLabel2("max conn：", nil, 0)
	maxconnw := widgets.NewQLineEdit(nil)
	maxconnw.SetText("1000")

	pingLabel := widgets.NewQLabel2("stop", nil, 0)
	fuckButton := widgets.NewQPushButton2("GO", nil)

	str := fmt.Sprintf("send %dPacket/s %dKB/s recv %dPacket/s %dKB/s",
		0, 0, 0, 0)
	statLabel := widgets.NewQLabel2(str, nil, 0)
	exitButton := widgets.NewQPushButton2("EXIT", nil)
	exitButton.ConnectClicked(func(checked bool) {
		app.Exit(0)
	})

	//systray
	sys := widgets.NewQSystemTrayIcon(nil)
	sys.SetIcon(window.Style().StandardIcon(widgets.QStyle__SP_MessageBoxCritical, nil, nil))
	sys.ConnectActivated(func(reason widgets.QSystemTrayIcon__ActivationReason) {
		if reason == widgets.QSystemTrayIcon__Trigger {
			window.Show()
		}
	})
	menu := widgets.NewQMenu(nil)
	exit := menu.AddAction("Exit")
	exit.ConnectTriggered(func(bool) { app.Exit(0) })
	sys.SetContextMenu(menu)

	check := func() {

		if sock5w.IsChecked() {
			tcpw.SetChecked(true)
		}

		if tcpw.IsChecked() {

			tcpbsw.SetEnabled(true)

			tcpmww.SetEnabled(true)

			tcprstw.SetEnabled(true)

			tcpgzw.SetEnabled(true)

			tcpstatw.SetEnabled(true)

		} else {
			sock5w.SetChecked(false)

			tcpbsw.SetEnabled(false)

			tcpmww.SetEnabled(false)

			tcprstw.SetEnabled(false)

			tcpgzw.SetEnabled(false)

			tcpstatw.SetEnabled(false)

		}
	}

	tcpw.ConnectClicked(func(checked bool) {
		check()
	})

	sock5w.ConnectClicked(func(checked bool) {
		check()
	})

	var gclient *pingtunnel.Client
	var gtimer *core.QTimer

	fuckButton.ConnectClicked(func(checked bool) {

		if gclient != nil {
			gclient.Stop()
			gtimer.Stop()
			gclient = nil

			serverw.SetEnabled(true)

			portw.SetEnabled(true)

			targetw.SetEnabled(true)

			timeoutw.SetEnabled(true)

			keyw.SetEnabled(true)

			tcpw.SetEnabled(true)

			tcpbsw.SetEnabled(true)

			tcpmww.SetEnabled(true)

			tcprstw.SetEnabled(true)

			tcpgzw.SetEnabled(true)

			tcpstatw.SetEnabled(true)

			nologw.SetEnabled(true)

			loglevelw.SetEnabled(true)

			sock5w.SetEnabled(true)

			maxconnw.SetEnabled(true)

			fuckButton.SetText("GO")

			pingLabel.SetText("stop")
			str := fmt.Sprintf("send %dPacket/s %dKB/s recv %dPacket/s %dKB/s",
				0, 0, 0, 0)
			statLabel.SetText(str)

			sys.ShowMessage("pingtunnel-qt", "stop ok", widgets.QSystemTrayIcon__Information, 5000)

			return
		}

		a := widgets.NewQMessageBox(nil)

		port, err := strconv.Atoi(portw.Text())
		if err != nil {
			a.SetText("listen port " + err.Error())
			a.Show()
			return
		}
		listen := ":" + strconv.Itoa(port)

		target := targetw.Text()
		server := serverw.Text()
		timeout, err := strconv.Atoi(timeoutw.Text())
		if err != nil {
			a.SetText("timeout " + err.Error())
			a.Show()
			return
		}
		key, err := strconv.Atoi(keyw.Text())
		if err != nil {
			a.SetText("key " + err.Error())
			a.Show()
			return
		}
		tcpmode := 0
		if tcpw.IsChecked() {
			tcpmode = 1
		}
		tcpmode_buffersize, err := strconv.Atoi(tcpbsw.Text())
		if err != nil {
			a.SetText("tcp buffer size " + err.Error())
			a.Show()
			return
		}
		tcpmode_maxwin, err := strconv.Atoi(tcpmww.Text())
		if err != nil {
			a.SetText("tcp max win " + err.Error())
			a.Show()
			return
		}
		tcpmode_resend_timems, err := strconv.Atoi(tcprstw.Text())
		if err != nil {
			a.SetText("tcp resend time " + err.Error())
			a.Show()
			return
		}
		tcpmode_compress, err := strconv.Atoi(tcpgzw.Text())
		if err != nil {
			a.SetText("tcp compress " + err.Error())
			a.Show()
			return
		}
		tcpmode_stat := 0
		if tcpstatw.IsChecked() {
			tcpmode_stat = 1
		}
		nolog := 0
		if nologw.IsChecked() {
			nolog = 1
		}

		loglevel := loglevelw.Text()

		level := loggo.LEVEL_INFO
		if loggo.NameToLevel(loglevel) >= 0 {
			level = loggo.NameToLevel(loglevel)
		}
		loggo.Ini(loggo.Config{
			Level:     level,
			Prefix:    "pingtunnel",
			MaxDay:    3,
			NoLogFile: nolog > 0,
		})

		open_sock5 := 0
		if sock5w.IsChecked() {
			open_sock5 = 1
		}

		maxconn, err := strconv.Atoi(maxconnw.Text())
		if err != nil {
			a.SetText("max connections " + err.Error())
			a.Show()
			return
		}

		if tcpmode == 0 {
			tcpmode_buffersize = 0
			tcpmode_maxwin = 0
			tcpmode_resend_timems = 0
			tcpmode_compress = 0
			tcpmode_stat = 0
		}

		c, err := pingtunnel.NewClient(listen, server, target, timeout, key,
			tcpmode, tcpmode_buffersize, tcpmode_maxwin, tcpmode_resend_timems, tcpmode_compress,
			tcpmode_stat, open_sock5, maxconn)
		if err != nil {
			loggo.Error("ERROR: %s", err.Error())
			a.SetText("NewClient " + err.Error())
			a.Show()
			return
		}

		gConfig := Config{}
		gConfig.Serverw = serverw.Text()

		gConfig.Portw = portw.Text()

		gConfig.Targetw = targetw.Text()

		gConfig.Timeoutw = timeoutw.Text()

		gConfig.Keyw = keyw.Text()

		if tcpw.IsChecked() {
			gConfig.Tcpw = "true"
		}

		gConfig.Tcpbsw = tcpbsw.Text()

		gConfig.Tcpmww = tcpmww.Text()

		gConfig.Tcprstw = tcprstw.Text()

		gConfig.Tcpgzw = tcpgzw.Text()

		if tcpstatw.IsChecked() {
			gConfig.Tcpstatw = "true"
		}

		if nologw.IsChecked() {
			gConfig.Nologw = "true"
		}

		gConfig.Loglevelw = loglevelw.Text()

		if sock5w.IsChecked() {
			gConfig.Sock5w = "true"
		}

		gConfig.Maxconnw = maxconnw.Text()

		saveJson(gConfig)

		loggo.Info("Client Listen %s (%s) Server %s (%s) TargetPort %s:", c.Addr(), c.IPAddr(),
			c.ServerAddr(), c.ServerIPAddr(), c.TargetAddr())

		err = c.Run()
		if err == nil {
			sys.ShowMessage("pingtunnel-qt", "start ok", widgets.QSystemTrayIcon__Information, 5000)
		} else {
			sys.ShowMessage("pingtunnel-qt", "start fail "+err.Error(), widgets.QSystemTrayIcon__Information, 5000)
			return
		}

		serverw.SetEnabled(false)

		portw.SetEnabled(false)

		targetw.SetEnabled(false)

		timeoutw.SetEnabled(false)

		keyw.SetEnabled(false)

		tcpw.SetEnabled(false)

		tcpbsw.SetEnabled(false)

		tcpmww.SetEnabled(false)

		tcprstw.SetEnabled(false)

		tcpgzw.SetEnabled(false)

		tcpstatw.SetEnabled(false)

		nologw.SetEnabled(false)

		loglevelw.SetEnabled(false)

		sock5w.SetEnabled(false)

		maxconnw.SetEnabled(false)

		fuckButton.SetText("STOP")

		gclient = c

		t := core.NewQTimer(nil)
		t.ConnectEvent(func(e *core.QEvent) bool {
			if c.RTT() != 0 {
				pingLabel.SetText(c.RTT().String())
			} else {
				pingLabel.SetText("ping fail")
			}
			str := fmt.Sprintf("send %dPacket/s %dKB/s recv %dPacket/s %dKB/s",
				c.SendPacket(), c.SendPacketSize()/1024, c.RecvPacket(), c.RecvPacketSize()/1024)
			statLabel.SetText(str)
			return true
		})
		t.Start(1000)
		gtimer = t
	})

	var echoLayout = widgets.NewQGridLayout2()
	echoLayout.AddWidget2(serverLabel, 0, 0, 0)
	echoLayout.AddWidget2(serverw, 0, 1, 0)
	echoLayout.AddWidget2(portLabel, 1, 0, 0)
	echoLayout.AddWidget2(portw, 1, 1, 0)
	echoLayout.AddWidget2(targetLabel, 2, 0, 0)
	echoLayout.AddWidget2(targetw, 2, 1, 0)
	echoLayout.AddWidget2(timeoutLabel, 3, 0, 0)
	echoLayout.AddWidget2(timeoutw, 3, 1, 0)
	echoLayout.AddWidget2(keyLabel, 4, 0, 0)
	echoLayout.AddWidget2(keyw, 4, 1, 0)
	echoLayout.AddWidget2(tcpLabel, 5, 0, 0)
	echoLayout.AddWidget2(tcpw, 5, 1, 0)
	echoLayout.AddWidget2(tcpbsLabel, 6, 0, 0)
	echoLayout.AddWidget2(tcpbsw, 6, 1, 0)
	echoLayout.AddWidget2(tcpmwLabel, 7, 0, 0)
	echoLayout.AddWidget2(tcpmww, 7, 1, 0)
	echoLayout.AddWidget2(tcprstLabel, 8, 0, 0)
	echoLayout.AddWidget2(tcprstw, 8, 1, 0)
	echoLayout.AddWidget2(tcpgzLabel, 9, 0, 0)
	echoLayout.AddWidget2(tcpgzw, 9, 1, 0)
	echoLayout.AddWidget2(tcpstatLabel, 10, 0, 0)
	echoLayout.AddWidget2(tcpstatw, 10, 1, 0)
	echoLayout.AddWidget2(nologLabel, 11, 0, 0)
	echoLayout.AddWidget2(nologw, 11, 1, 0)
	echoLayout.AddWidget2(loglevelLabel, 12, 0, 0)
	echoLayout.AddWidget2(loglevelw, 12, 1, 0)
	echoLayout.AddWidget2(sock5Label, 13, 0, 0)
	echoLayout.AddWidget2(sock5w, 13, 1, 0)
	echoLayout.AddWidget2(maxconnLabel, 14, 0, 0)
	echoLayout.AddWidget2(maxconnw, 14, 1, 0)

	echoLayout.AddWidget2(pingLabel, 15, 0, 0)
	echoLayout.AddWidget2(fuckButton, 15, 1, 0)

	echoLayout.AddWidget3(statLabel, 16, 0, 1, 2, core.Qt__AlignVCenter)
	echoLayout.AddWidget3(exitButton, 17, 0, 1, 2, core.Qt__AlignVCenter)

	echoGroup.SetLayout(echoLayout)

	var layout = widgets.NewQGridLayout2()
	layout.AddWidget2(echoGroup, 0, 0, 0)

	lg := loadJson()
	if lg != nil {
		gConfig := *lg

		serverw.SetText(gConfig.Serverw)

		portw.SetText(gConfig.Portw)

		targetw.SetText(gConfig.Targetw)

		timeoutw.SetText(gConfig.Timeoutw)

		keyw.SetText(gConfig.Keyw)

		tcpw.SetChecked(gConfig.Tcpw == "true")

		tcpbsw.SetText(gConfig.Tcpbsw)

		tcpmww.SetText(gConfig.Tcpmww)

		tcprstw.SetText(gConfig.Tcprstw)

		tcpgzw.SetText(gConfig.Tcpgzw)

		tcpstatw.SetChecked(gConfig.Tcpstatw == "true")

		nologw.SetChecked(gConfig.Nologw == "true")

		loglevelw.SetText(gConfig.Loglevelw)

		sock5w.SetChecked(gConfig.Sock5w == "true")

		maxconnw.SetText(gConfig.Maxconnw)

		check()
	}

	centralWidget.SetLayout(layout)
	window.SetCentralWidget(centralWidget)
	window.SetMinimumWidth(500)
	window.SetWindowTitle("pingtunnel-qt")

	//make the window a dialog to hide the minimize button
	//and don't exit the app if the last window is closed/hidden
	//but then you will need to provide some other way to close your application
	window.SetWindowFlags(core.Qt__Dialog)
	app.SetQuitOnLastWindowClosed(false)

	window.Show()

	sys.Show()

	widgets.QApplication_Exec()

}

type Config struct {
	Serverw string `json:"serverw"`

	Portw string `json:"portw"`

	Targetw string `json:"targetw"`

	Timeoutw string `json:"timeoutw"`

	Keyw string `json:"keyw"`

	Tcpw string `json:"tcpw"`

	Tcpbsw string `json:"tcpbsw"`

	Tcpmww string `json:"tcpmww"`

	Tcprstw string `json:"tcprstw"`

	Tcpgzw string `json:"tcpgzw"`

	Tcpstatw string `json:"tcpstatw"`

	Nologw string `json:"nologw"`

	Loglevelw string `json:"loglevelw"`

	Sock5w string `json:"sock5w"`

	Maxconnw string `json:"maxconnw"`
}

func saveJson(c Config) {
	jsonFile, err := os.OpenFile(".pingtunnel-qt.json",
		os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0666)
	if err != nil {
		return
	}
	defer jsonFile.Close()

	str, err := json.Marshal(&c)
	if err != nil {
		loggo.Error("saveJson fail %s", err)
		return
	}
	jsonFile.Write(str)
	jsonFile.Close()
}

func loadJson() *Config {
	jsonFile, err := os.Open(".pingtunnel-qt.json")
	if err != nil {
		return nil
	}
	defer jsonFile.Close()

	byteValue, _ := ioutil.ReadAll(jsonFile)
	var c Config

	err = json.Unmarshal(byteValue, &c)
	if err != nil {
		return nil
	}

	jsonFile.Close()
	return &c
}
